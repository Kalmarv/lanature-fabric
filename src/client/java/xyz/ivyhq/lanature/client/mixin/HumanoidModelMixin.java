package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.model.HumanoidPose;

/**
 * Gives a humanoid model the Bedrock pose its render state carries, over Java's own animation: the entity's model and
 * the armour models drawn on it alike, since each is posed from the same state.
 */
@Mixin(HumanoidModel.class)
abstract class HumanoidModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void lanature$bedrockPose(HumanoidRenderState state, CallbackInfo ci) {
		HumanoidPose pose = state.getData(HumanoidPose.KEY);
		if (pose != null) {
			pose.apply((HumanoidModel<?>) (Object) this);
		}
	}
}
