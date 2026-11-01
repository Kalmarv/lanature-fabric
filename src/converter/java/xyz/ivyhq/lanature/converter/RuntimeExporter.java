package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
		"manifest.json", "manifest.json", "materials/particles.material", "materials/particles.json", "materials/entity.material", "materials/entity.json");

	private final BedrockPack pack;
	private final TextureExporter textures;
	private final BindPoses bindPoses;
	private final Path assets;
	private final Report report;
	/** Render controllers the engine defines itself ({@code bedrock/engine/render_controllers.json}). */
	private final Set<String> engineRenderControllers;
	/** Bedrock block names the particles test for ({@code particle_expire_if_[not_]in_blocks}). */
	private final Set<String> blockNames = new java.util.TreeSet<>();

	public RuntimeExporter(BedrockPack pack, TextureExporter textures, BindPoses bindPoses, Set<String> engineRenderControllers, Path assets,
		Report report) {
		this.engineRenderControllers = engineRenderControllers;
		this.pack = pack;
		this.textures = textures;
		this.bindPoses = bindPoses;
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
		vanillaFogs(definitions);
		vanillaWater(definitions);
		int entityTextures = entityModels(definitions);
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
			+ textured + " effects textured, " + entityTextures + " entity textures");
	}

	/**
	 * Vanilla Bedrock's entity materials, from the last {@code entity.material} Mojang shipped in a resource pack (the
	 * 1.16.100 beta; materials are compiled into the game since), as {@code bedrock/vanilla/materials/entity.json}: the
	 * base the runtime resolves the pack's materials and its own additions for newer mobs on.
	 */
	public static void vanillaEntityMaterials(Path file, Path assets, Report report) throws IOException {
		JsonObject materials = Json.parse(java.nio.file.Files.readAllBytes(file)).getAsJsonObject();
		Json.writeCompact(assets.resolve("lanature/bedrock/vanilla/materials/entity.json"), materials);
		report.info("runtime", "vanilla entity materials: " + (materials.getAsJsonObject("materials").size() - 1) + " from " + file.getFileName());
	}

	public Set<String> blockNames() {
		return blockNames;
	}

	private static JsonObject description(JsonElement effect) {
		return effect.getAsJsonObject().getAsJsonObject("particle_effect").getAsJsonObject("description");
	}

	/**
	 * Exports an effect's texture into the particle atlas and replaces its {@code texture} path with the sprite id
	 * ({@code lanature:bedrock/<path>}: the atlas's {@code particle} directory source drops that prefix). Bedrock's
	 * block and item atlases ({@code atlas.terrain}, {@code atlas.items}) stay named.
	 */
	private boolean particleSprite(JsonElement effect) throws IOException {
		JsonObject params = description(effect).getAsJsonObject("basic_render_parameters");
		if (params == null || !params.has("texture")) {
			return false;
		}
		String texture = params.get("texture").getAsString();
		if (texture.equals("atlas.terrain") || texture.equals("atlas.items")) {
			return true; // the block or item atlas, which the runtime reads as Java's
		}
		String id = textures.export(texture, "particle/bedrock");
		if (id == null) {
			params.remove("texture");
			return false;
		}
		params.addProperty("texture", id.replaceFirst(":particle/", ":"));
		return true;
	}

	/**
	 * Vanilla effects the pack starts but does not define, so every one resolves: those its particle events spawn and
	 * its client entities and attachables (and the vanilla definitions exported for them) name in
	 * {@code particle_effects}, and the effects those vanilla ones spawn in turn.
	 */
	private void vanillaParticles(Set<String> defined, Map<String, JsonElement> definitions) {
		Map<String, String> vanilla = new HashMap<>();
		for (String path : pack.vanilla.paths("particles/")) {
			if (path.endsWith(".json")) {
				vanilla.put(description(Json.parse(pack.vanilla.get(path))).get("identifier").getAsString(), path);
			}
		}
		Deque<String> wanted = new ArrayDeque<>();
		definitions.forEach((path, json) -> {
			String folder = path.replaceFirst("^vanilla/", "");
			if (folder.startsWith("particles/")) {
				collectEffects(json.getAsJsonObject().getAsJsonObject("particle_effect").get("events"), wanted);
			} else if (folder.startsWith("entity/") || folder.startsWith("attachables/")) {
				namedEffects(json, wanted);
			}
		});
		Set<String> seen = new HashSet<>();
		Set<String> added = new java.util.TreeSet<>();
		while (!wanted.isEmpty()) {
			String id = wanted.poll();
			if (!seen.add(id) || defined.contains(id)) {
				continue;
			}
			String path = vanilla.get(id);
			if (path == null) {
				// the pack names effects that neither it nor vanilla defines; Bedrock spawns nothing for them, as the runtime does
				report.info("runtime", "the pack starts " + id + ", which neither it nor vanilla defines; nothing spawns, as in Bedrock");
				continue;
			}
			JsonElement json = Json.parse(pack.vanilla.get(path));
			definitions.put("vanilla/" + TextureExporter.sanitize(path), json);
			added.add(id);
			collectEffects(json.getAsJsonObject().getAsJsonObject("particle_effect").get("events"), wanted);
		}
		report.info("runtime", added.size() + " vanilla effects the pack starts but does not define: " + added);
	}

	/** The effects a client entity or attachable names in its description's {@code particle_effects}. */
	private static void namedEffects(JsonElement definition, Collection<String> out) {
		if (!definition.isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> root : definition.getAsJsonObject().entrySet()) {
			if (root.getValue().isJsonObject() && root.getValue().getAsJsonObject().get("description") instanceof JsonObject description
				&& description.get("particle_effects") instanceof JsonObject effects) {
				effects.entrySet().forEach(e -> out.add(e.getValue().getAsString()));
			}
		}
	}

	/** Where each kind of entity resource is defined, and the key its files list identifiers under. */
	private record Kind(String folder, String section) {
	}

	private static final Kind GEOMETRY = new Kind("models/", null);
	private static final Kind RENDER_CONTROLLERS = new Kind("render_controllers/", "render_controllers");
	private static final Kind ANIMATIONS = new Kind("animations/", "animations");
	private static final Kind CONTROLLERS = new Kind("animation_controllers/", "animation_controllers");

	/**
	 * What the pack's client entities and attachables (held items' models) need to draw their models: the vanilla geometry (with the parents legacy
	 * geometry inherits), render controllers, animations and animation controllers they name but the pack does not
	 * define, under {@code vanilla/}, and their textures, exported as standalone textures with each definition's
	 * {@code textures} entry rewritten to the Java texture id. Returns the number of textures exported.
	 */
	private int entityModels(Map<String, JsonElement> definitions) throws IOException {
		Map<Kind, Set<String>> wanted = new LinkedHashMap<>();
		for (Kind k : List.of(GEOMETRY, RENDER_CONTROLLERS, ANIMATIONS, CONTROLLERS)) {
			wanted.put(k, new LinkedHashSet<>());
		}
		int exportedTextures = 0;
		for (Map.Entry<String, JsonElement> e : definitions.entrySet()) {
			String rootKey = e.getKey().startsWith("entity/") ? "minecraft:client_entity"
				: e.getKey().startsWith("attachables/") ? "minecraft:attachable" : null;
			if (rootKey == null) {
				continue;
			}
			JsonObject root = e.getValue().getAsJsonObject().getAsJsonObject(rootKey);
			JsonObject desc = root == null ? null : root.getAsJsonObject("description");
			if (desc == null) {
				continue;
			}
			strings(desc.get("geometry"), wanted.get(GEOMETRY));
			renderControllers(desc.get("render_controllers"), wanted.get(RENDER_CONTROLLERS));
			// an animations entry names either an animation or a controller
			strings(desc.get("animations"), wanted.get(ANIMATIONS));
			strings(desc.get("animations"), wanted.get(CONTROLLERS));
			JsonObject tex = desc.getAsJsonObject("textures");
			if (tex != null) {
				for (Map.Entry<String, JsonElement> t : tex.entrySet()) {
					String id = textures.export(t.getValue().getAsString(), "entity/bedrock");
					if (id != null) {
						int colon = id.indexOf(':');
						t.setValue(new com.google.gson.JsonPrimitive(id.substring(0, colon) + ":textures/" + id.substring(colon + 1) + ".png"));
						exportedTextures++;
					}
				}
			}
		}
		for (Map.Entry<Kind, Set<String>> w : wanted.entrySet()) {
			Kind kind = w.getKey();
			Set<String> defined = new HashSet<>();
			definitions.forEach((path, json) -> {
				if (path.startsWith(kind.folder()) || path.startsWith("vanilla/" + kind.folder())) {
					defined.addAll(identifiers(kind, json));
				}
			});
			Map<String, String> vanilla = new HashMap<>();
			Map<String, JsonElement> vanillaJson = new HashMap<>();
			for (String path : pack.vanilla.paths(kind.folder())) {
				if (!path.endsWith(".json")) {
					continue;
				}
				JsonElement json;
				try {
					json = Json.parse(pack.vanilla.get(path));
				} catch (RuntimeException ex) {
					report.warn("runtime", "vanilla " + path + ": " + ex.getMessage());
					continue;
				}
				vanillaJson.put(path, json);
				for (String id : identifiers(kind, json)) {
					vanilla.putIfAbsent(id, path);
				}
			}
			java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(w.getValue());
			while (!queue.isEmpty()) {
				String id = queue.poll();
				if (defined.contains(id)) {
					continue;
				}
				String path = vanilla.get(id);
				if (path == null) {
					if (kind == RENDER_CONTROLLERS && engineRenderControllers.contains(id)) {
						report.info("runtime", id + " is the engine's own (bedrock/engine/render_controllers.json)");
					} else if (kind == GEOMETRY || kind == RENDER_CONTROLLERS) {
						report.warn("runtime", "entities name undefined " + id);
					}
					continue;
				}
				JsonElement json = vanillaJson.get(path);
				definitions.put("vanilla/" + TextureExporter.sanitize(path), json);
				defined.addAll(identifiers(kind, json));
				if (kind == GEOMETRY) {
					bindPoses.restore(path, json);
					queue.addAll(geometryParents(json));
				}
			}
		}
		return exportedTextures;
	}

	/** The identifiers a file of the kind defines (legacy geometry keys without their {@code :parent}). */
	private static Set<String> identifiers(Kind kind, JsonElement json) {
		Set<String> out = new LinkedHashSet<>();
		if (!json.isJsonObject()) {
			return out;
		}
		JsonObject o = json.getAsJsonObject();
		if (kind == GEOMETRY) {
			if (o.has("minecraft:geometry") && o.get("minecraft:geometry").isJsonArray()) {
				o.getAsJsonArray("minecraft:geometry").forEach(g -> out.add(g.getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString()));
			}
			o.keySet().forEach(k -> {
				if (k.startsWith("geometry.")) {
					out.add(k.split(":", 2)[0]);
				}
			});
		} else if (o.has(kind.section()) && o.get(kind.section()).isJsonObject()) {
			out.addAll(o.getAsJsonObject(kind.section()).keySet());
		}
		return out;
	}

	/** The geometries legacy geometry keys inherit from ({@code geometry.a:geometry.b}). */
	private static Set<String> geometryParents(JsonElement json) {
		Set<String> out = new LinkedHashSet<>();
		if (json.isJsonObject()) {
			json.getAsJsonObject().keySet().forEach(k -> {
				String[] parts = k.split(":", 2);
				if (parts[0].startsWith("geometry.") && parts.length == 2) {
					out.add(parts[1]);
				}
			});
		}
		return out;
	}

	private static void strings(JsonElement e, Set<String> out) {
		if (e != null && e.isJsonObject()) {
			e.getAsJsonObject().entrySet().forEach(x -> {
				if (x.getValue().isJsonPrimitive()) {
					out.add(x.getValue().getAsString());
				}
			});
		}
	}

	/** Render controller ids: plain strings, or one-key objects whose value is the condition. */
	private static void renderControllers(JsonElement e, Set<String> out) {
		if (e == null || !e.isJsonArray()) {
			return;
		}
		for (JsonElement x : e.getAsJsonArray()) {
			if (x.isJsonPrimitive()) {
				out.add(x.getAsString());
			} else if (x.isJsonObject()) {
				out.addAll(x.getAsJsonObject().keySet());
			}
		}
	}

	/**
	 * Vanilla fogs the pack's biomes name but do not define ({@code minecraft:fog_birch_forest} in a client biome file),
	 * under {@code vanilla/fogs/}.
	 */
	private void vanillaFogs(Map<String, JsonElement> definitions) {
		Set<String> defined = new HashSet<>();
		Set<String> wanted = new java.util.TreeSet<>();
		definitions.forEach((path, json) -> {
			if (path.startsWith("fogs/")) {
				defined.add(json.getAsJsonObject().getAsJsonObject("minecraft:fog_settings").getAsJsonObject("description").get("identifier").getAsString());
			} else if (path.startsWith("biomes/")) {
				JsonObject fog = json.getAsJsonObject().getAsJsonObject("minecraft:client_biome").getAsJsonObject("components")
					.getAsJsonObject("minecraft:fog_appearance");
				if (fog != null) {
					wanted.add(fog.get("fog_identifier").getAsString());
				}
			}
		});
		JsonElement legacy = parse("biomes_client.json");
		if (legacy != null) {
			legacy.getAsJsonObject().getAsJsonObject("biomes").entrySet().forEach(e -> {
				JsonElement id = e.getValue().getAsJsonObject().get("fog_identifier");
				if (id != null) {
					wanted.add(id.getAsString());
				}
			});
		}
		Map<String, String> vanilla = new HashMap<>();
		for (String path : pack.vanilla.paths("fogs/")) {
			if (path.endsWith(".json")) {
				vanilla.put(Json.parse(pack.vanilla.get(path)).getAsJsonObject().getAsJsonObject("minecraft:fog_settings")
					.getAsJsonObject("description").get("identifier").getAsString(), path);
			}
		}
		for (String id : wanted) {
			if (defined.contains(id)) {
				continue;
			}
			String path = vanilla.get(id);
			if (path == null) {
				report.warn("runtime", "biome uses undefined fog " + id);
				continue;
			}
			definitions.put("vanilla/" + TextureExporter.sanitize(path), Json.parse(pack.vanilla.get(path)));
		}
	}

	/**
	 * Vanilla's water surface transparencies, which the pack's are measured against (on Java, vanilla's water is the
	 * game's own): its {@code biomes_client.json} as {@code vanilla/biomes_client.json}, and the vanilla client biomes
	 * that set one ({@code minecraft:water_appearance} {@code surface_opacity}) under {@code vanilla/biomes/}.
	 */
	private void vanillaWater(Map<String, JsonElement> definitions) {
		byte[] legacy = pack.vanilla.get("biomes_client.json");
		if (legacy != null) {
			definitions.put("vanilla/biomes_client.json", Json.parse(legacy));
		}
		for (String path : pack.vanilla.paths("biomes/")) {
			if (!path.endsWith(".json")) {
				continue;
			}
			JsonElement json = Json.parse(pack.vanilla.get(path));
			JsonObject components = json.getAsJsonObject().getAsJsonObject("minecraft:client_biome").getAsJsonObject("components");
			JsonObject water = components == null ? null : components.getAsJsonObject("minecraft:water_appearance");
			if (water != null && water.has("surface_opacity")) {
				definitions.put("vanilla/" + TextureExporter.sanitize(path), json);
			}
		}
	}

	private static void collectEffects(JsonElement node, Collection<String> out) {
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
