package xyz.ivyhq.lanature.client.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack's own settings (its manifest's {@code settings}: sliders, toggles, dropdowns and multiselects) with the
 * player's choices, kept in {@code config/lanature.json}. Behind {@code q.get_pack_setting},
 * {@code q.is_pack_setting_enabled} and {@code q.is_pack_setting_selected}.
 */
public final class PackSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/settings");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final PackSettings INSTANCE = new PackSettings();

	public enum Type { SLIDER, TOGGLE, DROPDOWN, MULTISELECT, LABEL }

	public record Setting(String name, Type type, String text, double min, double max, double step, Object defaultValue, List<Option> options) {
	}

	public record Option(String name, String text) {
	}

	private final Map<String, Setting> settings = new LinkedHashMap<>();
	private final List<Setting> ordered = new ArrayList<>();
	/** Double for sliders, Boolean for toggles, String for dropdowns, Set of String for multiselects. */
	private final Map<String, Object> values = new LinkedHashMap<>();
	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("lanature.json");

	private PackSettings() {
	}

	public static PackSettings get() {
		return INSTANCE;
	}

	/** Reads the definitions from the converted manifest, then the saved values. */
	public synchronized void define(JsonObject manifest) {
		settings.clear();
		ordered.clear();
		JsonArray list = manifest.has("settings") ? manifest.getAsJsonArray("settings") : new JsonArray();
		for (JsonElement e : list) {
			JsonObject o = e.getAsJsonObject();
			Type type = switch (o.get("type").getAsString()) {
				case "slider" -> Type.SLIDER;
				case "toggle" -> Type.TOGGLE;
				case "dropdown" -> Type.DROPDOWN;
				case "multiselect" -> Type.MULTISELECT;
				default -> Type.LABEL;
			};
			List<Option> options = new ArrayList<>();
			if (o.has("options")) {
				o.getAsJsonArray("options").forEach(x -> options.add(new Option(x.getAsJsonObject().get("name").getAsString(),
					x.getAsJsonObject().get("text").getAsString())));
			}
			Object def = switch (type) {
				case SLIDER -> o.has("default") ? o.get("default").getAsDouble() : 0.0;
				case TOGGLE -> o.has("default") && o.get("default").getAsBoolean();
				case DROPDOWN -> o.has("default") ? o.get("default").getAsString() : options.isEmpty() ? "" : options.getFirst().name();
				case MULTISELECT -> {
					Set<String> s = new LinkedHashSet<>();
					if (o.has("defaults")) {
						o.getAsJsonArray("defaults").forEach(x -> s.add(x.getAsString()));
					}
					yield s;
				}
				case LABEL -> null;
			};
			Setting s = new Setting(o.has("name") ? o.get("name").getAsString() : null, type, o.has("text") ? o.get("text").getAsString() : "",
				num(o, "min", 0), num(o, "max", 1), num(o, "step", 0), def, List.copyOf(options));
			ordered.add(s);
			if (s.name() != null) {
				settings.put(s.name(), s);
			}
		}
		values.keySet().retainAll(settings.keySet());
		load();
	}

	private static double num(JsonObject o, String key, double fallback) {
		return o.has(key) ? o.get(key).getAsDouble() : fallback;
	}

	public synchronized List<Setting> all() {
		return List.copyOf(ordered);
	}

	public synchronized Object value(String name) {
		Setting s = settings.get(name);
		if (s == null) {
			return null;
		}
		Object v = values.get(name);
		return v != null ? v : s.defaultValue();
	}

	/** {@code q.get_pack_setting}: a slider's value, a toggle as 1 or 0, otherwise 0. */
	public double number(String name) {
		return switch (value(name)) {
			case Double d -> d;
			case Boolean b -> b ? 1 : 0;
			case null, default -> 0;
		};
	}

	public boolean enabled(String name) {
		return value(name) instanceof Boolean b ? b : number(name) != 0;
	}

	public boolean selected(String name, String option) {
		return switch (value(name)) {
			case String s -> s.equals(option);
			case Set<?> set -> set.contains(option);
			case null, default -> false;
		};
	}

	/** Changes a setting in memory; {@link #save} writes the settings out. */
	public synchronized void set(String name, Object value) {
		if (settings.containsKey(name)) {
			values.put(name, value);
		}
	}

	/** Returns every setting to the pack's default. */
	public synchronized void reset() {
		values.clear();
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				Setting s = settings.get(e.getKey());
				if (s == null) {
					continue;
				}
				JsonElement v = e.getValue();
				switch (s.type()) {
					case SLIDER -> values.put(s.name(), Math.clamp(v.getAsDouble(), s.min(), s.max()));
					case TOGGLE -> values.put(s.name(), v.getAsBoolean());
					case DROPDOWN -> values.put(s.name(), v.getAsString());
					case MULTISELECT -> {
						Set<String> set = new LinkedHashSet<>();
						v.getAsJsonArray().forEach(x -> set.add(x.getAsString()));
						values.put(s.name(), set);
					}
					case LABEL -> {
					}
				}
			}
		} catch (IOException | RuntimeException ex) {
			LOGGER.warn("Could not read {}; using the pack's defaults", file, ex);
		}
	}

	public synchronized void save() {
		JsonObject o = new JsonObject();
		values.forEach((k, v) -> o.add(k, GSON.toJsonTree(v)));
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			LOGGER.warn("Could not save {}", file, ex);
		}
	}
}
