package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.model.ModelRenderer;

/** A modelled end crystal keeps Java's beam to the dragon; its Bedrock model stands in for Java's crystal. */
@Mixin(EndCrystalRenderer.class)
abstract class EndCrystalRendererMixin {
	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/Identifier;III)V"))
	private <S> void lanature$crystal(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack poseStack, Identifier texture, int light,
		int overlay, int outline, Operation<Void> original) {
		if (((EndCrystalRenderState) state).getData(ModelRenderer.MODEL) == null) {
			original.call(collector, model, state, poseStack, texture, light, overlay, outline);
		}
	}
}
