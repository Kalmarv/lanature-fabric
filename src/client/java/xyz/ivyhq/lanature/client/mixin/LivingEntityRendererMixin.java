package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.model.ModelRenderer;
import xyz.ivyhq.lanature.client.model.ModelState;

/**
 * Draws a modelled living entity with its Bedrock model in place of Java's, inside Java's own submission: the body
 * yaw, death roll, sleeping and upside-down turns, the attribute scale, the hurt and white overlays and the
 * invisibility tint stay Java's, as do the shadow and name tag. What Java's renderers add for their own model is
 * skipped, since the Bedrock model's animations do their own: each renderer's extra turns (a fish's roll on land and
 * its sway, the cat's lie-down roll, the fox's pounce pitch, the armor stand's wiggle, the pufferfish's bob; only
 * {@link LivingEntityRenderer}'s own {@code setupRotations} runs), its model scale, and its layers (worn and held
 * items, collars). The model's size is Bedrock's (its behaviour scale,
 * babies' included, with the client entity's), in place of Java's age scale.
 */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	/** {@link LivingEntityRenderer}'s own {@code setupRotations}, called past any renderer's override. */
	@Unique
	private static @Nullable MethodHandle lanature$baseRotations;

	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V"))
	private void lanature$rotations(LivingEntityRenderer<?, ?, ?> self, LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale,
		Operation<Void> original) {
		if (state.getData(ModelRenderer.MODEL) == null) {
			original.call(self, state, poseStack, bodyRot, entityScale);
			return;
		}
		try {
			if (lanature$baseRotations == null) {
				// this method runs as LivingEntityRenderer's, so its lookup may call LivingEntityRenderer's methods as invokespecial does
				lanature$baseRotations = MethodHandles.lookup().findSpecial(LivingEntityRenderer.class, "setupRotations",
					MethodType.methodType(void.class, LivingEntityRenderState.class, PoseStack.class, float.class, float.class), LivingEntityRenderer.class);
			}
			lanature$baseRotations.invoke(self, state, poseStack, bodyRot, entityScale);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new IllegalStateException("LivingEntityRenderer.setupRotations", e);
		}
	}

	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;scale(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void lanature$javaScale(LivingEntityRenderer<?, ?, ?> self, LivingEntityRenderState state, PoseStack poseStack, Operation<Void> original) {
		if (state.getData(ModelRenderer.MODEL) == null) {
			original.call(self, state, poseStack);
		}
	}

	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
	private <S> void lanature$model(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light,
		int overlay, int color, UvMapping uvMapping, int outlineColor, Operation<Void> original) {
		ModelState bedrock = ((LivingEntityRenderState) state).getData(ModelRenderer.MODEL);
		if (bedrock == null) {
			original.call(collector, model, state, poseStack, renderType, light, overlay, color, uvMapping, outlineColor);
			return;
		}
		// back from Java's model space (Y down, 1.501 above the feet) to the entity's turned frame
		poseStack.pushPose();
		poseStack.translate(0.0F, 1.501F, 0.0F);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		ModelRenderer.submit(bedrock, poseStack, collector, light, overlay, color);
		poseStack.popPose();
	}

	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"))
	private boolean lanature$javaLayers(LivingEntityRenderer<?, ?, ?> self, LivingEntityRenderState state, Operation<Boolean> original) {
		return state.getData(ModelRenderer.MODEL) == null && original.call(self, state);
	}
}
