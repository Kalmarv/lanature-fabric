package xyz.ivyhq.lanature.client.particle;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.molang.Scope;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * The Bedrock particle system: effect definitions, running emitters, and the bridge from Java's particle spawns to
 * the pack's effects. It ticks with the client (20 times a second); billboards are evaluated every frame.
 */
public final class ParticleSystem {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/particles");
	public static final double DT = 0.05;
	/** Total live Bedrock particles; past this no more are emitted. */
	private static final int MAX_PARTICLES = 16384;
	private long tickNanos;
	private long extractNanos;
	private int tickCalls;
	private int extractCalls;
	private static final ParticleSystem INSTANCE = new ParticleSystem();

	final Random random = new Random();
	private Map<String, ParticleEffect> effects = Map.of();
	private Map<Identifier, String> triggers = Map.of();
	private Map<String, String> sites = Map.of();
	private Map<String, SoundEntry> sounds = Map.of();
	private final List<Emitter> emitters = new ArrayList<>();
	private final List<Emitter> added = new ArrayList<>();
	private final Object2IntOpenHashMap<String> emittersByEffect = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<String> particlesByEffect = new Object2IntOpenHashMap<>();
	/** The live block seed of each effect at each block ({@link ParticleEffect#blockSeed}). */
	private final Map<Seed, Emitter> seeds = new Object2ObjectOpenHashMap<>();
	private int particleCount;
	private final BedrockParticleRenderState renderState = new BedrockParticleRenderState();
	private final Billboards billboards = new Billboards();
	ClientLevel level;
	private boolean ticking;
	/** Set while the system itself is spawning a vanilla particle, so the trigger does not catch it. */
	private boolean bypassTriggers;

	record SoundEntry(Identifier sound, double volumeMin, double volumeMax, double pitchMin, double pitchMax) {
	}

	private ParticleSystem() {
	}

	public static ParticleSystem get() {
		return INSTANCE;
	}

	/** Replaces the loaded definitions (on resource reload); running emitters are dropped. */
	public void load(Map<String, ParticleEffect> effects, Map<Identifier, String> triggers, Map<String, String> sites, Map<String, SoundEntry> sounds) {
		this.effects = effects;
		this.triggers = triggers;
		this.sites = sites;
		this.sounds = sounds;
		clear();
	}

	public int effectCount() {
		return effects.size();
	}

	public boolean hasEffect(String id) {
		return effects.containsKey(id);
	}

	/** The pack effect Bedrock starts at a game-event site (the trigger table's {@code sites}), or null to leave Java's particles. */
	@Nullable String siteEffect(String site) {
		return level == null ? null : sites.get(site);
	}

	public void clear() {
		emitters.clear();
		added.clear();
		emittersByEffect.clear();
		particlesByEffect.clear();
		seeds.clear();
		particleCount = 0;
	}

	public void setLevel(ClientLevel level) {
		this.level = level;
		clear();
	}

	boolean hasRoom() {
		return particleCount < MAX_PARTICLES;
	}

	void particleAdded(Emitter e) {
		particleCount++;
		particlesByEffect.addTo(e.fx.id, 1);
	}

	public int emitterCount(String effect) {
		return emittersByEffect.getInt(effect);
	}

	public int particleCount(String effect) {
		return particlesByEffect.getInt(effect);
	}

	public int totalEmitters() {
		return emitters.size() + added.size();
	}

	public int totalParticles() {
		return particleCount;
	}

	/** Mean tick and extract cost since the last call, as {@code tick x ms, extract y ms}, for diagnostics. */
	public String timings() {
		String out = String.format(java.util.Locale.ROOT, "tick %.2f ms, extract %.2f ms",
			tickCalls == 0 ? 0 : tickNanos / 1e6 / tickCalls, extractCalls == 0 ? 0 : extractNanos / 1e6 / extractCalls);
		tickNanos = extractNanos = 0;
		tickCalls = extractCalls = 0;
		return out;
	}

	/** The effects with the most live particles, as {@code id emitters/particles}, for diagnostics. */
	public String busiest(int n) {
		return emittersByEffect.object2IntEntrySet().stream()
			.sorted((a, b) -> Integer.compare(particlesByEffect.getInt(b.getKey()), particlesByEffect.getInt(a.getKey())))
			.limit(n)
			.map(e -> e.getKey() + " " + e.getIntValue() + "/" + particlesByEffect.getInt(e.getKey()))
			.collect(java.util.stream.Collectors.joining(", "));
	}

	// spawning

	/** Starts an effect at a position, optionally following an entity. Returns null if the effect is unknown. */
	public Emitter spawn(String effect, double x, double y, double z, Entity follow, Variables preset) {
		ParticleEffect fx = effects.get(effect);
		if (fx == null || level == null) {
			return null;
		}
		Emitter e = create(fx, x, y, z, follow, fx.rate == ParticleEffect.Rate.MANUAL);
		if (preset != null) {
			preset.local().forEach(e.vars::set);
		}
		start(e);
		return e;
	}

	/** Starts an effect at a locator of an entity's model, following it as the entity moves, turns and animates. */
	public Emitter spawnOnEntity(String effect, Entity entity, Emitter.Locator locator, Variables preset) {
		ParticleEffect fx = effects.get(effect);
		if (fx == null || level == null) {
			return null;
		}
		Emitter e = new Emitter(this, fx, entity, locator, fx.rate == ParticleEffect.Rate.MANUAL);
		if (preset != null) {
			preset.local().forEach(e.vars::set);
		}
		start(e);
		return e;
	}

	/** Plays a sound event at a position. */
	public void playSound(Identifier sound, double x, double y, double z, SoundSource source, float volume, float pitch) {
		if (level != null) {
			level.playLocalSound(x, y, z, SoundEvent.createVariableRangeEvent(sound), source, volume, pitch, false);
		}
	}

	/** The random source scripts and effects draw from. */
	public Random random() {
		return random;
	}

	private Emitter create(ParticleEffect fx, double x, double y, double z, Entity follow, boolean oneShot) {
		return new Emitter(this, fx, x, y, z, follow, oneShot);
	}

	private void start(Emitter e) {
		emittersByEffect.addTo(e.fx.id, 1);
		(ticking ? added : emitters).add(e);
		e.initialize();
	}

	void spawnFromEvent(EffectSource source, Event.Spawn spawn, Scope scope) {
		ParticleEffect fx = effects.get(spawn.effect());
		if (fx == null) {
			return;
		}
		Emitter parent = source.emitter();
		boolean bound = spawn.type() == Event.SpawnType.EMITTER_BOUND;
		boolean particle = spawn.type() == Event.SpawnType.PARTICLE || spawn.type() == Event.SpawnType.PARTICLE_WITH_VELOCITY;
		Emitter.Locator locator = bound && parent.entity != null ? parent.boundLocator(source.worldX(), source.worldY(), source.worldZ()) : null;
		Emitter child = locator != null ? new Emitter(this, fx, parent.entity, locator, particle)
			: create(fx, source.worldX(), source.worldY(), source.worldZ(), bound ? parent.entity : null, particle);
		if (bound) {
			child.rotation.set(parent.rotation);
		}
		if (spawn.preEffect() != null) {
			Variables vars = scope.variables;
			scope.variables = child.vars;
			try {
				Expr.run(spawn.preEffect(), scope);
			} finally {
				scope.variables = vars;
			}
		}
		start(child);
		if (spawn.type() == Event.SpawnType.PARTICLE_WITH_VELOCITY) {
			for (BedrockParticle p : child.particles) {
				p.vx += source.velocityX();
				p.vy += source.velocityY();
				p.vz += source.velocityZ();
			}
		}
	}

	void playSound(String event, EffectSource source) {
		SoundEntry entry = sounds.get(event);
		if (entry == null || level == null) {
			return;
		}
		float volume = (float) (entry.volumeMin + random.nextDouble() * (entry.volumeMax - entry.volumeMin));
		float pitch = (float) (entry.pitchMin + random.nextDouble() * (entry.pitchMax - entry.pitchMin));
		level.playLocalSound(source.worldX(), source.worldY(), source.worldZ(), SoundEvent.createVariableRangeEvent(entry.sound),
			SoundSource.AMBIENT, volume, pitch, false);
	}

	/**
	 * A Java particle spawn the pack replaces: starts the pack's effect instead and returns true, or returns false to
	 * let the vanilla particle spawn.
	 */
	public boolean trigger(ParticleOptions options, double x, double y, double z, double xa, double ya, double za) {
		if (bypassTriggers || level == null || triggers.isEmpty()) {
			return false;
		}
		String effect = triggers.get(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()));
		if (effect == null) {
			return false;
		}
		Variables preset = new Variables();
		if (options instanceof ItemParticleOption item) {
			// the item's picture picks the effect: Bedrock's icon effect, or its terrain one for a block-drawn item
			effect = PictureEffects.item(level, item, preset);
			if (effect == null) {
				return false;
			}
		}
		ParticleEffect fx = effects.get(effect);
		Seed seed = fx != null && fx.blockSeed() ? new Seed(effect, BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z))) : null;
		if (seed != null && seeds.containsKey(seed)) {
			return true;
		}
		TriggerVariables.fill(options, xa, ya, za, preset);
		Emitter e = spawn(effect, x, y, z, null, preset);
		if (e != null && seed != null && !e.done()) {
			seeds.put(seed, e);
		}
		return e != null;
	}

	private record Seed(String effect, long block) {
	}

	// ticking

	public void tick(ClientLevel level) {
		if (level != this.level) {
			setLevel(level);
		}
		if (level == null) {
			return;
		}
		long start = System.nanoTime();
		ticking = true;
		try {
			for (Emitter e : emitters) {
				tickEmitter(e);
			}
		} catch (RuntimeException ex) {
			LOGGER.error("Bedrock particle tick failed; clearing particles", ex);
			clear();
		} finally {
			ticking = false;
		}
		emitters.addAll(added);
		added.clear();
		Iterator<Emitter> it = emitters.iterator();
		while (it.hasNext()) {
			Emitter e = it.next();
			if (e.done()) {
				it.remove();
				if (e.fx.blockSeed()) {
					seeds.values().remove(e);
				}
				int n = emittersByEffect.addTo(e.fx.id, -1) - 1;
				if (n <= 0) {
					emittersByEffect.removeInt(e.fx.id);
				}
			}
		}
		tickNanos += System.nanoTime() - start;
		tickCalls++;
	}

	private void tickEmitter(Emitter e) {
		e.follow();
		e.update(DT);
		List<BedrockParticle> live = e.particles;
		for (int i = 0; i < live.size(); i++) {
			BedrockParticle p = live.get(i);
			if (!p.expired) {
				p.update(DT);
			}
		}
		Iterator<BedrockParticle> it = live.iterator();
		while (it.hasNext()) {
			BedrockParticle p = it.next();
			if (p.expired) {
				it.remove();
				particleCount--;
				int n = particlesByEffect.addTo(e.fx.id, -1) - 1;
				if (n <= 0) {
					particlesByEffect.removeInt(e.fx.id);
				}
			}
		}
	}

	// rendering

	public BedrockParticleRenderState extract(Camera camera, Frustum frustum, float partialTick) {
		renderState.clear();
		if (level == null || emitters.isEmpty()) {
			return renderState;
		}
		long start = System.nanoTime();
		TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
		billboards.begin(camera, frustum, partialTick, level);
		for (Emitter e : emitters) {
			if (!e.fx.billboard) {
				continue;
			}
			// an effect on the block or item atlas reads it whole, at the UVs the engine handed it, and one drawing its
			// texture on its own reads that whole
			TextureAtlasSprite sprite = e.fx.atlas != ParticleEffect.Atlas.PARTICLES || e.fx.standalone ? null
				: e.fx.texture == null ? atlas.missingSprite() : atlas.getSprite(e.fx.texture);
			float u0 = sprite == null ? 0 : sprite.getU0();
			float v0 = sprite == null ? 0 : sprite.getV0();
			float u1 = sprite == null ? 1 : sprite.getU1();
			float v1 = sprite == null ? 1 : sprite.getV1();
			for (BedrockParticle p : e.particles) {
				if (!p.expired) {
					billboards.render(p, u0, v0, u1, v1, renderState);
				}
			}
		}
		extractNanos += System.nanoTime() - start;
		extractCalls++;
		return renderState;
	}
}
