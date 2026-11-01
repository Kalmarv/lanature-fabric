package xyz.ivyhq.lanature.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.model.AttachableRenderer;
import xyz.ivyhq.lanature.client.model.HumanoidPose;
import xyz.ivyhq.lanature.client.model.ModelState;

/**
 * Draws a first-person hand that is empty or holds an item with an attachable model the way Bedrock does: the player's
 * arm in its Bedrock pose (the pack's first-person animations), seen from the eye, with the attachable bound to its
 * hand. Hands holding other items keep Java's first-person rendering, whose item transforms Bedrock's engine replaces
 * with its own.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class FirstPersonHandsAndItemsRendererMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void lanature$attachable(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
		InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int light,
		CallbackInfo ci) {
		AvatarRenderState avatar = playerState.avatarRenderState;
		if (avatar == null || state.isScoping) {
			return;
		}
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? avatar.mainArm : avatar.mainArm.getOpposite();
		ModelState attachable = avatar.getData(AttachableRenderer.key(arm));
		HumanoidPose pose = avatar.getData(HumanoidPose.KEY);
		if (pose != null && (attachable != null || itemStack.isEmpty())) {
			AttachableRenderer.submitFirstPerson(avatar, attachable, arm, poseStack, collector, light);
			ci.cancel();
		}
	}
}
