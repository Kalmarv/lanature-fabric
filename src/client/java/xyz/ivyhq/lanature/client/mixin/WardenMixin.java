package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.particle.ParticleSystem;

/**
 * Bedrock's warden draws its digging particles through its scripts' dig effect ({@code minecraft:warden_dig}), where
 * Java's spawns block particles itself; while the pack's scripts run for a warden and that effect is loaded, Java's
 * give way to it.
 */
@Mixin(Warden.class)
abstract class WardenMixin {
	@Inject(method = "clientDiggingParticles", at = @At("HEAD"), cancellable = true)
	private void lanature$digEffect(AnimationState state, CallbackInfo ci) {
		if (EntityScripts.get().runs((Warden) (Object) this) && ParticleSystem.get().hasEffect("minecraft:warden_dig")) {
			ci.cancel();
		}
	}
}
