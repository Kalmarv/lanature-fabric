package xyz.ivyhq.lanature.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * The pack effects started at a game event in place of the Java particles the event spawns (the trigger table's
 * {@code sites}, by site name): Bedrock starts its effect once where Java spawns a burst, or gives the uses of one
 * Java particle type to different effects. Each method returns false to leave Java's particles.
 *
 * <p>{@code minecraft:block_destruct} (Java's {@code block}) is started for a broken block, a crack while mining, a
 * sprint step, a fall and a brushed flake, Bedrock's uses of it; Java's other {@code block} particles, the warden's
 * digging and the breeze's jumps, are other effects in Bedrock, which the pack leaves alone. The engine's amounts are
 * not published. The pack's own effect counts 48 particles as a broken block (it starts its step effects for any other
 * count above one) and one particle with a {@code v.velocity_scalar} under 0.6 as a step, so (inferred) a broken block
 * is 48 particles at speed 1 over the block's shape, a crack one at Java's crack power (a fifth), a step one at the
 * entity's speed in blocks per tick, a flake one at speed 1, and a fall the count the server sends at its speed.
 *
 * <p>The critical hit's and the totem's effects start at the entity's eyes and follow it, as Java's tracking emitter does
 * (the vanilla effects emit 0.5 to 0.9 blocks below where they start, about the middle of a body under its eyes, and the
 * pack's totem draws its circle 1.6 below, at a player's feet; inferred), a splash potion's at Java's point of
 * the splash with the potion's colour as {@code v.color} (the effect's own default range, 4, is Java's), and the
 * mace's smash at the top of the ground block with that block's picture, with its centre effect beside it.
 *
 * <p>Java's {@code entity_effect} particle is several Bedrock effects: an entity's status-effect swirl is
 * {@code mobspell_emitter} ({@code mobspell_ambient} for an ambient effect, a beacon's, whose Java particle is drawn faint),
 * a tipped arrow's trail {@code arrow_spell_emitter} (as is the spectral arrow's, a Java {@code effect} particle), each
 * started with one particle where Java spawns its one, in the particle's colour. A lingering cloud is
 * {@code mobspell_lingering}, which emits {@code π r²} times {@code v.particle_multiplier} particles once over a disc
 * of {@code v.cloud_radius} (the vanilla effect): the count Java spawns over the cloud each tick, so (inferred) the
 * engine starts it each tick Java spawns them, with a multiplier of 1, Java's radius (a fifth of a block while the
 * cloud waits) and an active time of that tick. Java's other {@code entity_effect} particles (an evoker's spell, a
 * stunned ravager, a raid bell, the wither) stay Java's.
 */
public final class SiteEffects {
	/** Inferred: the count the pack's effect takes for a broken block. */
	private static final int DESTROY_COUNT = 48;
	/** Java's crack particle power ({@code setPower(0.2F)}). */
	private static final double CRACK_SPEED = 0.2;
	private static final String BLOCK_DESTRUCT = "block_destruct";
	private static final String CRITICAL_HIT = "critical_hit";
	private static final String TOTEM = "totem";
	private static final String POTION_SPLASH = "potion_splash";
	private static final String MACE_SMASH = "mace_smash";
	private static final String MACE_SMASH_CENTER = "mace_smash_center";
	private static final String STATUS_EFFECT = "status_effect";
	private static final String STATUS_EFFECT_AMBIENT = "status_effect_ambient";
	private static final String ARROW_TRAIL = "arrow_trail";
	private static final String LINGERING_CLOUD = "lingering_cloud";
	/** Java's radius for a waiting cloud's particles. */
	private static final double WAITING_RADIUS = 0.2;
	/** Inferred: the active time of each start of a cloud's effect, one tick. */
	private static final double CLOUD_TICK = 0.05;

	private SiteEffects() {
	}

	/** A broken block ({@code ClientLevel.addDestroyBlockEffect}). */
	public static boolean destroy(ClientLevel level, BlockPos pos, BlockState state) {
		String effect = ParticleSystem.get().siteEffect(BLOCK_DESTRUCT);
		if (effect == null || state.isAir() || !state.shouldSpawnTerrainParticles()) {
			return false;
		}
		var shape = state.getShape(level, pos);
		AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
		double radius = Math.min(1.0, Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()))) / 2;
		Vec3 centre = box.getCenter();
		ParticleSystem.get().spawn(effect, pos.getX() + centre.x, pos.getY() + centre.y, pos.getZ() + centre.z, null,
			PictureEffects.block(level, pos, state, DESTROY_COUNT, radius, 1.0));
		return true;
	}

	/** A crack of a block being mined ({@code ClientLevel.addBreakingParticles}), at Java's place for it just off the face. */
	public static boolean crack(ClientLevel level, BlockPos pos, Direction face, BlockState state) {
		String effect = ParticleSystem.get().siteEffect(BLOCK_DESTRUCT);
		if (effect == null) {
			return false;
		}
		AABB box = state.getShape(level, pos).bounds();
		RandomSource random = level.getRandom();
		double x = pos.getX() + random.nextDouble() * (box.maxX - box.minX - 0.2) + 0.1 + box.minX;
		double y = pos.getY() + random.nextDouble() * (box.maxY - box.minY - 0.2) + 0.1 + box.minY;
		double z = pos.getZ() + random.nextDouble() * (box.maxZ - box.minZ - 0.2) + 0.1 + box.minZ;
		switch (face) {
			case DOWN -> y = pos.getY() + box.minY - 0.1;
			case UP -> y = pos.getY() + box.maxY + 0.1;
			case NORTH -> z = pos.getZ() + box.minZ - 0.1;
			case SOUTH -> z = pos.getZ() + box.maxZ + 0.1;
			case WEST -> x = pos.getX() + box.minX - 0.1;
			case EAST -> x = pos.getX() + box.maxX + 0.1;
		}
		ParticleSystem.get().spawn(effect, x, y, z, null, PictureEffects.block(level, pos, state, 1, 0, CRACK_SPEED));
		return true;
	}

	/** Java {@code block} particles of a known count, spread and speed: a sprint step, a fall's burst, a brushed flake. */
	public static boolean block(ClientLevel level, BlockParticleOption options, double x, double y, double z, int count, double radius, double speed) {
		String effect = ParticleSystem.get().siteEffect(BLOCK_DESTRUCT);
		if (effect == null || options.getType() != ParticleTypes.BLOCK) {
			return false;
		}
		ParticleSystem.get().spawn(effect, x, y, z, null, PictureEffects.block(level, BlockPos.containing(x, y, z), options.getState(), count, radius, speed));
		return true;
	}

	/** A Java tracking emitter ({@code ParticleEngine.createTrackingEmitter}): a critical hit, a totem; the effect follows the entity. */
	public static boolean trackingEmitter(Entity entity, ParticleOptions options) {
		String site = options.getType() == ParticleTypes.CRIT ? CRITICAL_HIT : options.getType() == ParticleTypes.TOTEM_OF_UNDYING ? TOTEM : null;
		String effect = site == null ? null : ParticleSystem.get().siteEffect(site);
		if (effect == null) {
			return false;
		}
		Vec3 eyes = entity.getEyePosition();
		ParticleSystem.get().spawn(effect, eyes.x, eyes.y, eyes.z, entity, null);
		return true;
	}

	/** A splash potion's splash ({@code LevelEventHandler.potionSplashParticles}, plain and instant), {@code rgb} its colour. */
	public static boolean potionSplash(BlockPos pos, int rgb) {
		String effect = ParticleSystem.get().siteEffect(POTION_SPLASH);
		if (effect == null) {
			return false;
		}
		Variables vars = new Variables();
		Variables.Struct color = new Variables.Struct();
		color.set("r", (rgb >> 16 & 0xFF) / 255.0);
		color.set("g", (rgb >> 8 & 0xFF) / 255.0);
		color.set("b", (rgb & 0xFF) / 255.0);
		color.set("a", 1.0);
		vars.set("color", color);
		Vec3 at = Vec3.atBottomCenterOf(pos);
		ParticleSystem.get().spawn(effect, at.x, at.y, at.z, null, vars);
		return true;
	}

	/** Whether a splash potion's spell particles are left to {@link #potionSplash}. */
	public static boolean replacesPotionSplash() {
		return ParticleSystem.get().siteEffect(POTION_SPLASH) != null;
	}

	/** A mace smash on the ground block {@code pos} ({@code ParticleUtils.spawnSmashAttackParticles}). */
	public static boolean smash(ClientLevel level, BlockPos pos) {
		String effect = ParticleSystem.get().siteEffect(MACE_SMASH);
		if (effect == null) {
			return false;
		}
		BlockState ground = level.getBlockState(pos);
		Vec3 top = Vec3.atCenterOf(pos).add(0, 0.5, 0);
		ParticleSystem.get().spawn(effect, top.x, top.y, top.z, null, PictureEffects.surface(level, pos, ground));
		String center = ParticleSystem.get().siteEffect(MACE_SMASH_CENTER);
		if (center != null) {
			ParticleSystem.get().spawn(center, top.x, top.y, top.z, null, PictureEffects.surface(level, pos, ground));
		}
		return true;
	}

	/** One swirl of an entity's status effects ({@code LivingEntity.tickEffects}), where Java spawns its particle. */
	public static boolean statusEffect(ParticleOptions options, double x, double y, double z) {
		if (!(options instanceof ColorParticleOption c) || c.getType() != ParticleTypes.ENTITY_EFFECT) {
			return false;
		}
		// Java draws an ambient effect's particle at alpha 38/255, the others' opaque
		return spawnColored(c.getAlpha() < 1 ? STATUS_EFFECT_AMBIENT : STATUS_EFFECT, options, x, y, z);
	}

	/** One particle of a tipped ({@code Arrow.makeParticle}) or spectral ({@code SpectralArrow.tick}) arrow's trail. */
	public static boolean arrowTrail(ParticleOptions options, double x, double y, double z) {
		return spawnColored(ARROW_TRAIL, options, x, y, z);
	}

	/** One tick of a lingering cloud's particles ({@code AreaEffectCloud.clientTick}) when they are Java's {@code entity_effect}. */
	public static boolean lingeringCloud(AreaEffectCloud cloud) {
		ParticleOptions particle = cloud.getParticle();
		String effect = ParticleSystem.get().siteEffect(LINGERING_CLOUD);
		if (effect == null || particle.getType() != ParticleTypes.ENTITY_EFFECT) {
			return false;
		}
		boolean waiting = cloud.isWaiting();
		// a waiting cloud spawns its particles on half its ticks, as Java's does
		if (!waiting || !cloud.getRandom().nextBoolean()) {
			Variables vars = new Variables();
			vars.set("color", TriggerVariables.color(particle));
			vars.set("cloud_radius", waiting ? WAITING_RADIUS : cloud.getRadius());
			vars.set("particle_multiplier", 1.0);
			vars.set("cloud_lifetime", CLOUD_TICK);
			ParticleSystem.get().spawn(effect, cloud.getX(), cloud.getY(), cloud.getZ(), null, vars);
		}
		return true;
	}

	private static boolean spawnColored(String site, ParticleOptions options, double x, double y, double z) {
		String effect = ParticleSystem.get().siteEffect(site);
		Variables.Struct color = TriggerVariables.color(options);
		if (effect == null || color == null) {
			return false;
		}
		Variables vars = new Variables();
		vars.set("color", color);
		ParticleSystem.get().spawn(effect, x, y, z, null, vars);
		return true;
	}
}
