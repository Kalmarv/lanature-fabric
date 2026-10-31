package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.environment.PackEnvironment;

/**
 * Bedrock's rain ({@code weather}) fog in place of Java's. Java pulls the fog in by fixed offsets as its rain fog
 * factor rises (the rain level where the sky is visible, eased); the pack's fog instead moves from the air distances to
 * its weather distances by the same factor. Where only part of the biome average has a weather fog, Java's offsets keep
 * the rest.
 */
@Mixin(AtmosphericFogEnvironment.class)
abstract class AtmosphericFogEnvironmentMixin {
	@Shadow
	private float rainFogMultiplier;

	@Inject(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;environmentalEnd:F",
		opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
	private void lanature$weatherFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci,
		@Share("weather") LocalRef<PackEnvironment.Weather> shared) {
		PackEnvironment.Weather weather = PackEnvironment.weather(level, camera.position());
		shared.set(weather);
		float amount = rainFogMultiplier * weather.coverage();
		if (amount > 0) {
			fog.environmentalStart = Mth.lerp(amount, fog.environmentalStart, weather.start());
			fog.environmentalEnd = Mth.lerp(amount, fog.environmentalEnd, weather.end());
		}
	}

	@ModifyExpressionValue(method = "setupFog", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/renderer/fog/environment/AtmosphericFogEnvironment;rainFogMultiplier:F", opcode = org.objectweb.asm.Opcodes.GETFIELD))
	private float lanature$javaRainFog(float multiplier, @Share("weather") LocalRef<PackEnvironment.Weather> shared) {
		PackEnvironment.Weather weather = shared.get();
		return weather == null ? multiplier : multiplier * (1 - weather.coverage());
	}
}
