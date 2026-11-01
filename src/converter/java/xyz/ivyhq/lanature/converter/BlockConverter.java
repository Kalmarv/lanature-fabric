package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.ivyhq.lanature.converter.BedrockBlocks.Effective;
import xyz.ivyhq.lanature.converter.BedrockBlocks.TexOption;
import xyz.ivyhq.lanature.converter.GeyserBlocks.JavaState;

/**
 * Rewrites the blockstates of every Java block whose Bedrock counterpart the pack retextures or reshapes.
 *
 * <p>For each model a blockstate uses, each element face is assigned the Bedrock face slot that shows the same
 * vanilla texture (by {@link TextureIdentity}), and takes the pack's texture for that slot. A face whose texture no
 * slot shows keeps its vanilla texture.
 *
 * <p>Weighted Bedrock variations become weighted blockstate variants. The model is one random number per block
 * position, from which each face's tile picks by its own cumulative weights: faces sharing a tile always match (both
 * planes of a cross plant), and tiles with different weight tables pick partly independently. Overlaying the tiles'
 * cumulative weights gives the exact set of combinations, one variant per interval.
 *
 * <p>A block's item takes its icon the same way, from the textures Bedrock draws the item with: the entry's
 * {@code carried_textures} where it has them, else its block textures (see {@link #convertItem}).
 */
public final class BlockConverter {
	/** Pseudo-slot: the converted block's own Java texture, for borrowed geometry the Bedrock entry leaves untextured. */
	private static final String OWN = "#own";

	private final BedrockPack pack;
	private final BedrockBlocks bedrock;
	private final GeyserBlocks geyser;
	private final JavaAssets java;
	private final TextureIdentity identity;
	private final TextureExporter textures;
	private final JsonObject shapes;
	private final JsonObject legacyData;
	private final Path assets;
	private final Report report;
	private final Map<String, String> writtenModels = new HashMap<>();
	private int blocks;
	private int items;
	private int models;
	private int keptFaces;
	private final Set<String> keptDetail = new java.util.TreeSet<>();

	public BlockConverter(BedrockPack pack, BedrockBlocks bedrock, GeyserBlocks geyser, JavaAssets java, TextureIdentity identity,
						  TextureExporter textures, JsonObject shapes, JsonObject legacyData, Path assets, Report report) {
		this.pack = pack;
		this.bedrock = bedrock;
		this.geyser = geyser;
		this.java = java;
		this.identity = identity;
		this.textures = textures;
		this.shapes = shapes;
		this.legacyData = legacyData;
		this.assets = assets;
		this.report = report;
	}

	/** What a Java state renders as on Bedrock: the block entry, and for potted plants the plant's state. */
	record Target(Effective effective, boolean potted, JavaState bedrockState) {
	}

	public void run() throws IOException {
		for (String block : geyser.blocks()) {
			convertBlock(block);
			convertItem(block);
		}
		keptDetail.forEach(k -> report.info("blocks", "kept vanilla face texture: " + k));
		report.info("blocks", blocks + " blockstates and " + items + " block items rewritten, " + models + " models generated, " + keptFaces
			+ " faces kept vanilla (no Bedrock slot shows their texture)");
	}

	private Target target(JavaState s) {
		if (s.block().startsWith("potted_") && s.bedrock().equals("flower_pot")) {
			String plant = s.block().substring("potted_".length());
			JavaState plantState = geyser.defaultState(plant);
			if (plantState == null && plant.endsWith("_bush")) {
				plantState = geyser.defaultState(plant.substring(0, plant.length() - "_bush".length()));
			}
			if (plantState != null) {
				return new Target(bedrock.effective(plantState.bedrock()), true, plantState);
			}
		}
		return new Target(bedrock.effective(s.bedrock()), false, s);
	}

	private void convertBlock(String block) throws IOException {
		List<JavaState> states = geyser.states(block);
		Map<JavaState, Target> targets = new LinkedHashMap<>();
		boolean affected = false;
		for (JavaState s : states) {
			Target t = target(s);
			targets.put(s, t);
			affected |= bedrock.affected(t.effective());
		}
		if (!affected) {
			return;
		}
		JsonObject blockstate = java.blockstate(block);
		if (blockstate == null) {
			report.warn("blocks", block + ": no vanilla blockstate");
			return;
		}
		JsonObject out = blockstate.deepCopy();
		boolean changed = false;
		if (out.has("variants")) {
			for (Map.Entry<String, JsonElement> v : out.getAsJsonObject("variants").entrySet()) {
				JavaState s = states.stream().filter(st -> st.matchesVariant(v.getKey())).findFirst().orElse(null);
				if (s == null || !bedrock.affected(targets.get(s).effective())) {
					continue;
				}
				JsonArray converted = convertRefs(block, v.getValue(), s, targets.get(s));
				changed |= !converted.equals(asArray(v.getValue()));
				v.setValue(converted.size() == 1 ? converted.get(0) : converted);
			}
		} else if (out.has("multipart")) {
			JavaState s = geyser.defaultState(block);
			Target t = targets.get(s);
			if (bedrock.affected(t.effective())) {
				for (JsonElement part : out.getAsJsonArray("multipart")) {
					JsonObject p = part.getAsJsonObject();
					JsonArray converted = convertRefs(block, p.get("apply"), s, t);
					changed |= !converted.equals(asArray(p.get("apply")));
					p.add("apply", converted.size() == 1 ? converted.get(0) : converted);
				}
			}
		}
		if (changed) {
			Json.write(assets.resolve("minecraft/blockstates/" + block + ".json"), out);
			blocks++;
		}
	}

	/**
	 * A block's item icon, drawn as Bedrock draws the block's item: with the entry's {@code carried_textures} where
	 * it has them, else with its block textures, and of a tile's variations the first. A carried texture is a picture
	 * of its own for the inventory, coloured in (vanilla's carried leaves and lily pad are green where their block
	 * textures are grey), so an item drawn with one loses Java's tint. Only items the pack's own pictures draw change;
	 * the rest keep Java's icon.
	 */
	private void convertItem(String block) throws IOException {
		JsonObject def = java.item(block);
		JavaState s = geyser.defaultState(block);
		if (def == null || s == null) {
			return;
		}
		Target t = target(s);
		Effective eff = t.effective();
		JsonElement carried = eff.entry().get("carried_textures");
		JsonObject entry = eff.entry().deepCopy();
		if (carried != null) {
			entry.add("textures", carried.deepCopy());
		}
		// the vanilla entry stays the block's: faces find their slot by the block textures Java's art matches
		Target item = new Target(new Effective(eff.block(), entry, eff.vanilla(), eff.aliasIndex()), false, s);
		boolean coloured = carried != null && !carried.equals(eff.entry().get("textures"));
		JsonObject out = def.deepCopy();
		if (convertItemModels(block, out, s, item, coloured)) {
			Json.write(assets.resolve("minecraft/items/" + block + ".json"), out);
			items++;
		}
	}

	/** Rewrites every {@code minecraft:model} node of an item definition; whether any changed. */
	private boolean convertItemModels(String block, JsonElement node, JavaState s, Target item, boolean coloured) throws IOException {
		boolean changed = false;
		if (node.isJsonArray()) {
			for (JsonElement e : node.getAsJsonArray()) {
				changed |= convertItemModels(block, e, s, item, coloured);
			}
		} else if (node.isJsonObject()) {
			JsonObject o = node.getAsJsonObject();
			if (o.get("type") instanceof JsonElement type && "minecraft:model".equals(type.getAsString()) && o.get("model") instanceof JsonElement ref
				&& ref.isJsonPrimitive()) {
				String model = convertItemModel(block, ref.getAsString(), s, item);
				if (model != null) {
					o.addProperty("model", model);
					if (coloured) {
						o.remove("tints");
					}
					changed = true;
				}
			}
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				changed |= convertItemModels(block, e.getValue(), s, item, coloured);
			}
		}
		return changed;
	}

	/** The model an item model is drawn with instead, or null when the pack's pictures do not draw it. */
	private String convertItemModel(String block, String ref, JavaState s, Target item) throws IOException {
		JavaAssets.Model m = java.model(ref);
		if (m == null) {
			return null;
		}
		int index = index(item, s, m);
		if (!packDraws(item.effective(), index)) {
			return null;
		}
		if (m.elementSource() != null) {
			JsonObject r = new JsonObject();
			r.addProperty("model", ref);
			// the first combination: every tile at its first variation
			String model = convertRef(block, r, s, item, false).get(0).getAsJsonObject().get("model").getAsString();
			return model.equals(ref) ? null : model;
		}
		// a flat item (item/generated): each layer whose Java texture a Bedrock slot shows takes that slot's picture
		JsonObject tex = new JsonObject();
		for (String var : m.textures().keySet()) {
			String javaTex = var.startsWith("layer") ? m.resolve("#" + var) : null;
			String slot = matchSlot(item.effective().vanilla(), index, javaTex, "north");
			String tile = slot == null ? null : BedrockBlocks.slotTile(item.effective().entry(), slot);
			List<TexOption> opts = tile == null ? null : bedrock.tileOptions(tile, index, true);
			if (opts == null || opts.isEmpty()) {
				continue;
			}
			String bedrockRef = opts.getFirst().ref();
			BedrockPack.Resolved first = pack.texture(bedrockRef);
			if (first == null || !first.fromPack() && identity.same(javaTex, bedrockRef)) {
				continue;
			}
			String id = javaTexture(bedrockRef);
			if (id != null) {
				tex.addProperty(var, id);
			}
		}
		if (tex.isEmpty()) {
			return null;
		}
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:" + m.path());
		model.add("textures", tex);
		return writeModel("item", block, model);
	}

	/** Whether the pack draws any of an entry's tiles: a tile of its own, or a picture of its own in one. */
	private boolean packDraws(Effective eff, int index) {
		for (String tile : BedrockBlocks.tiles(eff.entry())) {
			if (bedrock.tileFromPack(tile)) {
				return true;
			}
			List<TexOption> opts = bedrock.tileOptions(tile, index, true);
			if (opts != null && opts.stream().map(o -> pack.texture(o.ref())).anyMatch(r -> r != null && r.fromPack())) {
				return true;
			}
		}
		return false;
	}

	private static JsonArray asArray(JsonElement e) {
		if (e.isJsonArray()) {
			return e.getAsJsonArray();
		}
		JsonArray a = new JsonArray();
		a.add(e);
		return a;
	}

	private JsonArray convertRefs(String block, JsonElement refs, JavaState s, Target t) throws IOException {
		JsonArray out = new JsonArray();
		for (JsonElement r : asArray(refs)) {
			out.addAll(convertRef(block, r.getAsJsonObject(), s, t, true));
		}
		return out;
	}

	/**
	 * One blockstate model reference → the weighted variants that render it the Bedrock way; {@code counted}: faces
	 * left vanilla go in the report (a blockstate's, not an item's, which repeats its block's).
	 */
	private JsonArray convertRef(String block, JsonObject ref, JavaState s, Target t, boolean counted) throws IOException {
		JsonArray unchanged = new JsonArray();
		unchanged.add(ref);
		JavaAssets.Model m = java.model(ref.get("model").getAsString());
		if (m == null) {
			report.warn("blocks", block + ": missing model " + ref.get("model").getAsString());
			return unchanged;
		}
		Effective eff = t.effective();
		int index = index(t, s, m);
		String shape = eff.shapeChanged() ? eff.blockshape() : null;
		JavaAssets.Model geometry = m;
		JsonObject reference = eff.vanilla();
		int referenceIndex = index;
		if (shape != null) {
			JsonObject sh = shapes.has(shape) ? shapes.getAsJsonObject(shape) : null;
			String modelKey = t.potted() && sh != null && sh.has("potted_model") ? "potted_model" : "model";
			JavaAssets.Model g = sh == null ? null : java.model(sh.get(modelKey).getAsString());
			if (g == null) {
				report.warn("blocks", block + ": blockshape '" + shape + "' has no Java template; keeping vanilla geometry");
			} else {
				geometry = g;
				reference = bedrock.vanillaEntry(sh.get("bedrock").getAsString());
				referenceIndex = 0;
			}
		}
		JavaAssets.Model source = geometry.elementSource();
		if (source == null) {
			return unchanged; // builtin geometry (chests, signs): drawn by block entity renderers, not models
		}
		// Face slot per (variable, face direction).
		Map<String, Map<String, String>> slotsByVar = new LinkedHashMap<>();
		for (JsonElement el : source.elements()) {
			JsonObject faces = el.getAsJsonObject().getAsJsonObject("faces");
			if (faces == null) {
				continue;
			}
			for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
				String var = f.getValue().getAsJsonObject().get("texture").getAsString();
				String javaTex = geometry.resolve(var);
				String slot = matchSlot(reference, referenceIndex, javaTex, f.getKey());
				if (slot != null && BedrockBlocks.slotTile(eff.entry(), slot) == null) {
					slot = null;
				}
				if (slot == null && geometry != m) {
					slot = OWN; // borrowed geometry, nothing for this face: the block's own texture, not the template's
				}
				if (slot == null && counted) {
					keptFaces++;
					keptDetail.add(block + " " + var + "=" + javaTex);
				}
				slotsByVar.computeIfAbsent(var, k -> new LinkedHashMap<>()).put(f.getKey(), slot);
			}
		}
		// Options per tile, and whether anything differs from the vanilla Java texture.
		Map<String, List<TexOption>> options = new LinkedHashMap<>();
		boolean differs = shape != null && geometry != m;
		for (Map.Entry<String, Map<String, String>> e : slotsByVar.entrySet()) {
			String javaTex = geometry.resolve(e.getKey());
			for (String slot : e.getValue().values()) {
				String tile = slot == null ? null : OWN.equals(slot) ? OWN : BedrockBlocks.slotTile(eff.entry(), slot);
				if (tile == null || options.containsKey(tile)) {
					continue;
				}
				if (OWN.equals(tile)) {
					String own = m.resolve("#particle");
					if (own != null) {
						options.put(OWN, List.of(new TexOption(OWN + own, 1)));
					}
					continue;
				}
				List<TexOption> opts = bedrock.tileOptions(tile, index, true);
				if (opts == null || opts.isEmpty()) {
					continue;
				}
				options.put(tile, opts);
				BedrockPack.Resolved first = pack.texture(opts.getFirst().ref());
				differs |= opts.size() > 1 || javaTex == null || !identity.same(javaTex, opts.getFirst().ref()) || first == null || first.fromPack();
			}
		}
		if (!differs) {
			return unchanged;
		}
		// Slot → tile, so faces look their texture up by tile.
		Map<String, Map<String, String>> tilesByVar = new LinkedHashMap<>();
		slotsByVar.forEach((var, byDir) -> {
			Map<String, String> byTile = new LinkedHashMap<>();
			byDir.forEach((dir, slot) -> byTile.put(dir, slot == null ? null : OWN.equals(slot) ? OWN : BedrockBlocks.slotTile(eff.entry(), slot)));
			tilesByVar.put(var, byTile);
		});
		boolean split = tilesByVar.values().stream().anyMatch(v -> new LinkedHashSet<>(v.values()).size() > 1);
		List<Combination> combos = combinations(options);
		JsonArray out = new JsonArray();
		int baseWeight = ref.has("weight") ? ref.get("weight").getAsInt() : 1;
		for (Combination c : combos) {
			JsonObject model = split ? flattened(geometry, source, tilesByVar, c.choice) : child(geometry, tilesByVar, c.choice);
			String name = writeModel("block", block, model);
			JsonObject variant = ref.deepCopy();
			variant.addProperty("model", name);
			variant.remove("weight");
			if (combos.size() > 1 || baseWeight != 1) {
				variant.addProperty("weight", c.weight * baseWeight);
			}
			out.add(variant);
		}
		return out;
	}

	/** A model inheriting the geometry, with each texture variable bound to its (single) tile's texture. */
	private JsonObject child(JavaAssets.Model geometry, Map<String, Map<String, String>> tilesByVar, Map<String, String> choice) {
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:" + geometry.path());
		JsonObject tex = new JsonObject();
		for (Map.Entry<String, Map<String, String>> e : tilesByVar.entrySet()) {
			String tile = e.getValue().values().iterator().next();
			String id = tile == null ? null : choice.get(tile);
			if (id != null) {
				tex.addProperty(e.getKey().substring(1), id);
			}
		}
		model.add("textures", tex);
		return model;
	}

	/** A standalone model with the geometry's elements, giving each face its own tile's texture variable. */
	private JsonObject flattened(JavaAssets.Model geometry, JavaAssets.Model source, Map<String, Map<String, String>> tilesByVar,
								 Map<String, String> choice) {
		JsonObject model = new JsonObject();
		model.addProperty("parent", "minecraft:block/block");
		if (geometry.ambientOcclusion() != null) {
			model.addProperty("ambientocclusion", geometry.ambientOcclusion());
		}
		JsonObject tex = new JsonObject();
		geometry.textures().forEach((k, v) -> {
			String resolved = geometry.resolve("#" + k);
			if (resolved != null) {
				tex.addProperty(k, "minecraft:" + resolved);
			}
		});
		JsonArray elements = source.elements().deepCopy();
		for (JsonElement el : elements) {
			JsonObject faces = el.getAsJsonObject().getAsJsonObject("faces");
			if (faces == null) {
				continue;
			}
			for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
				JsonObject face = f.getValue().getAsJsonObject();
				String var = face.get("texture").getAsString();
				String tile = tilesByVar.get(var).get(f.getKey());
				String id = tile == null ? null : choice.get(tile);
				if (id != null) {
					String newVar = var.substring(1) + "_" + f.getKey();
					tex.addProperty(newVar, id);
					face.addProperty("texture", "#" + newVar);
				}
			}
		}
		// The particle texture follows the slot its vanilla texture came from, if any.
		String particle = geometry.resolve("#particle");
		for (Map.Entry<String, Map<String, String>> e : tilesByVar.entrySet()) {
			if (particle != null && particle.equals(geometry.resolve(e.getKey()))) {
				String tile = e.getValue().values().stream().filter(x -> x != null).findFirst().orElse(null);
				if (tile != null && choice.containsKey(tile)) {
					tex.addProperty("particle", choice.get(tile));
				}
				break;
			}
		}
		model.add("textures", tex);
		model.add("elements", elements);
		JsonObject display = displayOf(geometry);
		if (display != null) {
			model.add("display", display);
		}
		return model;
	}

	private static JsonObject displayOf(JavaAssets.Model m) {
		for (JavaAssets.Model x = m; x != null; x = x.parent()) {
			if (x.json().has("display")) {
				return x.json().getAsJsonObject("display").deepCopy();
			}
		}
		return null;
	}

	/** Writes a generated model ({@code folder}: {@code block} or {@code item}) once per content, returning its id. */
	private String writeModel(String folder, String block, JsonObject model) throws IOException {
		String content = Json.COMPACT.toJson(model);
		String existing = writtenModels.get(content);
		if (existing != null) {
			return existing;
		}
		String file = folder + "/gen/" + block + "_" + shortHash(content);
		String name = "lanature:" + file;
		Json.write(assets.resolve("lanature/models/" + file + ".json"), model);
		writtenModels.put(content, name);
		models++;
		return name;
	}

	private static String shortHash(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	record Combination(Map<String, String> choice, int weight) {
	}

	/**
	 * The combinations one random number per block selects: each tile's options span [0, 1) by cumulative weight,
	 * and every interval between the union of their boundaries is one combination weighted by its length.
	 */
	private List<Combination> combinations(Map<String, List<TexOption>> options) throws IOException {
		Map<String, List<String>> ids = new LinkedHashMap<>();
		Map<String, double[]> bounds = new LinkedHashMap<>();
		java.util.TreeSet<Double> cuts = new java.util.TreeSet<>(List.of(0.0, 1.0));
		for (Map.Entry<String, List<TexOption>> e : options.entrySet()) {
			List<String> i = new ArrayList<>();
			List<Double> w = new ArrayList<>();
			for (TexOption o : e.getValue()) {
				String id = javaTexture(o.ref());
				if (id != null) {
					i.add(id);
					w.add(o.weight());
				}
			}
			if (i.isEmpty()) {
				continue;
			}
			double sum = w.stream().mapToDouble(Double::doubleValue).sum();
			double[] upper = new double[i.size()];
			double acc = 0;
			for (int k = 0; k < upper.length; k++) {
				acc += w.get(k) / sum;
				upper[k] = k == upper.length - 1 ? 1.0 : acc;
				cuts.add(upper[k]);
			}
			ids.put(e.getKey(), i);
			bounds.put(e.getKey(), upper);
		}
		List<Map<String, String>> choices = new ArrayList<>();
		List<Double> raw = new ArrayList<>();
		Double previous = null;
		for (double cut : cuts) {
			if (previous != null && cut - previous > 1e-12) {
				double mid = (previous + cut) / 2;
				Map<String, String> choice = new LinkedHashMap<>();
				for (Map.Entry<String, double[]> b : bounds.entrySet()) {
					int k = 0;
					while (b.getValue()[k] <= mid) {
						k++;
					}
					choice.put(b.getKey(), ids.get(b.getKey()).get(k));
				}
				choices.add(choice);
				raw.add(cut - previous);
			}
			previous = cut;
		}
		return weighted(choices, raw);
	}

	/** Merges identical choices and scales weights to the smallest integers keeping 0.1% precision. */
	static List<Combination> weighted(List<Map<String, String>> choices, List<Double> raw) {
		Map<Map<String, String>, Double> merged = new LinkedHashMap<>();
		for (int i = 0; i < choices.size(); i++) {
			merged.merge(choices.get(i), raw.get(i), Double::sum);
		}
		double max = merged.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
		Map<Map<String, String>, Integer> ints = new LinkedHashMap<>();
		merged.forEach((k, v) -> ints.put(k, (int) Math.max(1, Math.round(v / max * 1000))));
		int gcd = ints.values().stream().reduce(0, BlockConverter::gcd);
		List<Combination> out = new ArrayList<>();
		ints.forEach((k, v) -> out.add(new Combination(k, v / Math.max(gcd, 1))));
		return out;
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	/** The Java texture id to use for a Bedrock texture: vanilla Java art when it is the same picture, else an export. */
	private String javaTexture(String ref) throws IOException {
		if (ref.startsWith(OWN)) {
			return "minecraft:" + ref.substring(OWN.length());
		}
		BedrockPack.Resolved r = pack.texture(ref);
		if (r != null && !r.fromPack()) {
			Set<String> javas = identity.javaFor(ref);
			if (!javas.isEmpty()) {
				return "minecraft:" + javas.iterator().next();
			}
		}
		return textures.export(ref, "block");
	}

	/** The face slot of a vanilla Bedrock entry that shows this Java texture, preferring the face's own direction. */
	private String matchSlot(JsonObject entry, int index, String javaTex, String dir) {
		if (entry == null || javaTex == null) {
			return null;
		}
		List<String> hits = new ArrayList<>();
		for (String d : BedrockBlocks.DIRECTIONS) {
			String tile = BedrockBlocks.slotTile(entry, d);
			List<TexOption> opts = tile == null ? null : bedrock.tileOptions(tile, index, false);
			if (opts != null && opts.stream().anyMatch(o -> identity.same(javaTex, o.ref()))) {
				hits.add(d);
			}
		}
		return hits.contains(dir) ? dir : hits.isEmpty() ? null : hits.getFirst();
	}

	/** The legacy tile index for a state: the legacy alias, the curated data layout, or texture identity. */
	private int index(Target t, JavaState s, JavaAssets.Model m) {
		Effective eff = t.effective();
		if (eff.aliasIndex() != null) {
			return eff.aliasIndex();
		}
		if (legacyData.has(eff.block())) {
			int data = 0;
			for (JsonElement term : legacyData.getAsJsonArray(eff.block())) {
				JsonObject o = term.getAsJsonObject();
				String v = t.bedrockState().bedrockStates().get(o.get("state").getAsString());
				if (v == null) {
					continue;
				}
				int value = o.has("values") ? o.getAsJsonObject("values").get(v).getAsInt() : Integer.parseInt(v);
				data += value * (o.has("multiplier") ? o.get("multiplier").getAsInt() : 1);
			}
			return data;
		}
		if (eff.vanilla() != null) {
			Set<String> used = new LinkedHashSet<>();
			m.textures().keySet().forEach(k -> {
				String r = m.resolve("#" + k);
				if (r != null) {
					used.add(r);
				}
			});
			// The index whose textures match the most of the model's (open and closed barrel tops share one list).
			int best = 0;
			int bestScore = 0;
			for (int i = 0; i < 64; i++) {
				int score = 0;
				boolean any = false;
				for (String tile : BedrockBlocks.tiles(eff.vanilla())) {
					int n = bedrock.listSize(tile, false);
					if (n <= 1 || i >= n) {
						continue;
					}
					any = true;
					for (TexOption o : bedrock.tileOptions(tile, i, false)) {
						for (String u : used) {
							if (identity.same(u, o.ref())) {
								score++;
							}
						}
					}
				}
				if (!any) {
					break;
				}
				if (score > bestScore) {
					best = i;
					bestScore = score;
				}
			}
			return best;
		}
		return 0;
	}
}
