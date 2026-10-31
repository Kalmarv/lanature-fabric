package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.model.ModelRenderer;

/** A modelled thrown item (ender pearl, splash and lingering potions): its Bedrock model stands in for Java's item sprite. */
@Mixin(ThrownItemRenderer.class)
abstract class ThrownItemRendererMixin {
	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ThrownItemRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void lanature$item(ItemStackRenderState item, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline,
		Operation<Void> original, @Local(argsOnly = true) ThrownItemRenderState state) {
		if (state.getData(ModelRenderer.MODEL) == null) {
			original.call(item, poseStack, collector, light, overlay, outline);
		}
	}
}
