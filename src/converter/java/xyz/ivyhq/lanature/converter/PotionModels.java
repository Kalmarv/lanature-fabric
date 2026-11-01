package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pack's potion pictures. Bedrock draws each potion as a texture of its own, named by its effect; Java draws one
 * bottle with a tinted overlay. For each potion item, every potion whose Bedrock texture the pack redraws gets that
 * texture through the item's model definition: a {@code select} on its {@code potion_contents}, falling back to Java's
 * tinted bottle for the rest (water, luck, and potions with custom effects or colours, which match no case).
 */
public final class PotionModels {
	/** The textures this step handles, so the path overrides leave them alone. */
	public static final List<String> PREFIX = List.of("textures/items/potion_bottle_");

	private PotionModels() {
	}

	public static void run(BedrockPack pack, JavaAssets java, JsonObject table, Path assets, Report report) throws IOException {
		JsonObject potions = table.getAsJsonObject("potions");
		int written = 0;
		for (Map.Entry<String, JsonElement> item : table.getAsJsonObject("items").entrySet()) {
			String prefix = "textures/items/" + item.getValue().getAsString();
			// texture name -> the Java potions drawn with it, in the table's order
			Map<String, JsonArray> cases = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> potion : potions.entrySet()) {
				String ref = prefix + potion.getValue().getAsString();
				BedrockPack.Resolved tex = pack.texture(ref);
				if (tex == null || !tex.fromPack()) {
					continue; // the pack leaves this one alone: Java's bottle stays
				}
				cases.computeIfAbsent(ref, k -> new JsonArray()).add("minecraft:" + potion.getKey());
			}
			if (cases.isEmpty()) {
				continue;
			}
			JsonArray switchCases = new JsonArray();
			for (Map.Entry<String, JsonArray> c : cases.entrySet()) {
				String name = c.getKey().substring("textures/items/".length()).toLowerCase(Locale.ROOT);
				BedrockPack.Resolved tex = pack.texture(c.getKey());
				write(assets.resolve("lanature/textures/item/potion/" + name + ".png"), Images.png(Images.decode(tex.path(), tex.bytes())));
				JsonObject model = new JsonObject();
				model.addProperty("parent", "minecraft:item/generated");
				JsonObject textures = new JsonObject();
				textures.addProperty("layer0", "lanature:item/potion/" + name);
				model.add("textures", textures);
				Json.write(assets.resolve("lanature/models/item/potion/" + name + ".json"), model);
				JsonObject ref = new JsonObject();
				ref.addProperty("type", "minecraft:model");
				ref.addProperty("model", "lanature:item/potion/" + name);
				JsonObject switchCase = new JsonObject();
				switchCase.add("when", c.getValue());
				switchCase.add("model", ref);
				switchCases.add(switchCase);
				written++;
			}
			JsonObject vanilla = java.item(item.getKey());
			JsonObject select = new JsonObject();
			select.addProperty("type", "minecraft:select");
			select.addProperty("property", "minecraft:component");
			select.addProperty("component", "minecraft:potion_contents");
			select.add("cases", switchCases);
			select.add("fallback", vanilla.get("model"));
			JsonObject definition = vanilla.deepCopy();
			definition.add("model", select);
			Json.write(assets.resolve("minecraft/items/" + item.getKey() + ".json"), definition);
			report.info("potions", item.getKey() + ": " + switchCases.size() + " pictures for " + countPotions(cases) + " potions");
		}
		report.info("potions", written + " potion textures");
	}

	private static int countPotions(Map<String, JsonArray> cases) {
		return cases.values().stream().mapToInt(JsonArray::size).sum();
	}

	private static void write(Path out, byte[] bytes) throws IOException {
		Files.createDirectories(out.getParent());
		Files.write(out, bytes);
	}
}
