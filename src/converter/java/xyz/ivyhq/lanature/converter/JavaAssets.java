package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Vanilla Java client assets ({@code assets/minecraft/...} in the client jar). */
public final class JavaAssets {
	private final FileTree assets;
	private final Map<String, Model> models = new HashMap<>();

	public JavaAssets(FileTree clientJar) {
		this.assets = clientJar.sub("assets/minecraft");
	}

	public FileTree tree() {
		return assets;
	}

	public JsonObject blockstate(String block) {
		byte[] b = assets.get("blockstates/" + block + ".json");
		return b == null ? null : Json.parse(b).getAsJsonObject();
	}

	public JsonObject item(String item) {
		byte[] b = assets.get("items/" + item + ".json");
		return b == null ? null : Json.parse(b).getAsJsonObject();
	}

	public boolean hasTexture(String path) {
		return assets.has("textures/" + path + ".png");
	}

	public byte[] texture(String path) {
		return assets.get("textures/" + path + ".png");
	}

	/** {@code minecraft:block/x}, {@code block/x} → {@code block/x}. */
	public static String strip(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	/** A model with its parent chain resolved: the effective elements and texture variables. */
	public Model model(String id) {
		String path = strip(id);
		Model cached = models.get(path);
		if (cached != null) {
			return cached;
		}
		byte[] b = assets.get("models/" + path + ".json");
		if (b == null) {
			return null;
		}
		JsonObject json = Json.parse(b).getAsJsonObject();
		Model parent = json.has("parent") ? model(json.get("parent").getAsString()) : null;
		Map<String, String> textures = new LinkedHashMap<>(parent == null ? Map.of() : parent.textures);
		if (json.has("textures")) {
			for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("textures").entrySet()) {
				if (e.getValue().isJsonPrimitive()) {
					textures.put(e.getKey(), e.getValue().getAsString());
				}
			}
		}
		JsonArray elements = json.has("elements") ? json.getAsJsonArray("elements") : parent == null ? null : parent.elements;
		Boolean ao = parent == null ? null : parent.ambientOcclusion;
		if (json.has("ambientocclusion")) {
			ao = json.get("ambientocclusion").getAsBoolean();
		}
		Model m = new Model(path, json, parent, textures, elements, ao);
		models.put(path, m);
		return m;
	}

	public record Model(String path, JsonObject json, Model parent, Map<String, String> textures, JsonArray elements, Boolean ambientOcclusion) {
		/** Follows {@code #var} references to a texture id, or null when unbound. */
		public String resolve(String ref) {
			String r = ref;
			for (int depth = 0; depth < 16 && r != null && r.startsWith("#"); depth++) {
				r = textures.get(r.substring(1));
			}
			return r == null || r.startsWith("#") ? null : strip(r);
		}

		/** The nearest ancestor (or self) that declares elements. */
		public Model elementSource() {
			Model m = this;
			while (m != null && !m.json.has("elements")) {
				m = m.parent;
			}
			return m;
		}
	}
}
