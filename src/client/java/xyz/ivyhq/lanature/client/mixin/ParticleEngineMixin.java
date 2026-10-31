package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.particle.BedrockParticleRenderState;
import xyz.ivyhq.lanature.client.particle.ParticleSystem;

/**
 * Runs the Bedrock particle system alongside vanilla's: it ticks with the particle engine (after the client entity
 * scripts that start effects), draws through the same particle render state, and takes over the Java particle spawns
 * the pack replaces.
 */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
	@Shadow
	protected ClientLevel level;

	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void lanature$replace(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
		CallbackInfoReturnable<Particle> cir) {
		if (ParticleSystem.get().trigger(options, x, y, z, xa, ya, za)) {
			cir.setReturnValue(null);
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void lanature$scripts(CallbackInfo ci) {
		EntityScripts.get().tick(level);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void lanature$tick(CallbackInfo ci) {
		ParticleSystem.get().tick(level);
	}

	@Inject(method = "extract", at = @At("TAIL"))
	private void lanature$extract(ParticlesRenderState state, Frustum frustum, Camera camera, float partialTick, CallbackInfo ci) {
		BedrockParticleRenderState bedrock = ParticleSystem.get().extract(camera, frustum, partialTick);
		if (!bedrock.isEmpty()) {
			state.add(bedrock);
		}
	}

	@Inject(method = "clearParticles", at = @At("TAIL"))
	private void lanature$clear(CallbackInfo ci) {
		EntityScripts.get().clear();
		ParticleSystem.get().clear();
	}
}
