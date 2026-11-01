package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.ivyhq.lanature.client.entity.EntityScripts;

/** Marks the extraction of the inventory's paperdoll, which keeps Java's pose (see {@link EntityScripts#paperdoll}). */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
		at = @At("HEAD"))
	private static void lanature$paperdollStart(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		EntityScripts.get().paperdoll(true);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
		at = @At("RETURN"))
	private static void lanature$paperdollEnd(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		EntityScripts.get().paperdoll(false);
	}
}
