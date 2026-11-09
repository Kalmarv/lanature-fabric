package xyz.ivyhq.lanature.client.environment;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.material.Fluids;
import xyz.ivyhq.lanature.client.environment.BiomeLooks.BiomeLook;

/**
 * The pack's water surface transparency on Java's water. Water is drawn from copies of Java's water sprites brought to
 * full opacity ({@link WaterSprites}), still in the translucent layer, and the alpha of its tint gives the opacity:
 * Java's (the textures' top alpha, 180 of 255) times each biome's share of it ({@link BiomeLook#waterOpacity}),
 * averaged over the nearby biomes as Java blends the water colour, up to fully opaque. A share above 1 (the pack's
 * swamps, 1.0 over vanilla's 0.65) makes the water more opaque than Java's.
 */
public final class WaterOpacity {
	/** The largest share the tint cache holds: the pack's most opaque water (1.0) over vanilla's least (0.55) is 1.82. */
	private static final float SHARE_MAX = 2;
	/** The share, 0 to {@link #SHARE_MAX}, as a grey level: the level's tint cache averages colours channel by channel. */
	private static final ColorResolver RESOLVER = (biome, x, z) -> {
		BiomeLook look = BiomeLooks.of(biome);
		float share = look == null || look.waterOpacity() == null ? 1 : look.waterOpacity();
		int grey = Math.round(Math.clamp(share / SHARE_MAX, 0, 1) * 255);
		return ARGB.color(255, grey, grey, grey);
	};

	private WaterOpacity() {
	}

	public static void register() {
		ColorResolverRegistry.register(RESOLVER);
		SpriteSourceRegistry.register(Identifier.fromNamespaceAndPath("lanature", "alpha_normalised"), WaterSprites.MAP_CODEC);
		// Java's water model (FluidStateModelSet) with the full-opacity copies, kept translucent
		FluidRenderingRegistry.register(Fluids.WATER, Fluids.FLOWING_WATER, new FluidModel.Unbaked(full("water_still"), full("water_flow"),
			full("water_overlay"), BlockTintSources.water()));
	}

	private static Material full(String texture) {
		return new Material(Identifier.fromNamespaceAndPath("lanature", "block/" + texture + "_full"), true);
	}

	/** The water tint at a position with the water's opacity in its alpha. */
	public static int apply(int tint, BlockAndTintGetter level, BlockPos pos) {
		float share = BiomeLooks.active() ? ARGB.red(level.getBlockTint(pos, RESOLVER)) / 255f * SHARE_MAX : 1;
		float opacity = Math.min(1, share * WaterSprites.topAlpha() / 255f);
		return ARGB.color(Math.round(ARGB.alpha(tint) * opacity), tint);
	}
}
