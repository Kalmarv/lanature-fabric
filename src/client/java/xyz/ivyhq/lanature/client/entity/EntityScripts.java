package xyz.ivyhq.lanature.client.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.ClientEntity;
import xyz.ivyhq.lanature.client.model.Geometry;
import xyz.ivyhq.lanature.client.model.HumanoidPose;
import xyz.ivyhq.lanature.client.model.ModelState;
import xyz.ivyhq.lanature.molang.Functions;

/**
 * Runs the client entity scripts of every entity near the camera that the pack defines, and of every modelled entity
 * close enough to be drawn, on the client tick before the particles update; gives modelled entities their look for
 * each rendered frame; and registers the queries that read the running controller or animation. Held items with a
 * modelled attachable run its scripts too, one instance per holder and hand, while their holder is close enough to
 * be drawn.
 */
public final class EntityScripts {
	private static final EntityScripts INSTANCE = new EntityScripts();
	/** Bedrock runs these scripts for actors it renders; the pack's effects are all near the camera. */
	private static final double RANGE = 64;

	private EntityDefinitions defs = EntityDefinitions.EMPTY;
	private boolean models = true;
	/** While the inventory's paperdoll is extracted: it keeps Java's pose and items (see {@link #paperdoll}). */
	private boolean paperdoll;
	private final Map<Entity, EntityInstance> instances = new HashMap<>();
	private final Map<Held, EntityInstance> attachables = new HashMap<>();
	private ClientLevel level;
	private long frame;

	private EntityScripts() {
	}

	public static EntityScripts get() {
		return INSTANCE;
	}

	/** Replaces the definitions (on resource reload); running scripts start over. */
	public void load(EntityDefinitions defs) {
		clear();
		this.defs = defs;
	}

	public int definitionCount() {
		return defs.entities().size();
	}

	public int running() {
		return instances.size();
	}

	/** A hand of a holder, which an attachable's instance runs for. */
	private record Held(LivingEntity holder, HumanoidArm arm) {
	}

	/** Whether the pack's scripts run for an entity (it has a client entity and is in range). */
	public boolean runs(Entity entity) {
		return instances.containsKey(entity);
	}

	/** An entity's running controllers and their states, for diagnostics; empty if no scripts run for it. */
	public String describe(Entity entity) {
		EntityInstance i = instances.get(entity);
		return i == null ? "" : i.describe();
	}

	/** The animations an entity played in its last frame, with their weights and times, for diagnostics. */
	public String describePlaying(Entity entity) {
		EntityInstance i = instances.get(entity);
		return i == null ? "" : i.describePlaying();
	}

	/** The value of one of an entity's variables (null if it has none), for diagnostics. */
	public @Nullable Object variable(Entity entity, String name) {
		EntityInstance i = instances.get(entity);
		return i == null ? null : i.variable(name);
	}

	/** The running controllers of the attachable an entity holds in a hand, for diagnostics; empty if none runs. */
	public String describeAttachable(LivingEntity holder, HumanoidArm arm) {
		EntityInstance i = attachables.get(new Held(holder, arm));
		return i == null ? "" : i.definition().id() + (i.describePlaying().isEmpty() ? "" : ": " + i.describePlaying());
	}

	public void clear() {
		instances.values().forEach(EntityInstance::stop);
		instances.clear();
		attachables.values().forEach(EntityInstance::stop);
		attachables.clear();
	}

	public void tick(ClientLevel level) {
		if (level != this.level) {
			clear();
			this.level = level;
		}
		if (level == null || defs.entities().isEmpty()) {
			return;
		}
		frame++;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity.isRemoved()) {
				continue;
			}
			ClientEntity def = defs.entities().get(entity.getType());
			if (def != null && inRange(entity, def, camera)) {
				EntityInstance instance = instances.computeIfAbsent(entity, e -> new EntityInstance(e, def, defs));
				instance.tick(frame);
				instance.prune();
			}
			if (entity instanceof LivingEntity holder && entity.shouldRender(camera.x, camera.y, camera.z)) {
				for (HumanoidArm arm : HumanoidArm.values()) {
					EntityInstance instance = attachable(holder, arm);
					if (instance != null && instance.lastFrame() != frame) {
						instance.tick(frame);
						instance.prune();
					}
				}
			}
		}
		prune(instances);
		prune(attachables);
	}

	/** Stops and drops the instances that did not run this frame (their entity left, or its held item changed). */
	private void prune(Map<?, EntityInstance> running) {
		running.values().removeIf(i -> {
			if (i.entity().isRemoved() || i.lastFrame() != frame) {
				i.stop();
				return true;
			}
			return false;
		});
	}

	/**
	 * The instance of the modelled attachable a holder holds in a hand, started on first use (and again when the held
	 * item changes to another attachable); null when the hand holds none.
	 */
	private @Nullable EntityInstance attachable(LivingEntity holder, HumanoidArm arm) {
		ClientEntity def = defs.attachables().get(holder.getItemHeldByArm(arm).getItem());
		Held held = new Held(holder, arm);
		EntityInstance instance = attachables.get(held);
		if (def == null || !def.modelled()) {
			return null;
		}
		if (instance == null || instance.definition() != def) {
			if (instance != null) {
				instance.stop();
			}
			instance = new EntityInstance(holder, def, defs, arm);
			attachables.put(held, instance);
		}
		instance.holder(instances.get(holder));
		return instance;
	}

	/**
	 * Marks the extraction of the inventory's paperdoll. Bedrock draws the paperdoll as a render of its own, with its
	 * own scripts ({@code v.is_paperdoll}); the scripts here run once per entity, for the camera's view (in first person,
	 * the first-person animations), so the paperdoll keeps Java's pose and held items rather than take that view's.
	 */
	public void paperdoll(boolean extracting) {
		paperdoll = extracting;
	}

	boolean inPaperdoll() {
		return paperdoll;
	}

	/** Whether modelled entities draw their Bedrock models (true) or Java's. */
	public void setModels(boolean enabled) {
		models = enabled;
	}

	/** Within script range, or for a modelled entity, within the distance Java draws it from. */
	private static boolean inRange(Entity entity, ClientEntity def, Vec3 camera) {
		return entity.position().distanceToSqr(camera) <= RANGE * RANGE || def.modelled() && entity.shouldRender(camera.x, camera.y, camera.z);
	}

	/**
	 * A modelled entity's look for the frame being rendered, or null to draw Java's model. An entity drawn before
	 * its scripts first ran (it just appeared) runs them now, so it never shows Java's model for a frame.
	 */
	public @Nullable ModelState model(Entity entity, float partialTick) {
		ClientEntity def = defs.entities().get(entity.getType());
		if (!models || def == null || !def.modelled() || entity.level() != level) {
			return null;
		}
		return running(entity, def).model(partialTick);
	}

	/** The geometry Bedrock draws a player with: its skin's, the slim arms' or the default (Steve's) of its client entity. */
	private static final String SLIM_GEOMETRY = "geometry.humanoid.customSlim";

	/**
	 * The Bedrock pose of an entity whose Java model takes one (see {@link ClientEntity#posesJavaModel}), for the frame
	 * being rendered; null to leave Java's animation.
	 */
	public @Nullable HumanoidPose humanoidPose(Entity entity, float partialTick) {
		ClientEntity def = defs.entities().get(entity.getType());
		if (!models || paperdoll || def == null || !def.posesJavaModel() || entity.level() != level) {
			return null;
		}
		Geometry geometry = def.geometries().get("default");
		if (entity instanceof ClientAvatarEntity avatar && avatar.getSkin().model() == PlayerModelType.SLIM && defs.geometries().containsKey(SLIM_GEOMETRY)) {
			geometry = defs.geometries().get(SLIM_GEOMETRY);
		}
		EntityInstance instance = running(entity, def);
		return HumanoidPose.of(geometry, instance.pose(geometry, partialTick), instance.shownBones(partialTick), bodyPlacedByJava(entity, partialTick));
	}

	/**
	 * Whether Java's renderer turns and moves the whole body of a humanoid ({@code LivingEntityRenderer} and
	 * {@code AvatarRenderer.setupRotations}): asleep, swimming or crawling, gliding, or in a riptide spin.
	 */
	private static boolean bodyPlacedByJava(Entity entity, float partialTick) {
		return entity instanceof LivingEntity le && (le.isSleeping() || le.getSwimAmount(partialTick) > 0 || le.isFallFlying() || le.isAutoSpinAttack());
	}

	/** An entity's running instance; one drawn before its scripts first ran (it just appeared) runs them now. */
	private EntityInstance running(Entity entity, ClientEntity def) {
		EntityInstance instance = instances.get(entity);
		if (instance == null) {
			instance = new EntityInstance(entity, def, defs);
			instances.put(entity, instance);
			instance.tick(frame);
		}
		return instance;
	}

	/**
	 * The look of the attachable a holder holds in a hand, for the frame being rendered, or null to draw Java's item.
	 * One drawn before its scripts first ran (it was just picked up) runs them now.
	 */
	public @Nullable ModelState attachableModel(LivingEntity holder, HumanoidArm arm, float partialTick) {
		if (!models || paperdoll || holder.level() != level) {
			return null;
		}
		EntityInstance instance = attachable(holder, arm);
		if (instance == null) {
			return null;
		}
		if (instance.lastFrame() != frame) {
			instance.tick(frame);
		}
		return instance.model(partialTick);
	}

	/** {@code q.state_time}, {@code q.anim_time} and the animation-finished queries, on the script that is running. */
	public static void registerQueries() {
		Functions.registerNumericQuery("state_time", (s, a) -> s.host instanceof EntityInstance i && i.currentController != null ? i.currentController.stateTime : 0);
		Functions.registerNumericQuery("anim_time", (s, a) -> s.host instanceof EntityInstance i && i.currentAnimation != null ? i.currentAnimation.queryTime() : 0);
		Functions.registerNumericQuery("all_animations_finished",
			(s, a) -> s.host instanceof EntityInstance i && i.currentController != null && i.currentController.animationsFinished(false) ? 1 : 0);
		Functions.registerNumericQuery("get_default_bone_pivot",
			(s, a) -> s.host instanceof EntityInstance i && a.length >= 2 ? i.defaultBonePivot(String.valueOf(a[0].value(s)), (int) a[1].eval(s)) : 0);
		Functions.registerNumericQuery("any_animation_finished",
			(s, a) -> s.host instanceof EntityInstance i && i.currentController != null && i.currentController.animationsFinished(true) ? 1 : 0);
	}
}
