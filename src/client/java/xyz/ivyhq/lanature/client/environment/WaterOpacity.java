package xyz.ivyhq.lanature.client.environment;

import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import xyz.ivyhq.lanature.client.environment.BiomeLooks.BiomeLook;

/**
 * The pack's water surface transparency on Java's water: each biome's share of Java's opacity
 * ({@link BiomeLook#waterOpacity}), averaged over the nearby biomes as Java blends the water colour, scales the alpha
 * of the water's tint, which Java's terrain shader multiplies the texture's alpha (180 of 255) by. A share above 1
 * cannot raise it past the texture's own and draws as Java's: the pack's swamps (1.0 over vanilla's 0.65).
 */
public final class WaterOpacity {
	/** The share, 0 to 1, as a grey level: the level's tint cache averages colours channel by channel. */
	private static final ColorResolver RESOLVER = (biome, x, z) -> {
		BiomeLook look = BiomeLooks.of(biome);
		float share = look == null || look.waterOpacity() == null ? 1 : Math.clamp(look.waterOpacity(), 0, 1);
		int grey = Math.round(share * 255);
		return ARGB.color(255, grey, grey, grey);
	};

	private WaterOpacity() {
	}

	public static void register() {
		ColorResolverRegistry.register(RESOLVER);
	}

	/** The water tint at a position with the pack's opacity in its alpha. */
	public static int apply(int tint, BlockAndTintGetter level, BlockPos pos) {
		if (!BiomeLooks.active()) {
			return tint;
		}
		int share = ARGB.red(level.getBlockTint(pos, RESOLVER));
		return ARGB.color(ARGB.alpha(tint) * share / 255, tint);
	}
}
