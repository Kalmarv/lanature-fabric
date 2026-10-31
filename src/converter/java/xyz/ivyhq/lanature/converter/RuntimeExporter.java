package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Bedrock definitions the client runtime interprets, as strict JSON under {@code assets/lanature/bedrock/}, with
 * the particle textures they draw exported into the particle atlas and each effect's {@code texture} rewritten to its
 * sprite id.
 */
public final class RuntimeExporter {
	/** Folders copied as they are, subpack files over root files of the same path. */
	private static final List<String> FOLDERS = List.of("particles/", "entity/", "animation_controllers/", "animations/",
		"render_controllers/", "models/", "attachables/", "fogs/", "biomes/");
	/** Single files: pack path → path under {@code bedrock/}. */
	private static final Map<String, String> FILES = Map.of("sounds.json", "sounds.json", "biomes_client.json", "biomes_client.json",
		"manifest.json", "manifest.json", "materials/particles.material", "materials/particles.json");

	private final BedrockPack pack;
	private final TextureExporter textures;
	private final Path assets;
	private final Report report;
	/** Bedrock block names the particles test for ({@code particle_expire_if_[not_]in_blocks}). */
	private final Set<String> blockNames = new java.util.TreeSet<>();

	public RuntimeExporter(BedrockPack pack, TextureExporter textures, Path assets, Report report) {
		this.pack = pack;
		this.textures = textures;
		this.assets = assets;
		this.report = report;
	}

	public void run() throws IOException {
		Map<String, JsonElement> definitions = new LinkedHashMap<>();
		for (String folder : FOLDERS) {
			for (String path : pack.packPaths(folder)) {
				JsonElement json = path.endsWith(".json") ? parse(path) : null;
				if (json != null) {
					definitions.put(TextureExporter.sanitize(path), json);
				}
			}
		}
		Set<String> particleIds = new LinkedHashSet<>();
		for (Map.Entry<String, JsonElement> e : definitions.entrySet()) {
			if (e.getKey().startsWith("particles/")) {
				particleIds.add(description(e.getValue()).get("identifier").getAsString());
			}
		}
		vanillaParticles(particleIds, definitions);
		int textured = 0;
		for (Map.Entry<String, JsonElement> e : definitions.entrySet()) {
			if (e.getKey().startsWith("particles/") || e.getKey().startsWith("vanilla/particles/")) {
				textured += particleSprite(e.getValue()) ? 1 : 0;
				JsonObject components = e.getValue().getAsJsonObject().getAsJsonObject("particle_effect").getAsJsonObject("components");
				for (String key : List.of("minecraft:particle_expire_if_in_blocks", "minecraft:particle_expire_if_not_in_blocks")) {
					if (components != null && components.has(key)) {
						components.getAsJsonArray(key).forEach(b -> blockNames.add(b.getAsString()));
					}
				}
			}
			Json.writeCompact(assets.resolve("lanature/bedrock/" + e.getKey()), e.getValue());
		}
		int files = definitions.size();
		for (Map.Entry<String, String> file : FILES.entrySet()) {
			JsonElement json = parse(file.getKey());
			if (json != null) {
				Json.writeCompact(assets.resolve("lanature/bedrock/" + file.getValue()), json);
				files++;
			}
		}
		report.info("runtime", files + " Bedrock definitions exported, " + particleIds.size() + " pack particle effects, "
			+ textured + " effects textured");
	}

	public Set<String> blockNames() {
		return blockNames;
	}

	private static JsonObject description(JsonElement effect) {
		return effect.getAsJsonObject().getAsJsonObject("particle_effect").getAsJsonObject("description");
	}

	/**
	 * Exports an effect's texture into the particle atlas and replaces its {@code texture} path with the sprite id
	 * ({@code lanature:bedrock/<path>}: the atlas's {@code particle} directory source drops that prefix).
	 */
	private boolean particleSprite(JsonElement effect) throws IOException {
		JsonObject params = description(effect).getAsJsonObject("basic_render_parameters");
		if (params == null || !params.has("texture")) {
			return false;
		}
		String id = textures.export(params.get("texture").getAsString(), "particle/bedrock");
		if (id == null) {
			params.remove("texture");
			return false;
		}
		params.addProperty("texture", id.replaceFirst(":particle/", ":"));
		return true;
	}

	/** Vanilla effects the pack's events spawn but does not define, so every event resolves. */
	private void vanillaParticles(Set<String> defined, Map<String, JsonElement> definitions) {
		Map<String, String> vanilla = new HashMap<>();
		for (String path : pack.vanilla.paths("particles/")) {
			if (path.endsWith(".json")) {
				vanilla.put(description(Json.parse(pack.vanilla.get(path))).get("identifier").getAsString(), path);
			}
		}
		Set<String> wanted = new LinkedHashSet<>();
		definitions.forEach((path, json) -> {
			if (path.startsWith("particles/")) {
				collectEffects(json.getAsJsonObject().getAsJsonObject("particle_effect").get("events"), wanted);
			}
		});
		for (String id : wanted) {
			if (defined.contains(id)) {
				continue;
			}
			String path = vanilla.get(id);
			if (path == null) {
				report.warn("runtime", "event spawns undefined effect " + id);
				continue;
			}
			definitions.put("vanilla/" + TextureExporter.sanitize(path), Json.parse(pack.vanilla.get(path)));
		}
	}

	private static void collectEffects(JsonElement node, Set<String> out) {
		if (node == null) {
			return;
		}
		if (node.isJsonObject()) {
			JsonObject o = node.getAsJsonObject();
			if (o.has("particle_effect") && o.get("particle_effect").isJsonObject() && o.getAsJsonObject("particle_effect").has("effect")) {
				out.add(o.getAsJsonObject("particle_effect").get("effect").getAsString());
			}
			o.entrySet().forEach(e -> collectEffects(e.getValue(), out));
		} else if (node.isJsonArray()) {
			node.getAsJsonArray().forEach(e -> collectEffects(e, out));
		}
	}

	private JsonElement parse(String path) {
		byte[] bytes = pack.packFile(path);
		if (bytes == null) {
			return null;
		}
		try {
			return Json.parse(bytes);
		} catch (RuntimeException e) {
			report.warn("runtime", path + ": " + e.getMessage());
			return null;
		}
	}
}
