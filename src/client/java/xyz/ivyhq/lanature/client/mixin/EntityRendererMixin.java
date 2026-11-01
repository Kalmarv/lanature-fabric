package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.model.HumanoidPose;
import xyz.ivyhq.lanature.client.model.ModelRenderer;
import xyz.ivyhq.lanature.client.model.ModelState;

/** Gives the render state of a modelled entity its Bedrock look for the frame, and of a posed Java model its Bedrock pose. */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
		at = @At("RETURN"))
	private void lanature$model(T entity, float partialTicks, CallbackInfoReturnable<S> cir) {
		ModelState model = EntityScripts.get().model(entity, partialTicks);
		if (model != null) {
			cir.getReturnValue().setData(ModelRenderer.MODEL, model);
		}
		HumanoidPose pose = EntityScripts.get().humanoidPose(entity, partialTicks);
		if (pose != null) {
			cir.getReturnValue().setData(HumanoidPose.KEY, pose);
		}
	}
}
