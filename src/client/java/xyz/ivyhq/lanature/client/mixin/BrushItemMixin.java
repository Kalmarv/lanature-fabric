package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/** Starts the pack's block effect for each flake brushed off a block. */
@Mixin(BrushItem.class)
abstract class BrushItemMixin {
	@WrapOperation(method = "spawnDustParticles", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private void lanature$flake(Level level, ParticleOptions options, double x, double y, double z, double xa, double ya, double za, Operation<Void> original) {
		if (!(level instanceof ClientLevel client && options instanceof BlockParticleOption block && SiteEffects.block(client, block, x, y, z, 1, 0, 1.0))) {
			original.call(level, options, x, y, z, xa, ya, za);
		}
	}
}
