package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.world.entity.AreaEffectCloud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/** Starts the pack's lingering cloud effect in place of a tick of the cloud's Java particles. */
@Mixin(AreaEffectCloud.class)
abstract class AreaEffectCloudMixin {
	@Inject(method = "clientTick", at = @At("HEAD"), cancellable = true)
	private void lanature$cloud(CallbackInfo ci) {
		if (SiteEffects.lingeringCloud((AreaEffectCloud) (Object) this)) {
			ci.cancel();
		}
	}
}
