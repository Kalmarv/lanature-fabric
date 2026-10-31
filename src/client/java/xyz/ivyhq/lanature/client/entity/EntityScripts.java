package xyz.ivyhq.lanature.client.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.ClientEntity;
import xyz.ivyhq.lanature.client.model.ModelState;
import xyz.ivyhq.lanature.molang.Functions;

/**
 * Runs the client entity scripts of every entity near the camera that the pack defines, and of every modelled entity
 * close enough to be drawn, on the client tick before the particles update; gives modelled entities their look for
 * each rendered frame; and registers the queries that read the running controller or animation.
 */
public final class EntityScripts {
	private static final EntityScripts INSTANCE = new EntityScripts();
	/** Bedrock runs these scripts for actors it renders; the pack's effects are all near the camera. */
	private static final double RANGE = 64;

	private EntityDefinitions defs = EntityDefinitions.EMPTY;
	private boolean models = true;
	private final Map<Entity, EntityInstance> instances = new HashMap<>();
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

	public void clear() {
		instances.values().forEach(EntityInstance::stop);
		instances.clear();
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
			ClientEntity def = defs.entities().get(entity.getType());
			if (def == null || entity.isRemoved() || !inRange(entity, def, camera)) {
				continue;
			}
			EntityInstance instance = instances.computeIfAbsent(entity, e -> new EntityInstance(e, def, defs));
			instance.tick(frame);
			instance.prune();
		}
		instances.entrySet().removeIf(e -> {
			if (e.getKey().isRemoved() || e.getValue().lastFrame() != frame) {
				e.getValue().stop();
				return true;
			}
			return false;
		});
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
		EntityInstance instance = instances.get(entity);
		if (instance == null) {
			instance = new EntityInstance(entity, def, defs);
			instances.put(entity, instance);
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
		Functions.registerNumericQuery("any_animation_finished",
			(s, a) -> s.host instanceof EntityInstance i && i.currentController != null && i.currentController.animationsFinished(true) ? 1 : 0);
	}
}
