package xyz.ivyhq.lanature.client.environment;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.resources.LegacyStuffWrapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ColorMapColorUtil;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack's Bedrock colour maps ({@code textures/colormap/}) that Java has no picture for. Bedrock samples a map by
 * the biome's temperature and downfall, as Java samples its own grass and foliage maps; it tints the blocks of the
 * engine's table with one (birch leaves with {@code birch}), and a client biome may take its foliage or grass colour
 * from one ({@code "color": {"color_map": "swamp_foliage"}}). A block whose map the pack leaves alone keeps Java's tint.
 */
public final class Colormaps {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/colormaps");
	/** The engine table: block → map name. Read once at start-up, since block tints are registered then. */
	private static final String ENGINE_TABLE = "/assets/lanature/bedrock/engine/colormaps.json";
	/** What the last resource reload loaded. */
	private static volatile Loaded current = Loaded.EMPTY;
	/** The loaded maps, by name; replaced on each resource reload. */
	private static volatile Map<String, int[]> maps = Map.of();

	private Colormaps() {
	}

	/** The pack's maps and the vanilla client biomes that name one, read off-thread on a resource reload. */
	public record Loaded(Map<String, int[]> maps, Map<String, Map<String, String>> biomeMaps) {
		public static final Loaded EMPTY = new Loaded(Map.of(), Map.of());
	}

	/** Reads {@code bedrock/colormaps.json} and the maps it names. */
	public static Loaded load(ResourceManager rm, @Nullable JsonObject table) {
		if (table == null) {
			return Loaded.EMPTY;
		}
		Map<String, int[]> loaded = new HashMap<>();
		for (JsonElement name : table.getAsJsonArray("maps")) {
			Identifier id = Identifier.fromNamespaceAndPath("lanature", "bedrock/colormaps/" + name.getAsString() + ".png");
			try {
				int[] pixels = LegacyStuffWrapper.getPixels(rm, id);
				if (pixels.length != 256 * 256) {
					LOGGER.warn("Colour map {} is not 256x256; ignored", id);
					continue;
				}
				loaded.put(name.getAsString(), pixels);
			} catch (IOException e) {
				LOGGER.warn("Could not read colour map {}", id, e);
			}
		}
		Map<String, Map<String, String>> biomeMaps = new HashMap<>();
		table.getAsJsonObject("biomes").entrySet().forEach(e -> {
			Map<String, String> refs = new HashMap<>();
			e.getValue().getAsJsonObject().entrySet().forEach(r -> refs.put(r.getKey(), r.getValue().getAsString()));
			biomeMaps.put(e.getKey(), Map.copyOf(refs));
		});
		return new Loaded(Map.copyOf(loaded), Map.copyOf(biomeMaps));
	}

	/** Uses loaded maps; biome looks sample them when next bound, and chunks drawn after take the block tints. */
	public static void apply(Loaded loaded) {
		current = loaded;
		maps = loaded.maps();
	}

	public static Loaded current() {
		return current;
	}

	/** Whether the pack replaces this map. */
	public static boolean has(String map) {
		return maps.containsKey(map);
	}

	/** A replaced map's colour for a biome, by its climate; null when the pack leaves the map alone. */
	public static @Nullable Integer sample(String map, Biome biome) {
		return sample(map, biome.getBaseTemperature(), biome.climateSettings.downfall());
	}

	/** A replaced map's colour at a climate, as Java samples its own maps; null when the pack leaves the map alone. */
	public static @Nullable Integer sample(String map, double temperature, double downfall) {
		int[] pixels = maps.get(map);
		if (pixels == null) {
			return null;
		}
		return ColorMapColorUtil.get(Mth.clamp(temperature, 0, 1), Mth.clamp(downfall, 0, 1), pixels, -1);
	}

	/**
	 * Gives each block of the engine table a tint from its map, averaged over the nearby biomes as Java blends its
	 * foliage colour, in place of the tint the game's block colours hold for it; while the pack leaves the map alone the
	 * block keeps that tint. Called once the game has its block colours, so the tint replaced is the game's own (and
	 * any other mod's), not a copy's.
	 */
	public static void registerBlockTints(BlockColors colors) {
		for (Map.Entry<Block, String> e : engineTable().entrySet()) {
			String map = e.getValue();
			BlockTintSource fallback = colors.getTintSource(e.getKey().defaultBlockState(), 0);
			if (fallback == null) {
				LOGGER.warn("{} has no Java tint to replace with colour map {}", e.getKey(), map);
				continue;
			}
			ColorResolver resolver = (biome, x, z) -> {
				Integer c = sample(map, biome);
				return c != null ? c : -1;
			};
			ColorResolverRegistry.register(resolver);
			BlockColorRegistry.register(List.of(new BlockTintSource() {
				@Override
				public int color(BlockState state) {
					// a block drawn out of the world (falling, held by an enderman): Java's default foliage climate
					Integer c = sample(map, 0.5, 1.0);
					return c != null ? c : fallback.color(state);
				}

				@Override
				public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
					return has(map) ? level.getBlockTint(pos, resolver) : fallback.colorInWorld(state, level, pos);
				}
			}), e.getKey());
		}
	}

	private static Map<Block, String> engineTable() {
		Map<Block, String> out = new HashMap<>();
		try (InputStream in = Colormaps.class.getResourceAsStream(ENGINE_TABLE)) {
			if (in == null) {
				throw new IllegalStateException("missing " + ENGINE_TABLE);
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject blocks = com.google.gson.JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("blocks");
				for (Map.Entry<String, JsonElement> e : blocks.entrySet()) {
					BuiltInRegistries.BLOCK.getOptional(Identifier.parse(e.getKey())).ifPresentOrElse(b -> out.put(b, e.getValue().getAsString()),
						() -> LOGGER.warn("Engine colour map table names unknown block {}", e.getKey()));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("could not read " + ENGINE_TABLE, e);
		}
		return out;
	}
}
