package xyz.ivyhq.lanature.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.client.particle.Expr;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;

/**
 * The bone channels of a Bedrock animation: per bone, rotation (degrees), position (pixels) and scale, each a constant
 * or keyframes. Values are evaluated as Bedrock writes them; {@link Pose} turns them into the geometry's axes.
 *
 * <p>Keyframes hold a value, or a {@code pre} and {@code post} value (the value arriving at and leaving the key), and a
 * {@code lerp_mode}: {@code linear} (the default), {@code step} (hold until the next key) or {@code catmullrom} (a
 * Catmull-Rom spline through the neighbouring keys). Before the first key a channel holds that key's {@code pre}, after
 * the last its {@code post}.
 */
public record BoneAnimation(List<Track> tracks, @Nullable Node animTimeUpdate, @Nullable Node blendWeight, boolean overridePrevious) {
	public static final BoneAnimation NONE = new BoneAnimation(List.of(), null, null, false);

	public enum Kind { ROTATION, POSITION, SCALE }

	public enum Lerp { LINEAR, STEP, CATMULLROM }

	/** One bone's channels; a null channel is not animated. */
	public record Track(String bone, @Nullable Channel rotation, @Nullable Channel position, @Nullable Channel scale) {
	}

	/** A key's three components arriving ({@code pre}) and leaving ({@code post}). */
	public record Key(double time, Node[] pre, Node[] post, Lerp lerp) {
	}

	/** Keys in time order; a constant channel is one key. */
	public record Channel(Key[] keys) {
		/**
		 * The channel's value at {@code time} into {@code out}. Each component is evaluated with {@code this} as that
		 * component's current value ({@code current}).
		 */
		public void evaluate(double time, Scope s, float[] current, float[] out) {
			Key[] k = keys;
			if (k.length == 1 || time <= k[0].time) {
				eval(k[0].pre, s, current, out);
				return;
			}
			if (time >= k[k.length - 1].time) {
				eval(k[k.length - 1].post, s, current, out);
				return;
			}
			int i = 0;
			while (time >= k[i + 1].time) {
				i++;
			}
			Key a = k[i];
			Key b = k[i + 1];
			if (a.lerp == Lerp.STEP) {
				eval(a.post, s, current, out);
				return;
			}
			float t = (float) ((time - a.time) / (b.time - a.time));
			float[] va = new float[3];
			float[] vb = new float[3];
			eval(a.post, s, current, va);
			eval(b.pre, s, current, vb);
			if (a.lerp == Lerp.CATMULLROM || b.lerp == Lerp.CATMULLROM) {
				float[] v0 = new float[3];
				float[] v3 = new float[3];
				if (i > 0) {
					eval(k[i - 1].post, s, current, v0);
				} else {
					System.arraycopy(va, 0, v0, 0, 3);
				}
				if (i + 2 < k.length) {
					eval(k[i + 2].pre, s, current, v3);
				} else {
					System.arraycopy(vb, 0, v3, 0, 3);
				}
				for (int c = 0; c < 3; c++) {
					out[c] = catmullRom(v0[c], va[c], vb[c], v3[c], t);
				}
				return;
			}
			for (int c = 0; c < 3; c++) {
				out[c] = va[c] + (vb[c] - va[c]) * t;
			}
		}

		private static void eval(Node[] v, Scope s, float[] current, float[] out) {
			for (int c = 0; c < 3; c++) {
				s.thisValue = current[c];
				out[c] = (float) Expr.eval(v[c], s);
			}
		}

		private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
			float t2 = t * t;
			float t3 = t2 * t;
			return 0.5F * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
		}
	}

	/** The bone channels of an animation's JSON. */
	public static BoneAnimation parse(JsonObject animation) {
		List<Track> tracks = new ArrayList<>();
		if (animation.has("bones") && animation.get("bones").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : animation.getAsJsonObject("bones").entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				JsonObject bone = e.getValue().getAsJsonObject();
				Channel rotation = channel(bone.get("rotation"), 0);
				Channel position = channel(bone.get("position"), 0);
				Channel scale = channel(bone.get("scale"), 1);
				if (rotation != null || position != null || scale != null) {
					tracks.add(new Track(e.getKey(), rotation, position, scale));
				}
			}
		}
		Node update = animation.has("anim_time_update") ? Expr.scalar(animation.get("anim_time_update"), 0) : null;
		Node weight = animation.has("blend_weight") ? Expr.scalar(animation.get("blend_weight"), 1) : null;
		boolean override = animation.has("override_previous_animation") && animation.get("override_previous_animation").getAsBoolean();
		if (tracks.isEmpty() && update == null && weight == null) {
			return NONE;
		}
		return new BoneAnimation(List.copyOf(tracks), update, weight, override);
	}

	private static @Nullable Channel channel(@Nullable JsonElement e, double fallback) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonObject()) {
			TreeMap<Double, Key> keys = new TreeMap<>();
			for (Map.Entry<String, JsonElement> k : e.getAsJsonObject().entrySet()) {
				double time;
				try {
					time = Double.parseDouble(k.getKey());
				} catch (NumberFormatException ex) {
					continue;
				}
				keys.put(time, key(time, k.getValue(), fallback));
			}
			return keys.isEmpty() ? null : new Channel(keys.values().toArray(Key[]::new));
		}
		Node[] v = vector(e, fallback);
		return new Channel(new Key[] {new Key(0, v, v, Lerp.LINEAR)});
	}

	private static Key key(double time, JsonElement v, double fallback) {
		if (v.isJsonObject()) {
			JsonObject o = v.getAsJsonObject();
			Node[] pre = o.has("pre") ? vector(o.get("pre"), fallback) : null;
			Node[] post = o.has("post") ? vector(o.get("post"), fallback) : null;
			Lerp lerp = switch (o.has("lerp_mode") ? o.get("lerp_mode").getAsString() : "linear") {
				case "step" -> Lerp.STEP;
				case "catmullrom" -> Lerp.CATMULLROM;
				default -> Lerp.LINEAR;
			};
			if (pre == null && post == null) {
				pre = post = vector(null, fallback);
			}
			return new Key(time, pre == null ? post : pre, post == null ? pre : post, lerp);
		}
		Node[] value = vector(v, fallback);
		return new Key(time, value, value, Lerp.LINEAR);
	}

	/** Three components; a single value stands for all three. */
	private static Node[] vector(@Nullable JsonElement e, double fallback) {
		return Expr.vector(e, 3, fallback);
	}
}
