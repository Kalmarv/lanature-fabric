package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pack's block sound groups ({@code blocks.json} {@code sound}), written to {@code bedrock/block_sounds.json}.
 *
 * <p>Bedrock names a block's break, step, place and hit sounds by a group ({@code fungus}, {@code roots}); Java gives
 * each block a {@code SoundType} of the same sounds. For every Java block whose Bedrock block the pack moves to
 * another group, the table lists the donors: the Java blocks whose Bedrock blocks are in that group in vanilla and
 * which the pack leaves there, the block named for the group first. The runtime gives the block its donors' most
 * common sound type (on a tie, the first's), so the group's Java counterpart is read off Java's own blocks.
 */
public final class BlockSounds {
	private BlockSounds() {
	}

	public static void run(BedrockBlocks bedrock, GeyserBlocks geyser, Path assets, Report report) throws IOException {
		// Java block → (vanilla group, the pack's group), by its default state's Bedrock block
		Map<String, String[]> groups = new LinkedHashMap<>();
		for (String block : geyser.blocks()) {
			GeyserBlocks.JavaState s = geyser.defaultState(block);
			if (s == null) {
				continue;
			}
			BedrockBlocks.Effective eff = bedrock.effective(s.bedrock());
			groups.put(block, new String[] {sound(eff.vanilla()), sound(eff.entry())});
		}
		JsonObject out = new JsonObject();
		Map<String, Integer> moved = new TreeMap<>();
		groups.forEach((block, g) -> {
			if (g[1] == null || g[1].equals(g[0])) {
				return;
			}
			if (g[1].isEmpty()) {
				report.info("sounds", block + ": the pack names an empty sound group; keeps Java's");
				return;
			}
			List<String> donors = new ArrayList<>();
			groups.forEach((other, h) -> {
				if (g[1].equals(h[0]) && g[1].equals(h[1])) {
					// the group's namesake first: the runtime breaks a tie between donors' sound types by order
					donors.add(other.equals(g[1]) ? 0 : donors.size(), "minecraft:" + other);
				}
			});
			if (donors.isEmpty()) {
				report.warn("sounds", block + ": no Java block is in Bedrock's sound group " + g[1] + "; keeps Java's");
				return;
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("group", g[1]);
			JsonArray d = new JsonArray();
			donors.forEach(d::add);
			entry.add("donors", d);
			out.add("minecraft:" + block, entry);
			moved.merge(g[1], 1, Integer::sum);
		});
		Json.write(assets.resolve("lanature/bedrock/block_sounds.json"), out);
		report.info("sounds", out.size() + " Java blocks take another Bedrock sound group: " + moved);
	}

	private static String sound(JsonObject entry) {
		JsonElement s = entry == null ? null : entry.get("sound");
		return s == null || !s.isJsonPrimitive() ? null : s.getAsString();
	}
}
