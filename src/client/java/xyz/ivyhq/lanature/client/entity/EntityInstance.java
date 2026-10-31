package xyz.ivyhq.lanature.client.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.DyeColor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Animation;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.ClientEntity;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Controller;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.EffectRef;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Entry;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.State;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Transition;
import xyz.ivyhq.lanature.client.model.EntityMaterial;
import xyz.ivyhq.lanature.client.model.Geometry;
import xyz.ivyhq.lanature.client.model.ModelState;
import xyz.ivyhq.lanature.client.model.RenderController;
import xyz.ivyhq.lanature.client.model.Skeleton;
import xyz.ivyhq.lanature.client.molang.EntityHost;
import xyz.ivyhq.lanature.client.molang.EntityQueries;
import xyz.ivyhq.lanature.client.particle.Emitter;
import xyz.ivyhq.lanature.client.particle.Expr;
import xyz.ivyhq.lanature.client.particle.ParticleSystem;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * One entity's running client entity scripts. Each frame: the engine variables, {@code initialize} once,
 * {@code pre_animation}, then the {@code animate} list. Temp variables last the whole frame, so the controllers read
 * what {@code pre_animation} computed. An animation or controller that went unplayed in a frame (its weight fell to 0,
 * or its state was left) is reset: a controller leaves its state and stops the effects it started, and starts again
 * from its initial state when it next plays.
 *
 * <p>Frames are client ticks. Each animation played in one records its weight (its {@code animate} entry's, times
 * that of the controller playing it) and its time; a modelled entity's pose is evaluated per rendered frame from
 * those, at the animation time interpolated between the last two ticks, with entity queries read at the frame's
 * partial tick. Variables keep the values the last tick computed.
 */
final class EntityInstance implements EntityHost {
	private final Entity entity;
	private final ClientEntity def;
	private final EntityDefinitions defs;
	private final Variables vars = new Variables();
	private final Variables temps = new Variables();
	private final Scope scope;
	private final Map<String, ControllerRun> controllers = new HashMap<>();
	private final Map<String, AnimationRun> animations = new HashMap<>();
	/** The animations played in the current frame, in play order (the order their bone channels apply in). */
	private final List<AnimationRun> played = new ArrayList<>();
	private boolean initialized;
	/** The partial tick entity queries read at: 1 while the scripts tick, the frame's while a pose is evaluated. */
	private float partialTick = 1;
	private long frame;
	private float scale = 1;
	/** What {@code q.state_time}, {@code q.anim_time} and the animation-finished queries refer to while a script runs. */
	ControllerRun currentController;
	AnimationRun currentAnimation;

	EntityInstance(Entity entity, ClientEntity def, EntityDefinitions defs) {
		this.entity = entity;
		this.def = def;
		this.defs = defs;
		this.scope = new Scope(vars, temps, null, this, ParticleSystem.get().random());
		this.scope.resources = (kind, name) -> switch (kind) {
			case "geometry" -> def.geometries().get(name);
			case "texture" -> def.textures().get(name);
			case "material" -> def.materials().get(name);
			default -> null;
		};
	}

	@Override
	public float partialTick() {
		return partialTick;
	}

	@Override
	public Entity entity() {
		return entity;
	}

	/** The last frame this entity's scripts ran in. */
	long lastFrame() {
		return frame;
	}

	void tick(long frame) {
		this.frame = frame;
		temps.clear();
		played.clear();
		engineVariables();
		if (!initialized) {
			initialized = true;
			run(def.initialize());
		}
		run(def.preAnimation());
		// the client entity's scripts.scale on the behaviour pack's
		scale = (def.scale() == null ? 1 : (float) Expr.eval(def.scale(), scope)) * def.behaviourScale().of(entity);
		for (Entry e : def.animate()) {
			play(e, 1);
		}
		controllers.values().removeIf(c -> {
			if (c.lastFrame != frame) {
				c.stop();
				return true;
			}
			return false;
		});
		animations.values().removeIf(a -> {
			if (a.lastFrame != frame) {
				a.stop();
				return true;
			}
			return false;
		});
	}

	String describe() {
		StringBuilder out = new StringBuilder();
		new java.util.TreeMap<>(controllers).forEach((id, c) -> {
			if (!out.isEmpty()) {
				out.append(", ");
			}
			out.append(id.replaceFirst("^controller\\.animation\\.", "")).append('=').append(c.state).append(c.emitters.isEmpty() ? "" : c.emitters.stream().map(Emitter::describe).toList().toString());
		});
		return out.toString();
	}

	String describePlaying() {
		StringBuilder out = new StringBuilder(describe());
		for (AnimationRun a : played) {
			out.append(out.isEmpty() ? "" : ", ").append(a.def.id()).append(String.format(" w%.2f t%.2f", a.weight, a.time)).append(a.waiting() ? String.format(" waiting %.2f", a.delay) : "");
		}
		return out.toString();
	}

	/** Stops everything this entity started (it left the world or the range). */
	void stop() {
		controllers.values().forEach(ControllerRun::stop);
		animations.values().forEach(AnimationRun::stop);
		controllers.clear();
		animations.clear();
	}

	/** The variables Bedrock's engine sets on an actor before its scripts run. */
	/** The variables Bedrock's engine sets on an entity, at the current partial tick. */
	private void engineVariables() {
		Minecraft mc = Minecraft.getInstance();
		vars.set("is_first_person", entity == mc.player && mc.options.getCameraType().isFirstPerson() ? 1.0 : 0.0);
		vars.set("is_paperdoll", 0.0);
		vars.set("map_face_icon", 0.0);
		vars.set("attack_time", entity instanceof LivingEntity le ? (double) le.getSwingAnimation(partialTick) : 0.0);
		if (entity instanceof Cat cat) {
			// the cat's pose, in the order Java's cat model takes it: 0 sneak, 1 sprint, 2 sit, 4 lying down (while its
			// lie-down amount has not run back to 0), 3 walk
			float lieDown = cat.getLieDownAmount(partialTick);
			vars.set("state", cat.isCrouching() ? 0.0 : cat.isSprinting() ? 1.0 : cat.isInSittingPose() ? 2.0 : lieDown > 0 ? 4.0 : 3.0);
			vars.set("liedownamount", (double) lieDown);
			vars.set("liedownamounttail", (double) cat.getLieDownAmountTail(partialTick));
			vars.set("lieonplayer", cat.isLyingOnTopOfSleepingPlayer() ? 1.0 : 0.0);
		}
		if (entity instanceof AbstractFish) {
			// the fish's swim cycle, which its scripts blend by q.frame_alpha; taken to run a step a tick, as the tail of
			// Java's fish runs on its age
			vars.set("animationamount", (double) entity.tickCount);
			vars.set("animationamountprev", entity.tickCount - 1.0);
		}
	}

	private void run(Node script) {
		if (script != null) {
			Expr.run(script, scope);
		}
	}

	private double eval(Node n) {
		return Expr.eval(n, scope);
	}

	/** Plays an animation or controller by its entity-local name, under the weight of whatever plays it. */
	private void play(Entry e, double outerWeight) {
		double weight = outerWeight;
		if (e.weight() != null) {
			double w = eval(e.weight());
			if (w <= 0) {
				return;
			}
			weight *= w;
		}
		String id = def.animations().get(e.name());
		if (id == null) {
			return;
		}
		Controller c = defs.controllers().get(id);
		if (c != null) {
			ControllerRun run = controllers.computeIfAbsent(id, k -> new ControllerRun(c));
			if (run.lastFrame != frame) {
				run.tick(weight);
			}
			return;
		}
		Animation a = defs.animations().get(id);
		if (a != null) {
			AnimationRun run = animations.computeIfAbsent(id, k -> new AnimationRun(a));
			if (run.lastFrame != frame) {
				run.weight = weight;
				played.add(run);
				run.tick();
			} else {
				run.weight = Math.max(run.weight, weight);
			}
		}
	}

	// models

	/**
	 * The entity's look this frame, at the frame's partial tick: for each render controller whose condition holds,
	 * what it draws and the pose of its geometry. Null when the entity is not modelled or nothing is drawn.
	 */
	@Nullable ModelState model(float partialTick) {
		if (!def.modelled()) {
			return null;
		}
		this.partialTick = partialTick;
		AnimationRun outer = currentAnimation;
		try {
			engineVariables();
			List<ModelState.Part> parts = new ArrayList<>();
			Map<Geometry, Matrix4f[]> poses = new IdentityHashMap<>();
			int engineColor = engineColor(entity);
			for (Entry e : def.renderControllers()) {
				if (e.weight() != null && eval(e.weight()) == 0) {
					continue;
				}
				RenderController rc = defs.renderControllers().get(e.name());
				RenderController.Draw draw = rc == null ? null : rc.evaluate(scope, EntityMaterial.DEFAULT, engineColor);
				if (draw == null) {
					continue;
				}
				Matrix4f[] bones = poses.computeIfAbsent(draw.geometry(), g -> {
					Skeleton skeleton = new Skeleton(g);
					for (AnimationRun run : played) {
						if (run.waiting()) {
							continue;
						}
						currentAnimation = run;
						run.posing = true;
						try {
							skeleton.apply(run.def.bones(), run.renderTime(partialTick), run.weight, scope);
						} finally {
							run.posing = false;
						}
					}
					return skeleton.matrices();
				});
				parts.add(new ModelState.Part(draw, bones));
			}
			return parts.isEmpty() ? null : new ModelState(List.copyOf(parts), scale, EntityQueries.bodyYaw(entity, partialTick));
		} finally {
			currentAnimation = outer;
			this.partialTick = 1;
		}
	}

	/**
	 * The colour Bedrock's engine gives an entity's colour-mask materials, its {@code minecraft:color}: a tame cat's or
	 * wolf's collar and a sheep's wool (the vanilla behaviour pack sets it in the cat's and wolf's tame groups and in
	 * each wool group), here the same dyes as Java tracks them; white for every other entity.
	 */
	private static int engineColor(Entity entity) {
		DyeColor dye = switch (entity) {
			case Cat cat when cat.isTame() -> cat.getCollarColor();
			case Wolf wolf when wolf.isTame() -> wolf.getCollarColor();
			case Sheep sheep -> sheep.getColor();
			default -> null;
		};
		return dye == null ? -1 : ARGB.opaque(dye.getTextureDiffuseColor());
	}

	// effects

	private void startEffect(EffectRef ref, List<Emitter> owner) {
		String effect = def.particleEffects().getOrDefault(ref.effect(), ref.effect());
		Vector3f locator = ref.locator() == null ? new Vector3f() : new Vector3f(def.locators().getOrDefault(ref.locator(), new Vector3f()));
		locator.mul(scale);
		Variables preset = null;
		if (ref.preEffectScript() != null) {
			// pre_effect_script writes the new emitter's variables, with the entity as the query host
			preset = new Variables();
			Variables own = scope.variables;
			scope.variables = preset;
			try {
				run(ref.preEffectScript());
			} finally {
				scope.variables = own;
			}
		}
		ParticleSystem ps = ParticleSystem.get();
		Emitter emitter = ref.bindToActor() ? ps.spawnOnEntity(effect, entity, locator, preset)
			: ps.spawn(effect, entity.getX() + rotated(locator).x, entity.getY() + locator.y, entity.getZ() + rotated(locator).z, null, preset);
		if (emitter != null) {
			owner.add(emitter);
		}
	}

	private Vector3f rotated(Vector3f locator) {
		float yaw = entity instanceof LivingEntity le ? le.yBodyRot : entity.getYRot();
		return new Vector3f(locator).rotateY((float) Math.toRadians(-yaw));
	}

	private void playSound(String name) {
		String definition = def.soundEffects().get(name);
		if (definition == null) {
			return;
		}
		// the local player hosts the pack's world ambience (wind, presences), which belongs with ambient sounds
		SoundSource source = entity == Minecraft.getInstance().player ? SoundSource.AMBIENT : entity.getSoundSource();
		ParticleSystem.get().playSound(EntityDefinitions.sound(definition), entity.getX(), entity.getY(), entity.getZ(), source, 1, 1);
	}

	private static void stopAll(List<Emitter> emitters) {
		emitters.forEach(Emitter::stop);
		emitters.clear();
	}

	// runs

	final class ControllerRun {
		private final Controller def;
		private String state;
		double stateTime;
		long lastFrame = -1;
		private final List<Emitter> emitters = new ArrayList<>();

		ControllerRun(Controller def) {
			this.def = def;
		}

		void tick(double weight) {
			lastFrame = frame;
			ControllerRun outer = currentController;
			currentController = this;
			try {
				if (state == null) {
					enter(def.initialState());
				} else {
					stateTime += ParticleSystem.DT;
					State s = def.states().get(state);
					if (s != null) {
						for (Transition t : s.transitions()) {
							if (eval(t.condition()) != 0 && def.states().containsKey(t.target())) {
								exit();
								enter(t.target());
								break;
							}
						}
					}
				}
				State s = def.states().get(state);
				if (s != null) {
					for (Entry e : s.animations()) {
						play(e, weight);
					}
				}
			} finally {
				currentController = outer;
			}
		}

		private void enter(String name) {
			state = name;
			stateTime = 0;
			State s = def.states().get(name);
			if (s == null) {
				return;
			}
			// a state's animations start over when it is entered
			for (Entry e : s.animations()) {
				AnimationRun a = animations.get(EntityInstance.this.def.animations().get(e.name()));
				if (a != null) {
					a.stop();
				}
			}
			run(s.onEntry());
			for (EffectRef ref : s.particles()) {
				startEffect(ref, emitters);
			}
			s.sounds().forEach(EntityInstance.this::playSound);
		}

		private void exit() {
			State s = def.states().get(state);
			if (s != null) {
				run(s.onExit());
			}
			stopAll(emitters);
		}

		void stop() {
			if (state != null) {
				ControllerRun outer = currentController;
				currentController = this;
				try {
					exit();
				} finally {
					currentController = outer;
				}
			}
			state = null;
		}

		/** Whether every animation of the current state has played to its end ({@code any} for at least one). */
		boolean animationsFinished(boolean any) {
			State s = def.states().get(state);
			if (s == null || s.animations().isEmpty()) {
				return true;
			}
			boolean all = true;
			boolean some = false;
			for (Entry e : s.animations()) {
				AnimationRun a = animations.get(EntityInstance.this.def.animations().get(e.name()));
				boolean done = a != null && a.finished;
				all &= done;
				some |= done;
			}
			return any ? some : all;
		}
	}

	final class AnimationRun {
		private final Animation def;
		double time;
		/** The time at the previous frame, continuous across a loop (so it may be below 0 just after one). */
		private double previousTime;
		/** The time a pose is being evaluated at, and whether one is. */
		private double renderTime;
		private boolean posing;
		double weight;
		/** Seconds still to wait before the animation (or its next loop) plays; while waiting it poses nothing. */
		private double delay;
		boolean started;
		boolean finished;
		long lastFrame = -1;
		private final List<Emitter> emitters = new ArrayList<>();

		AnimationRun(Animation def) {
			this.def = def;
		}

		/** What {@code q.anim_time} reads: the animation's time, or the one a pose is being evaluated at. */
		double queryTime() {
			return posing ? renderTime : time;
		}

		/** The time between the last two frames at a partial tick, wrapped into a looping animation. */
		double renderTime(float partialTick) {
			double t = previousTime + (time - previousTime) * partialTick;
			if (def.loop() == EntityDefinitions.Loop.LOOP && def.length() > 0 && t < 0) {
				t += def.length();
			}
			renderTime = t;
			return t;
		}

		void tick() {
			lastFrame = frame;
			AnimationRun outer = currentAnimation;
			currentAnimation = this;
			try {
				if (!started) {
					started = true;
					finished = false;
					// an animation whose time follows a query (distance walked) starts where the query is
					time = def.bones().animTimeUpdate() == null ? 0 : eval(def.bones().animTimeUpdate());
					previousTime = time;
					delay = def.startDelay() == null ? 0 : eval(def.startDelay());
					if (delay <= 0) {
						fire(Double.NEGATIVE_INFINITY, true, time);
					}
				} else if (delay > 0) {
					delay -= ParticleSystem.DT;
					previousTime = time;
					if (delay <= 0) {
						fire(Double.NEGATIVE_INFINITY, true, time);
					}
				} else if (!finished) {
					double prev = time;
					double next = def.bones().animTimeUpdate() == null ? time + ParticleSystem.DT : eval(def.bones().animTimeUpdate());
					if (def.length() > 0 && next >= def.length()) {
						fire(prev, false, def.length());
						double loopDelay = def.loop() == EntityDefinitions.Loop.LOOP && def.loopDelay() != null ? eval(def.loopDelay()) : 0;
						if (loopDelay > 0) {
							// the next loop starts from the beginning once the delay is over
							time = 0;
							previousTime = 0;
							delay = loopDelay;
						} else if (def.loop() == EntityDefinitions.Loop.LOOP) {
							time = next % def.length();
							previousTime = time - (next - prev);
							fire(Double.NEGATIVE_INFINITY, true, time);
						} else {
							time = def.length();
							previousTime = prev;
							finished = true;
						}
					} else {
						time = next;
						previousTime = prev;
						fire(prev, false, time);
					}
				} else {
					previousTime = time;
				}
			} finally {
				currentAnimation = outer;
			}
		}

		/** Fires what is keyed in (from, to], or [.., to] when {@code inclusive}. */
		private void fire(double from, boolean inclusive, double to) {
			def.timeline().subMap(from, inclusive, to, true).values().forEach(list -> list.forEach(EntityInstance.this::run));
			def.particles().subMap(from, inclusive, to, true).values().forEach(list -> list.forEach(ref -> startEffect(ref, emitters)));
			def.sounds().subMap(from, inclusive, to, true).values().forEach(list -> list.forEach(EntityInstance.this::playSound));
		}

		void stop() {
			stopAll(emitters);
			started = false;
			finished = false;
			time = 0;
			previousTime = 0;
			delay = 0;
		}

		boolean waiting() {
			return delay > 0;
		}
	}

	/** Prunes emitters that have ended on their own, so the lists do not grow on long-lived states. */
	void prune() {
		for (ControllerRun c : controllers.values()) {
			prune(c.emitters);
		}
		for (AnimationRun a : animations.values()) {
			prune(a.emitters);
		}
	}

	private static void prune(List<Emitter> emitters) {
		Iterator<Emitter> it = emitters.iterator();
		while (it.hasNext()) {
			if (it.next().finished()) {
				it.remove();
			}
		}
	}
}
