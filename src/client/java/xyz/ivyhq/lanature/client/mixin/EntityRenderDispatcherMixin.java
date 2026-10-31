package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.model.ModelRenderer;
import xyz.ivyhq.lanature.client.model.ModelState;

/**
 * Draws the Bedrock model of a modelled entity that is not living (living ones are drawn inside Java's
 * {@code LivingEntityRenderer}): at the entity's position, turned to its yaw, before its Java renderer runs. The
 * renderer still draws what Bedrock's engine draws around such a model (the fishing line, the end crystal's beam, the
 * name tag and leash); each renderer's own model is dropped by its mixin ({@code FishingHookRendererMixin} and the like).
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
	private <S extends EntityRenderState> void lanature$model(EntityRenderer<?, S> renderer, S state, PoseStack poseStack, SubmitNodeCollector collector,
		CameraRenderState camera, Operation<Void> original) {
		ModelState model = state.getData(ModelRenderer.MODEL);
		if (model != null && !(state instanceof LivingEntityRenderState)) {
			poseStack.pushPose();
			// the model's frame, as a living entity's body turn leaves it
			poseStack.rotateDegrees(Axis.YP, 180.0F - model.bodyYaw());
			ModelRenderer.submit(model, poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, -1);
			poseStack.popPose();
		}
		original.call(renderer, state, poseStack, collector, camera);
	}
}
