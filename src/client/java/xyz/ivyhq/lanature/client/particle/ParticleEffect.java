package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import xyz.ivyhq.lanature.molang.Molang;
import xyz.ivyhq.lanature.molang.Node;

/** A compiled Bedrock particle effect: its description, curves, components and events. */
final class ParticleEffect {
	enum Rate { INSTANT, STEADY, MANUAL }

	enum Lifetime { ONCE, LOOPING, EXPRESSION }

	enum Shape { POINT, SPHERE, BOX, DISC, CUSTOM, ENTITY_AABB }

	enum Direction { OUTWARDS, INWARDS, VECTOR }

	enum Facing {
		ROTATE_XYZ, ROTATE_Y, LOOKAT_XYZ, LOOKAT_Y, LOOKAT_DIRECTION, DIRECTION_X, DIRECTION_Y, DIRECTION_Z,
		EMITTER_TRANSFORM_XY, EMITTER_TRANSFORM_XZ, EMITTER_TRANSFORM_YZ
	}

	enum Motion { NONE, DYNAMIC, PARAMETRIC }

	record Flipbook(Node[] base, double[] size, double[] step, Node fps, Node maxFrame, boolean stretch, boolean loop) {
	}

	/** A colour stop: constant RGBA or expressions. */
	record Color(Node[] rgba) {
	}

	record CollisionEvent(String event, double minSpeed) {
	}

	final String id;
	final Material material;
	final Identifier texture;
	final List<Curve> curves = new ArrayList<>();
	final Map<String, Event> events = new HashMap<>();

	// emitter
	Node emitterCreation;
	Node emitterPerUpdate;
	boolean localPosition;
	boolean localRotation;
	boolean localVelocity;
	Rate rate = Rate.MANUAL;
	Node numParticles = Molang.constant(10);
	Node spawnRate = Molang.constant(1);
	Node maxParticles = Molang.constant(50);
	Lifetime lifetime = Lifetime.EXPRESSION;
	Node activeTime = Molang.constant(10);
	Node sleepTime = Molang.ZERO;
	Node activation = Molang.ONE;
	Node expiration = Molang.ZERO;
	String[] emitterCreationEvents = new String[0];
	String[] emitterExpirationEvents = new String[0];
	final TreeMap<Double, String[]> emitterTimeline = new TreeMap<>();
	Shape shape = Shape.POINT;
	Node[] shapeOffset = Expr.vector(null, 3, 0);
	Node shapeRadius = Molang.ONE;
	Node[] halfDimensions = Expr.vector(null, 3, 0);
	Node[] planeNormal = {Molang.ZERO, Molang.ONE, Molang.ZERO};
	boolean surfaceOnly;
	Direction direction = Direction.OUTWARDS;
	Node[] directionVector = Expr.vector(null, 3, 0);

	// particle
	Node particlePerUpdate;
	Node particlePerRender;
	Node maxLifetime;
	Node particleExpiration;
	String[] particleCreationEvents = new String[0];
	String[] particleExpirationEvents = new String[0];
	final TreeMap<Double, String[]> particleTimeline = new TreeMap<>();
	Node speed = Molang.ZERO;
	Node[] speedVector;
	Node spin = Molang.ZERO;
	Node spinRate = Molang.ZERO;
	Motion motion = Motion.NONE;
	Node[] acceleration = Expr.vector(null, 3, 0);
	Node drag = Molang.ZERO;
	Node rotationAcceleration = Molang.ZERO;
	Node rotationDrag = Molang.ZERO;
	Node[] relativePosition;
	Node[] parametricDirection;
	Node parametricRotation;
	boolean collision;
	Node collisionEnabled = Molang.ONE;
	double collisionDrag;
	double restitution;
	double collisionRadius = 0.01;
	boolean expireOnContact;
	final List<CollisionEvent> collisionEvents = new ArrayList<>();
	double[] killPlane;
	Set<BlockState> expireInBlocks;
	Set<BlockState> expireNotInBlocks;
	boolean billboard;
	Node[] size = {Molang.constant(0.1), Molang.constant(0.1)};
	Facing facing = Facing.ROTATE_XYZ;
	boolean directionFromVelocity = true;
	double minSpeed = 0.01;
	Node[] customDirection;
	double textureWidth = 1;
	double textureHeight = 1;
	Node[] uv = Expr.vector(null, 2, 0);
	Node[] uvSize = Expr.vector(null, 2, 1);
	Flipbook flipbook;
	Node[] tint;
	Node gradientInterpolant;
	double[] gradientKeys;
	Node[][] gradientColors;
	boolean lighting;

	ParticleEffect(String id, Material material, Identifier texture) {
		this.id = id;
		this.material = material;
		this.texture = texture;
	}

	/**
	 * A block seed: an invisible particle that must stay inside given blocks and spawns the visible effect from its
	 * lifetime timeline (the pack's torch flames run for 20 s). The engine re-triggers its effect far more often than
	 * the seed lives, so the system keeps one seed per block.
	 */
	boolean blockSeed() {
		return expireNotInBlocks != null && !particleTimeline.isEmpty();
	}

	/**
	 * Parses one {@code particle_effect}; {@code blocks} resolves a Bedrock block name to the Java states it covers,
	 * {@code materials} holds the resolved particle materials.
	 */
	static ParticleEffect parse(JsonObject root, Function<String, Set<BlockState>> blocks, Map<String, Material> materials) {
		JsonObject pe = root.getAsJsonObject("particle_effect");
		JsonObject desc = pe.getAsJsonObject("description");
		JsonObject params = desc.has("basic_render_parameters") ? desc.getAsJsonObject("basic_render_parameters") : new JsonObject();
		Material material = Material.lookup(materials, params.has("material") ? params.get("material").getAsString() : "particles_alpha");
		Identifier texture = params.has("texture") ? Identifier.tryParse(params.get("texture").getAsString()) : null;
		ParticleEffect fx = new ParticleEffect(desc.get("identifier").getAsString(), material, texture);
		if (pe.has("curves")) {
			pe.getAsJsonObject("curves").entrySet().forEach(e -> {
				if (e.getValue().isJsonObject()) {
					fx.curves.add(Curve.parse(e.getKey(), e.getValue().getAsJsonObject()));
				}
			});
		}
		if (pe.has("events")) {
			pe.getAsJsonObject("events").entrySet().forEach(e -> {
				if (e.getValue().isJsonObject()) {
					fx.events.put(e.getKey(), Event.parse(e.getValue().getAsJsonObject()));
				}
			});
		}
		JsonObject c = pe.has("components") ? pe.getAsJsonObject("components") : new JsonObject();
		for (Map.Entry<String, JsonElement> e : c.entrySet()) {
			String key = e.getKey().toLowerCase(Locale.ROOT);
			key = key.startsWith("minecraft:") ? key.substring(10) : key;
			fx.component(key, e.getValue(), blocks);
		}
		return fx;
	}

	private static JsonObject obj(JsonElement e) {
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	private static double num(JsonObject o, String key, double fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : fallback;
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
	}

	private static String[] events(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return new String[0];
		}
		if (e.isJsonArray()) {
			List<String> out = new ArrayList<>();
			e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
			return out.toArray(String[]::new);
		}
		return new String[] {e.getAsString()};
	}

	private static void timeline(JsonElement e, TreeMap<Double, String[]> out) {
		obj(e).entrySet().forEach(t -> {
			try {
				out.put(Double.parseDouble(t.getKey()), events(t.getValue()));
			} catch (NumberFormatException ignored) {
				// a malformed key never fires on Bedrock either
			}
		});
	}

	private void component(String key, JsonElement v, Function<String, Set<BlockState>> blocks) {
		JsonObject o = obj(v);
		switch (key) {
			case "emitter_initialization" -> {
				emitterCreation = Expr.script(o.get("creation_expression"));
				emitterPerUpdate = Expr.script(o.get("per_update_expression"));
			}
			case "emitter_local_space" -> {
				localPosition = bool(o, "position", false);
				localRotation = bool(o, "rotation", false);
				localVelocity = bool(o, "velocity", false);
			}
			case "emitter_rate_instant" -> {
				rate = Rate.INSTANT;
				numParticles = Expr.scalar(o.get("num_particles"), 10);
			}
			case "emitter_rate_steady" -> {
				rate = Rate.STEADY;
				spawnRate = Expr.scalar(o.get("spawn_rate"), 1);
				maxParticles = Expr.scalar(o.get("max_particles"), 50);
			}
			case "emitter_rate_manual" -> {
				rate = Rate.MANUAL;
				maxParticles = Expr.scalar(o.get("max_particles"), 50);
			}
			case "emitter_lifetime_once" -> {
				lifetime = Lifetime.ONCE;
				activeTime = Expr.scalar(o.get("active_time"), 10);
			}
			case "emitter_lifetime_looping" -> {
				lifetime = Lifetime.LOOPING;
				activeTime = Expr.scalar(o.get("active_time"), 10);
				sleepTime = Expr.scalar(o.get("sleep_time"), 0);
			}
			case "emitter_lifetime_expression" -> {
				lifetime = Lifetime.EXPRESSION;
				activation = Expr.scalar(o.get("activation_expression"), 1);
				expiration = Expr.scalar(o.get("expiration_expression"), 0);
			}
			case "emitter_lifetime_events" -> {
				emitterCreationEvents = events(o.get("creation_event"));
				emitterExpirationEvents = events(o.get("expiration_event"));
				timeline(o.get("timeline"), emitterTimeline);
			}
			case "emitter_shape_point" -> shape(Shape.POINT, o);
			case "emitter_shape_sphere" -> shape(Shape.SPHERE, o);
			case "emitter_shape_box" -> shape(Shape.BOX, o);
			case "emitter_shape_disc" -> shape(Shape.DISC, o);
			case "emitter_shape_custom" -> shape(Shape.CUSTOM, o);
			case "emitter_shape_entity_aabb" -> shape(Shape.ENTITY_AABB, o);
			case "particle_initialization" -> {
				particlePerUpdate = Expr.script(o.get("per_update_expression"));
				particlePerRender = Expr.script(o.get("per_render_expression"));
			}
			case "particle_lifetime_expression" -> {
				maxLifetime = o.has("max_lifetime") ? Expr.scalar(o.get("max_lifetime"), 1) : null;
				particleExpiration = o.has("expiration_expression") ? Expr.scalar(o.get("expiration_expression"), 0) : null;
			}
			case "particle_lifetime_events" -> {
				particleCreationEvents = events(o.get("creation_event"));
				particleExpirationEvents = events(o.get("expiration_event"));
				timeline(o.get("timeline"), particleTimeline);
			}
			case "particle_initial_speed" -> {
				if (v.isJsonArray()) {
					speedVector = Expr.vector(v, 3, 0);
				} else {
					speed = Expr.scalar(v, 0);
				}
			}
			case "particle_initial_spin" -> {
				spin = Expr.scalar(o.get("rotation"), 0);
				spinRate = Expr.scalar(o.get("rotation_rate"), 0);
			}
			case "particle_motion_dynamic" -> {
				motion = Motion.DYNAMIC;
				acceleration = Expr.vector(o.get("linear_acceleration"), 3, 0);
				drag = Expr.scalar(o.get("linear_drag_coefficient"), 0);
				rotationAcceleration = Expr.scalar(o.get("rotation_acceleration"), 0);
				rotationDrag = Expr.scalar(o.get("rotation_drag_coefficient"), 0);
			}
			case "particle_motion_parametric" -> {
				motion = Motion.PARAMETRIC;
				relativePosition = o.has("relative_position") ? Expr.vector(o.get("relative_position"), 3, 0) : null;
				parametricDirection = o.has("direction") ? Expr.vector(o.get("direction"), 3, 0) : null;
				parametricRotation = o.has("rotation") ? Expr.scalar(o.get("rotation"), 0) : null;
			}
			case "particle_motion_collision" -> {
				collision = true;
				collisionEnabled = Expr.scalar(o.get("enabled"), 1);
				collisionDrag = num(o, "collision_drag", 0);
				restitution = num(o, "coefficient_of_restitution", 0);
				collisionRadius = Math.max(0.001, num(o, "collision_radius", 0.01));
				expireOnContact = bool(o, "expire_on_contact", false);
				JsonElement ev = o.get("events");
				List<JsonObject> list = new ArrayList<>();
				if (ev != null && ev.isJsonArray()) {
					ev.getAsJsonArray().forEach(x -> list.add(obj(x)));
				} else if (ev != null && ev.isJsonObject()) {
					list.add(ev.getAsJsonObject());
				}
				for (JsonObject x : list) {
					if (x.has("event")) {
						collisionEvents.add(new CollisionEvent(x.get("event").getAsString(), num(x, "min_speed", 2)));
					}
				}
			}
			case "particle_kill_plane" -> {
				if (v.isJsonArray() && v.getAsJsonArray().size() == 4) {
					killPlane = new double[4];
					for (int i = 0; i < 4; i++) {
						killPlane[i] = v.getAsJsonArray().get(i).getAsDouble();
					}
				}
			}
			case "particle_expire_if_in_blocks" -> expireInBlocks = blockSet(v, blocks);
			case "particle_expire_if_not_in_blocks" -> expireNotInBlocks = blockSet(v, blocks);
			case "particle_appearance_billboard" -> billboard(o);
			case "particle_appearance_tinting" -> tinting(o);
			case "particle_appearance_lighting" -> lighting = true;
			default -> {
				// components the pack does not use
			}
		}
	}

	private static Set<BlockState> blockSet(JsonElement v, Function<String, Set<BlockState>> blocks) {
		Set<BlockState> out = new java.util.HashSet<>();
		if (v.isJsonArray()) {
			v.getAsJsonArray().forEach(b -> {
				if (b.isJsonPrimitive()) {
					out.addAll(blocks.apply(b.getAsString()));
				}
			});
		}
		return out;
	}

	private void shape(Shape s, JsonObject o) {
		shape = s;
		shapeOffset = Expr.vector(o.get("offset"), 3, 0);
		shapeRadius = Expr.scalar(o.get("radius"), 1);
		halfDimensions = Expr.vector(o.get("half_dimensions"), 3, 0);
		if (o.has("plane_normal")) {
			JsonElement n = o.get("plane_normal");
			planeNormal = n.isJsonPrimitive() ? switch (n.getAsString()) {
				case "x" -> new Node[] {Molang.ONE, Molang.ZERO, Molang.ZERO};
				case "z" -> new Node[] {Molang.ZERO, Molang.ZERO, Molang.ONE};
				default -> new Node[] {Molang.ZERO, Molang.ONE, Molang.ZERO};
			} : Expr.vector(n, 3, 0);
		}
		surfaceOnly = bool(o, "surface_only", false);
		JsonElement d = o.get("direction");
		if (s == Shape.POINT || s == Shape.CUSTOM) {
			direction = Direction.VECTOR;
			directionVector = Expr.vector(d, 3, 0);
		} else if (d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isString()
			&& (d.getAsString().equals("inwards") || d.getAsString().equals("outwards"))) {
			direction = d.getAsString().equals("inwards") ? Direction.INWARDS : Direction.OUTWARDS;
		} else if (d != null) {
			direction = Direction.VECTOR;
			directionVector = Expr.vector(d, 3, 0);
		}
	}

	private void billboard(JsonObject o) {
		billboard = true;
		size = Expr.vector(o.get("size"), 2, 0.1);
		facing = switch (o.has("facing_camera_mode") ? o.get("facing_camera_mode").getAsString() : "rotate_xyz") {
			case "rotate_y" -> Facing.ROTATE_Y;
			case "lookat_xyz" -> Facing.LOOKAT_XYZ;
			case "lookat_y" -> Facing.LOOKAT_Y;
			case "lookat_direction" -> Facing.LOOKAT_DIRECTION;
			case "direction_x" -> Facing.DIRECTION_X;
			case "direction_y" -> Facing.DIRECTION_Y;
			case "direction_z" -> Facing.DIRECTION_Z;
			case "emitter_transform_xy" -> Facing.EMITTER_TRANSFORM_XY;
			case "emitter_transform_xz" -> Facing.EMITTER_TRANSFORM_XZ;
			case "emitter_transform_yz" -> Facing.EMITTER_TRANSFORM_YZ;
			default -> Facing.ROTATE_XYZ;
		};
		JsonObject dir = obj(o.get("direction"));
		if (dir.has("mode") && dir.get("mode").getAsString().equals("custom")) {
			directionFromVelocity = false;
			customDirection = Expr.vector(dir.get("custom_direction"), 3, 0);
		}
		minSpeed = num(dir, "min_speed_threshold", 0.01);
		JsonObject uvJson = obj(o.get("uv"));
		textureWidth = num(uvJson, "texture_width", 1);
		textureHeight = num(uvJson, "texture_height", 1);
		if (uvJson.has("flipbook")) {
			JsonObject f = uvJson.getAsJsonObject("flipbook");
			flipbook = new Flipbook(Expr.vector(f.get("base_UV"), 2, 0), constants(f.get("size_UV")), constants(f.get("step_UV")),
				Expr.scalar(f.get("frames_per_second"), 1), Expr.scalar(f.get("max_frame"), 1),
				bool(f, "stretch_to_lifetime", false), bool(f, "loop", false));
		} else {
			uv = Expr.vector(uvJson.get("uv"), 2, 0);
			uvSize = uvJson.has("uv_size") ? Expr.vector(uvJson.get("uv_size"), 2, 0)
				: new Node[] {Molang.constant(textureWidth), Molang.constant(textureHeight)};
		}
	}

	private static double[] constants(JsonElement e) {
		double[] out = new double[2];
		if (e != null && e.isJsonArray()) {
			for (int i = 0; i < 2 && i < e.getAsJsonArray().size(); i++) {
				out[i] = e.getAsJsonArray().get(i).getAsDouble();
			}
		}
		return out;
	}

	private void tinting(JsonObject o) {
		JsonElement color = o.get("color");
		if (color == null) {
			return;
		}
		if (color.isJsonObject()) {
			JsonObject g = color.getAsJsonObject();
			gradientInterpolant = Expr.scalar(g.get("interpolant"), 0);
			JsonElement stops = g.get("gradient");
			TreeMap<Double, Node[]> byKey = new TreeMap<>();
			if (stops != null && stops.isJsonArray()) {
				int n = stops.getAsJsonArray().size();
				for (int i = 0; i < n; i++) {
					byKey.put(n == 1 ? 0 : (double) i / (n - 1), color(stops.getAsJsonArray().get(i)));
				}
			} else if (stops != null && stops.isJsonObject()) {
				stops.getAsJsonObject().entrySet().forEach(e -> byKey.put(Double.parseDouble(e.getKey()), color(e.getValue())));
			}
			gradientKeys = byKey.keySet().stream().mapToDouble(Double::doubleValue).toArray();
			gradientColors = byKey.values().toArray(Node[][]::new);
			if (gradientKeys.length == 0) {
				gradientInterpolant = null;
			}
		} else {
			tint = color(color);
		}
	}

	/** {@code [r, g, b, a]} expressions, or {@code "#AARRGGBB"} / {@code "#RRGGBB"}. */
	static Node[] color(JsonElement e) {
		if (e.isJsonPrimitive() && e.getAsString().startsWith("#")) {
			String hex = e.getAsString().substring(1);
			long v = Long.parseLong(hex, 16);
			double a = hex.length() > 6 ? ((v >> 24) & 0xFF) / 255.0 : 1;
			return new Node[] {Molang.constant(((v >> 16) & 0xFF) / 255.0), Molang.constant(((v >> 8) & 0xFF) / 255.0),
				Molang.constant((v & 0xFF) / 255.0), Molang.constant(a)};
		}
		Node[] c = Expr.vector(e, 4, 1);
		if (e.isJsonArray() && e.getAsJsonArray().size() == 3) {
			c[3] = Molang.ONE;
		}
		return c;
	}

	/** Event names → compiled events, firing each that exists. */
	void fire(String[] names, EffectSource source, xyz.ivyhq.lanature.molang.Scope scope) {
		for (String name : names) {
			Event e = events.get(name);
			if (e != null) {
				e.fire(source, scope);
			}
		}
	}
}
