package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.model.AttachableRenderer;
import xyz.ivyhq.lanature.client.model.ModelState;

/** Gives an armed entity's render state the attachable models of the items it holds, for the frame. */
@Mixin(ArmedEntityRenderState.class)
abstract class ArmedEntityRenderStateMixin {
	@Inject(method = "extractArmedEntityRenderState", at = @At("RETURN"))
	private static void lanature$attachables(LivingEntity entity, ArmedEntityRenderState state, ItemModelResolver itemModelResolver, float partialTicks,
		CallbackInfo ci) {
		for (HumanoidArm arm : HumanoidArm.values()) {
			ModelState model = EntityScripts.get().attachableModel(entity, arm, partialTicks);
			if (model != null) {
				state.setData(AttachableRenderer.key(arm), model);
			}
		}
	}
}
