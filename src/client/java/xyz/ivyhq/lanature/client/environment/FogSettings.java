package xyz.ivyhq.lanature.client.environment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * A Bedrock fog definition ({@code minecraft:fog_settings}): the distance fog for air, rain ({@code weather}) and
 * water (with how the water fog comes in, {@link Transition}), any of which may be missing. Bedrock reads each setting
 * from the top of its fog stack down, so a missing one falls through to the layer below ({@link #over}) and finally to
 * the engine's own, which here is Java's. The volumetric settings and the lava and powder snow fogs have no Java
 * equivalent the pack needs and are not read.
 */
public record FogSettings(@Nullable Distance air, @Nullable Distance weather, @Nullable Distance water) {
	public static final FogSettings NONE = new FogSettings(null, null, null);

	/**
	 * One distance fog. With {@code render} the distances are fractions of the render distance
	 * ({@code render_distance_type: render}), otherwise blocks. A water fog may carry a {@link Transition}.
	 */
	public record Distance(float start, float end, int color, boolean render, @Nullable Transition transition) {
		public float start(float renderBlocks) {
			return render ? start * renderBlocks : start;
		}

		public float end(float renderBlocks) {
			return render ? end * renderBlocks : end;
		}
	}

	/**
	 * How a fog comes in after the camera enters it ({@code transition_fog}): it moves from {@code init} (the
	 * {@code init_fog} distances; its colour is not read) to the fog's own by a progress that rises linearly to
	 * {@code midPercent} at {@code midSeconds}, then to 1 at {@code maxSeconds}, never below {@code minPercent}. Vanilla
	 * Bedrock's water fogs all use 0.25, 5 s at 0.6 and 30 s, from an end of 0.01 blocks: Java's own water vision ramp
	 * ({@code LocalPlayer.getWaterVision}, which scales the end by at least 0.25), so the curve's reading is taken from
	 * it (inferred).
	 */
	public record Transition(Distance init, float minPercent, float midSeconds, float midPercent, float maxSeconds) {
		public float progress(float seconds) {
			float p;
			if (seconds < midSeconds) {
				p = midPercent * seconds / midSeconds;
			} else if (seconds < maxSeconds) {
				p = midPercent + (1 - midPercent) * (seconds - midSeconds) / (maxSeconds - midSeconds);
			} else {
				p = 1;
			}
			return Math.clamp(p, minPercent, 1);
		}
	}

	/** This fog's settings, each missing one taken from {@code below}. */
	public FogSettings over(FogSettings below) {
		return new FogSettings(air != null ? air : below.air, weather != null ? weather : below.weather, water != null ? water : below.water);
	}

	/** The identifier and settings of a fog file. */
	static @Nullable Parsed parse(JsonObject file) {
		JsonObject root = file.getAsJsonObject("minecraft:fog_settings");
		if (root == null) {
			return null;
		}
		JsonObject distance = root.getAsJsonObject("distance");
		FogSettings settings = distance == null ? NONE
			: new FogSettings(distance(distance.getAsJsonObject("air")), distance(distance.getAsJsonObject("weather")), distance(distance.getAsJsonObject("water")));
		return new Parsed(root.getAsJsonObject("description").get("identifier").getAsString(), settings);
	}

	record Parsed(String id, FogSettings settings) {
	}

	private static @Nullable Distance distance(@Nullable JsonObject o) {
		if (o == null || !o.has("fog_start") || !o.has("fog_end") || !o.has("fog_color")) {
			return null;
		}
		boolean render = o.has("render_distance_type") && o.get("render_distance_type").getAsString().equals("render");
		return new Distance(o.get("fog_start").getAsFloat(), o.get("fog_end").getAsFloat(), color(o.get("fog_color")), render,
			transition(o.getAsJsonObject("transition_fog")));
	}

	private static @Nullable Transition transition(@Nullable JsonObject o) {
		Distance init = o == null ? null : distance(o.getAsJsonObject("init_fog"));
		if (init == null || !o.has("min_percent") || !o.has("mid_seconds") || !o.has("mid_percent") || !o.has("max_seconds")) {
			return null;
		}
		return new Transition(init, o.get("min_percent").getAsFloat(), o.get("mid_seconds").getAsFloat(), o.get("mid_percent").getAsFloat(),
			o.get("max_seconds").getAsFloat());
	}

	/** A Bedrock colour: {@code "#RRGGBB"} (or {@code "#AARRGGBB"}, alpha dropped) or {@code [r, g, b]} in 0 to 1. */
	static int color(JsonElement e) {
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			int rgb = 0;
			for (int i = 0; i < 3; i++) {
				rgb = rgb << 8 | Math.clamp(Math.round(a.get(i).getAsFloat() * 255), 0, 255);
			}
			return rgb;
		}
		String s = e.getAsString();
		return (int) (Long.parseLong(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF);
	}
}
