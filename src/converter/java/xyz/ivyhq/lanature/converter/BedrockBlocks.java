package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bedrock's {@code blocks.json} and terrain atlas tiles with the pack merged over vanilla.
 *
 * <p>Pack {@code blocks.json} entries merge field by field over the vanilla entry (an entry with only
 * {@code sound} keeps vanilla textures). Terrain tiles replace the vanilla tile of the same name. Since blocks were
 * flattened, vanilla carries both the legacy entry ({@code sapling}, one tile list indexed by legacy data) and the
 * flattened ones ({@code oak_sapling}); a pack entry under the legacy name applies to the flattened blocks it covers
 * unless the pack also defines the flattened name.
 */
public final class BedrockBlocks {
	public static final List<String> DIRECTIONS = List.of("down", "up", "north", "south", "west", "east");

	private final JsonObject vanillaBlocks;
	private final JsonObject packBlocks;
	private final JsonObject vanillaTiles;
	private final JsonObject packTiles;
	/** Flattened block → (legacy entry, index), found where a flattened entry's textures equal one index of a legacy list. */
	private final Map<String, Alias> aliases = new HashMap<>();

	public record Alias(String legacy, int index) {
	}

	public record TexOption(String ref, double weight) {
	}

	public BedrockBlocks(JsonObject vanillaBlocks, JsonObject packBlocks, JsonObject vanillaTiles, JsonObject packTiles, Report report) {
		this.vanillaBlocks = vanillaBlocks;
		this.packBlocks = packBlocks;
		this.vanillaTiles = vanillaTiles;
		this.packTiles = packTiles;
		findAliases(report);
	}

	private void findAliases(Report report) {
		for (Map.Entry<String, JsonElement> legacy : vanillaBlocks.entrySet()) {
			if (!legacy.getValue().isJsonObject() || !packBlocks.has(legacy.getKey())) {
				continue; // only legacy entries the pack overrides matter
			}
			JsonObject le = legacy.getValue().getAsJsonObject();
			int n = maxListSize(le, false);
			if (n < 2) {
				continue;
			}
			for (int i = 0; i < n; i++) {
				Map<String, String> want = faceTextures(le, i, false);
				for (Map.Entry<String, JsonElement> flat : vanillaBlocks.entrySet()) {
					if (flat.getKey().equals(legacy.getKey()) || !flat.getValue().isJsonObject() || packBlocks.has(flat.getKey())) {
						continue;
					}
					JsonObject fe = flat.getValue().getAsJsonObject();
					if (maxListSize(fe, false) <= 1 && want.equals(faceTextures(fe, 0, false))) {
						aliases.put(flat.getKey(), new Alias(legacy.getKey(), i));
						report.info("blocks", "pack entry '" + legacy.getKey() + "'[" + i + "] applies to flattened '" + flat.getKey() + "'");
					}
				}
			}
		}
	}

	/** The first texture of each face at an index, for comparing entries. */
	private Map<String, String> faceTextures(JsonObject entry, int index, boolean merged) {
		Map<String, String> out = new HashMap<>();
		for (String dir : DIRECTIONS) {
			String tile = slotTile(entry, dir);
			List<TexOption> opts = tile == null ? null : tileOptions(tile, index, merged);
			out.put(dir, opts == null || opts.isEmpty() ? null : opts.getFirst().ref());
		}
		return out;
	}

	public JsonObject vanillaEntry(String block) {
		JsonElement e = vanillaBlocks.get(block);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	/** The entry Bedrock renders the block with, and the tile index the legacy alias fixes (or null). */
	public Effective effective(String block) {
		JsonObject base = vanillaEntry(block);
		JsonObject merged = base == null ? new JsonObject() : base.deepCopy();
		Integer index = null;
		JsonElement own = packBlocks.get(block);
		Alias alias = aliases.get(block);
		if (own != null && own.isJsonObject()) {
			own.getAsJsonObject().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue().deepCopy()));
		} else if (alias != null) {
			packBlocks.getAsJsonObject(alias.legacy()).entrySet().forEach(e -> merged.add(e.getKey(), e.getValue().deepCopy()));
			index = alias.index();
		}
		return new Effective(block, merged, base, index);
	}

	public record Effective(String block, JsonObject entry, JsonObject vanilla, Integer aliasIndex) {
		public String blockshape() {
			return entry.has("blockshape") ? entry.get("blockshape").getAsString() : null;
		}

		public boolean shapeChanged() {
			return !Objects.equals(blockshape(), vanilla != null && vanilla.has("blockshape") ? vanilla.get("blockshape").getAsString() : null);
		}
	}

	/** Whether the pack changes how this block looks (its textures, shape, or any tile it uses). */
	public boolean affected(Effective eff) {
		if (eff.vanilla() == null && !packBlocks.has(eff.block()) && eff.aliasIndex() == null) {
			return false;
		}
		if (eff.shapeChanged() || !Objects.equals(eff.entry().get("textures"), eff.vanilla() == null ? null : eff.vanilla().get("textures"))) {
			return true;
		}
		for (String tile : tiles(eff.entry())) {
			if (packTiles.has(tile)) {
				return true;
			}
		}
		return false;
	}

	/** Every tile an entry's {@code textures} uses. */
	public static Set<String> tiles(JsonObject entry) {
		Set<String> out = new LinkedHashSet<>();
		JsonElement t = entry.get("textures");
		if (t != null && t.isJsonPrimitive()) {
			out.add(t.getAsString());
		} else if (t != null && t.isJsonObject()) {
			t.getAsJsonObject().entrySet().forEach(e -> out.add(e.getValue().getAsString()));
		}
		return out;
	}

	/** The tile on one face: a direction key, then {@code side} for horizontal faces, or the single tile. */
	public static String slotTile(JsonObject entry, String dir) {
		JsonElement t = entry.get("textures");
		if (t == null) {
			return null;
		}
		if (t.isJsonPrimitive()) {
			return t.getAsString();
		}
		JsonObject o = t.getAsJsonObject();
		if (o.has(dir)) {
			return o.get(dir).getAsString();
		}
		if (!dir.equals("up") && !dir.equals("down") && o.has("side")) {
			return o.get("side").getAsString();
		}
		return null;
	}

	private JsonObject tile(String name, boolean merged) {
		JsonElement e = merged && packTiles.has(name) ? packTiles.get(name) : vanillaTiles.get(name);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	public boolean tileFromPack(String name) {
		return packTiles.has(name);
	}

	/** Number of entries in a tile's list (1 when not a list, 0 when missing). */
	public int listSize(String name, boolean merged) {
		JsonObject t = tile(name, merged);
		if (t == null) {
			return 0;
		}
		JsonElement tex = t.get("textures");
		return tex.isJsonArray() ? tex.getAsJsonArray().size() : 1;
	}

	private int maxListSize(JsonObject entry, boolean merged) {
		int n = 0;
		for (String tile : tiles(entry)) {
			n = Math.max(n, listSize(tile, merged));
		}
		return n;
	}

	/** The weighted textures a tile shows at a legacy index (clamped to the list), or null when the tile is missing. */
	public List<TexOption> tileOptions(String name, int index, boolean merged) {
		JsonObject t = tile(name, merged);
		if (t == null) {
			return null;
		}
		JsonElement tex = t.get("textures");
		if (tex.isJsonArray()) {
			JsonArray arr = tex.getAsJsonArray();
			if (arr.isEmpty()) {
				return List.of();
			}
			tex = arr.get(Math.min(index, arr.size() - 1));
		}
		return options(tex);
	}

	private static List<TexOption> options(JsonElement tex) {
		List<TexOption> out = new ArrayList<>();
		if (tex.isJsonPrimitive()) {
			out.add(new TexOption(tex.getAsString(), 1));
		} else if (tex.isJsonObject()) {
			JsonObject o = tex.getAsJsonObject();
			if (o.has("variations")) {
				for (JsonElement v : o.getAsJsonArray("variations")) {
					JsonObject vo = v.getAsJsonObject();
					double w = vo.has("weight") ? vo.get("weight").getAsDouble() : 1;
					if (w > 0) {
						out.add(new TexOption(vo.get("path").getAsString(), w));
					}
				}
			} else if (o.has("path")) {
				out.add(new TexOption(o.get("path").getAsString(), 1));
			}
		}
		return out;
	}
}
