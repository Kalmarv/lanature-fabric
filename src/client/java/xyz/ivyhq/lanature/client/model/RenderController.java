package xyz.ivyhq.lanature.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.particle.Expr;
import xyz.ivyhq.lanature.molang.Molang;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;

/**
 * A Bedrock render controller: which geometry, texture and per-bone materials an entity draws with, and which bones
 * show. Its fields are Molang over the entity's {@code geometry.}, {@code texture.} and {@code material.} names and the
 * controller's own {@code array.} lists (indexed as Bedrock does: negative indices clamp to 0, larger ones wrap).
 * Material and visibility rules match bone names with {@code *} wildcards, without regard to case; later rules
 * override earlier ones. Only the first texture is drawn (later ones are Bedrock's enchantment glint and colour-mask
 * layers). {@code overlay_color} ({@code r}, {@code g}, {@code b}, {@code a}; {@code this} is 0, Bedrock's default
 * of none) is mixed over the texture by materials with {@code USE_OVERLAY}, by its alpha; {@code color} (the same
 * channels, {@code this} being the engine's colour) is the change colour of materials with {@code USE_COLOR_MASK},
 * which without it is the engine's colour (a collar's or wool's dye, else white), and
 * {@code uv_anim} ({@code offset} and {@code scale}, each u and v) moves the texture coordinates of materials with
 * {@code USE_UV_ANIM} (see {@link EntityMaterial}).
 */
public record RenderController(String id, Map<String, List<Node>> arrays, Node geometry, @Nullable Node texture, List<Rule> materials,
	List<Rule> partVisibility, boolean ignoreLighting, @Nullable Node[] overlayColor, @Nullable Node[] color, @Nullable Node[] uvAnim) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/entities");

	/** A bone pattern and the expression it gets. */
	public record Rule(Pattern pattern, Node value, Map<Geometry, int[]> matches) {
		/** The indices of the bones the pattern matches in a geometry, cached per geometry. */
		int[] bones(Geometry g) {
			synchronized (matches) {
				return matches.computeIfAbsent(g, k -> {
					List<Integer> out = new ArrayList<>();
					for (int i = 0; i < k.bones().size(); i++) {
						if (pattern.matcher(k.bones().get(i).name()).matches()) {
							out.add(i);
						}
					}
					return out.stream().mapToInt(Integer::intValue).toArray();
				});
			}
		}
	}

	/**
	 * What a controller draws this frame: the geometry, its texture, each bone's material (null: hidden), lighting, the
	 * overlay colour as {@code 0xAABBGGRR} (0 for none; see {@link EntityMaterial#overlayCoords}), the change colour as
	 * ARGB, and the texture coordinates' offset and scale (u, v, u, v; null for none).
	 */
	public record Draw(Geometry geometry, Identifier texture, @Nullable EntityMaterial[] boneMaterials, boolean ignoreLighting, int overlayColor,
		int changeColor, float @Nullable [] uvAnim) {
		/** A vertex's texture coordinate {@code uv} (u when {@code axis} is 0, else v) drawn with a material. */
		public float uv(EntityMaterial material, int axis, float uv) {
			return material.uvAnim() && uvAnim != null ? uvAnim[axis] + uv * uvAnim[2 + axis] : uv;
		}
	}

	/**
	 * Evaluates the controller with the entity's scope, whose resolver gives the entity's geometries, textures and
	 * materials, and the engine's colour for the entity (ARGB); null when it names no geometry or texture that exists.
	 */
	public @Nullable Draw evaluate(Scope s, EntityMaterial fallbackMaterial, int engineColor) {
		Scope.ResourceResolver entity = s.resources;
		Map<String, List<Object>> resolved = new HashMap<>();
		s.resources = (kind, name) -> {
			if (!kind.equals("array")) {
				return entity == null ? null : entity.resolve(kind, name);
			}
			return resolved.computeIfAbsent(name, n -> {
				List<Node> elements = arrays.get(n);
				if (elements == null) {
					return List.of();
				}
				List<Object> out = new ArrayList<>(elements.size());
				// array elements name the entity's resources
				Scope.ResourceResolver self = s.resources;
				s.resources = entity;
				try {
					for (Node e : elements) {
						out.add(Molang.value(e, s));
					}
				} finally {
					s.resources = self;
				}
				return out;
			});
		};
		try {
			if (!(Molang.value(geometry, s) instanceof Geometry g)) {
				return null;
			}
			Object tex = texture == null ? null : Molang.value(texture, s);
			if (!(tex instanceof Identifier t)) {
				return null;
			}
			EntityMaterial[] boneMaterials = new EntityMaterial[g.bones().size()];
			java.util.Arrays.fill(boneMaterials, fallbackMaterial);
			for (Rule r : materials) {
				if (Molang.value(r.value(), s) instanceof EntityMaterial m) {
					for (int b : r.bones(g)) {
						boneMaterials[b] = m;
					}
				}
			}
			// each rule sets the visibility of the bones it matches, later rules over earlier ones ({"*": false} then
			// {"light": true} shows only the light)
			boolean[] visible = new boolean[boneMaterials.length];
			java.util.Arrays.fill(visible, true);
			for (Rule r : partVisibility) {
				boolean v = Molang.eval(r.value(), s) != 0;
				for (int b : r.bones(g)) {
					visible[b] = v;
				}
			}
			for (int b = 0; b < boneMaterials.length; b++) {
				if (!visible[b] || g.bones().get(b).neverRender()) {
					boneMaterials[b] = null;
				}
			}
			int overlay = 0;
			if (overlayColor != null) {
				float[] c = channels(overlayColor, new float[4], s);
				overlay = pack(c[3], c[2], c[1], c[0]); // 0xAABBGGRR
			}
			int change = engineColor;
			if (color != null) {
				float[] engine = {ARGB.redFloat(engineColor), ARGB.greenFloat(engineColor), ARGB.blueFloat(engineColor), ARGB.alphaFloat(engineColor)};
				float[] c = channels(color, engine, s);
				change = pack(c[3], c[0], c[1], c[2]);
			}
			return new Draw(g, t, boneMaterials, ignoreLighting, overlay, change, uvAnim == null ? null : channels(uvAnim, new float[4], s));
		} finally {
			s.resources = entity;
		}
	}

	/** Four channels from 0 to 1 as bytes, the first highest. */
	private static int pack(float... channels) {
		int out = 0;
		for (float c : channels) {
			out = out << 8 | Math.clamp(Math.round(c * 255), 0, 255);
		}
		return out;
	}

	/** Evaluates a field's expressions, {@code this} in each being that channel's default. */
	private static float[] channels(Node[] nodes, float[] defaults, Scope s) {
		double before = s.thisValue;
		try {
			float[] out = new float[nodes.length];
			for (int i = 0; i < nodes.length; i++) {
				s.thisValue = defaults[i];
				out[i] = (float) Molang.eval(nodes[i], s);
			}
			return out;
		} finally {
			s.thisValue = before;
		}
	}

	/** Every render controller in the files, later files replacing same-named controllers of earlier ones. */
	public static Map<String, RenderController> load(List<JsonObject> files) {
		Map<String, RenderController> out = new HashMap<>();
		for (JsonObject f : files) {
			if (!f.has("render_controllers") || !f.get("render_controllers").isJsonObject()) {
				continue;
			}
			for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("render_controllers").entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				try {
					out.put(e.getKey(), parse(e.getKey(), e.getValue().getAsJsonObject()));
				} catch (RuntimeException ex) {
					LOGGER.warn("Skipping render controller {}, which does not load", e.getKey(), ex);
				}
			}
		}
		return Map.copyOf(out);
	}

	private static RenderController parse(String id, JsonObject c) {
		Map<String, List<Node>> arrays = new HashMap<>();
		if (c.has("arrays") && c.get("arrays").isJsonObject()) {
			// grouped by kind (textures, geometries, materials); the names are what expressions use
			for (Map.Entry<String, JsonElement> kind : c.getAsJsonObject("arrays").entrySet()) {
				if (!kind.getValue().isJsonObject()) {
					continue;
				}
				for (Map.Entry<String, JsonElement> a : kind.getValue().getAsJsonObject().entrySet()) {
					List<Node> elements = new ArrayList<>();
					if (a.getValue().isJsonArray()) {
						a.getValue().getAsJsonArray().forEach(x -> elements.add(Expr.scalar(x, 0)));
					}
					arrays.put(a.getKey().toLowerCase(Locale.ROOT).replaceFirst("^array\\.", ""), List.copyOf(elements));
				}
			}
		}
		Node geometry = Expr.scalar(c.get("geometry"), 0);
		Node texture = c.has("textures") && c.get("textures").isJsonArray() && !c.getAsJsonArray("textures").isEmpty()
			? Expr.scalar(c.getAsJsonArray("textures").get(0), 0)
			: null;
		boolean ignoreLighting = c.has("ignore_lighting") && c.get("ignore_lighting").getAsBoolean();
		Node[] uvAnim = null;
		if (c.has("uv_anim") && c.get("uv_anim").isJsonObject()) {
			JsonObject o = c.getAsJsonObject("uv_anim");
			Node[] offset = pair(o.get("offset"), 0);
			Node[] scale = pair(o.get("scale"), 1);
			uvAnim = new Node[] {offset[0], offset[1], scale[0], scale[1]};
		}
		return new RenderController(id, Map.copyOf(arrays), geometry, texture, rules(c.get("materials")), rules(c.get("part_visibility")), ignoreLighting,
			rgba(c.get("overlay_color"), 0), rgba(c.get("color"), 1), uvAnim);
	}

	/** A colour field's {@code r}, {@code g}, {@code b} and {@code a} (missing ones being {@code fallback}); null if absent. */
	private static Node @Nullable [] rgba(@Nullable JsonElement e, double fallback) {
		if (e == null || !e.isJsonObject()) {
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		String[] channels = {"r", "g", "b", "a"};
		Node[] out = new Node[4];
		for (int i = 0; i < 4; i++) {
			out[i] = Expr.scalar(o.get(channels[i]), fallback);
		}
		return out;
	}

	/** A two-element array of expressions (u and v), missing ones being {@code fallback}. */
	private static Node[] pair(@Nullable JsonElement e, double fallback) {
		Node[] out = new Node[2];
		for (int i = 0; i < 2; i++) {
			JsonElement x = e != null && e.isJsonArray() && e.getAsJsonArray().size() > i ? e.getAsJsonArray().get(i) : null;
			out[i] = Expr.scalar(x, fallback);
		}
		return out;
	}

	private static List<Rule> rules(@Nullable JsonElement e) {
		List<Rule> out = new ArrayList<>();
		if (e == null || !e.isJsonArray()) {
			return out;
		}
		for (JsonElement x : e.getAsJsonArray()) {
			if (x.isJsonObject()) {
				x.getAsJsonObject().entrySet().forEach(r -> out.add(new Rule(glob(r.getKey()), Expr.scalar(r.getValue(), 1), new IdentityHashMap<>())));
			}
		}
		return List.copyOf(out);
	}

	private static Pattern glob(String pattern) {
		String[] parts = pattern.split("\\*", -1);
		for (int i = 0; i < parts.length; i++) {
			parts[i] = Pattern.quote(parts[i]);
		}
		return Pattern.compile(String.join(".*", parts), Pattern.CASE_INSENSITIVE);
	}
}
