package xyz.ivyhq.lanature.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game time of an entity's last kinetic weapon hit, negative while it has none. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("lastKineticHitFeedbackTime")
	long lanature$lastKineticHitFeedbackTime();
}
