package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Java biome → its Bedrock biome and that biome's tags ({@code assets/lanature/bedrock/biomes_java.json}), for the
 * biome queries. Biomes are matched by name, or by {@code biome_names.json} where Java renamed them; each table entry
 * carries the Bedrock numeric id, which must agree with Geyser's.
 */
public final class BiomeTags {
	private BiomeTags() {
	}

	public static void run(FileTree vanilla, JsonObject geyserBiomes, JsonObject renamed, Path assets, Report report) throws IOException {
		JsonObject out = new JsonObject();
		int missing = 0;
		for (Map.Entry<String, JsonElement> e : geyserBiomes.entrySet()) {
			String java = e.getKey();
			String bedrock = JavaAssets.strip(java);
			if (renamed.has(java)) {
				JsonArray entry = renamed.getAsJsonArray(java);
				bedrock = entry.get(0).getAsString();
				int id = e.getValue().getAsJsonObject().get("bedrock_id").getAsInt();
				if (entry.get(1).getAsInt() != id) {
					report.warn("biomes", java + ": biome_names.json says " + bedrock + " (" + entry.get(1) + "), Geyser sends id " + id);
				}
			}
			String path = "behavior_pack/biomes/" + bedrock + ".biome.json";
			JsonArray tags = new JsonArray();
			if (vanilla.has(path)) {
				JsonObject components = Json.parse(vanilla.get(path)).getAsJsonObject().getAsJsonObject("minecraft:biome").getAsJsonObject("components");
				if (components.has("minecraft:tags")) {
					components.getAsJsonObject("minecraft:tags").getAsJsonArray("tags").forEach(tags::add);
				}
			} else {
				missing++;
				report.warn("biomes", java + ": no Bedrock biome " + bedrock);
			}
			JsonObject b = new JsonObject();
			b.addProperty("bedrock", "minecraft:" + bedrock);
			b.add("tags", tags);
			out.add(java, b);
		}
		Json.writeCompact(assets.resolve("lanature/bedrock/biomes_java.json"), out);
		report.info("biomes", out.size() + " Java biomes mapped to Bedrock biomes and tags, " + missing + " without one");
	}
}
