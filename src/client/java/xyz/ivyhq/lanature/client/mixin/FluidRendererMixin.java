package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.environment.WaterOpacity;

/** The pack's water surface transparency, in the alpha of the water's tint ({@link WaterOpacity}). */
@Mixin(FluidRenderer.class)
abstract class FluidRendererMixin {
	@ModifyExpressionValue(method = "tesselate", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/color/block/BlockTintSource;colorInWorld(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
	private int lanature$waterOpacity(int tint, @Local(argsOnly = true) BlockAndTintGetter level, @Local(argsOnly = true) BlockPos pos,
		@Local(argsOnly = true) FluidState fluidState) {
		return fluidState.is(FluidTags.WATER) ? WaterOpacity.apply(tint, level, pos) : tint;
	}
}
