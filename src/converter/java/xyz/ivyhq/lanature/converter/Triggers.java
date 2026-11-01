package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java particle type → the Bedrock effect the game spawns in its place, kept only where the pack defines that effect
 * (otherwise the vanilla Java particle stays). From Geyser's particle table: its {@code bedrockId}, or for level-event
 * particles the effect {@code particle_events.json} names for the event; these are {@code triggers.json}'s
 * {@code particles}, one effect started for each Java particle. Its {@code sites} are the game events at which the
 * runtime starts a Bedrock effect itself, from {@code trigger_sites.json}: site → the effect, kept where the pack
 * defines it. The Java types a site takes over never map particle by particle, since Bedrock starts their effect only
 * at that site; types in {@code trigger_skip.json} stay vanilla.
 */
public final class Triggers {
	private Triggers() {
	}

	public static void run(JsonObject geyserParticles, JsonObject events, JsonObject sites, JsonObject skip, Set<String> packEffects, Path assets,
		Report report) throws IOException {
		JsonObject atSites = new JsonObject();
		Set<String> taken = new HashSet<>();
		for (Map.Entry<String, JsonElement> e : sites.entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject site = e.getValue().getAsJsonObject();
			String effect = site.get("effect").getAsString();
			site.getAsJsonArray("replaces").forEach(t -> taken.add(t.getAsString()));
			if (packEffects.contains(effect)) {
				atSites.addProperty(e.getKey(), effect);
				report.info("triggers", "site " + e.getKey() + " starts " + effect + ": " + site.get("reason").getAsString());
			} else {
				report.info("triggers", "site " + e.getKey() + " stays vanilla: the pack does not define " + effect);
			}
		}
		JsonObject particles = new JsonObject();
		int skipped = 0;
		for (Map.Entry<String, JsonElement> e : geyserParticles.entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			String effect = o.has("bedrockId") ? o.get("bedrockId").getAsString()
				: o.has("eventType") && events.has(o.get("eventType").getAsString()) ? events.get(o.get("eventType").getAsString()).getAsString() : null;
			String java = "minecraft:" + e.getKey().toLowerCase(Locale.ROOT);
			if (effect == null || !packEffects.contains(effect)) {
				continue;
			}
			if (skip.has(java)) {
				skipped++;
				report.info("triggers", java + " stays vanilla: " + skip.get(java).getAsString());
			} else if (taken.contains(java)) {
				report.info("triggers", java + " is not mapped to " + effect + " for each particle: a site starts Bedrock's effect for it");
			} else {
				particles.addProperty(java, effect);
			}
		}
		JsonObject out = new JsonObject();
		out.add("particles", particles);
		out.add("sites", atSites);
		Json.write(assets.resolve("lanature/bedrock/triggers.json"), out);
		report.info("triggers", particles.size() + " Java particle types replaced by pack effects, " + atSites.size() + " game-event sites with pack effects, "
			+ skipped + " types left vanilla (trigger_skip.json)");
	}
}
