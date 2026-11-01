package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/**
 * Starts the pack's splash effect once for a splash potion's splash, plain or instant, in place of Java's 100 spell
 * particles; the bottle's shards (item particles) stay.
 */
@Mixin(LevelEventHandler.class)
abstract class LevelEventHandlerMixin {
	@Inject(method = "potionSplashParticles", at = @At("HEAD"))
	private void lanature$splash(ParticleType<SpellParticleOption> particleType, BlockPos pos, int data, RandomSource random, CallbackInfo ci) {
		SiteEffects.potionSplash(pos, data);
	}

	@WrapOperation(method = "potionSplashParticles", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private void lanature$spell(ClientLevel level, ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
		Operation<Void> original) {
		if (!SiteEffects.replacesPotionSplash()) {
			original.call(level, options, x, y, z, xa, ya, za);
		}
	}
}
