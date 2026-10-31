package xyz.ivyhq.lanature.client.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.particle.Expr;
import xyz.ivyhq.lanature.molang.Node;

/**
 * The compiled client entity definitions ({@code minecraft:client_entity}), animation controllers and animations, with
 * each entity's model locators. Only what drives effects is kept: scripts, controller states and the timed particle,
 * sound and timeline entries of animations; bone animation waits for model rendering.
 */
public record EntityDefinitions(Map<EntityType<?>, ClientEntity> entities, Map<String, Controller> controllers, Map<String, Animation> animations) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/entities");
	public static final EntityDefinitions EMPTY = new EntityDefinitions(Map.of(), Map.of(), Map.of());

	/** Bedrock entity identifiers whose Java entity type has another name. */
	private static final Map<String, String> JAVA_IDS = Map.of(
		"minecraft:ender_crystal", "minecraft:end_crystal",
		"minecraft:xp_orb", "minecraft:experience_orb",
		"minecraft:fishing_hook", "minecraft:fishing_bobber",
		"minecraft:villager_v2", "minecraft:villager");

	/** An {@code animate} entry: an animation or controller by its entity-local name, played while its weight is above 0. */
	public record Entry(String name, Node weight) {
	}

	/** A particle effect a state or an animation starts, by the entity's short name for it. */
	public record EffectRef(String effect, String locator, Node preEffectScript, boolean bindToActor) {
	}

	public record ClientEntity(String id, Node initialize, Node preAnimation, List<Entry> animate, Node scale, Map<String, String> animations,
		Map<String, String> particleEffects, Map<String, String> soundEffects, Map<String, Vector3f> locators) {
	}

	public record Transition(String target, Node condition) {
	}

	public record State(List<Entry> animations, List<Transition> transitions, Node onEntry, Node onExit, List<EffectRef> particles, List<String> sounds) {
	}

	public record Controller(String id, String initialState, Map<String, State> states) {
	}

	public enum Loop { ONCE, LOOP, HOLD }

	public record Animation(String id, Loop loop, double length, TreeMap<Double, List<Node>> timeline, TreeMap<Double, List<EffectRef>> particles,
		TreeMap<Double, List<String>> sounds) {
	}

	public static EntityDefinitions load(List<JsonObject> entityFiles, List<JsonObject> controllerFiles, List<JsonObject> animationFiles,
		List<JsonObject> modelFiles) {
		Map<String, Map<String, Vector3f>> locators = new HashMap<>();
		modelFiles.forEach(f -> geometryLocators(f, locators));
		Map<String, Controller> controllers = new HashMap<>();
		for (JsonObject f : controllerFiles) {
			section(f, "animation_controllers").forEach((id, v) -> guarded(id, () -> controllers.put(id, controller(id, v))));
		}
		Map<String, Animation> animations = new HashMap<>();
		for (JsonObject f : animationFiles) {
			section(f, "animations").forEach((id, v) -> guarded(id, () -> animations.put(id, animation(id, v))));
		}
		Map<EntityType<?>, ClientEntity> entities = new HashMap<>();
		for (JsonObject f : entityFiles) {
			JsonObject desc = f.has("minecraft:client_entity") ? f.getAsJsonObject("minecraft:client_entity").getAsJsonObject("description") : null;
			if (desc == null || !desc.has("identifier")) {
				continue;
			}
			String id = desc.get("identifier").getAsString();
			Identifier javaId = Identifier.tryParse(JAVA_IDS.getOrDefault(id, id));
			EntityType<?> type = javaId == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(javaId).orElse(null);
			if (type == null) {
				LOGGER.info("Client entity {} has no Java entity; skipped", id);
				continue;
			}
			guarded(id, () -> entities.put(type, entity(id, desc, locators)));
		}
		return new EntityDefinitions(Map.copyOf(entities), Map.copyOf(controllers), Map.copyOf(animations));
	}

	private static void guarded(String id, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			LOGGER.warn("Skipping {}, which does not load", id, e);
		}
	}

	private static Map<String, JsonObject> section(JsonObject file, String key) {
		Map<String, JsonObject> out = new HashMap<>();
		if (file.has(key) && file.get(key).isJsonObject()) {
			file.getAsJsonObject(key).entrySet().forEach(e -> {
				if (e.getValue().isJsonObject()) {
					out.put(e.getKey(), e.getValue().getAsJsonObject());
				}
			});
		}
		return out;
	}

	// client entities

	private static ClientEntity entity(String id, JsonObject desc, Map<String, Map<String, Vector3f>> geometryLocators) {
		JsonObject scripts = obj(desc, "scripts");
		Map<String, Vector3f> locators = new HashMap<>();
		// the default geometry's locators first, then those only other geometries have
		JsonObject geometry = obj(desc, "geometry");
		List<String> geometries = new ArrayList<>();
		if (geometry.has("default")) {
			geometries.add(geometry.get("default").getAsString());
		}
		geometry.entrySet().forEach(e -> geometries.add(e.getValue().getAsString()));
		for (String g : geometries) {
			geometryLocators.getOrDefault(g, Map.of()).forEach(locators::putIfAbsent);
		}
		return new ClientEntity(id, Expr.script(scripts.get("initialize")), Expr.script(scripts.get("pre_animation")), entries(scripts.get("animate")),
			scripts.has("scale") ? Expr.scalar(scripts.get("scale"), 1) : null, strings(obj(desc, "animations")), strings(obj(desc, "particle_effects")),
			strings(obj(desc, "sound_effects")), Map.copyOf(locators));
	}

	/**
	 * Locators of every geometry, in Java model axes and blocks: Bedrock's model faces -Z in pixels, so a point
	 * {@code (x, y, z)} is {@code (-x, y, -z) / 16} in front of a Java entity that faces +Z before its yaw turns it.
	 */
	private static void geometryLocators(JsonObject file, Map<String, Map<String, Vector3f>> out) {
		if (file.has("minecraft:geometry") && file.get("minecraft:geometry").isJsonArray()) {
			for (JsonElement g : file.getAsJsonArray("minecraft:geometry")) {
				JsonObject go = g.getAsJsonObject();
				out.put(go.getAsJsonObject("description").get("identifier").getAsString(), boneLocators(go));
			}
		}
		file.entrySet().forEach(e -> {
			if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
				out.put(e.getKey().split(":", 2)[0], boneLocators(e.getValue().getAsJsonObject()));
			}
		});
	}

	private static Map<String, Vector3f> boneLocators(JsonObject geometry) {
		Map<String, Vector3f> out = new HashMap<>();
		if (!geometry.has("bones")) {
			return out;
		}
		for (JsonElement b : geometry.getAsJsonArray("bones")) {
			JsonObject bone = b.getAsJsonObject();
			obj(bone, "locators").entrySet().forEach(e -> {
				JsonElement v = e.getValue();
				JsonArray at = v.isJsonArray() ? v.getAsJsonArray() : v.isJsonObject() && v.getAsJsonObject().has("offset") ? v.getAsJsonObject().getAsJsonArray("offset") : null;
				if (at != null && at.size() == 3) {
					out.put(e.getKey(), new Vector3f(-at.get(0).getAsFloat(), at.get(1).getAsFloat(), -at.get(2).getAsFloat()).div(16));
				}
			});
		}
		return out;
	}

	// controllers

	private static Controller controller(String id, JsonObject c) {
		Map<String, State> states = new HashMap<>();
		obj(c, "states").entrySet().forEach(e -> {
			JsonObject s = e.getValue().getAsJsonObject();
			List<Transition> transitions = new ArrayList<>();
			if (s.has("transitions")) {
				for (JsonElement t : s.getAsJsonArray("transitions")) {
					t.getAsJsonObject().entrySet().forEach(te -> transitions.add(new Transition(te.getKey(), Expr.scalar(te.getValue(), 0))));
				}
			}
			List<EffectRef> particles = new ArrayList<>();
			effects(s.get("particle_effects"), particles);
			List<String> sounds = new ArrayList<>();
			sounds(s.get("sound_effects"), sounds);
			states.put(e.getKey(), new State(entries(s.get("animations")), List.copyOf(transitions), Expr.script(s.get("on_entry")), Expr.script(s.get("on_exit")),
				List.copyOf(particles), List.copyOf(sounds)));
		});
		String initial = c.has("initial_state") ? c.get("initial_state").getAsString() : "default";
		return new Controller(id, initial, Map.copyOf(states));
	}

	// animations

	private static Animation animation(String id, JsonObject a) {
		Loop loop = Loop.ONCE;
		if (a.has("loop")) {
			JsonElement l = a.get("loop");
			if (l.isJsonPrimitive() && l.getAsJsonPrimitive().isString()) {
				loop = l.getAsString().equals("hold_on_last_frame") ? Loop.HOLD : Loop.ONCE;
			} else if (l.isJsonPrimitive() && l.getAsJsonPrimitive().isBoolean()) {
				loop = l.getAsBoolean() ? Loop.LOOP : Loop.ONCE;
			}
		}
		TreeMap<Double, List<Node>> timeline = new TreeMap<>();
		obj(a, "timeline").entrySet().forEach(e -> {
			Node n = Expr.script(e.getValue());
			if (n != null) {
				timeline.computeIfAbsent(time(e.getKey()), k -> new ArrayList<>()).add(n);
			}
		});
		TreeMap<Double, List<EffectRef>> particles = new TreeMap<>();
		obj(a, "particle_effects").entrySet().forEach(e -> effects(e.getValue(), particles.computeIfAbsent(time(e.getKey()), k -> new ArrayList<>())));
		TreeMap<Double, List<String>> sounds = new TreeMap<>();
		obj(a, "sound_effects").entrySet().forEach(e -> sounds(e.getValue(), sounds.computeIfAbsent(time(e.getKey()), k -> new ArrayList<>())));
		double length;
		if (a.has("animation_length")) {
			length = a.get("animation_length").getAsDouble();
		} else {
			// Bedrock's default length is the last keyframe of anything the animation keys
			length = Math.max(lastKey(timeline), Math.max(lastKey(particles), lastKey(sounds)));
			for (Map.Entry<String, JsonElement> bone : obj(a, "bones").entrySet()) {
				if (bone.getValue().isJsonObject()) {
					for (Map.Entry<String, JsonElement> channel : bone.getValue().getAsJsonObject().entrySet()) {
						if (channel.getValue().isJsonObject()) {
							for (String key : channel.getValue().getAsJsonObject().keySet()) {
								length = Math.max(length, time(key));
							}
						}
					}
				}
			}
		}
		return new Animation(id, loop, length, timeline, particles, sounds);
	}

	private static double lastKey(TreeMap<Double, ?> map) {
		return map.isEmpty() ? 0 : Math.max(0, map.lastKey());
	}

	private static double time(String key) {
		try {
			return Double.parseDouble(key);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// shared

	private static void effects(JsonElement e, List<EffectRef> out) {
		if (e == null) {
			return;
		}
		if (e.isJsonArray()) {
			e.getAsJsonArray().forEach(x -> effects(x, out));
			return;
		}
		if (!e.isJsonObject() || !e.getAsJsonObject().has("effect")) {
			return;
		}
		JsonObject o = e.getAsJsonObject();
		out.add(new EffectRef(o.get("effect").getAsString(), o.has("locator") ? o.get("locator").getAsString() : null,
			Expr.script(o.get("pre_effect_script")), !o.has("bind_to_actor") || o.get("bind_to_actor").getAsBoolean()));
	}

	private static void sounds(JsonElement e, List<String> out) {
		if (e == null) {
			return;
		}
		if (e.isJsonArray()) {
			e.getAsJsonArray().forEach(x -> sounds(x, out));
		} else if (e.isJsonObject() && e.getAsJsonObject().has("effect")) {
			out.add(e.getAsJsonObject().get("effect").getAsString());
		}
	}

	private static List<Entry> entries(JsonElement e) {
		List<Entry> out = new ArrayList<>();
		if (e == null || !e.isJsonArray()) {
			return out;
		}
		for (JsonElement x : e.getAsJsonArray()) {
			if (x.isJsonPrimitive()) {
				out.add(new Entry(x.getAsString(), null));
			} else if (x.isJsonObject()) {
				x.getAsJsonObject().entrySet().forEach(en -> out.add(new Entry(en.getKey(), Expr.scalar(en.getValue(), 1))));
			}
		}
		return List.copyOf(out);
	}

	private static Map<String, String> strings(JsonObject o) {
		Map<String, String> out = new HashMap<>();
		o.entrySet().forEach(e -> {
			if (e.getValue().isJsonPrimitive()) {
				out.put(e.getKey(), e.getValue().getAsString());
			}
		});
		return Map.copyOf(out);
	}

	private static JsonObject obj(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
	}

	/** The Java sound event the converter made of a Bedrock sound definition. */
	static Identifier sound(String definition) {
		StringBuilder out = new StringBuilder(definition.length());
		for (char c : definition.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
		}
		return Identifier.fromNamespaceAndPath("lanature", out.toString());
	}
}
