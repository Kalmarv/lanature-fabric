package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which Bedrock vanilla texture is the same picture as which Java vanilla texture. Three sources, in order:
 * GeyserMC PackConverter's rename table, the same name in the corresponding folder, and identical pixels.
 * Bedrock references are extensionless pack paths ({@code textures/blocks/sapling_oak}); Java ones are texture
 * paths without namespace ({@code block/oak_sapling}).
 */
public final class TextureIdentity {
	private static final Map<String, String> FOLDERS = Map.of("block", "blocks", "item", "items");
	private final Map<String, Set<String>> javaToBedrock = new HashMap<>();
	private final Map<String, Set<String>> bedrockToJava = new HashMap<>();
	private final Map<String, String> source = new HashMap<>();

	public TextureIdentity(JsonObject packConverterTable, JsonObject extra, FileTree bedrockVanilla, JavaAssets java, Report report) throws IOException {
		int table = 0;
		int named = 0;
		int pixels = 0;
		for (Map.Entry<String, JsonElement> e : packConverterTable.entrySet()) {
			if (e.getValue().isJsonObject()) {
				String javaFolder = e.getKey();
				String bedrockFolder = FOLDERS.getOrDefault(javaFolder, javaFolder);
				table += section(e.getValue().getAsJsonObject(), javaFolder, "textures/" + bedrockFolder + "/", bedrockVanilla, java);
			} else {
				for (String bedrockPath : strings(e.getValue())) {
					table += link(e.getKey(), "textures/" + bedrockPath, "table", bedrockVanilla, java);
				}
			}
		}
		for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
			if (!e.getKey().startsWith("_")) {
				table += link(e.getKey(), e.getValue().getAsString(), "extra", bedrockVanilla, java);
			}
		}
		for (String path : java.tree().paths("textures/")) {
			if (!path.endsWith(".png")) {
				continue;
			}
			String javaTex = path.substring("textures/".length(), path.length() - 4);
			int slash = javaTex.indexOf('/');
			if (slash < 0) {
				continue;
			}
			String folder = javaTex.substring(0, slash);
			String bedrockRef = "textures/" + FOLDERS.getOrDefault(folder, folder) + javaTex.substring(slash);
			named += link(javaTex, bedrockRef, "name", bedrockVanilla, java);
		}
		// Identical pixels, over the folders block models and items draw from.
		Map<String, List<String>> javaByHash = new HashMap<>();
		for (String folder : List.of("block", "item")) {
			for (String path : java.tree().paths("textures/" + folder + "/")) {
				if (path.endsWith(".png")) {
					BufferedImage img = Images.decode(path, java.tree().get(path));
					javaByHash.computeIfAbsent(Images.pixelHash(img), k -> new ArrayList<>())
						.add(path.substring("textures/".length(), path.length() - 4));
				}
			}
		}
		for (String folder : List.of("blocks", "items")) {
			for (String path : bedrockVanilla.paths("textures/" + folder + "/")) {
				String ext = path.endsWith(".png") ? ".png" : path.endsWith(".tga") ? ".tga" : null;
				if (ext == null) {
					continue;
				}
				String ref = path.substring(0, path.length() - 4);
				BufferedImage img;
				try {
					img = Images.decode(path, bedrockVanilla.get(path));
				} catch (IOException ex) {
					report.warn("identity", "cannot decode vanilla " + path + ": " + ex.getMessage());
					continue;
				}
				for (String javaTex : javaByHash.getOrDefault(Images.pixelHash(img), List.of())) {
					pixels += link(javaTex, ref, "pixels", bedrockVanilla, java);
				}
			}
		}
		report.info("identity", "texture links: " + table + " from PackConverter's table, " + named + " by name, " + pixels + " by identical pixels");
	}

	/** A folder section: name → Bedrock name, or a nested object for a Java subfolder (Bedrock names stay in the section folder). */
	private int section(JsonObject sec, String javaPrefix, String bedrockPrefix, FileTree bedrockVanilla, JavaAssets java) {
		int n = 0;
		for (Map.Entry<String, JsonElement> m : sec.entrySet()) {
			if (m.getValue().isJsonObject()) {
				n += section(m.getValue().getAsJsonObject(), javaPrefix + "/" + m.getKey(), bedrockPrefix, bedrockVanilla, java);
			} else {
				for (String bedrockName : strings(m.getValue())) {
					n += link(javaPrefix + "/" + m.getKey(), bedrockPrefix + bedrockName, "table", bedrockVanilla, java);
				}
			}
		}
		return n;
	}

	private int link(String javaTex, String bedrockRef, String how, FileTree bedrockVanilla, JavaAssets java) {
		if (!java.hasTexture(javaTex)) {
			return 0;
		}
		if (!bedrockVanilla.has(bedrockRef + ".png") && !bedrockVanilla.has(bedrockRef + ".tga")) {
			return 0;
		}
		boolean added = javaToBedrock.computeIfAbsent(javaTex, k -> new LinkedHashSet<>()).add(bedrockRef);
		bedrockToJava.computeIfAbsent(bedrockRef, k -> new LinkedHashSet<>()).add(javaTex);
		if (added) {
			source.put(javaTex + "|" + bedrockRef, how);
			return 1;
		}
		return 0;
	}

	private static Collection<String> strings(JsonElement e) {
		List<String> out = new ArrayList<>();
		if (e.isJsonArray()) {
			e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
		} else {
			out.add(e.getAsString());
		}
		return out;
	}

	public boolean same(String javaTex, String bedrockRef) {
		return javaToBedrock.getOrDefault(javaTex, Set.of()).contains(bedrockRef);
	}

	public Set<String> bedrockFor(String javaTex) {
		return javaToBedrock.getOrDefault(javaTex, Set.of());
	}

	public Set<String> javaFor(String bedrockRef) {
		return bedrockToJava.getOrDefault(bedrockRef, Set.of());
	}
}
