package xyz.ivyhq.lanature.client.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
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
import xyz.ivyhq.lanature.client.mixin.FirstPersonHandsAndItemsAccessor;
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
import xyz.ivyhq.lanature.molang.VariableHost;
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
 *
 * <p>An attachable's instance is a held item's: it runs on its holder (its queries read the holder) for one hand, with
 * the context Bedrock gives an attachable, {@code c.item_slot} and {@code c.is_first_person}. The local player's own
 * first-person animations read a context too, {@code c.player_offhand_arm_height}.
 */
final class EntityInstance implements EntityHost, VariableHost {
	/** Java's {@code Consumable.CONSUME_EFFECTS_START_FRACTION}: the part of a consumable's time before its first bite. */
	private static final float CONSUME_EFFECTS_START_FRACTION = 0.21875F;
	/** The ticks between a consumable's bites ({@code Consumable.shouldEmitParticlesAndSounds}). */
	private static final double BITE_INTERVAL = 4;

	private final Entity entity;
	private final ClientEntity def;
	/** The hand an attachable is held in; null for an entity's own client entity. */
	private final @Nullable HumanoidArm arm;
	private final Variables context = new Variables();
	private final EntityDefinitions defs;
	private final Variables vars = new Variables();
	/** What another scope reads of {@link #vars} with {@code ->}: the variables the client entity declares public. */
	private final Variables publicVariables;
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
	/** The bones of each geometry as last posed for drawing, which {@link #locator} follows. */
	private Map<Geometry, Matrix4f[]> posed = Map.of();
	/** What {@code q.state_time}, {@code q.anim_time} and the animation-finished queries refer to while a script runs. */
	ControllerRun currentController;
	AnimationRun currentAnimation;

	EntityInstance(Entity entity, ClientEntity def, EntityDefinitions defs) {
		this(entity, def, defs, null);
	}

	/** An attachable's instance, held by {@code holder} in {@code arm}. */
	EntityInstance(LivingEntity holder, ClientEntity def, EntityDefinitions defs, HumanoidArm arm) {
		this((Entity) holder, def, defs, arm);
	}

	private EntityInstance(Entity entity, ClientEntity def, EntityDefinitions defs, @Nullable HumanoidArm arm) {
		this.entity = entity;
		this.def = def;
		this.defs = defs;
		this.arm = arm;
		this.publicVariables = Variables.view(vars, def.publicVariables());
		this.scope = new Scope(vars, temps, context, this, ParticleSystem.get().random());
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

	@Override
	public boolean attached() {
		return arm != null;
	}

	ClientEntity definition() {
		return def;
	}

	/**
	 * For an attachable: its holder, {@code c.owning_entity}, which its scripts read with {@code ->}: the holder's
	 * running instance, whose public variables they see, or the bare entity when it runs no scripts.
	 */
	void holder(@Nullable EntityInstance holder) {
		context.set("owning_entity", holder != null ? holder : entity);
	}

	@Override
	public Variables publicVariables() {
		return publicVariables;
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

	@Nullable Object variable(String name) {
		return vars.get(name);
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

	/**
	 * The variables Bedrock's engine sets on an entity, at the current partial tick, and its context: an attachable's
	 * slot ({@code main_hand} or {@code off_hand}) and whether it is seen in first person, and how far the local
	 * player's hands are raised. No published list names the engine's variables; these are the ones the vanilla and pack
	 * scripts read but never assign, each given its meaning from how the vanilla animations use it.
	 */
	private void engineVariables() {
		Minecraft mc = Minecraft.getInstance();
		double firstPerson = entity == mc.player && mc.options.getCameraType().isFirstPerson() && !EntityScripts.get().inPaperdoll() ? 1.0 : 0.0;
		vars.set("is_first_person", firstPerson);
		if (arm != null && entity instanceof LivingEntity holder) {
			boolean mainHand = arm == holder.getMainArm();
			context.set("item_slot", mainHand ? "main_hand" : "off_hand");
			context.set("is_first_person", firstPerson);
			// whether the held item is drawn enchanted (vanilla's attachables and armour pick their glint material by it)
			vars.set("is_enchanted", holder.getItemInHand(mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND).hasFoil() ? 1.0 : 0.0);
		}
		// how far the first-person hands are raised, 0 lowered to 1 raised: the first-person animations lower the arms
		// by 10 pixels times one less this (v. for the main hand, c. for the off hand) as an item is swapped, as Java
		// lowers its hands; raised for any other entity, which is never seen in first person
		double mainHand = 1;
		double offHand = 1;
		if (entity == mc.player && mc.player.firstPersonHandsAndItems() instanceof FirstPersonHandsAndItemsAccessor hands) {
			mainHand = Mth.lerp(partialTick, hands.lanature$oMainHandHeight(), hands.lanature$mainHandHeight());
			offHand = Mth.lerp(partialTick, hands.lanature$oOffHandHeight(), hands.lanature$offHandHeight());
		}
		vars.set("player_arm_height", mainHand);
		context.set("player_offhand_arm_height", offHand);
		vars.set("is_paperdoll", 0.0);
		vars.set("map_face_icon", 0.0);
		vars.set("attack_time", entity instanceof LivingEntity le ? (double) le.getSwingAnimation(partialTick) : 0.0);
		vars.set("gliding_speed_value", glidingSpeed(entity));
		if (entity instanceof LivingEntity le) {
			// what the entity holds and does with it
			vars.set("is_holding_right", le.getItemHeldByArm(HumanoidArm.RIGHT).isEmpty() ? 0.0 : 1.0);
			vars.set("is_holding_left", le.getItemHeldByArm(HumanoidArm.LEFT).isEmpty() ? 0.0 : 1.0);
			ItemUseAnimation use = le.isUsingItem() ? le.getUseItem().getUseAnimation() : ItemUseAnimation.NONE;
			vars.set("is_brandishing_spear", use == ItemUseAnimation.TRIDENT ? 1.0 : 0.0);
			vars.set("is_holding_spyglass", use == ItemUseAnimation.SPYGLASS ? 1.0 : 0.0);
			vars.set("is_tooting_goat_horn", use == ItemUseAnimation.TOOT_HORN ? 1.0 : 0.0);
			vars.set("is_using_brush", use == ItemUseAnimation.BRUSH ? 1.0 : 0.0);
			vars.set("damage_nearby_mobs", le.isAutoSpinAttack() ? 1.0 : 0.0);
			vars.set("is_sneaking", le.isCrouching() ? 1.0 : 0.0);
			vars.set("swim_amount", (double) le.getSwimAmount(partialTick));
			vars.set("player_x_rotation", (double) le.getXRot(partialTick));
			// how long the held item has been used, in ticks: the spear scripts time its charge by it
			double useTicks = le.getTicksUsingItem(partialTick);
			vars.set("item_use_duration", useTicks);
			useItemProgress(le, use, useTicks);
			if (le instanceof Player) {
				SpearVariables.fill(le, useTicks, vars);
			}
		}
		// no VR headset and no split screen in Java
		vars.set("is_using_vr", 0.0);
		vars.set("is_horizontal_splitscreen", 0.0);
		vars.set("is_vertical_splitscreen", 0.0);
		// the first-person hand bob and breathing (vanilla Bedrock's, which the pack leaves as they are) stay off: Java
		// bobs the hands with its own view bob, which stays
		vars.set("bob_animation", 0.0);
		// a Java skin's arms are full length, classic or slim (inferred: the offsets are taken for a shorter arm's)
		vars.set("short_arm_offset_left", 0.0);
		vars.set("short_arm_offset_right", 0.0);
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

	/**
	 * What walk cycles divide their swing by, {@code v.gliding_speed_value}, which the vanilla scripts read but never set:
	 * 1, and while gliding the cube of the speed over Java's elytra baseline, never below 1. Java's humanoid model divides
	 * its walk cycle by the same value ({@code HumanoidRenderState.speedValue}).
	 */
	private static double glidingSpeed(Entity entity) {
		if (!(entity instanceof LivingEntity le) || !le.isFallFlying()) {
			return 1;
		}
		double s = le.getDeltaMovement().lengthSqr() / 0.2;
		return Math.max(1, s * s * s);
	}

	/**
	 * Eating and drinking, by the phases of Java's consumable (inferred: the names fit them, and no published source
	 * defines the engine's values): {@code use_item_startup_progress} runs 0 to 1 over the wait before the first bite
	 * ({@code consumeTicks * 0.21875}, 7 of food's 32 ticks) and holds there, then {@code use_item_interval_progress}
	 * runs 0 to 1 over each 4 ticks after it, the interval of the bites (Java's sounds and crumbs), starting at 0 so the
	 * arm moves on without a jump. Vanilla's humanoid {@code use_item_progress} raises the right arm by the first and
	 * shakes it by the second.
	 */
	private void useItemProgress(LivingEntity le, ItemUseAnimation use, double useTicks) {
		Consumable consumable = use == ItemUseAnimation.EAT || use == ItemUseAnimation.DRINK ? le.getUseItem().get(DataComponents.CONSUMABLE) : null;
		double startup = 0;
		double interval = 0;
		if (consumable != null) {
			int wait = (int) (consumable.consumeTicks() * CONSUME_EFFECTS_START_FRACTION);
			startup = wait > 0 ? Math.min(1, useTicks / wait) : 1;
			if (useTicks > wait) {
				interval = Mth.positiveModulo(useTicks - wait, BITE_INTERVAL) / BITE_INTERVAL;
			}
		}
		vars.set("use_item_startup_progress", startup);
		vars.set("use_item_interval_progress", interval);
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
		return atFrame(partialTick, () -> {
			List<ModelState.Part> parts = new ArrayList<>();
			Map<Geometry, Matrix4f[]> poses = new IdentityHashMap<>();
			// an attachable is not its holder, and has no colour of its own
			int engineColor = arm == null ? engineColor(entity) : -1;
			for (Entry e : def.renderControllers()) {
				if (e.weight() != null && eval(e.weight()) == 0) {
					continue;
				}
				RenderController rc = defs.renderControllers().get(e.name());
				RenderController.Draw draw = rc == null ? null : rc.evaluate(scope, EntityMaterial.DEFAULT, engineColor);
				if (draw == null) {
					continue;
				}
				parts.add(new ModelState.Part(draw, poses.computeIfAbsent(draw.geometry(), this::skeleton)));
			}
			posed = poses;
			return parts.isEmpty() ? null : new ModelState(List.copyOf(parts), scale, EntityQueries.bodyYaw(entity, partialTick));
		});
	}

	/**
	 * A geometry's bones posed by this frame's animations, at the frame's partial tick (see {@link Skeleton#matrices}),
	 * for an entity whose Java model takes its Bedrock pose.
	 */
	Matrix4f[] pose(Geometry geometry, float partialTick) {
		Matrix4f[] bones = atFrame(partialTick, () -> skeleton(geometry));
		posed = Map.of(geometry, bones);
		return bones;
	}

	/**
	 * Where a locator is this frame, in Java model axes and blocks for the model facing +Z before its yaw, at the
	 * entity's scale: on its bone as last posed for drawing (the geometries drawn last frame), else where the
	 * geometry puts it at rest. An attachable's locators, in its holder's hand, stay at rest.
	 */
	Vector3f locator(String name) {
		if (arm == null) {
			for (Map.Entry<Geometry, Matrix4f[]> e : posed.entrySet()) {
				List<Geometry.Bone> bones = e.getKey().bones();
				for (int b = 0; b < bones.size(); b++) {
					Vector3f at = bones.get(b).locators().get(name);
					if (at != null) {
						// the bone's pose is in geometry pixels and the model's frame, which faces -Z
						Vector3f p = e.getValue()[b].transformPosition(at, new Vector3f());
						return new Vector3f(-p.x, p.y, -p.z).mul(scale);
					}
				}
			}
		}
		return new Vector3f(def.locators().getOrDefault(name, new Vector3f())).mul(scale);
	}

	/**
	 * The names (lower case) of the bones the render controllers draw this frame, whose conditions hold, by their
	 * {@code part_visibility}: for an entity whose Java model takes its Bedrock pose, which of its parts show.
	 */
	java.util.Set<String> shownBones(float partialTick) {
		return atFrame(partialTick, () -> {
			java.util.Set<String> shown = new java.util.HashSet<>();
			for (Entry e : def.renderControllers()) {
				if (e.weight() != null && eval(e.weight()) == 0) {
					continue;
				}
				RenderController rc = defs.renderControllers().get(e.name());
				RenderController.Draw draw = rc == null ? null : rc.evaluate(scope, EntityMaterial.DEFAULT, -1);
				if (draw != null) {
					for (int b = 0; b < draw.boneMaterials().length; b++) {
						if (draw.boneMaterials()[b] != null) {
							shown.add(draw.geometry().bones().get(b).name().toLowerCase(java.util.Locale.ROOT));
						}
					}
				}
			}
			return shown;
		});
	}

	/** {@code q.get_default_bone_pivot}: a bone's pivot in the default geometry, in Bedrock's axes; 0 for no such bone. */
	double defaultBonePivot(String bone, int axis) {
		Geometry g = def.geometries().get("default");
		int b = g == null ? -1 : g.bone(bone);
		if (b < 0 || axis < 0 || axis > 2) {
			return 0;
		}
		Vector3f pivot = g.bones().get(b).pivot();
		// the geometry keeps X mirrored from Bedrock's
		return axis == 0 ? -pivot.x : axis == 1 ? pivot.y : pivot.z;
	}

	/** Runs {@code body} as of the rendered frame: entity queries read at its partial tick, the engine variables set for it. */
	private <T> T atFrame(float partialTick, java.util.function.Supplier<T> body) {
		this.partialTick = partialTick;
		AnimationRun outer = currentAnimation;
		try {
			engineVariables();
			return body.get();
		} finally {
			currentAnimation = outer;
			this.partialTick = 1;
		}
	}

	/** Poses a geometry with the animations played in the last frame, each at its time at the current partial tick. */
	private Matrix4f[] skeleton(Geometry geometry) {
		Skeleton skeleton = new Skeleton(geometry);
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
		String name = ref.locator();
		Emitter.Locator locator = name == null ? Vector3f::new : () -> locator(name);
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
		Vector3f at = rotated(locator.at());
		Emitter emitter = ref.bindToActor() ? ps.spawnOnEntity(effect, entity, locator, preset)
			: ps.spawn(effect, entity.getX() + at.x, entity.getY() + at.y, entity.getZ() + at.z, null, preset);
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
		/** The last value of the query an animation's time follows ({@code anim_time_update}). */
		private double queryValue;
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
					// an animation whose time follows a query (distance walked) starts where the query is, within the
					// animation: wrapped into a loop, at most the end of one that plays once
					time = 0;
					if (def.bones().animTimeUpdate() != null) {
						queryValue = eval(def.bones().animTimeUpdate());
						time = within(queryValue);
					}
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
					double next = time + ParticleSystem.DT;
					if (def.bones().animTimeUpdate() != null) {
						// the time moves as far as the query did
						double value = eval(def.bones().animTimeUpdate());
						next = time + (value - queryValue);
						queryValue = value;
					}
					if (next < prev) {
						// a query that went back: the time follows it, crossing no keyed events
						time = within(next);
						previousTime = time;
					} else if (def.length() > 0 && next >= def.length()) {
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

		/** A time put within the animation: wrapped into a looping one's length, else between 0 and its length. */
		private double within(double t) {
			if (def.length() <= 0) {
				return Math.max(0, t);
			}
			if (def.loop() == EntityDefinitions.Loop.LOOP) {
				return t - Math.floor(t / def.length()) * def.length();
			}
			return Mth.clamp(t, 0, def.length());
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
