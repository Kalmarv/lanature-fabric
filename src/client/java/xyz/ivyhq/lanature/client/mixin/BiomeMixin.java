package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.environment.BiomeLooks;
import xyz.ivyhq.lanature.client.environment.BiomeLooks.BiomeLook;

/**
 * The pack's water surface, grass and foliage colours for the current level's biomes. The grass colour replaces the
 * base colour, so Java's swamp and dark forest modifiers still apply over it.
 */
@Mixin(Biome.class)
abstract class BiomeMixin {
	@ModifyReturnValue(method = "getWaterColor", at = @At("RETURN"))
	private int lanature$water(int color) {
		BiomeLook look = BiomeLooks.of((Biome) (Object) this);
		return look != null && look.water() != null ? look.water() : color;
	}

	@ModifyReturnValue(method = "getBaseGrassColor", at = @At("RETURN"))
	private int lanature$grass(int color) {
		BiomeLook look = BiomeLooks.of((Biome) (Object) this);
		return look != null && look.grass() != null ? look.grass() : color;
	}

	@ModifyReturnValue(method = "getFoliageColor", at = @At("RETURN"))
	private int lanature$foliage(int color) {
		BiomeLook look = BiomeLooks.of((Biome) (Object) this);
		return look != null && look.foliage() != null ? look.foliage() : color;
	}
}
