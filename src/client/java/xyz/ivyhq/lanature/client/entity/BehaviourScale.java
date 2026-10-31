package xyz.ivyhq.lanature.client.entity;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.Salmon;
import org.jspecify.annotations.Nullable;

/**
 * The size Bedrock gives an entity in its behaviour pack's {@code minecraft:scale} (see the converter's
 * {@code EntityScales}): {@code scale} from its components, {@code baby} from its baby groups, and the other groups'
 * scales by name. Bedrock's component groups are server state; here the group an entity is in is chosen by what Java
 * tracks of the same thing: being a baby, and the salmon's size.
 */
public record BehaviourScale(@Nullable Float scale, @Nullable Float baby, Map<String, Float> groups) {
	/** For an entity the behaviour pack does not describe: Java's own age scale. */
	public static final BehaviourScale NONE = new BehaviourScale(null, null, Map.of());

	public static BehaviourScale parse(JsonObject o) {
		Map<String, Float> groups = new HashMap<>();
		if (o.has("groups")) {
			o.getAsJsonObject("groups").entrySet().forEach(e -> groups.put(e.getKey(), e.getValue().getAsFloat()));
		}
		return new BehaviourScale(o.has("scale") ? o.get("scale").getAsFloat() : null, o.has("baby") ? o.get("baby").getAsFloat() : null, Map.copyOf(groups));
	}

	/**
	 * The entity's scale: a baby's group scale, or else its grown scale by Java's age scale; grown, the group its Java
	 * state names, else its only scaling group (the cat's {@code minecraft:cat_adult}), else its components' scale.
	 */
	public float of(Entity entity) {
		Float grown = group(entity);
		if (grown == null) {
			grown = groups.size() == 1 ? groups.values().iterator().next() : scale;
		}
		float adult = grown == null ? 1 : grown;
		if (entity instanceof LivingEntity living && living.isBaby()) {
			return baby != null ? baby : adult * living.getAgeScale();
		}
		return adult;
	}

	private @Nullable Float group(Entity entity) {
		if (entity instanceof Salmon salmon) {
			return groups.get(switch (salmon.getVariant()) {
				case SMALL -> "scale_small";
				case MEDIUM -> "scale_normal";
				case LARGE -> "scale_large";
			});
		}
		return null;
	}
}
