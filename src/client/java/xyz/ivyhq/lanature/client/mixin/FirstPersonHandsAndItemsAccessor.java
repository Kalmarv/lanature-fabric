package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How far each first-person hand is raised (0 lowered, 1 raised) this tick and last, for Bedrock's arm heights. */
@Mixin(FirstPersonHandsAndItems.class)
public interface FirstPersonHandsAndItemsAccessor {
	@Accessor("mainHandHeight")
	float lanature$mainHandHeight();

	@Accessor("oMainHandHeight")
	float lanature$oMainHandHeight();

	@Accessor("offHandHeight")
	float lanature$offHandHeight();

	@Accessor("oOffHandHeight")
	float lanature$oOffHandHeight();
}
