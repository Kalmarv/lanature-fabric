package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Compiles the converted particle files, the trigger table and the event-sound table for {@link ParticleSystem}. */
public final class ParticleLoader {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/particles");

	private ParticleLoader() {
	}

	/**
	 * {@code triggers}: Java particle type → the pack effect started for each such particle; {@code sites}: game-event
	 * site → the pack effect Bedrock starts there (see {@link SiteEffects}).
	 */
	public record Result(Map<String, ParticleEffect> effects, Map<Identifier, String> triggers, Map<String, String> sites,
		Map<String, ParticleSystem.SoundEntry> sounds) {
		public void apply() {
			ParticleSystem.get().load(effects, triggers, sites, sounds);
		}
	}

	/** Files are read in order; a later file with the same identifier (the pack's over vanilla's) wins. */
	public static Result load(Collection<JsonObject> files, Map<String, Set<net.minecraft.world.level.block.state.BlockState>> blockNames,
		Map<String, Material> materials, JsonObject triggerTable, JsonObject soundTable) {
		Map<String, ParticleEffect> effects = new HashMap<>();
		for (JsonObject file : files) {
			try {
				ParticleEffect fx = ParticleEffect.parse(file, name -> blockNames.getOrDefault(name, Set.of()), materials);
				effects.put(fx.id, fx);
			} catch (RuntimeException e) {
				LOGGER.warn("Skipping a particle effect that does not load", e);
			}
		}
		Map<Identifier, String> triggers = types(triggerTable.getAsJsonObject("particles"), effects);
		Map<String, String> sites = new HashMap<>();
		triggerTable.getAsJsonObject("sites").entrySet().forEach(e -> {
			if (effects.containsKey(e.getValue().getAsString())) {
				sites.put(e.getKey(), e.getValue().getAsString());
			}
		});
		Map<String, ParticleSystem.SoundEntry> sounds = new HashMap<>();
		soundTable.entrySet().forEach(e -> {
			JsonObject o = e.getValue().getAsJsonObject();
			double[] volume = range(o.get("volume"));
			double[] pitch = range(o.get("pitch"));
			sounds.put(e.getKey(), new ParticleSystem.SoundEntry(Identifier.parse(o.get("sound").getAsString()), volume[0], volume[1], pitch[0], pitch[1]));
		});
		return new Result(Map.copyOf(effects), Map.copyOf(triggers), Map.copyOf(sites), Map.copyOf(sounds));
	}

	/** A table of Java particle type → effect, keeping the effects that loaded. */
	private static Map<Identifier, String> types(JsonObject table, Map<String, ParticleEffect> effects) {
		Map<Identifier, String> out = new HashMap<>();
		if (table != null) {
			table.entrySet().forEach(e -> {
				Identifier id = Identifier.tryParse(e.getKey());
				if (id != null && effects.containsKey(e.getValue().getAsString())) {
					out.put(id, e.getValue().getAsString());
				}
			});
		}
		return out;
	}

	private static double[] range(JsonElement e) {
		if (e == null) {
			return new double[] {1, 1};
		}
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			return new double[] {a.get(0).getAsDouble(), a.get(a.size() - 1).getAsDouble()};
		}
		return new double[] {e.getAsDouble(), e.getAsDouble()};
	}
}
