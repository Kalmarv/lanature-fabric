package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The size Bedrock draws each of the pack's client entities at, from the vanilla behaviour pack's
 * {@code minecraft:scale} ({@code assets/lanature/bedrock/entity_scales.json}). Bedrock keeps an entity's scale on
 * the server side, in its components or in component groups its events add (the cat's {@code minecraft:cat_adult}
 * 0.8, a baby group's 0.5), where Java bakes the same sizes into its models' meshes; the client entity only adds its
 * own {@code scripts.scale}. Per entity: {@code scale} from its components, {@code baby} from the groups that make it
 * a baby, and {@code groups}, the other groups' scales by name, for the runtime to choose by what Java tracks.
 */
public final class EntityScales {
	private EntityScales() {
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
				report.warn("scales", "behaviour " + path + ": " + e.getMessage());
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
				report.warn("scales", id + ": no vanilla behaviour entity; drawn at Java's age scale");
				continue;
			}
			JsonObject scales = new JsonObject();
			Double base = scale(entity.getAsJsonObject("components"));
			if (base != null) {
				scales.addProperty("scale", base);
			}
			JsonObject groups = new JsonObject();
			JsonObject componentGroups = entity.has("component_groups") ? entity.getAsJsonObject("component_groups") : new JsonObject();
			for (Map.Entry<String, JsonElement> g : componentGroups.entrySet()) {
				JsonObject components = g.getValue().getAsJsonObject();
				Double value = scale(components);
				if (value == null) {
					continue;
				}
				if (components.has("minecraft:is_baby")) {
					if (scales.has("baby") && scales.get("baby").getAsDouble() != value) {
						report.warn("scales", id + ": baby groups disagree (" + scales.get("baby") + ", " + g.getKey() + " " + value + "); keeping the first");
					} else {
						scales.addProperty("baby", value);
					}
				} else {
					groups.addProperty(g.getKey(), value);
				}
			}
			if (!groups.isEmpty()) {
				scales.add("groups", groups);
			}
			out.add(id, scales);
			report.info("scales", id + " " + scales);
		}
		Json.writeCompact(assets.resolve("lanature/bedrock/entity_scales.json"), out);
	}

	private static Double scale(JsonObject components) {
		if (components == null || !components.has("minecraft:scale")) {
			return null;
		}
		return components.getAsJsonObject("minecraft:scale").get("value").getAsDouble();
	}
}
