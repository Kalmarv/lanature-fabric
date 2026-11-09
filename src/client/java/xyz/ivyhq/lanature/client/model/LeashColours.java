package xyz.ivyhq.lanature.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * The pack's leash colours ({@code bedrock/lead_rope.json}, from its rope texture): Java draws a leash in segments
 * alternating between a light and a dark colour, which the pack's rope replaces. Unset, leashes keep Java's.
 */
public final class LeashColours {
	private static volatile @Nullable LeashColours current;

	private final float[] light;
	private final float[] dark;

	private LeashColours(float[] light, float[] dark) {
		this.light = light;
		this.dark = dark;
	}

	public static @Nullable LeashColours parse(@Nullable JsonObject json) {
		return json == null || !json.has("light") || !json.has("dark") ? null : new LeashColours(rgb(json.getAsJsonArray("light")), rgb(json.getAsJsonArray("dark")));
	}

	public static void set(@Nullable LeashColours colours) {
		current = colours;
	}

	public static @Nullable LeashColours get() {
		return current;
	}

	/** A segment's colour: Java's dark segments are the 0.7-shaded ones. */
	public float[] segment(boolean dark) {
		return dark ? this.dark : light;
	}

	private static float[] rgb(JsonArray a) {
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}
}
