package xyz.ivyhq.lanature.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.model.AttachableRenderer;
import xyz.ivyhq.lanature.client.model.ModelState;

/**
 * Draws a held item with its attachable model in place of Java's item, for humanoid holders (whose hands are where
 * the attachables' humanoid binding puts them). Java's own item motions (the spear's stab, item use) are left to the
 * attachable's animations; other holders keep Java's item.
 */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void lanature$attachable(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack itemStack, HumanoidArm arm, PoseStack poseStack,
		SubmitNodeCollector collector, int light, CallbackInfo ci) {
		ModelState model = state.getData(AttachableRenderer.key(arm));
		if (model == null || !(((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof HumanoidModel<?> humanoid)) {
			return;
		}
		// the arm's frame, as HumanoidModel.translateToHand gives it (the player model's own moves a slim arm's items half
		// a pixel out, where Bedrock's slim item bones are not)
		poseStack.pushPose();
		humanoid.root().translateAndRotate(poseStack);
		humanoid.getArm(arm).translateAndRotate(poseStack);
		AttachableRenderer.submitInHand(model, arm, poseStack, collector, light);
		poseStack.popPose();
		ci.cancel();
	}
}
