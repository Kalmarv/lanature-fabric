package xyz.ivyhq.lanature.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Bedrock entity geometry: bones in a hierarchy, each with its pivot, rest rotation and faces.
 *
 * <p>Coordinates are kept as Blockbench keeps them, which is Bedrock's model space with X mirrored: a right-handed
 * space, in pixels, the model facing -Z. Bedrock writes a point {@code (x, y, z)} that is {@code (-x, y, z)} here, a
 * cube {@code origin} its far corner on X, and a rotation {@code (rx, ry, rz)} in degrees that is {@code (-rx, -ry, rz)}
 * here, applied Z·Y·X (X first). A cube's own rotation is baked into its faces, and so is its bone's
 * {@code bind_pose_rotation}: that turns the bone's own cubes about its pivot, under whatever animations do, and not its
 * children (the cat's second tail segment, a child of the first, has the same 90° bind pose and continues it). Faces carry their four corners as seen
 * from outside (top left, top right, bottom left, bottom right) and the texture coordinates of each, normalised by the
 * geometry's texture size.
 */
public record Geometry(String id, int textureWidth, int textureHeight, List<Bone> bones, Map<String, Integer> index) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/entities");

	/** A bone; {@code parent} indexes {@link #bones}, -1 for a root. Bones come after their parents. */
	public record Bone(String name, int parent, Vector3f pivot, Vector3f rotation, List<Face> faces, boolean neverRender, Map<String, Vector3f> locators) {
	}

	/** Corners {@code xyz} (four × three) in the order top left, top right, bottom left, bottom right; {@code uv} likewise. */
	public record Face(float[] xyz, float[] uv, Vector3f normal) {
	}

	/** The index of a bone by name (Bedrock matches bone names without regard to case), or -1. */
	public int bone(String name) {
		Integer i = index.get(name.toLowerCase(Locale.ROOT));
		return i == null ? -1 : i;
	}

	/** Every geometry the files define, by identifier; legacy {@code geometry.a:geometry.b} keys inherit b's bones. */
	public static Map<String, Geometry> load(List<JsonObject> files) {
		Map<String, JsonObject> modern = new LinkedHashMap<>();
		Map<String, JsonObject> legacy = new LinkedHashMap<>();
		Map<String, String> legacyParents = new HashMap<>();
		for (JsonObject f : files) {
			if (f.has("minecraft:geometry") && f.get("minecraft:geometry").isJsonArray()) {
				for (JsonElement g : f.getAsJsonArray("minecraft:geometry")) {
					JsonObject o = g.getAsJsonObject();
					modern.put(o.getAsJsonObject("description").get("identifier").getAsString(), o);
				}
			}
			for (Map.Entry<String, JsonElement> e : f.entrySet()) {
				if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
					String[] parts = e.getKey().split(":", 2);
					legacy.put(parts[0], e.getValue().getAsJsonObject());
					if (parts.length == 2) {
						legacyParents.put(parts[0], parts[1]);
					} else {
						legacyParents.remove(parts[0]);
					}
				}
			}
		}
		Map<String, Geometry> out = new HashMap<>();
		modern.forEach((id, o) -> guarded(id, () -> {
			JsonObject d = o.getAsJsonObject("description");
			out.put(id, build(id, i(d, "texture_width", 64), i(d, "texture_height", 64), bones(o)));
		}));
		for (String id : legacy.keySet()) {
			guarded(id, () -> {
				// the inheritance chain, root first: a child's bones replace the parent's of the same name
				List<JsonObject> chain = new ArrayList<>();
				String at = id;
				while (at != null && legacy.containsKey(at) && chain.size() < 16) {
					chain.addFirst(legacy.get(at));
					at = legacyParents.get(at);
				}
				Map<String, JsonObject> bones = new LinkedHashMap<>();
				int width = 64;
				int height = 64;
				for (JsonObject g : chain) {
					width = i(g, "texturewidth", width);
					height = i(g, "textureheight", height);
					for (JsonObject b : bones(g)) {
						bones.put(b.get("name").getAsString().toLowerCase(Locale.ROOT), b);
					}
				}
				out.put(id, build(id, width, height, new ArrayList<>(bones.values())));
			});
		}
		return Map.copyOf(out);
	}

	private static void guarded(String id, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			LOGGER.warn("Skipping geometry {}, which does not load", id, e);
		}
	}

	private static List<JsonObject> bones(JsonObject g) {
		List<JsonObject> out = new ArrayList<>();
		if (g.has("bones") && g.get("bones").isJsonArray()) {
			g.getAsJsonArray("bones").forEach(b -> {
				if (b.isJsonObject() && b.getAsJsonObject().has("name")) {
					out.add(b.getAsJsonObject());
				}
			});
		}
		return out;
	}

	private static Geometry build(String id, int width, int height, List<JsonObject> boneJson) {
		Map<String, JsonObject> byName = new LinkedHashMap<>();
		boneJson.forEach(b -> byName.put(b.get("name").getAsString().toLowerCase(Locale.ROOT), b));
		List<Bone> bones = new ArrayList<>();
		Map<String, Integer> index = new HashMap<>();
		// parents first; a bone whose parent is missing (or a cycle) becomes a root
		for (String name : byName.keySet()) {
			add(name, byName, bones, index, width, height, new java.util.HashSet<>());
		}
		return new Geometry(id, width, height, List.copyOf(bones), Map.copyOf(index));
	}

	private static int add(String name, Map<String, JsonObject> byName, List<Bone> bones, Map<String, Integer> index, int width, int height,
		java.util.Set<String> visiting) {
		Integer done = index.get(name);
		if (done != null) {
			return done;
		}
		JsonObject b = byName.get(name);
		int parent = -1;
		if (b.has("parent") && visiting.add(name)) {
			String p = b.get("parent").getAsString().toLowerCase(Locale.ROOT);
			if (byName.containsKey(p)) {
				parent = add(p, byName, bones, index, width, height, visiting);
			}
		}
		Vector3f pivot = point(b.get("pivot"));
		Vector3f rotation = rotation(b.get("rotation"));
		boolean mirror = b.has("mirror") && b.get("mirror").getAsBoolean();
		float inflate = b.has("inflate") ? b.get("inflate").getAsFloat() : 0;
		List<Face> faces = new ArrayList<>();
		if (b.has("cubes") && b.get("cubes").isJsonArray()) {
			for (JsonElement c : b.getAsJsonArray("cubes")) {
				if (c.isJsonObject()) {
					cube(c.getAsJsonObject(), mirror, inflate, width, height, faces);
				}
			}
		}
		Vector3f bindPose = rotation(b.get("bind_pose_rotation"));
		if (bindPose.lengthSquared() != 0) {
			Matrix3f rot = rotationMatrix(bindPose, new Matrix3f());
			faces.forEach(f -> turn(f.xyz(), f.normal(), pivot, rot));
		}
		Map<String, Vector3f> locators = new HashMap<>();
		if (b.has("locators") && b.get("locators").isJsonObject()) {
			b.getAsJsonObject("locators").entrySet().forEach(e -> {
				JsonElement v = e.getValue();
				JsonElement at = v.isJsonObject() ? v.getAsJsonObject().get("offset") : v;
				if (at != null && at.isJsonArray()) {
					locators.put(e.getKey(), point(at));
				}
			});
		}
		boolean never = b.has("neverRender") && b.get("neverRender").getAsBoolean();
		bones.add(new Bone(b.get("name").getAsString(), parent, pivot, rotation, List.copyOf(faces), never, Map.copyOf(locators)));
		index.put(name, bones.size() - 1);
		return bones.size() - 1;
	}

	/** A Bedrock point as a point here (X mirrored). */
	private static Vector3f point(@Nullable JsonElement e) {
		if (e == null || !e.isJsonArray()) {
			return new Vector3f();
		}
		JsonArray a = e.getAsJsonArray();
		return new Vector3f(-a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	/** A Bedrock rotation as a rotation here: X and Y turn the other way. */
	static Vector3f rotation(@Nullable JsonElement e) {
		if (e == null || !e.isJsonArray()) {
			return new Vector3f();
		}
		JsonArray a = e.getAsJsonArray();
		return new Vector3f(-a.get(0).getAsFloat(), -a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	/** Z·Y·X rotation by degrees, as Blockbench applies Bedrock rotations. */
	static Matrix3f rotationMatrix(Vector3f degrees, Matrix3f out) {
		return out.rotationZ((float) Math.toRadians(degrees.z)).rotateY((float) Math.toRadians(degrees.y)).rotateX((float) Math.toRadians(degrees.x));
	}

	private enum Side {
		EAST, WEST, UP, DOWN, SOUTH, NORTH;

		final String key = name().toLowerCase(Locale.ROOT);
	}

	private static void cube(JsonObject c, boolean boneMirror, float boneInflate, int width, int height, List<Face> out) {
		JsonArray origin = c.getAsJsonArray("origin");
		JsonArray sizeJson = c.getAsJsonArray("size");
		if (origin == null || sizeJson == null) {
			return;
		}
		float sx = sizeJson.get(0).getAsFloat();
		float sy = sizeJson.get(1).getAsFloat();
		float sz = sizeJson.get(2).getAsFloat();
		float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : boneInflate;
		boolean mirror = c.has("mirror") ? c.get("mirror").getAsBoolean() : boneMirror;
		float x0 = -(origin.get(0).getAsFloat() + sx) - inflate;
		float y0 = origin.get(1).getAsFloat() - inflate;
		float z0 = origin.get(2).getAsFloat() - inflate;
		float x1 = x0 + sx + 2 * inflate;
		float y1 = y0 + sy + 2 * inflate;
		float z1 = z0 + sz + 2 * inflate;
		Map<Side, float[]> rects = new HashMap<>();
		Map<Side, Integer> uvRotation = new HashMap<>();
		JsonElement uv = c.get("uv");
		if (uv != null && uv.isJsonArray()) {
			boxUv(uv.getAsJsonArray().get(0).getAsFloat(), uv.getAsJsonArray().get(1).getAsFloat(), (float) Math.floor(sx), (float) Math.floor(sy),
				(float) Math.floor(sz), mirror, rects);
		} else if (uv != null && uv.isJsonObject()) {
			JsonObject faces = uv.getAsJsonObject();
			for (Side side : Side.values()) {
				if (!faces.has(side.key) || !faces.get(side.key).isJsonObject()) {
					continue;
				}
				JsonObject f = faces.getAsJsonObject(side.key);
				JsonArray at = f.getAsJsonArray("uv");
				if (at == null) {
					continue;
				}
				float u = at.get(0).getAsFloat();
				float v = at.get(1).getAsFloat();
				float w;
				float h;
				if (f.has("uv_size")) {
					w = f.getAsJsonArray("uv_size").get(0).getAsFloat();
					h = f.getAsJsonArray("uv_size").get(1).getAsFloat();
				} else {
					float[] dims = switch (side) {
						case EAST, WEST -> new float[] {sz, sy};
						case UP, DOWN -> new float[] {sx, sz};
						default -> new float[] {sx, sy};
					};
					w = dims[0];
					h = dims[1];
				}
				// Bedrock's up and down faces run the other way in both directions
				rects.put(side, side == Side.UP || side == Side.DOWN ? new float[] {u + w, v + h, u, v} : new float[] {u, v, u + w, v + h});
				if (f.has("uv_rotation")) {
					uvRotation.put(side, f.get("uv_rotation").getAsInt());
				}
			}
		} else {
			return;
		}
		Vector3f pivot = c.has("pivot") ? point(c.get("pivot")) : null;
		Matrix3f rot = c.has("rotation") ? rotationMatrix(rotation(c.get("rotation")), new Matrix3f()) : null;
		for (Map.Entry<Side, float[]> e : rects.entrySet()) {
			float[] xyz = corners(e.getKey(), x0, y0, z0, x1, y1, z1);
			if (xyz == null) {
				continue;
			}
			Vector3f normal = switch (e.getKey()) {
				case EAST -> new Vector3f(1, 0, 0);
				case WEST -> new Vector3f(-1, 0, 0);
				case UP -> new Vector3f(0, 1, 0);
				case DOWN -> new Vector3f(0, -1, 0);
				case SOUTH -> new Vector3f(0, 0, 1);
				case NORTH -> new Vector3f(0, 0, -1);
			};
			if (rot != null) {
				turn(xyz, normal, pivot == null ? new Vector3f() : pivot, rot);
			}
			float[] r = e.getValue();
			float u0 = r[0] / width;
			float v0 = r[1] / height;
			float u1 = r[2] / width;
			float v1 = r[3] / height;
			// top left, top right, bottom left, bottom right
			float[][] corner = {{u0, v0}, {u1, v0}, {u0, v1}, {u1, v1}};
			for (int turn = uvRotation.getOrDefault(e.getKey(), 0); turn > 0; turn -= 90) {
				float[] a = corner[0];
				corner[0] = corner[2];
				corner[2] = corner[3];
				corner[3] = corner[1];
				corner[1] = a;
			}
			float[] uvs = new float[8];
			for (int k = 0; k < 4; k++) {
				uvs[k * 2] = corner[k][0];
				uvs[k * 2 + 1] = corner[k][1];
			}
			out.add(new Face(xyz, uvs, normal));
		}
	}

	/** Turns a face's corners about {@code pivot}, and its normal, in place. */
	private static void turn(float[] xyz, Vector3f normal, Vector3f pivot, Matrix3f rot) {
		Vector3f v = new Vector3f();
		for (int k = 0; k < 4; k++) {
			v.set(xyz[k * 3], xyz[k * 3 + 1], xyz[k * 3 + 2]).sub(pivot).mul(rot).add(pivot);
			xyz[k * 3] = v.x;
			xyz[k * 3 + 1] = v.y;
			xyz[k * 3 + 2] = v.z;
		}
		normal.mul(rot);
	}

	/**
	 * Minecraft's box layout for a cube {@code w × h × d} at {@code (u, v)}: [x0, y0, x1, y1] per face, in pixels.
	 * Mirroring flips every face across and swaps east and west.
	 */
	private static void boxUv(float u, float v, float w, float h, float d, boolean mirror, Map<Side, float[]> out) {
		float[][] at = {
			{0, d, d, h}, // east: from (x, y), size (x, y)
			{d + w, d, d, h}, // west
			{d + w, d, -w, -d}, // up
			{d + 2 * w, 0, -w, d}, // down
			{2 * d + w, d, w, h}, // south
			{d, d, w, h}, // north
		};
		if (mirror) {
			for (float[] f : at) {
				f[0] += f[2];
				f[2] = -f[2];
			}
			float[] east = at[0];
			at[0] = at[1];
			at[1] = east;
		}
		Side[] sides = Side.values();
		for (int i = 0; i < sides.length; i++) {
			float[] f = at[i];
			out.put(sides[i], new float[] {u + f[0], v + f[1], u + f[0] + f[2], v + f[1] + f[3]});
		}
	}

	/** A face's corners (top left, top right, bottom left, bottom right as seen from outside), or null if it has no area. */
	private static float @Nullable [] corners(Side side, float x0, float y0, float z0, float x1, float y1, float z1) {
		float[][] c = switch (side) {
			case EAST -> new float[][] {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z1}, {x1, y0, z0}};
			case WEST -> new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z0}, {x0, y0, z1}};
			case UP -> new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {x1, y1, z1}};
			case DOWN -> new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x0, y0, z0}, {x1, y0, z0}};
			case SOUTH -> new float[][] {{x0, y1, z1}, {x1, y1, z1}, {x0, y0, z1}, {x1, y0, z1}};
			case NORTH -> new float[][] {{x1, y1, z0}, {x0, y1, z0}, {x1, y0, z0}, {x0, y0, z0}};
		};
		boolean flat = switch (side) {
			case EAST, WEST -> y1 == y0 || z1 == z0;
			case UP, DOWN -> x1 == x0 || z1 == z0;
			default -> x1 == x0 || y1 == y0;
		};
		if (flat) {
			return null;
		}
		float[] out = new float[12];
		for (int k = 0; k < 4; k++) {
			System.arraycopy(c[k], 0, out, k * 3, 3);
		}
		return out;
	}

	private static int i(JsonObject o, String key, int fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
	}
}
