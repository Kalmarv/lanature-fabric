package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.environment.PackEnvironment;

/**
 * Bedrock's water fog transition in place of Java's water vision ramp where the pack sets one: the time under water is
 * Java's own counter, which Bedrock's transition is taken to follow (inferred).
 */
@Mixin(WaterFogEnvironment.class)
abstract class WaterFogEnvironmentMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void lanature$transition(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!(camera.entity() instanceof LocalPlayer player)) {
			return;
		}
		float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
		float start = camera.attributeProbe().getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, partialTicks);
		float end = camera.attributeProbe().getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, partialTicks);
		PackEnvironment.WaterFog water = PackEnvironment.waterFog(level, camera.position(), ((LocalPlayerAccessor) player).lanature$waterVisionTime() / 20.0F,
			Math.max(0.25F, player.getWaterVision()), start, end);
		if (water != null) {
			fog.environmentalStart = water.start();
			fog.environmentalEnd = water.end();
			fog.skyEnd = water.end();
			fog.cloudEnd = water.end();
		}
	}
}
