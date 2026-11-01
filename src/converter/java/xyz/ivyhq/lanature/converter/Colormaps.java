package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pack's colour maps that have no Java texture of the same picture: Bedrock's birch, evergreen and per-biome
 * foliage and grass maps, where Java uses a constant or a biome's colour. Each goes to {@code bedrock/colormaps/},
 * with {@code bedrock/colormaps.json} naming them, the vanilla client biomes that take their foliage or grass colour
 * from a map ({@code "color": {"color_map": "swamp_foliage"}}), and the blocks the engine tints with one
 * ({@code bedrock/engine/colormaps.json}). Items keep their tints: Bedrock draws a colour-mapped block's item with
 * the entry's carried textures, which {@link BlockConverter} handles.
 */
public final class Colormaps {
	private static final String FOLDER = "textures/colormap/";

	private Colormaps() {
	}

	/** Whether this step writes the texture (a colour map Java has no picture for). */
	public static boolean handles(TextureIdentity identity, String ref) {
		return ref.startsWith(FOLDER) && identity.javaFor(ref).isEmpty();
	}

	public static void run(BedrockPack pack, TextureIdentity identity, FileTree vanilla, JsonObject engine, Path assets, Report report)
		throws IOException {
		// maps the pack's own client biomes name, which may be new ones
		Set<String> named = new HashSet<>();
		for (String path : pack.packPaths("biomes/")) {
			JsonElement json = path.endsWith(".client_biome.json") ? pack.packJson(path) : null;
			if (json != null) {
				JsonObject components = json.getAsJsonObject().getAsJsonObject("minecraft:client_biome").getAsJsonObject("components");
				JsonObject refs = new JsonObject();
				map(components, "minecraft:foliage_appearance", "foliage", refs);
				map(components, "minecraft:grass_appearance", "grass", refs);
				refs.entrySet().forEach(e -> named.add(e.getValue().getAsString()));
			}
		}
		Map<String, BufferedImage> maps = new TreeMap<>();
		for (String path : pack.packPaths(FOLDER)) {
			String ext = path.substring(path.lastIndexOf('.'));
			String ref = path.substring(0, path.length() - ext.length());
			BedrockPack.Resolved tex = pack.texture(ref);
			if (!BedrockPack.TEXTURE_EXTENSIONS.contains(ext) || tex == null || !tex.path().equals(path) || !handles(identity, ref)) {
				continue;
			}
			String name = ref.substring(FOLDER.length());
			if (!pack.vanilla.has(ref + ".png") && !pack.vanilla.has(ref + ".tga") && !named.contains(name)) {
				report.info("colormaps", name + ": not a vanilla map and named by no client biome; unused");
				continue;
			}
			BufferedImage img = Images.decode(path, tex.bytes());
			maps.put(name, img);
			Path out = assets.resolve("lanature/bedrock/colormaps/" + name + ".png");
			Files.createDirectories(out.getParent());
			Files.write(out, Images.png(img));
		}
		JsonObject biomes = new JsonObject();
		for (String path : vanilla.paths("biomes/")) {
			if (!path.endsWith(".client_biome.json")) {
				continue;
			}
			JsonObject root = Json.parse(vanilla.get(path)).getAsJsonObject().getAsJsonObject("minecraft:client_biome");
			JsonObject components = root.getAsJsonObject("components");
			JsonObject refs = new JsonObject();
			map(components, "minecraft:foliage_appearance", "foliage", refs);
			map(components, "minecraft:grass_appearance", "grass", refs);
			if (refs.size() > 0) {
				biomes.add(root.getAsJsonObject("description").get("identifier").getAsString(), refs);
			}
		}
		JsonObject blocks = new JsonObject();
		for (Map.Entry<String, JsonElement> e : engine.getAsJsonObject("blocks").entrySet()) {
			if (maps.containsKey(e.getValue().getAsString())) {
				blocks.add(e.getKey(), e.getValue());
			}
		}
		JsonObject out = new JsonObject();
		JsonArray names = new JsonArray();
		maps.keySet().forEach(names::add);
		out.add("maps", names);
		out.add("blocks", blocks);
		out.add("biomes", biomes);
		Json.write(assets.resolve("lanature/bedrock/colormaps.json"), out);
		report.info("colormaps", maps.size() + " maps " + maps.keySet() + "; blocks " + blocks.keySet() + "; " + biomes.size() + " vanilla biomes name a map");
	}

	private static void map(JsonObject components, String component, String key, JsonObject out) {
		JsonObject c = components.getAsJsonObject(component);
		if (c != null && c.get("color") instanceof JsonObject color && color.has("color_map")) {
			out.add(key, color.get("color_map"));
		}
	}
}
