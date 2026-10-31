package xyz.ivyhq.lanature.client.environment;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.bedrock.BedrockResources.BiomeInfo;

/**
 * What the pack makes each biome look like: its fog, water, sky, grass and foliage colours.
 *
 * <p>The pack describes biomes twice: in the legacy {@code biomes_client.json}, whose {@code default} entry fills
 * every field a biome entry leaves out and stands for every biome the file does not list, and in per-biome
 * {@code client_biome} files. Bedrock does not document which wins when one pack has both; here a client biome file's
 * components override the legacy entry's fields (it is the newer format). Vanilla Bedrock's own client biomes are not
 * read: they stand for vanilla, which on Java is the game's own biome values, so whatever the pack does not set stays
 * Java's.
 *
 * <p>The fog follows Bedrock's fog stack: the biome's fog over the data default (the fog of the {@code default}
 * entry), each setting falling through on its own; what neither sets is the engine default, Java's value.
 */
public final class BiomeLooks {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/biomes");
	private static volatile Table table = new Table(Map.of(), null);
	/** The current level's biomes → their looks; read from chunk meshing threads, never changed once published. */
	private static volatile Map<Biome, BiomeLook> byBiome = Map.of();

	private BiomeLooks() {
	}

	/** One biome's look; a null colour leaves Java's. */
	public record BiomeLook(FogSettings fog, @Nullable Integer water, @Nullable Integer sky, @Nullable Integer grass, @Nullable Integer foliage) {
	}

	/** Java biome id → look, and the look of every biome the table does not name (the {@code default} entry). */
	public record Table(Map<String, BiomeLook> byJavaId, @Nullable BiomeLook fallback) {
	}

	/**
	 * Resolves the pack's biome data.
	 *
	 * @param fogFiles fog definitions, later files replacing earlier ones of the same identifier
	 * @param legacy {@code biomes_client.json}
	 * @param clientBiomes the pack's {@code client_biome} files
	 * @param javaBiomes Java biome id → its Bedrock biome
	 */
	public static Table resolve(List<JsonObject> fogFiles, JsonObject legacy, List<JsonObject> clientBiomes, Map<String, BiomeInfo> javaBiomes) {
		Map<String, FogSettings> fogs = new HashMap<>();
		for (JsonObject f : fogFiles) {
			FogSettings.Parsed p = FogSettings.parse(f);
			if (p != null) {
				fogs.put(p.id(), p.settings());
			}
		}
		Map<String, JsonObject> entries = new HashMap<>();
		JsonObject biomes = legacy.getAsJsonObject("biomes");
		if (biomes != null) {
			biomes.entrySet().forEach(e -> entries.put(strip(e.getKey()), e.getValue().getAsJsonObject()));
		}
		Map<String, JsonObject> components = new HashMap<>();
		for (JsonObject f : clientBiomes) {
			JsonObject root = f.getAsJsonObject("minecraft:client_biome");
			if (root != null) {
				components.put(strip(root.getAsJsonObject("description").get("identifier").getAsString()), root.getAsJsonObject("components"));
			}
		}
		JsonObject defaults = entries.getOrDefault("default", new JsonObject());
		FogSettings dataDefault = fog(fogs, string(defaults, "fog_identifier"));
		Map<String, BiomeLook> out = new HashMap<>();
		javaBiomes.forEach((java, info) -> out.put(java, look(strip(info.bedrock()), entries, components, defaults, dataDefault, fogs)));
		BiomeLook fallback = entries.isEmpty() && components.isEmpty() ? null : look("default", Map.of(), Map.of(), defaults, dataDefault, fogs);
		return new Table(Map.copyOf(out), fallback);
	}

	private static BiomeLook look(String bedrock, Map<String, JsonObject> entries, Map<String, JsonObject> components, JsonObject defaults,
		FogSettings dataDefault, Map<String, FogSettings> fogs) {
		JsonObject entry = entries.getOrDefault(bedrock, new JsonObject());
		JsonObject c = components.getOrDefault(bedrock, new JsonObject());
		JsonElement fogId = first(component(c, "minecraft:fog_appearance", "fog_identifier"), string(entry, "fog_identifier"), string(defaults, "fog_identifier"));
		FogSettings fog = fog(fogs, fogId).over(dataDefault);
		Integer water = color(first(component(c, "minecraft:water_appearance", "surface_color"), entry.get("water_surface_color"), defaults.get("water_surface_color")));
		return new BiomeLook(fog, water, color(component(c, "minecraft:sky_color", "sky_color")),
			color(component(c, "minecraft:grass_appearance", "color")), color(component(c, "minecraft:foliage_appearance", "color")));
	}

	/** Uses a resolved table for the level whose registries are given (on joining a level and after a reload). */
	public static void bind(Table resolved, @Nullable RegistryAccess registries) {
		table = resolved;
		bind(registries);
	}

	public static void bind(@Nullable RegistryAccess registries) {
		Table t = table;
		Map<Biome, BiomeLook> map = new IdentityHashMap<>();
		if (registries != null) {
			registries.lookupOrThrow(Registries.BIOME).listElements().forEach(holder -> {
				BiomeLook look = t.byJavaId().getOrDefault(holder.key().identifier().toString(), t.fallback());
				if (look != null) {
					map.put(holder.value(), look);
				}
			});
		}
		byBiome = map;
		PackEnvironment.clear();
		LOGGER.debug("Biome looks bound for {} biomes", map.size());
	}

	/** The pack's look for a biome of the current level, or null where the pack leaves it to Java. */
	public static @Nullable BiomeLook of(Biome biome) {
		return byBiome.get(biome);
	}

	/** Whether any biome of the current level has a look. */
	public static boolean active() {
		return !byBiome.isEmpty();
	}

	public static Table table() {
		return table;
	}

	private static FogSettings fog(Map<String, FogSettings> fogs, @Nullable JsonElement id) {
		if (id == null) {
			return FogSettings.NONE;
		}
		FogSettings fog = fogs.get(id.getAsString());
		if (fog == null) {
			LOGGER.warn("Unknown fog {}", id.getAsString());
			return FogSettings.NONE;
		}
		return fog;
	}

	private static @Nullable JsonElement component(JsonObject components, String component, String field) {
		JsonObject o = components.getAsJsonObject(component);
		return o == null ? null : o.get(field);
	}

	private static @Nullable JsonElement string(JsonObject o, String field) {
		JsonElement e = o.get(field);
		return e != null && e.isJsonPrimitive() ? e : null;
	}

	private static @Nullable JsonElement first(@Nullable JsonElement... values) {
		for (JsonElement v : values) {
			if (v != null && !v.isJsonNull()) {
				return v;
			}
		}
		return null;
	}

	/** A colour value, or null when absent or not a plain colour (a grass colour map, say). */
	private static @Nullable Integer color(@Nullable JsonElement e) {
		if (e == null || !(e.isJsonArray() || e.isJsonPrimitive())) {
			return null;
		}
		return FogSettings.color(e);
	}

	private static String strip(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
