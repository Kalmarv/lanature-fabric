package xyz.ivyhq.lanature.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.ivyhq.lanature.client.environment.BiomeLooks;
import xyz.ivyhq.lanature.client.environment.PackEnvironment;
import xyz.ivyhq.lanature.client.particle.SiteEffects;

/**
 * Puts the pack's biome layers into the client level's environment attributes between Java's static layers
 * (dimension, biome) and its dynamic ones (time of day, weather), hands the fog colour in rain to the pack's weather
 * fogs, and binds the pack's biome looks to the level's biomes. A broken block and the cracks of one being mined
 * start the pack's block effect in place of Java's terrain particles.
 */
@Mixin(ClientLevel.class)
@SuppressWarnings("unchecked")
abstract class ClientLevelMixin {
	@WrapOperation(method = "addEnvironmentAttributeLayers", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem$Builder;addDefaultLayers(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/attribute/EnvironmentAttributeSystem$Builder;"))
	private EnvironmentAttributeSystem.Builder lanature$packLayers(EnvironmentAttributeSystem.Builder builder, Level level,
		Operation<EnvironmentAttributeSystem.Builder> original) {
		ClientLevel self = (ClientLevel) (Object) this;
		BiomeLooks.bind(self.registryAccess());
		builder.addStaticLayers(level);
		PackEnvironment.addLayers(builder, self);
		builder.addDynamicLayers(level);
		if (level.canHaveWeather()) {
			// the weather adds its layers last
			List<EnvironmentAttributeLayer<?>> fogColor = ((EnvironmentAttributeSystemBuilderAccessor) builder).lanature$layers().get(EnvironmentAttributes.FOG_COLOR);
			if (fogColor != null && fogColor.getLast() instanceof EnvironmentAttributeLayer.TimeBased<?> weather) {
				fogColor.set(fogColor.size() - 1, PackEnvironment.weatherFogColor(self, (EnvironmentAttributeLayer.TimeBased<Vector3fc>) weather));
			}
		}
		return builder;
	}

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void lanature$destroy(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (SiteEffects.destroy((ClientLevel) (Object) this, pos, state)) {
			ci.cancel();
		}
	}

	@Inject(method = "addBreakingParticles", at = @At("HEAD"), cancellable = true)
	private void lanature$crack(BlockPos pos, Direction direction, BlockState state, CallbackInfo ci) {
		if (SiteEffects.crack((ClientLevel) (Object) this, pos, direction, state)) {
			ci.cancel();
		}
	}
}
