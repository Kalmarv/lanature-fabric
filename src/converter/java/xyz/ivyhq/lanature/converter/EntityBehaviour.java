package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.TreeSet;

/**
 * What the vanilla behaviour pack sets for each of the pack's client entities that its client side reads
 * ({@code assets/lanature/bedrock/entity_behaviour.json}): its size and its variant numbers.
 *
 * <p>Bedrock keeps an entity's scale on the server side, in its components or in component groups its events add (the
 * cat's {@code minecraft:cat_adult} 0.8, a baby group's 0.5), where Java bakes the same sizes into its models' meshes;
 * the client entity only adds its own {@code scripts.scale}. Per entity: {@code scale} from its components,
 * {@code baby} from the groups that make it a baby, and {@code groups}, the other groups' scales by name, for the
 * runtime to choose by what Java tracks.
 *
 * <p>The numbers {@code q.variant}, {@code q.mark_variant} and {@code q.skin_id} read come from the
 * {@code minecraft:variant}, {@code minecraft:mark_variant} and {@code minecraft:skin_id} components of the groups the
 * entity's events add (a villager's {@code farmer} group sets variant 1, its {@code desert_villager} group mark variant
 * 1): {@code variants}, per kind, the groups' values by group name, for the runtime to pick by the Java state the group
 * stands for.
 */
public final class EntityBehaviour {
	private static final List<String> VARIANT_KINDS = List.of("variant", "mark_variant", "skin_id");

	private EntityBehaviour() {
	}

	public static void run(BedrockPack pack, FileTree behaviourEntities, Path assets, Report report) throws IOException {
		Map<String, JsonObject> behaviour = new HashMap<>();
		for (String path : behaviourEntities.paths()) {
			if (!path.endsWith(".json")) {
				continue;
			}
			JsonObject entity;
			try {
				entity = Json.parse(behaviourEntities.get(path)).getAsJsonObject().getAsJsonObject("minecraft:entity");
			} catch (RuntimeException e) {
				report.warn("behaviour", "behaviour " + path + ": " + e.getMessage());
				continue;
			}
			if (entity != null) {
				behaviour.put(entity.getAsJsonObject("description").get("identifier").getAsString(), entity);
			}
		}
		Set<String> ids = new TreeSet<>();
		for (String path : pack.packPaths("entity/")) {
			JsonElement json = path.endsWith(".json") ? pack.packJson(path) : null;
			JsonObject client = json == null ? null : json.getAsJsonObject().getAsJsonObject("minecraft:client_entity");
			if (client != null) {
				ids.add(client.getAsJsonObject("description").get("identifier").getAsString());
			}
		}
		JsonObject out = new JsonObject();
		for (String id : ids) {
			JsonObject entity = behaviour.get(id);
			if (entity == null) {
				report.warn("behaviour", id + ": no vanilla behaviour entity; drawn at Java's age scale");
				continue;
			}
			JsonObject entry = new JsonObject();
			Double base = scale(entity.getAsJsonObject("components"));
			if (base != null) {
				entry.addProperty("scale", base);
			}
			JsonObject groups = new JsonObject();
			JsonObject variants = new JsonObject();
			JsonObject componentGroups = entity.has("component_groups") ? entity.getAsJsonObject("component_groups") : new JsonObject();
			for (Map.Entry<String, JsonElement> g : componentGroups.entrySet()) {
				JsonObject components = g.getValue().getAsJsonObject();
				for (String kind : VARIANT_KINDS) {
					if (components.get("minecraft:" + kind) instanceof JsonObject c && c.has("value")) {
						if (!variants.has(kind)) {
							variants.add(kind, new JsonObject());
						}
						variants.getAsJsonObject(kind).addProperty(g.getKey(), c.get("value").getAsInt());
					}
				}
				Double value = scale(components);
				if (value == null) {
					continue;
				}
				if (components.has("minecraft:is_baby")) {
					if (entry.has("baby") && entry.get("baby").getAsDouble() != value) {
						report.warn("behaviour", id + ": baby groups disagree (" + entry.get("baby") + ", " + g.getKey() + " " + value + "); keeping the first");
					} else {
						entry.addProperty("baby", value);
					}
				} else {
					groups.addProperty(g.getKey(), value);
				}
			}
			if (!groups.isEmpty()) {
				entry.add("groups", groups);
			}
			if (!variants.isEmpty()) {
				entry.add("variants", variants);
			}
			out.add(id, entry);
			report.info("behaviour", id + " " + entry);
		}
		Json.writeCompact(assets.resolve("lanature/bedrock/entity_behaviour.json"), out);
	}

	private static Double scale(JsonObject components) {
		if (components == null || !components.has("minecraft:scale")) {
			return null;
		}
		return components.getAsJsonObject("minecraft:scale").get("value").getAsDouble();
	}
}
