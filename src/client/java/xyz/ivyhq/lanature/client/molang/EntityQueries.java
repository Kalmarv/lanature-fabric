package xyz.ivyhq.lanature.client.molang;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import xyz.ivyhq.lanature.molang.Functions;

/** Entity queries, on the host's entity; 0 when the host has none. */
final class EntityQueries {
	private EntityQueries() {
	}

	private static void flag(String name, Predicate<Entity> test) {
		Functions.registerNumericQuery(name, (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			return e != null && test.test(e) ? 1 : 0;
		});
	}

	private static void value(String name, ToDoubleFunction<Entity> f) {
		Functions.registerNumericQuery(name, (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			return e == null ? 0 : f.applyAsDouble(e);
		});
	}

	static void register() {
		flag("is_alive", Entity::isAlive);
		flag("is_on_ground", Entity::onGround);
		flag("is_in_water", Entity::isInWater);
		flag("is_in_water_or_rain", Entity::isInWaterOrRain);
		flag("is_in_contact_with_water", e -> e.isInWaterOrRain() || e.isInWater());
		flag("is_sneaking", Entity::isShiftKeyDown);
		flag("is_swimming", Entity::isSwimming);
		flag("is_riding", Entity::isPassenger);
		flag("is_spectator", Entity::isSpectator);
		flag("is_baby", e -> e instanceof LivingEntity le && le.isBaby());
		flag("is_sleeping", e -> e instanceof LivingEntity le && le.isSleeping());
		flag("is_gliding", e -> e instanceof LivingEntity le && le.isFallFlying());
		flag("is_local_player", e -> e == Minecraft.getInstance().player);
		// Java draws no entity inside a UI paper doll through these scripts
		flag("is_in_ui", e -> false);
		value("health", e -> e instanceof LivingEntity le ? le.getHealth() : 0);
		value("max_health", e -> e instanceof LivingEntity le ? le.getMaxHealth() : 0);
		value("body_y_rotation", e -> e instanceof LivingEntity le ? le.yBodyRot : e.getYRot());
		value("ground_speed", e -> Math.sqrt(sq(e.getX() - e.xo) + sq(e.getZ() - e.zo)) * 20);
		value("vertical_speed", e -> (e.getY() - e.yo) * 20);
		value("life_time", e -> e.tickCount / 20.0);
		value("model_scale", e -> 1);
		value("modified_distance_moved", e -> e instanceof LivingEntity le ? le.walkAnimation.position() : 0);
		value("modified_move_speed", e -> e instanceof LivingEntity le ? le.walkAnimation.speed() : 0);
		value("hurt_time", e -> e instanceof LivingEntity le ? le.hurtTime : 0);
		value("swim_amount", e -> e instanceof LivingEntity le ? le.getSwimAmount(1) : 0);
		// the head's pitch, and its yaw from the body
		value("target_x_rotation", Entity::getXRot);
		value("target_y_rotation", e -> e instanceof LivingEntity le ? Mth.wrapDegrees(le.yHeadRot - le.yBodyRot) : 0);
		// item use in seconds: the used item's elapsed and full duration, and the same for the main hand only
		value("item_in_use_duration", e -> e instanceof LivingEntity le && le.isUsingItem() ? le.getTicksUsingItem() / 20.0 : 0);
		value("item_max_use_duration", e -> e instanceof LivingEntity le && le.isUsingItem() ? le.getUseItem().getUseDuration(le) / 20.0 : 0);
		value("main_hand_item_use_duration",
			e -> e instanceof LivingEntity le && le.isUsingItem() && le.getUsedItemHand() == InteractionHand.MAIN_HAND ? le.getTicksUsingItem() / 20.0 : 0);
		value("main_hand_item_max_duration", e -> e instanceof LivingEntity le ? le.getMainHandItem().getUseDuration(le) / 20.0 : 0);
		flag("head_is_in_water", e -> e.isEyeInFluid(FluidTags.WATER));
		flag("has_head_gear", e -> e instanceof LivingEntity le && !le.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
		flag("is_levitating", e -> e instanceof LivingEntity le && le.hasEffect(MobEffects.LEVITATION));
		flag("is_crawling", Entity::isVisuallyCrawling);
		flag("is_jumping", e -> !e.onGround() && e.getY() > e.yo && !e.isInWater());
		flag("is_sitting", e -> e instanceof TamableAnimal t && t.isInSittingPose());
		flag("is_tamed", e -> e instanceof TamableAnimal t && t.isTame());
		// scripts only run for entities near the camera
		flag("is_on_screen", e -> true);
		flag("blocking", e -> e instanceof LivingEntity le && le.isBlocking());
		// Java has no emotes, and no entity state matching Bedrock's charging flag
		flag("is_emoting", e -> false);
		flag("is_charging", e -> false);
		Functions.registerNumericQuery("is_riding_any_entity_of_type", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			Entity vehicle = e == null ? null : e.getVehicle();
			if (vehicle == null) {
				return 0;
			}
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
			for (int i = 0; i < a.length; i++) {
				if (ClientQueries.str(s, a, i).equals(id)) {
					return 1;
				}
			}
			return 0;
		});
		Functions.registerNumericQuery("is_item_equipped", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			String slot = a.length == 0 ? "main_hand" : ClientQueries.str(s, a, 0);
			return e != null && !ClientQueries.slot(e, slot).isEmpty() ? 1 : 0;
		});
		Functions.registerQuery("get_equipped_item_name", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			String slot = a.length == 0 ? "main_hand" : ClientQueries.str(s, a, 0);
			ItemStack stack = e == null ? ItemStack.EMPTY : ClientQueries.slot(e, slot);
			return stack.isEmpty() ? "" : ClientQueries.itemId(stack).replaceFirst("^minecraft:", "");
		});
		// is_item_name_any(slot, [index,] names...)
		Functions.registerNumericQuery("is_item_name_any", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			if (e == null || a.length < 2) {
				return 0;
			}
			String id = ClientQueries.itemId(ClientQueries.slot(e, ClientQueries.str(s, a, 0)));
			int first = a[1].value(s) instanceof String ? 1 : 2;
			for (int i = first; i < a.length; i++) {
				if (ClientQueries.str(s, a, i).equals(id)) {
					return 1;
				}
			}
			return 0;
		});
		Functions.registerNumericQuery("is_name_any", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			if (e == null || !e.hasCustomName()) {
				return 0;
			}
			String name = e.getCustomName().getString();
			for (int i = 0; i < a.length; i++) {
				if (ClientQueries.str(s, a, i).equals(name)) {
					return 1;
				}
			}
			return 0;
		});
	}

	private static double sq(double v) {
		return v * v;
	}
}
