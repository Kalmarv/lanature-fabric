package xyz.ivyhq.lanature.client.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Animation;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.ClientEntity;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Controller;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.EffectRef;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Entry;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.State;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.Transition;
import xyz.ivyhq.lanature.client.molang.EntityHost;
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
	private boolean initialized;
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
		engineVariables();
		if (!initialized) {
			initialized = true;
			run(def.initialize());
		}
		run(def.preAnimation());
		scale = def.scale() == null ? 1 : (float) Expr.eval(def.scale(), scope);
		for (Entry e : def.animate()) {
			play(e);
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

	/** Stops everything this entity started (it left the world or the range). */
	void stop() {
		controllers.values().forEach(ControllerRun::stop);
		animations.values().forEach(AnimationRun::stop);
		controllers.clear();
		animations.clear();
	}

	/** The variables Bedrock's engine sets on an actor before its scripts run. */
	private void engineVariables() {
		Minecraft mc = Minecraft.getInstance();
		vars.set("is_first_person", entity == mc.player && mc.options.getCameraType().isFirstPerson() ? 1.0 : 0.0);
		vars.set("is_paperdoll", 0.0);
		vars.set("map_face_icon", 0.0);
		vars.set("attack_time", entity instanceof LivingEntity le ? (double) le.getSwingAnimation(1) : 0.0);
	}

	private void run(Node script) {
		if (script != null) {
			Expr.run(script, scope);
		}
	}

	private double eval(Node n) {
		return Expr.eval(n, scope);
	}

	private void play(Entry e) {
		if (e.weight() != null && eval(e.weight()) <= 0) {
			return;
		}
		String id = def.animations().get(e.name());
		if (id == null) {
			return;
		}
		Controller c = defs.controllers().get(id);
		if (c != null) {
			ControllerRun run = controllers.computeIfAbsent(id, k -> new ControllerRun(c));
			if (run.lastFrame != frame) {
				run.tick();
			}
			return;
		}
		Animation a = defs.animations().get(id);
		if (a != null) {
			AnimationRun run = animations.computeIfAbsent(id, k -> new AnimationRun(a));
			if (run.lastFrame != frame) {
				run.tick();
			}
		}
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

		void tick() {
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
						play(e);
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
		boolean started;
		boolean finished;
		long lastFrame = -1;
		private final List<Emitter> emitters = new ArrayList<>();

		AnimationRun(Animation def) {
			this.def = def;
		}

		void tick() {
			lastFrame = frame;
			AnimationRun outer = currentAnimation;
			currentAnimation = this;
			try {
				if (!started) {
					started = true;
					finished = false;
					time = 0;
					fire(Double.NEGATIVE_INFINITY, true, 0);
				} else if (!finished) {
					double prev = time;
					time += ParticleSystem.DT;
					if (def.length() > 0 && time >= def.length()) {
						fire(prev, false, def.length());
						if (def.loop() == EntityDefinitions.Loop.LOOP) {
							time -= def.length();
							fire(Double.NEGATIVE_INFINITY, true, time);
						} else {
							time = def.length();
							finished = true;
						}
					} else {
						fire(prev, false, time);
					}
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
