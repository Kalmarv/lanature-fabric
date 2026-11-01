package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ParticleUtils;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/** Starts the pack's smash effects once for a mace smash, in place of Java's dust pillar particles. */
@Mixin(ParticleUtils.class)
abstract class ParticleUtilsMixin {
	@Inject(method = "spawnSmashAttackParticles", at = @At("HEAD"), cancellable = true)
	private static void lanature$smash(LevelAccessor level, BlockPos pos, int count, CallbackInfo ci) {
		if (level instanceof ClientLevel client && SiteEffects.smash(client, pos)) {
			ci.cancel();
		}
	}
}
