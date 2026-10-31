package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bedrock block name → the Java block states Geyser maps to it, for the Bedrock block names the runtime tests
 * ({@code assets/lanature/bedrock/block_names.json}). A Java block appears as its id when all of its states map to
 * the name, otherwise as each matching state ({@code minecraft:water[level=3]}).
 */
public final class BlockNames {
	private BlockNames() {
	}

	public static void run(GeyserBlocks geyser, Set<String> names, Path assets, Report report) throws IOException {
		Map<String, List<GeyserBlocks.JavaState>> byBedrock = geyser.byBedrock();
		JsonObject out = new JsonObject();
		int unmapped = 0;
		for (String name : names) {
			List<GeyserBlocks.JavaState> states = byBedrock.getOrDefault(JavaAssets.strip(name), List.of());
			JsonArray java = new JsonArray();
			Map<String, List<GeyserBlocks.JavaState>> byBlock = states.stream()
				.collect(Collectors.groupingBy(GeyserBlocks.JavaState::block, java.util.TreeMap::new, Collectors.toList()));
			byBlock.forEach((block, matching) -> {
				if (matching.size() == geyser.states(block).size()) {
					java.add("minecraft:" + block);
				} else {
					matching.forEach(s -> java.add("minecraft:" + block + "[" + s.properties().entrySet().stream()
						.map(p -> p.getKey() + "=" + p.getValue()).collect(Collectors.joining(",")) + "]"));
				}
			});
			if (java.isEmpty()) {
				unmapped++;
				report.warn("blocks", "particle block test names " + name + ", which no Java block state maps to");
			}
			out.add(name, java);
		}
		Json.writeCompact(assets.resolve("lanature/bedrock/block_names.json"), out);
		report.info("blocks", names.size() + " Bedrock block names for particle block tests, " + unmapped + " without a Java block");
	}
}
