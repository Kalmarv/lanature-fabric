package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How long the player's eyes have been under water, in ticks (up to 600; it falls by 10 a tick out of water). */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	@Accessor("waterVisionTime")
	int lanature$waterVisionTime();
}
