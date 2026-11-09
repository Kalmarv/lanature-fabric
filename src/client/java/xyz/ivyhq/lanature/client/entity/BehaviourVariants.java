package xyz.ivyhq.lanature.client.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * The variant numbers the vanilla behaviour pack's component groups set ({@code variants} in
 * {@code bedrock/entity_behaviour.json}, see the converter's {@code EntityBehaviour}): per Bedrock entity, per kind
 * ({@code variant}, {@code mark_variant}, {@code skin_id}), each group's value by group name. Bedrock's groups are
 * server state; the queries pick the group by the Java state it stands for.
 */
public final class BehaviourVariants {
	private static volatile Map<String, Map<String, Map<String, Integer>>> current = Map.of();

	private BehaviourVariants() {
	}

	/** Reads every entity's {@code variants} from the behaviour table. */
	public static Map<String, Map<String, Map<String, Integer>>> parse(JsonObject behaviour) {
		Map<String, Map<String, Map<String, Integer>>> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : behaviour.entrySet()) {
			if (!(e.getValue() instanceof JsonObject entity) || !(entity.get("variants") instanceof JsonObject kinds)) {
				continue;
			}
			Map<String, Map<String, Integer>> byKind = new HashMap<>();
			for (Map.Entry<String, JsonElement> kind : kinds.entrySet()) {
				Map<String, Integer> groups = new HashMap<>();
				kind.getValue().getAsJsonObject().entrySet().forEach(g -> groups.put(g.getKey(), g.getValue().getAsInt()));
				byKind.put(kind.getKey(), Map.copyOf(groups));
			}
			out.put(e.getKey(), Map.copyOf(byKind));
		}
		return Map.copyOf(out);
	}

	public static void set(Map<String, Map<String, Map<String, Integer>>> table) {
		current = table;
	}

	/** The value a group of an entity sets for a kind, or {@code fallback} (the component's default) when none does. */
	public static int of(String entity, String kind, String group, int fallback) {
		return current.getOrDefault(entity, Map.of()).getOrDefault(kind, Map.of()).getOrDefault(group, fallback);
	}
}
