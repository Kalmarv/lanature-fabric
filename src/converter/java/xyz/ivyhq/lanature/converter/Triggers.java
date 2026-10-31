package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java particle type → the Bedrock effect the game spawns in its place, kept only where the pack defines that effect
 * (otherwise the vanilla Java particle stays). From Geyser's particle table: its {@code bedrockId}, or for level-event
 * particles the effect {@code particle_events.json} names for the event. Types in {@code trigger_skip.json} stay vanilla.
 */
public final class Triggers {
	private Triggers() {
	}

	public static void run(JsonObject geyserParticles, JsonObject events, JsonObject skip, Set<String> packEffects, Path assets, Report report) throws IOException {
		JsonObject out = new JsonObject();
		int skipped = 0;
		for (Map.Entry<String, JsonElement> e : geyserParticles.entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			String effect = o.has("bedrockId") ? o.get("bedrockId").getAsString()
				: o.has("eventType") && events.has(o.get("eventType").getAsString()) ? events.get(o.get("eventType").getAsString()).getAsString() : null;
			String java = "minecraft:" + e.getKey().toLowerCase(Locale.ROOT);
			if (effect != null && packEffects.contains(effect)) {
				if (skip.has(java)) {
					skipped++;
					report.info("triggers", java + " stays vanilla: " + skip.get(java).getAsString());
				} else {
					out.addProperty(java, effect);
				}
			}
		}
		Json.write(assets.resolve("lanature/bedrock/triggers.json"), out);
		report.info("triggers", out.size() + " Java particle types replaced by pack effects, " + skipped + " left vanilla (trigger_skip.json)");
	}
}
