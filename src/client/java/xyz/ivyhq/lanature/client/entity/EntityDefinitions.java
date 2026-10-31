package xyz.ivyhq.lanature.client.entity;

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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.model.BoneAnimation;
import xyz.ivyhq.lanature.client.model.EntityMaterial;
import xyz.ivyhq.lanature.client.model.Geometry;
import xyz.ivyhq.lanature.client.model.RenderController;
import xyz.ivyhq.lanature.client.particle.Expr;
import xyz.ivyhq.lanature.molang.Node;

/**
 * The compiled client entity definitions ({@code minecraft:client_entity}), animation controllers, animations,
 * geometries and render controllers: the pack's over the vanilla Bedrock files it relies on. An entity is
 * <em>modelled</em>, drawn with its Bedrock geometry in place of Java's model, when the pack gives it a geometry or
 * render controller of its own and every render controller it names exists; others keep Java's model and run only
 * their scripts.
 */
public record EntityDefinitions(Map<EntityType<?>, ClientEntity> entities, Map<String, Controller> controllers, Map<String, Animation> animations,
	Map<String, RenderController> renderControllers) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/entities");
	public static final EntityDefinitions EMPTY = new EntityDefinitions(Map.of(), Map.of(), Map.of(), Map.of());

	/**
	 * Entities whose Java look the port keeps even though the pack remodels them: Java draws the player's skin, cape,
	 * armour and held items, and the villager's biome and profession layers, through layers on its own model, and
	 * the Bedrock render controllers for them use multi-texture and colour-mask materials the port does not draw.
	 */
	private static final java.util.Set<String> JAVA_MODEL = java.util.Set.of("minecraft:player", "minecraft:villager_v2");

	/** One kind of source: the vanilla Bedrock files the pack relies on, or the pack's own. */
	public record Files(List<JsonObject> entities, List<JsonObject> controllers, List<JsonObject> animations, List<JsonObject> models,
		List<JsonObject> renderControllers) {
		public static final Files NONE = new Files(List.of(), List.of(), List.of(), List.of(), List.of());
	}

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

	/**
	 * A client entity. {@code locators} are in Java model axes and blocks, for an entity facing +Z before its yaw;
	 * {@code renderControllers} pairs each controller's identifier with its condition (null: always).
	 */
	public record ClientEntity(String id, Node initialize, Node preAnimation, List<Entry> animate, Node scale, Map<String, String> animations,
		Map<String, String> particleEffects, Map<String, String> soundEffects, Map<String, Vector3f> locators, Map<String, Geometry> geometries,
		Map<String, Identifier> textures, Map<String, EntityMaterial> materials, List<Entry> renderControllers, boolean modelled, BehaviourScale behaviourScale) {
	}

	public record Transition(String target, Node condition) {
	}

	public record State(List<Entry> animations, List<Transition> transitions, Node onEntry, Node onExit, List<EffectRef> particles, List<String> sounds) {
	}

	public record Controller(String id, String initialState, Map<String, State> states) {
	}

	public enum Loop { ONCE, LOOP, HOLD }

	/**
	 * An animation; {@code startDelay} (seconds before it plays, evaluated as it starts) and {@code loopDelay} (seconds
	 * before each loop, evaluated as it ends) are null when it has none.
	 */
	public record Animation(String id, Loop loop, double length, @Nullable Node startDelay, @Nullable Node loopDelay, TreeMap<Double, List<Node>> timeline,
		TreeMap<Double, List<EffectRef>> particles, TreeMap<Double, List<String>> sounds, BoneAnimation bones) {
	}

	/** The Bedrock identifiers of the modelled entities, sorted. */
	public List<String> modelled() {
		return entities.values().stream().filter(ClientEntity::modelled).map(ClientEntity::id).sorted().toList();
	}

	/** Loads the vanilla files, then the pack's over them (same identifiers replace); {@code materials} by name. */
	/** {@code scales}: the behaviour pack's scale of each entity (see {@link BehaviourScale}), by Bedrock identifier. */
	public static EntityDefinitions load(Files vanilla, Files pack, Map<String, EntityMaterial> materials, JsonObject scales) {
		Map<String, Geometry> geometries = new HashMap<>(Geometry.load(vanilla.models()));
		Map<String, Geometry> packGeometries = Geometry.load(pack.models());
		geometries.putAll(packGeometries);
		Map<String, RenderController> renderControllers = new HashMap<>(RenderController.load(vanilla.renderControllers()));
		Map<String, RenderController> packRenderControllers = RenderController.load(pack.renderControllers());
		renderControllers.putAll(packRenderControllers);
		Map<String, Controller> controllers = new HashMap<>();
		for (JsonObject f : concat(vanilla.controllers(), pack.controllers())) {
			section(f, "animation_controllers").forEach((id, v) -> guarded(id, () -> controllers.put(id, controller(id, v))));
		}
		Map<String, Animation> animations = new HashMap<>();
		for (JsonObject f : concat(vanilla.animations(), pack.animations())) {
			section(f, "animations").forEach((id, v) -> guarded(id, () -> animations.put(id, animation(id, v))));
		}
		Sources sources = new Sources(geometries, packGeometries.keySet(), renderControllers, packRenderControllers.keySet(), materials, scales);
		Map<EntityType<?>, ClientEntity> entities = new HashMap<>();
		for (JsonObject f : concat(vanilla.entities(), pack.entities())) {
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
			guarded(id, () -> entities.put(type, entity(id, desc, sources)));
		}
		return new EntityDefinitions(Map.copyOf(entities), Map.copyOf(controllers), Map.copyOf(animations), Map.copyOf(renderControllers));
	}

	private record Sources(Map<String, Geometry> geometries, java.util.Set<String> packGeometries, Map<String, RenderController> renderControllers,
		java.util.Set<String> packRenderControllers, Map<String, EntityMaterial> materials, JsonObject scales) {
	}

	private static List<JsonObject> concat(List<JsonObject> a, List<JsonObject> b) {
		List<JsonObject> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
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

	private static ClientEntity entity(String id, JsonObject desc, Sources sources) {
		JsonObject scripts = obj(desc, "scripts");
		Map<String, Geometry> geometries = new HashMap<>();
		boolean packLook = false;
		for (Map.Entry<String, String> e : strings(obj(desc, "geometry")).entrySet()) {
			Geometry g = sources.geometries().get(e.getValue());
			if (g == null) {
				LOGGER.warn("{}: geometry {} ({}) is not defined", id, e.getKey(), e.getValue());
				continue;
			}
			geometries.put(e.getKey().toLowerCase(Locale.ROOT), g);
			packLook |= sources.packGeometries().contains(e.getValue());
		}
		Map<String, Identifier> textures = new HashMap<>();
		strings(obj(desc, "textures")).forEach((k, v) -> {
			Identifier t = Identifier.tryParse(v);
			if (t != null) {
				textures.put(k.toLowerCase(Locale.ROOT), t);
			}
		});
		Map<String, EntityMaterial> materials = new HashMap<>();
		strings(obj(desc, "materials")).forEach((k, v) -> materials.put(k.toLowerCase(Locale.ROOT), EntityMaterial.lookup(sources.materials(), v)));
		List<Entry> renderControllers = entries(desc.get("render_controllers"));
		boolean complete = !renderControllers.isEmpty();
		for (Entry rc : renderControllers) {
			if (!sources.renderControllers().containsKey(rc.name())) {
				LOGGER.warn("{}: render controller {} is not defined; the entity keeps Java's model", id, rc.name());
				complete = false;
			}
			packLook |= sources.packRenderControllers().contains(rc.name());
		}
		boolean modelled = packLook && complete && !JAVA_MODEL.contains(id);
		// the default geometry's locators first, then those only other geometries have
		Map<String, Vector3f> locators = new HashMap<>();
		List<Geometry> ordered = new ArrayList<>();
		if (geometries.containsKey("default")) {
			ordered.add(geometries.get("default"));
		}
		ordered.addAll(geometries.values());
		for (Geometry g : ordered) {
			for (Geometry.Bone b : g.bones()) {
				// geometry axes (model facing -Z) to a Java entity facing +Z: a half turn about Y
				b.locators().forEach((name, at) -> locators.putIfAbsent(name, new Vector3f(-at.x, at.y, -at.z).div(16)));
			}
		}
		return new ClientEntity(id, Expr.script(scripts.get("initialize")), Expr.script(scripts.get("pre_animation")), entries(scripts.get("animate")),
			scripts.has("scale") ? Expr.scalar(scripts.get("scale"), 1) : null, strings(obj(desc, "animations")), strings(obj(desc, "particle_effects")),
			strings(obj(desc, "sound_effects")), Map.copyOf(locators), Map.copyOf(geometries), Map.copyOf(textures), Map.copyOf(materials),
			renderControllers, modelled, sources.scales().has(id) ? BehaviourScale.parse(sources.scales().getAsJsonObject(id)) : BehaviourScale.NONE);
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
		Node startDelay = a.has("start_delay") ? Expr.scalar(a.get("start_delay"), 0) : null;
		Node loopDelay = a.has("loop_delay") ? Expr.scalar(a.get("loop_delay"), 0) : null;
		return new Animation(id, loop, length, startDelay, loopDelay, timeline, particles, sounds, BoneAnimation.parse(a));
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
