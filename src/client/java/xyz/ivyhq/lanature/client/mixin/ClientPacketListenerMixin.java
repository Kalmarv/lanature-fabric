package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/**
 * Starts the pack's block effect once for a burst of Java {@code block} particles the server sends (a fall's), with
 * the burst's count, spread and speed, where Java spawns each particle of it.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Shadow
	private ClientLevel level;

	@Inject(method = "handleParticleEvent", cancellable = true, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER))
	private void lanature$blockBurst(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (packet.count() > 0 && packet.particle() instanceof BlockParticleOption block && block.getType() == ParticleTypes.BLOCK
			&& SiteEffects.block(level, block, packet.x(), packet.y(), packet.z(), packet.count(),
				Math.max(packet.xDist(), Math.max(packet.yDist(), packet.zDist())), Math.max(packet.xMaxSpeed(), Math.max(packet.yMaxSpeed(), packet.zMaxSpeed())))) {
			ci.cancel();
		}
	}
}
