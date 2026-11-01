package xyz.ivyhq.lanature.client.particle;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SpellParticleOption;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * The variables Bedrock's engine hands an effect it starts, filled from the Java particle being replaced:
 * {@code v.direction} from the particle's velocity (the enchanting table's book direction, a fishing wake's heading)
 * and {@code v.color} from a coloured particle.
 */
final class TriggerVariables {
	private TriggerVariables() {
	}

	static void fill(ParticleOptions options, double xa, double ya, double za, Variables out) {
		Variables.Struct direction = new Variables.Struct();
		direction.set("x", xa);
		direction.set("y", ya);
		direction.set("z", za);
		out.set("direction", direction);
		Variables.Struct color = color(options);
		if (color != null) {
			out.set("color", color);
		}
	}

	/** A coloured particle's colour as {@code v.color}'s {@code r, g, b, a}; null for a particle without one. */
	static Variables.@Nullable Struct color(ParticleOptions options) {
		float[] rgba = switch (options) {
			case ColorParticleOption c -> new float[] {c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()};
			case SpellParticleOption c -> new float[] {c.getRed(), c.getGreen(), c.getBlue(), 1};
			default -> null;
		};
		if (rgba == null) {
			return null;
		}
		Variables.Struct color = new Variables.Struct();
		color.set("r", (double) rgba[0]);
		color.set("g", (double) rgba[1]);
		color.set("b", (double) rgba[2]);
		color.set("a", (double) rgba[3]);
		return color;
	}
}
