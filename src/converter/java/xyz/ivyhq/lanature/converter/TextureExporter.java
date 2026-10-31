package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes Bedrock textures as Java PNGs under {@code assets/lanature/textures/<folder>/}, decoding TGA, and attaches
 * flipbook animations as {@code .png.mcmeta}. A texture already exported to a folder is written once.
 */
public final class TextureExporter {
	private final BedrockPack pack;
	private final Path assets;
	private final Report report;
	private final Map<String, JsonObject> flipbooks = new HashMap<>();
	private final Map<String, String> exported = new HashMap<>();

	public TextureExporter(BedrockPack pack, Path assets, Report report) {
		this.pack = pack;
		this.assets = assets;
		this.report = report;
		JsonElement fb = pack.packJson("textures/flipbook_textures.json");
		if (fb != null) {
			for (JsonElement e : fb.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				flipbooks.putIfAbsent(o.get("flipbook_texture").getAsString(), o);
			}
		}
	}

	/** {@code textures/ln_b/Oak Leaves/x} → {@code ln_b/oak_leaves/x}: the path Java resource ids allow. */
	public static String sanitize(String ref) {
		String r = ref.startsWith("textures/") ? ref.substring("textures/".length()) : ref;
		StringBuilder out = new StringBuilder(r.length());
		for (char c : r.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
		}
		return out.toString();
	}

	/**
	 * Exports a Bedrock texture reference into {@code textures/<folder>/} and returns its Java texture id
	 * ({@code lanature:<folder>/<path>}), or null when the reference resolves to nothing.
	 */
	public String export(String ref, String folder) throws IOException {
		String key = folder + "|" + ref;
		if (exported.containsKey(key)) {
			return exported.get(key);
		}
		BedrockPack.Resolved r = pack.texture(ref);
		String id = null;
		if (r != null) {
			String path = folder + "/" + sanitize(ref);
			Path out = assets.resolve("lanature/textures/" + path + ".png");
			Files.createDirectories(out.getParent());
			java.awt.image.BufferedImage image = Images.decode(r.path(), r.bytes());
			Files.write(out, Images.png(image));
			JsonObject flip = flipbooks.get(ref);
			if (flip != null) {
				int frameCount = Math.max(1, image.getHeight() / image.getWidth());
				JsonObject meta = animation(flip, frameCount);
				int listed = flip.has("frames") && flip.get("frames").isJsonArray() ? flip.getAsJsonArray("frames").size() : 0;
				int kept = meta.getAsJsonObject("animation").has("frames") ? meta.getAsJsonObject("animation").getAsJsonArray("frames").size() : listed;
				if (kept < listed) {
					report.warn("textures", ref + ": flipbook lists " + (listed - kept) + " frame(s) past its " + frameCount + " frames; dropped");
				}
				Json.write(Path.of(out + ".mcmeta"), meta);
			}
			id = "lanature:" + path;
		} else {
			report.warn("textures", "missing texture " + ref);
		}
		exported.put(key, id);
		return id;
	}

	/**
	 * Bedrock flipbook → Java animation metadata. Frames are square tiles stacked vertically; Java refuses a frame
	 * index past the last tile, so such entries are dropped.
	 */
	static JsonObject animation(JsonObject flip, int frameCount) {
		JsonObject anim = new JsonObject();
		if (flip.has("ticks_per_frame")) {
			anim.addProperty("frametime", Math.max(1, flip.get("ticks_per_frame").getAsInt()));
		}
		if (flip.has("blend_frames")) {
			anim.addProperty("interpolate", flip.get("blend_frames").getAsBoolean());
		}
		if (flip.has("frames") && flip.get("frames").isJsonArray()) {
			JsonArray frames = new JsonArray();
			flip.getAsJsonArray("frames").forEach(f -> {
				if (!f.isJsonPrimitive() || f.getAsInt() < frameCount) {
					frames.add(f);
				}
			});
			anim.add("frames", frames);
		}
		JsonObject meta = new JsonObject();
		meta.add("animation", anim);
		return meta;
	}
}
