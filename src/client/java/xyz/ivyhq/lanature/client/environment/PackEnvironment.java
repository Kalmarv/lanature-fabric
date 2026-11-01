package xyz.ivyhq.lanature.client.environment;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.GaussianSampler;
import net.minecraft.world.attribute.LerpFunction;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.client.environment.BiomeLooks.BiomeLook;
import xyz.ivyhq.lanature.client.environment.FogSettings.Distance;

/**
 * The pack's biome fogs and sky colours as environment attribute layers of the client level, placed after the
 * biome layer and before the time of day and weather, so Java darkens them at night and in rain as it does its own
 * biome colours. Like Bedrock's biome fog layer, the value at the camera is an average over the biomes around it
 * (with the kernel Java averages its own biome attributes with).
 *
 * <p>Rain is Bedrock's {@code weather} fog: its colour takes over from the air fog's with the rain level (here), in place
 * of Java's darkening of the fog colour in rain and thunder ({@link #weatherFogColor}), and its distances replace Java's
 * own rain fog (in the atmospheric fog environment, {@link #weather}). Bedrock's own default weather fog is a mid grey
 * that would be nearly black with Java's darkening on top, so the weather colour is taken to stand for it.
 *
 * <p>Water fog comes in after the camera goes under by the pack's {@code transition_fog} ({@link #waterFog}), in place
 * of Java's water vision ramp, which is vanilla Bedrock's.
 */
public final class PackEnvironment {
	private static volatile @Nullable Sample cached;

	private PackEnvironment() {
	}

	/** Adds the pack's layers; called while the client level builds its attribute system. */
	public static void addLayers(EnvironmentAttributeSystem.Builder builder, ClientLevel level) {
		layer(builder, level, EnvironmentAttributes.FOG_COLOR, (look, base) -> {
			Distance air = look.fog().air();
			Distance weather = look.fog().weather();
			Vector3fc color = air != null ? ARGB.vector3fFromRGB24(air.color()) : base;
			float rain = level.getRainLevel(1.0F);
			return weather != null && rain > 0 ? ARGB.srgbLerp(rain, color, ARGB.vector3fFromRGB24(weather.color())) : color;
		});
		layer(builder, level, EnvironmentAttributes.FOG_START_DISTANCE, (look, base) -> {
			Distance air = look.fog().air();
			return air != null ? air.start(renderBlocks()) : base;
		});
		layer(builder, level, EnvironmentAttributes.FOG_END_DISTANCE, (look, base) -> {
			Distance air = look.fog().air();
			return air != null ? Math.max(0, air.end(renderBlocks())) : base;
		});
		layer(builder, level, EnvironmentAttributes.WATER_FOG_COLOR, (look, base) -> {
			Distance water = look.fog().water();
			return water != null ? ARGB.vector3fFromRGB24(water.color()) : base;
		});
		layer(builder, level, EnvironmentAttributes.WATER_FOG_START_DISTANCE, (look, base) -> {
			Distance water = look.fog().water();
			return water != null ? water.start(renderBlocks()) : base;
		});
		layer(builder, level, EnvironmentAttributes.WATER_FOG_END_DISTANCE, (look, base) -> {
			Distance water = look.fog().water();
			return water != null ? Math.max(0, water.end(renderBlocks())) : base;
		});
		layer(builder, level, EnvironmentAttributes.SKY_COLOR, (look, base) -> look.sky() != null ? ARGB.vector3fFromRGB24(look.sky()) : base);
	}

	/**
	 * Java's weather layer for the fog colour, kept only where the biome average has no pack weather fog: the pack's
	 * weather colour, already in the value from the biome layer, stands for Java's darkening in rain. The layer is
	 * positional, which a dimension-wide query skips; only the camera reads the fog colour, always at a position.
	 */
	public static EnvironmentAttributeLayer.Positional<Vector3fc> weatherFogColor(ClientLevel level, EnvironmentAttributeLayer.TimeBased<Vector3fc> java) {
		return (base, pos, interpolator) -> {
			Vector3fc darkened = java.applyTimeBased(base, 0);
			if (!BiomeLooks.active()) {
				return darkened;
			}
			float covered;
			if (interpolator == null) {
				BiomeLook look = BiomeLooks.of(level.getBiomeManager().getNoiseBiomeAtPosition(pos.x, pos.y, pos.z).value());
				covered = look != null && look.fog().weather() != null ? 1 : 0;
			} else {
				covered = coverage(sample(level, pos));
			}
			return covered >= 1 ? base : ARGB.srgbLerp(covered, darkened, base);
		};
	}

	/** The share of a sample's weight whose biomes have a pack weather fog. */
	private static float coverage(Sample s) {
		double covered = 0;
		for (Reference2DoubleMap.Entry<Biome> e : Reference2DoubleMaps.fastIterable(s.weights())) {
			BiomeLook look = BiomeLooks.of(e.getKey());
			if (look != null && look.fog().weather() != null) {
				covered += e.getDoubleValue();
			}
		}
		return (float) (covered / s.total());
	}

	/**
	 * The pack's rain fog at the camera: how much of the biome average defines one ({@code coverage}, 0 to 1) and its
	 * average distances in blocks.
	 */
	public record Weather(float coverage, float start, float end) {
		static final Weather NONE = new Weather(0, 0, 0);
	}

	public static Weather weather(ClientLevel level, Vec3 camera) {
		if (!BiomeLooks.active()) {
			return Weather.NONE;
		}
		Sample s = sample(level, camera);
		float render = renderBlocks();
		double covered = 0;
		double start = 0;
		double end = 0;
		for (Reference2DoubleMap.Entry<Biome> e : Reference2DoubleMaps.fastIterable(s.weights())) {
			BiomeLook look = BiomeLooks.of(e.getKey());
			Distance w = look == null ? null : look.fog().weather();
			if (w != null) {
				covered += e.getDoubleValue();
				start += e.getDoubleValue() * w.start(render);
				end += e.getDoubleValue() * w.end(render);
			}
		}
		return covered <= 0 ? Weather.NONE : new Weather((float) (covered / s.total()), (float) (start / covered), (float) (end / covered));
	}

	/** The water fog's distances in blocks. */
	public record WaterFog(float start, float end) {
	}

	/**
	 * The water fog at the camera as it comes in, or null where no biome of the average has a pack transition (Java's
	 * own ramp then stands). Each biome's fog moves from its transition's {@code init_fog} to the averaged distances by
	 * its own progress after {@code seconds} under water; a biome without a pack transition keeps Java's (the end
	 * scaled by {@code javaProgress}, the start as it is). Bedrock's own blending of transitions between biomes is not
	 * documented; this weighs them as the distances are weighed.
	 *
	 * @param start the averaged water fog start, before any transition
	 * @param end the averaged water fog end, before any transition
	 */
	public static @Nullable WaterFog waterFog(ClientLevel level, Vec3 camera, float seconds, float javaProgress, float start, float end) {
		if (!BiomeLooks.active()) {
			return null;
		}
		Sample s = sample(level, camera);
		float render = renderBlocks();
		boolean any = false;
		double fogStart = 0;
		double fogEnd = 0;
		for (Reference2DoubleMap.Entry<Biome> e : Reference2DoubleMaps.fastIterable(s.weights())) {
			BiomeLook look = BiomeLooks.of(e.getKey());
			Distance water = look == null ? null : look.fog().water();
			FogSettings.Transition t = water == null ? null : water.transition();
			double w = e.getDoubleValue();
			if (t == null) {
				fogStart += w * start;
				fogEnd += w * end * javaProgress;
			} else {
				any = true;
				float p = t.progress(seconds);
				fogStart += w * Mth.lerp(p, t.init().start(render), start);
				fogEnd += w * Mth.lerp(p, t.init().end(render), end);
			}
		}
		return any ? new WaterFog((float) (fogStart / s.total()), (float) (fogEnd / s.total())) : null;
	}

	private static <V> void layer(EnvironmentAttributeSystem.Builder builder, ClientLevel level, EnvironmentAttribute<V> attribute,
		BiFunction<BiomeLook, V, V> value) {
		builder.addPositionalLayer(attribute, (base, pos, interpolator) -> {
			if (!BiomeLooks.active()) {
				return base;
			}
			if (interpolator == null) {
				// a single point, as Java samples its own biome layer without an interpolator
				BiomeLook look = BiomeLooks.of(level.getBiomeManager().getNoiseBiomeAtPosition(pos.x, pos.y, pos.z).value());
				return look == null ? base : value.apply(look, base);
			}
			return blend(attribute, base, sample(level, pos), value);
		});
	}

	/** The weighted average of each sampled biome's value, as Java's spatial interpolator averages biome attributes. */
	private static <V> V blend(EnvironmentAttribute<V> attribute, V base, Sample s, BiFunction<BiomeLook, V, V> value) {
		LerpFunction<V> lerp = attribute.type().spatialLerp();
		V result = null;
		double accumulated = 0;
		for (Reference2DoubleMap.Entry<Biome> e : Reference2DoubleMaps.fastIterable(s.weights())) {
			BiomeLook look = BiomeLooks.of(e.getKey());
			V v = look == null ? base : value.apply(look, base);
			accumulated += e.getDoubleValue();
			result = result == null ? v : lerp.apply((float) (e.getDoubleValue() / accumulated), result, v);
		}
		return result == null ? base : result;
	}

	/** The biome weights around a position, kept for the rest of the tick (the camera asks once per attribute). */
	private static Sample sample(ClientLevel level, Vec3 pos) {
		long tick = level.getGameTime();
		Sample s = cached;
		if (s != null && s.level() == level && s.tick() == tick && s.pos().equals(pos)) {
			return s;
		}
		Reference2DoubleOpenHashMap<Biome> weights = new Reference2DoubleOpenHashMap<>();
		double[] total = {0};
		GaussianSampler.sample(pos.scale(0.25), level.getBiomeManager()::getNoiseBiomeAtQuart, (weight, biome) -> {
			weights.addTo(biome.value(), weight);
			total[0] += weight;
		});
		s = new Sample(level, pos, tick, weights, total[0]);
		cached = s;
		return s;
	}

	/** Drops the kept sample (and the level it holds) when the level or the looks change. */
	static void clear() {
		cached = null;
	}

	private record Sample(ClientLevel level, Vec3 pos, long tick, Reference2DoubleMap<Biome> weights, double total) {
	}

	/** Bedrock's render distance fogs are fractions of the render distance in blocks. */
	private static float renderBlocks() {
		return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
	}
}
