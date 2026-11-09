package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.GeyserEruptionParticle;
import net.minecraft.client.particle.NoRenderParticle;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/** Starts the pack's geyser effects for a potent sulfur eruption, in place of the base, plume and poof particles Java's spawns. */
@Mixin(GeyserEruptionParticle.class)
abstract class GeyserEruptionParticleMixin extends NoRenderParticle {
	@Shadow
	@Final
	private int waterBlocks;

	private GeyserEruptionParticleMixin(ClientLevel level, double x, double y, double z) {
		super(level, x, y, z);
	}

	@WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private boolean lanature$javaParticles(ClientLevel level, ParticleOptions options, double x, double y, double z, double xa, double ya, double za) {
		return !SiteEffects.replacesGeyser();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void lanature$geyser(CallbackInfo ci) {
		if (SiteEffects.replacesGeyser()) {
			SiteEffects.geyser(age, lifetime, waterBlocks, x, y, z);
		}
	}
}
