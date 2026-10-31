package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bedrock {@code sound_definitions.json} → Java {@code sounds.json}.
 *
 * <p>Every pack sound event becomes {@code lanature:<bedrock name>}, which the runtime plays for Bedrock sound
 * references. Where the pack redefines a vanilla Bedrock event, the Java events Geyser maps to it are replaced too.
 * Pack {@code .ogg} files are copied; references to vanilla Bedrock files resolve to Java's own sound through
 * PackConverter's sound-path table.
 */
public final class SoundConverter {
	private final BedrockPack pack;
	private final JsonObject geyserSounds;
	private final Map<String, String> bedrockToJavaFile = new HashMap<>();
	/** Java sound files without extension ({@code block/stone/break1}), from the asset index. */
	private final Set<String> javaFiles;
	private final Path assets;
	private final Report report;

	public SoundConverter(BedrockPack pack, JsonObject geyserSounds, JsonObject packConverterSounds, Set<String> javaFiles, Path assets, Report report) {
		this.pack = pack;
		this.javaFiles = javaFiles;
		this.geyserSounds = geyserSounds;
		this.assets = assets;
		this.report = report;
		table(packConverterSounds, "");
	}

	/** The sound files in Minecraft's asset index, as paths under {@code sounds/} without {@code .ogg}. */
	public static Set<String> javaSounds(JsonObject assetIndex) {
		Set<String> out = new HashSet<>();
		for (String key : assetIndex.getAsJsonObject("objects").keySet()) {
			if (key.startsWith("minecraft/sounds/") && key.endsWith(".ogg")) {
				out.add(key.substring("minecraft/sounds/".length(), key.length() - 4));
			}
		}
		return out;
	}

	/** Java path → Bedrock path(s); a nested object is a folder of both. */
	private void table(JsonObject section, String prefix) {
		for (Map.Entry<String, JsonElement> e : section.entrySet()) {
			if (e.getValue().isJsonObject()) {
				table(e.getValue().getAsJsonObject(), prefix + e.getKey() + "/");
			} else if (e.getValue().isJsonArray()) {
				e.getValue().getAsJsonArray().forEach(b -> bedrockToJavaFile.putIfAbsent("sounds/" + prefix + b.getAsString(), prefix + e.getKey()));
			} else {
				bedrockToJavaFile.putIfAbsent("sounds/" + prefix + e.getValue().getAsString(), prefix + e.getKey());
			}
		}
	}

	public void run() throws IOException {
		JsonObject defs = new JsonObject();
		for (FileTree layer : new FileTree[] {pack.root, pack.subpack}) {
			if (layer.has("sounds/sound_definitions.json")) {
				JsonObject d = Json.parse(layer.get("sounds/sound_definitions.json")).getAsJsonObject();
				JsonObject sd = d.has("sound_definitions") ? d.getAsJsonObject("sound_definitions") : d;
				sd.entrySet().forEach(e -> defs.add(e.getKey(), e.getValue()));
			}
		}
		JsonObject vanillaDefs = Json.parse(pack.vanilla.get("sounds/sound_definitions.json")).getAsJsonObject().getAsJsonObject("sound_definitions");
		JsonObject lanature = new JsonObject();
		JsonObject minecraft = new JsonObject();
		for (Map.Entry<String, JsonElement> e : defs.entrySet()) {
			JsonObject event = e.getValue().isJsonObject() ? definition(e.getKey(), e.getValue().getAsJsonObject()) : null;
			if (event == null) {
				continue;
			}
			lanature.add(eventName(e.getKey()), event);
			if (vanillaDefs.has(e.getKey())) {
				for (Map.Entry<String, JsonElement> g : geyserSounds.entrySet()) {
					JsonObject go = g.getValue().getAsJsonObject();
					if (go.has("playsound_mapping") && go.get("playsound_mapping").getAsString().equals(e.getKey())) {
						JsonObject replaced = event.deepCopy();
						replaced.addProperty("replace", true);
						minecraft.add(g.getKey(), replaced);
					}
				}
			}
		}
		int packEvents = lanature.size();
		JsonObject table = eventTable(lanature, vanillaDefs);
		entitySounds(lanature, vanillaDefs);
		Json.write(assets.resolve("lanature/sounds.json"), lanature);
		Json.writeCompact(assets.resolve("lanature/bedrock/sound_events.json"), table);
		if (!minecraft.isEmpty()) {
			Json.write(assets.resolve("minecraft/sounds.json"), minecraft);
		}
		report.info("sounds", packEvents + " events, " + copied + " pack sound files, " + minecraft.size() + " vanilla Java events replaced, "
			+ unresolved + " references unresolved; " + table.size() + " Bedrock event sounds, "
			+ (lanature.size() - packEvents) + " vanilla definitions they and the client entities play");
	}

	private int copied;
	private int unresolved;

	/** One Bedrock sound definition as a Java sound event, or null when none of its files resolve. */
	private JsonObject definition(String name, JsonObject def) throws IOException {
		JsonArray sounds = new JsonArray();
		JsonArray list = new JsonArray();
		JsonElement declared = def.get("sounds");
		if (declared != null && declared.isJsonArray()) {
			list = declared.getAsJsonArray();
		} else if (declared != null) {
			list.add(declared); // a single sound, as an object or a name
		}
		for (JsonElement s : list) {
			JsonObject so = s.isJsonObject() ? s.getAsJsonObject() : null;
			String file = so == null ? s.getAsString() : so.has("name") ? so.get("name").getAsString() : null;
			if (file == null) {
				continue;
			}
			String javaName = soundFile(file);
			if (javaName == null) {
				unresolved++;
				report.warn("sounds", name + ": no file for " + file);
				continue;
			}
			if (javaName.startsWith("lanature:")) {
				copied++;
			}
			JsonObject js = new JsonObject();
			js.addProperty("name", javaName);
			if (so != null) {
				copyNumber(so, "volume", js, "volume");
				copyNumber(so, "pitch", js, "pitch");
				copyNumber(so, "weight", js, "weight");
				if (so.has("stream") && so.get("stream").getAsBoolean()) {
					js.addProperty("stream", true);
				}
			}
			if (def.has("max_distance") && def.get("max_distance").isJsonPrimitive()) {
				js.addProperty("attenuation_distance", Math.round(def.get("max_distance").getAsDouble()));
			}
			sounds.add(js);
		}
		if (sounds.isEmpty()) {
			return null;
		}
		JsonObject event = new JsonObject();
		event.add("sounds", sounds);
		return event;
	}

	/**
	 * Bedrock's {@code individual_event_sounds} (what particle {@code sound_effect}s name): event → the Java sound
	 * event of the definition it plays, with its volume and pitch (a number or a {@code [min, max]} range). Vanilla
	 * definitions an event plays are added to {@code lanature} as they are needed.
	 */
	private JsonObject eventTable(JsonObject lanature, JsonObject vanillaDefs) throws IOException {
		JsonObject events = new JsonObject();
		for (FileTree layer : new FileTree[] {pack.vanilla, pack.root, pack.subpack}) {
			if (layer.has("sounds.json")) {
				JsonObject d = Json.parse(layer.get("sounds.json")).getAsJsonObject();
				if (d.has("individual_event_sounds") && d.getAsJsonObject("individual_event_sounds").has("events")) {
					d.getAsJsonObject("individual_event_sounds").getAsJsonObject("events").entrySet().forEach(e -> events.add(e.getKey(), e.getValue()));
				}
			}
		}
		JsonObject table = new JsonObject();
		for (Map.Entry<String, JsonElement> e : events.entrySet()) {
			JsonObject ev = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
			if (ev == null || !ev.has("sound") || ev.get("sound").getAsString().isEmpty()) {
				continue;
			}
			String def = ev.get("sound").getAsString();
			String id = eventName(def);
			if (!require(def, lanature, vanillaDefs)) {
				continue;
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("sound", "lanature:" + id);
			if (ev.has("volume")) {
				entry.add("volume", ev.get("volume"));
			}
			if (ev.has("pitch")) {
				entry.add("pitch", ev.get("pitch"));
			}
			table.add(e.getKey(), entry);
		}
		return table;
	}

	/** Makes sure {@code lanature} has the definition, converting vanilla Bedrock's when the pack has none; false if neither resolves. */
	private boolean require(String def, JsonObject lanature, JsonObject vanillaDefs) throws IOException {
		String id = eventName(def);
		if (!lanature.has(id) && vanillaDefs.has(def) && vanillaDefs.get(def).isJsonObject()) {
			JsonObject converted = definition(def, vanillaDefs.getAsJsonObject(def));
			if (converted != null) {
				lanature.add(id, converted);
			}
		}
		return lanature.has(id);
	}

	/** The definitions client entities name in {@code sound_effects}, which their scripts play directly. */
	private void entitySounds(JsonObject lanature, JsonObject vanillaDefs) throws IOException {
		for (String path : pack.packPaths("entity/")) {
			JsonElement json = path.endsWith(".json") ? Json.parse(pack.packFile(path)) : null;
			JsonObject desc = json != null && json.isJsonObject() && json.getAsJsonObject().has("minecraft:client_entity")
				? json.getAsJsonObject().getAsJsonObject("minecraft:client_entity").getAsJsonObject("description") : null;
			if (desc == null || !desc.has("sound_effects")) {
				continue;
			}
			for (Map.Entry<String, JsonElement> e : desc.getAsJsonObject("sound_effects").entrySet()) {
				if (!require(e.getValue().getAsString(), lanature, vanillaDefs)) {
					report.warn("sounds", desc.get("identifier").getAsString() + " plays undefined sound " + e.getValue().getAsString());
				}
			}
		}
	}

	/** The Java sound id for a Bedrock file reference: a copied pack file, or the vanilla Java file it maps to. */
	private String soundFile(String ref) throws IOException {
		byte[] bytes = pack.packFile(ref + ".ogg");
		if (bytes != null) {
			String path = TextureExporter.sanitize(ref.startsWith("sounds/") ? ref.substring("sounds/".length()) : ref);
			Path out = assets.resolve("lanature/sounds/" + path + ".ogg");
			if (!Files.exists(out)) {
				Files.createDirectories(out.getParent());
				Files.write(out, bytes);
			}
			return "lanature:" + path;
		}
		String java = bedrockToJavaFile.get(ref);
		if (java == null && ref.startsWith("sounds/")) {
			java = ref.substring("sounds/".length()); // most vanilla files share their path
		}
		return java == null || !javaFiles.contains(java) ? null : "minecraft:" + java;
	}

	/** Bedrock event names are dotted lowercase; Java ids allow the same characters. */
	public static String eventName(String bedrock) {
		return TextureExporter.sanitize(bedrock.toLowerCase(Locale.ROOT));
	}

	private static void copyNumber(JsonObject from, String key, JsonObject to, String toKey) {
		if (from.has(key) && from.get(key).isJsonPrimitive() && from.get(key).getAsJsonPrimitive().isNumber()) {
			to.add(toKey, from.get(key));
		}
	}
}
