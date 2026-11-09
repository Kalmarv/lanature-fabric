package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.model.LeashColours;

/** The pack's lead rope colours on Java's leash segments ({@link LeashColours}). */
@Mixin(LeashFeatureRenderer.class)
abstract class LeashFeatureRendererMixin {
	@WrapOperation(method = "addVertexPair", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private static VertexConsumer lanature$leashColour(VertexConsumer builder, float r, float g, float b, float a, Operation<VertexConsumer> original,
		@Local(argsOnly = true) int k, @Local(argsOnly = true) boolean backwards) {
		LeashColours colours = LeashColours.get();
		if (colours == null) {
			return original.call(builder, r, g, b, a);
		}
		// Java's own choice of the shaded segment
		float[] c = colours.segment(k % 2 == (backwards ? 1 : 0));
		return original.call(builder, c[0], c[1], c[2], a);
	}
}
