package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.ivyhq.lanature.client.bedrock.BlockSounds;

/** The pack's block sound groups ({@link BlockSounds}). */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Inject(method = "getSoundType", at = @At("HEAD"), cancellable = true)
	private void lanature$soundType(CallbackInfoReturnable<SoundType> cir) {
		SoundType pack = BlockSounds.of(((BlockBehaviour.BlockStateBase) (Object) this).getBlock());
		if (pack != null) {
			cir.setReturnValue(pack);
		}
	}
}
