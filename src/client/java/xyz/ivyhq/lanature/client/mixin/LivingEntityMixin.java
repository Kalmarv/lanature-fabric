package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/** Starts the pack's status-effect swirl where Java spawns its particle. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@WrapOperation(method = "tickEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private void lanature$swirl(Level level, ParticleOptions options, double x, double y, double z, double xa, double ya, double za, Operation<Void> original) {
		if (!(level.isClientSide() && SiteEffects.statusEffect(options, x, y, z))) {
			original.call(level, options, x, y, z, xa, ya, za);
		}
	}
}
