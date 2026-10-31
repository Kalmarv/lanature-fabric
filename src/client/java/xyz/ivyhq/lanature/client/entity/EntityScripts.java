package xyz.ivyhq.lanature.client.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions.ClientEntity;
import xyz.ivyhq.lanature.molang.Functions;

/**
 * Runs the client entity scripts of every entity near the camera that the pack defines, on the client tick before
 * the particles update, and registers the queries that read the running controller or animation.
 */
public final class EntityScripts {
	private static final EntityScripts INSTANCE = new EntityScripts();
	/** Bedrock runs these scripts for actors it renders; the pack's effects are all near the camera. */
	private static final double RANGE = 64;

	private EntityDefinitions defs = EntityDefinitions.EMPTY;
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
			if (def == null || entity.isRemoved() || entity.position().distanceToSqr(camera) > RANGE * RANGE) {
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

	/** {@code q.state_time}, {@code q.anim_time} and the animation-finished queries, on the script that is running. */
	public static void registerQueries() {
		Functions.registerNumericQuery("state_time", (s, a) -> s.host instanceof EntityInstance i && i.currentController != null ? i.currentController.stateTime : 0);
		Functions.registerNumericQuery("anim_time", (s, a) -> s.host instanceof EntityInstance i && i.currentAnimation != null ? i.currentAnimation.time : 0);
		Functions.registerNumericQuery("all_animations_finished",
			(s, a) -> s.host instanceof EntityInstance i && i.currentController != null && i.currentController.animationsFinished(false) ? 1 : 0);
		Functions.registerNumericQuery("any_animation_finished",
			(s, a) -> s.host instanceof EntityInstance i && i.currentController != null && i.currentController.animationsFinished(true) ? 1 : 0);
	}
}
