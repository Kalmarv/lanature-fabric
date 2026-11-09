package xyz.ivyhq.lanature.client.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import org.jspecify.annotations.Nullable;

/**
 * The pack's block sound groups ({@code bedrock/block_sounds.json}): a block the pack moves to another Bedrock sound
 * group takes the sound type most common among its donors, Java blocks in that group in vanilla Bedrock.
 */
public final class BlockSounds {
	private static volatile Map<Block, SoundType> current = Map.of();

	private BlockSounds() {
	}

	/** Resolves the table; needs the block registry, not a level. */
	public static Map<Block, SoundType> resolve(@Nullable JsonObject table) {
		Map<Block, SoundType> out = new HashMap<>();
		if (table == null) {
			return out;
		}
		for (Map.Entry<String, JsonElement> e : table.entrySet()) {
			Optional<Block> block = block(e.getKey());
			if (block.isEmpty()) {
				continue;
			}
			Map<SoundType, Integer> votes = new HashMap<>();
			SoundType best = null;
			for (JsonElement d : e.getValue().getAsJsonObject().getAsJsonArray("donors")) {
				Optional<Block> donor = block(d.getAsString());
				if (donor.isPresent()) {
					SoundType t = donor.get().defaultBlockState().getSoundType();
					int n = votes.merge(t, 1, Integer::sum);
					if (best == null || n > votes.get(best)) {
						best = t;
					}
				}
			}
			if (best != null) {
				out.put(block.get(), best);
			}
		}
		return out;
	}

	public static void set(Map<Block, SoundType> table) {
		current = Map.copyOf(table);
	}

	/** The table in use, by block. */
	public static Map<Block, SoundType> table() {
		return current;
	}

	/** The pack's sound type for a block, or null to keep Java's. */
	public static @Nullable SoundType of(Block block) {
		Map<Block, SoundType> t = current;
		return t.isEmpty() ? null : t.get(block);
	}

	private static Optional<Block> block(String id) {
		Identifier i = Identifier.tryParse(id);
		return i == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(i);
	}
}
