package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java block state → Bedrock block, from GeyserMC's {@code blocks.nbt} (one entry per Java state id; the Bedrock
 * identifier is omitted when it equals the Java block name) and Minecraft's own block report for the state ids.
 */
public final class GeyserBlocks {
	/** Java block name (no namespace) → its states in id order. */
	private final Map<String, List<JavaState>> statesByBlock = new LinkedHashMap<>();

	public GeyserBlocks(Path blocksNbt, JsonObject blockReport) throws IOException {
		List<?> mappings = (List<?>) Nbt.readCompressed(blocksNbt).get("bedrock_mappings");
		for (Map.Entry<String, JsonElement> e : blockReport.entrySet()) {
			String block = JavaAssets.strip(e.getKey());
			List<JavaState> states = new ArrayList<>();
			for (JsonElement s : e.getValue().getAsJsonObject().getAsJsonArray("states")) {
				JsonObject so = s.getAsJsonObject();
				int id = so.get("id").getAsInt();
				Map<String, String> props = new TreeMap<>();
				if (so.has("properties")) {
					so.getAsJsonObject("properties").entrySet().forEach(p -> props.put(p.getKey(), p.getValue().getAsString()));
				}
				Map<?, ?> m = (Map<?, ?>) mappings.get(id);
				String bedrockId = m.containsKey("bedrock_identifier") ? (String) m.get("bedrock_identifier") : block;
				Map<String, String> bedrockStates = new TreeMap<>();
				if (m.get("state") instanceof Map<?, ?> st) {
					st.forEach((k, v) -> bedrockStates.put((String) k, String.valueOf(v)));
				}
				boolean isDefault = so.has("default") && so.get("default").getAsBoolean();
				states.add(new JavaState(block, props, isDefault, JavaAssets.strip(bedrockId), bedrockStates));
			}
			statesByBlock.put(block, states);
		}
	}

	public Iterable<String> blocks() {
		return statesByBlock.keySet();
	}

	public List<JavaState> states(String block) {
		return statesByBlock.getOrDefault(block, List.of());
	}

	public JavaState defaultState(String block) {
		List<JavaState> states = states(block);
		return states.stream().filter(JavaState::isDefault).findFirst().orElse(states.isEmpty() ? null : states.getFirst());
	}

	/** Java blocks whose states map to the given Bedrock block. */
	public Map<String, List<JavaState>> byBedrock() {
		Map<String, List<JavaState>> out = new HashMap<>();
		statesByBlock.values().forEach(l -> l.forEach(s -> out.computeIfAbsent(s.bedrock(), k -> new ArrayList<>()).add(s)));
		return out;
	}

	public record JavaState(String block, Map<String, String> properties, boolean isDefault, String bedrock, Map<String, String> bedrockStates) {
		/** Whether this state satisfies a blockstate variant key like {@code age=3,facing=north} (empty = all). */
		public boolean matchesVariant(String key) {
			if (key.isEmpty()) {
				return true;
			}
			for (String part : key.split(",")) {
				int eq = part.indexOf('=');
				if (!part.substring(eq + 1).equals(properties.get(part.substring(0, eq)))) {
					return false;
				}
			}
			return true;
		}
	}
}
