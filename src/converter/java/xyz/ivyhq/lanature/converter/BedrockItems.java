package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Java item → what its Bedrock item's components say that Java items lack ({@code assets/lanature/bedrock/items.json}):
 * its tags, for the equipped-item tag queries, and its cooldown's type ({@code attack} or {@code use}), for
 * {@code q.cooldown_time_remaining}. Items map by Geyser's Java → Bedrock item table, and the components are Geyser's,
 * which it records only for items Bedrock defines by components (the spears among them): an older item's tags and
 * cooldown are missing from the data, and the report lists what is present.
 */
public final class BedrockItems {
	private BedrockItems() {
	}

	public static void run(JsonObject geyserItems, Map<String, Object> bedrockComponents, Path assets, Report report) throws IOException {
		JsonObject out = new JsonObject();
		TreeSet<String> tagsPresent = new TreeSet<>();
		Map<String, Integer> cooldowns = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : geyserItems.entrySet()) {
			String bedrock = e.getValue().getAsJsonObject().get("bedrock_identifier").getAsString();
			if (!(bedrockComponents.get(bedrock) instanceof Map<?, ?> item) || !(item.get("components") instanceof Map<?, ?> components)) {
				continue;
			}
			JsonObject entry = new JsonObject();
			if (components.get("item_tags") instanceof List<?> tags && !tags.isEmpty()) {
				JsonArray array = new JsonArray();
				for (Object tag : tags) {
					array.add((String) tag);
					tagsPresent.add((String) tag);
				}
				entry.add("tags", array);
			}
			if (components.get("minecraft:cooldown") instanceof Map<?, ?> cooldown && cooldown.get("type") instanceof String type) {
				entry.addProperty("cooldown", type);
				cooldowns.merge(type, 1, Integer::sum);
			}
			if (!entry.isEmpty()) {
				out.add(e.getKey(), entry);
			}
		}
		Json.writeCompact(assets.resolve("lanature/bedrock/items.json"), out);
		report.info("items", out.size() + " Java items with Bedrock item tags or cooldowns: tags " + tagsPresent + ", cooldowns by type " + cooldowns);
	}
}
