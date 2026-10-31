package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * A particle effect event: any of {@code expression}, {@code particle_effect}, {@code sound_effect},
 * {@code sequence} and {@code randomize}. The expression runs first, in the firing emitter's or particle's scope; the
 * temp variables it sets stay visible to the rest of the event, which is how {@code pre_effect_expression} passes values
 * to the new effect ({@code v.spawn = t.s;}).
 */
final class Event {
	enum SpawnType { EMITTER, EMITTER_BOUND, PARTICLE, PARTICLE_WITH_VELOCITY }

	record Spawn(String effect, SpawnType type, Node preEffect) {
	}

	private final Node expression;
	private final Spawn spawn;
	private final String sound;
	private final List<Event> sequence;
	private final List<Event> randomize;
	private final double[] weights;
	private final double totalWeight;

	private Event(Node expression, Spawn spawn, String sound, List<Event> sequence, List<Event> randomize, double[] weights) {
		this.expression = expression;
		this.spawn = spawn;
		this.sound = sound;
		this.sequence = sequence;
		this.randomize = randomize;
		this.weights = weights;
		double total = 0;
		for (double w : weights) {
			total += w;
		}
		this.totalWeight = total;
	}

	static Event parse(JsonObject o) {
		Node expression = Expr.script(o.get("expression"));
		Spawn spawn = null;
		if (o.has("particle_effect") && o.get("particle_effect").isJsonObject()) {
			JsonObject p = o.getAsJsonObject("particle_effect");
			if (p.has("effect")) {
				SpawnType type = switch (p.has("type") ? p.get("type").getAsString() : "emitter") {
					case "emitter_bound" -> SpawnType.EMITTER_BOUND;
					case "particle" -> SpawnType.PARTICLE;
					case "particle_with_velocity" -> SpawnType.PARTICLE_WITH_VELOCITY;
					default -> SpawnType.EMITTER;
				};
				spawn = new Spawn(p.get("effect").getAsString(), type, Expr.script(p.get("pre_effect_expression")));
			}
		}
		String sound = null;
		if (o.has("sound_effect") && o.get("sound_effect").isJsonObject() && o.getAsJsonObject("sound_effect").has("event_name")) {
			sound = o.getAsJsonObject("sound_effect").get("event_name").getAsString();
		}
		List<Event> sequence = list(o.get("sequence"));
		List<Event> randomize = list(o.get("randomize"));
		double[] weights = new double[randomize.size()];
		if (o.has("randomize")) {
			for (int i = 0; i < weights.length; i++) {
				JsonObject r = o.getAsJsonArray("randomize").get(i).getAsJsonObject();
				weights[i] = r.has("weight") ? r.get("weight").getAsDouble() : 1;
			}
		}
		return new Event(expression, spawn, sound, sequence, randomize, weights);
	}

	private static List<Event> list(JsonElement e) {
		List<Event> out = new ArrayList<>();
		if (e != null && e.isJsonArray()) {
			e.getAsJsonArray().forEach(x -> {
				if (x.isJsonObject()) {
					out.add(parse(x.getAsJsonObject()));
				}
			});
		}
		return out;
	}

	/** Fires in {@code scope} (set up for {@code source}) with a fresh temp namespace shared by the whole event. */
	void fire(EffectSource source, Scope scope) {
		Variables temps = scope.temps;
		scope.temps = new Variables();
		try {
			run(source, scope);
		} finally {
			scope.temps = temps;
		}
	}

	private void run(EffectSource source, Scope scope) {
		Expr.run(expression, scope);
		if (spawn != null) {
			source.system().spawnFromEvent(source, spawn, scope);
		}
		if (sound != null) {
			source.system().playSound(sound, source);
		}
		for (Event e : sequence) {
			e.run(source, scope);
		}
		if (!randomize.isEmpty() && totalWeight > 0) {
			double pick = scope.random.nextDouble() * totalWeight;
			for (int i = 0; i < weights.length; i++) {
				pick -= weights[i];
				if (pick < 0 || i == weights.length - 1) {
					randomize.get(i).run(source, scope);
					break;
				}
			}
		}
	}
}
