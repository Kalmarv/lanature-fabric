package xyz.ivyhq.lanature.client.molang;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.bedrock.BedrockResources;
import xyz.ivyhq.lanature.client.mixin.LivingEntityAccessor;
import xyz.ivyhq.lanature.client.particle.PictureEffects;
import xyz.ivyhq.lanature.molang.Functions;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;

/** Entity queries, on the host's entity; 0 when the host has none. */
public final class EntityQueries {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/molang");
	private static final Map<String, Predicate<BlockState>> BLOCK_TAGS = Map.of("water", state -> state.is(Blocks.WATER));
	private static final Set<String> UNKNOWN_BLOCK_TAGS = ConcurrentHashMap.newKeySet();

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

	/** A value that changes smoothly between ticks, read at the host's partial tick. */
	private static void smooth(String name, SmoothValue f) {
		Functions.registerNumericQuery(name, (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			return e == null ? 0 : f.at(e, s.host instanceof EntityHost h ? h.partialTick() : 1);
		});
	}

	private interface SmoothValue {
		double at(Entity e, float partialTick);
	}

	/** The body's yaw at a partial tick: a living entity's body rotation, any other entity's yaw. */
	public static float bodyYaw(Entity e, float pt) {
		return e instanceof LivingEntity le ? Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot) : e.getYRot(pt);
	}

	/** Whether the entity walks: Java's renderers give riders and the dead no walk animation. */
	private static boolean walks(LivingEntity e) {
		return !e.isPassenger() && e.isAlive();
	}

	static void register() {
		flag("is_alive", Entity::isAlive);
		flag("is_on_ground", Entity::onGround);
		flag("is_in_water", e -> e.isInWater() && !floatingHook(e));
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
		smooth("body_y_rotation", EntityQueries::bodyYaw);
		value("ground_speed", e -> floatingHook(e) ? 0 : Math.sqrt(sq(e.getX() - e.xo) + sq(e.getZ() - e.zo)) * 20);
		value("vertical_speed", e -> floatingHook(e) ? 0 : (e.getY() - e.yo) * 20);
		smooth("life_time", (e, pt) -> (e.tickCount + pt - 1) / 20.0);
		value("model_scale", e -> 1);
		smooth("modified_distance_moved", (e, pt) -> e instanceof LivingEntity le && walks(le) ? le.walkAnimation.position(pt) : 0);
		smooth("modified_move_speed", (e, pt) -> e instanceof LivingEntity le && walks(le) ? le.walkAnimation.speed(pt) : 0);
		value("hurt_time", e -> e instanceof LivingEntity le ? le.hurtTime : 0);
		smooth("swim_amount", (e, pt) -> e instanceof LivingEntity le ? le.getSwimAmount(pt) : 0);
		// the head's pitch, and its yaw from the body
		smooth("target_x_rotation", Entity::getXRot);
		smooth("target_y_rotation", (e, pt) -> e instanceof LivingEntity le ? Mth.wrapDegrees(Mth.rotLerp(pt, le.yHeadRotO, le.yHeadRot) - bodyYaw(e, pt)) : 0);
		// item use in seconds: the used item's elapsed and full duration, and the same for the main hand only
		value("item_in_use_duration", e -> e instanceof LivingEntity le && le.isUsingItem() ? le.getTicksUsingItem() / 20.0 : 0);
		value("item_max_use_duration", e -> e instanceof LivingEntity le && le.isUsingItem() ? le.getUseItem().getUseDuration(le) / 20.0 : 0);
		value("main_hand_item_use_duration",
			e -> e instanceof LivingEntity le && le.isUsingItem() && le.getUsedItemHand() == InteractionHand.MAIN_HAND ? le.getTicksUsingItem() / 20.0 : 0);
		value("main_hand_item_max_duration", e -> e instanceof LivingEntity le ? le.getMainHandItem().getUseDuration(le) / 20.0 : 0);
		// whole ticks since the entity last hit with a kinetic weapon (a spear's charge), -1 if it never has: the pack's
		// spear plays its hit animation while this is from 0 to 5, so never must read below 0
		value("ticks_since_last_kinetic_weapon_hit", e -> {
			long last = e instanceof LivingEntityAccessor le ? le.lanature$lastKineticHitFeedbackTime() : -1;
			return last < 0 ? -1 : e.level().getGameTime() - last;
		});
		// the facing of a sleeper's bed as a yaw (inferred: Java's own direction angles; vanilla's sleep animation turns
		// its root by it, which Java's renderer stands in for)
		value("sleep_rotation", e -> e instanceof LivingEntity le && le.getBedOrientation() != null ? le.getBedOrientation().toYRot() : 0);
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
		// item_remaining_use_duration([hand, [normalization]]): the time left of the item in use in that hand (the main
		// hand by default), in seconds; with a normalization, as that times the fraction of its full duration left
		// (inferred from vanilla's first-person crossbow, which scales the bow by it from 1 to 0)
		Functions.registerNumericQuery("item_remaining_use_duration", (s, a) -> {
			if (!(ClientQueries.entity(s.host) instanceof LivingEntity le) || !le.isUsingItem()) {
				return 0;
			}
			String slot = a.length == 0 ? "main_hand" : ClientQueries.str(s, a, 0);
			if (le.getUsedItemHand() != (slot.equals("off_hand") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND)) {
				return 0;
			}
			int remaining = le.getUseItemRemainingTicks();
			if (a.length < 2) {
				return remaining / 20.0;
			}
			int full = le.getUseItem().getUseDuration(le);
			return full <= 0 ? 0 : remaining / (double) full * ClientQueries.num(s, a, 1);
		});
		// is_item_name_any(slot, [index,] names...): an empty slot's name is '', as the pack tests for an empty hand
		Functions.registerNumericQuery("is_item_name_any", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			if (e == null || a.length < 2) {
				return 0;
			}
			ItemStack stack = ClientQueries.slot(e, ClientQueries.str(s, a, 0));
			String id = stack.isEmpty() ? "" : ClientQueries.itemId(stack);
			int first = a[1].value(s) instanceof String ? 1 : 2;
			for (int i = first; i < a.length; i++) {
				if (ClientQueries.str(s, a, i).equals(id)) {
					return 1;
				}
			}
			return 0;
		});
		// equipped_item_all_tags / equipped_item_any_tag(slot, [index,] tags...): the Bedrock item tags of the item there
		Functions.registerNumericQuery("equipped_item_all_tags", (s, a) -> itemTags(s, a, true));
		Functions.registerNumericQuery("equipped_item_any_tag", (s, a) -> itemTags(s, a, false));
		// the main hand's kinetic weapon: Bedrock's spears and Java's time their charge alike, by a delay and each
		// condition's longest duration, in ticks (Geyser's Bedrock spear components and Java's spears give equal values)
		kinetic("kinetic_weapon_delay", KineticWeapon::delayTicks);
		kinetic("kinetic_weapon_dismount_duration", w -> kineticDuration(w.dismountConditions()));
		kinetic("kinetic_weapon_knockback_duration", w -> kineticDuration(w.knockbackConditions()));
		kinetic("kinetic_weapon_damage_duration", w -> kineticDuration(w.damageConditions()));
		smooth("cooldown_time_remaining", EntityQueries::cooldownRemaining);
		// whether the held crossbow is loaded (Bedrock's crossbow goes only in the main hand)
		flag("item_is_charged", e -> e instanceof LivingEntity le && CrossbowItem.isCharged(le.getMainHandItem()));
		// a player's skin has a cape and its settings show it
		flag("has_cape", e -> e instanceof AbstractClientPlayer p && p.getSkin().cape() != null && p.isModelPartShown(PlayerModelPart.CAPE));
		// the block the entity stands on: its picture in the block atlas and its colour (the warden digs it up)
		surface("surface_particle_texture_coordinate", "surface_particle_texture_coordinate");
		surface("surface_particle_texture_size", "surface_particle_texture_size");
		surface("surface_particle_color", "surface_particle_tint");
		// an end crystal's base: Java's crystals placed by players have none, the exit pillars' do
		flag("show_bottom", e -> e instanceof EndCrystal c && c.showsBottom());
		// relative_block_has_any_tag(x, y, z, tags...): the Bedrock block tags of the block at an offset from the entity
		Functions.registerNumericQuery("relative_block_has_any_tag", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			if (e == null || a.length < 4) {
				return 0;
			}
			BlockState state = e.level().getBlockState(BlockPos.containing(e.getX() + ClientQueries.num(s, a, 0),
				e.getY() + ClientQueries.num(s, a, 1), e.getZ() + ClientQueries.num(s, a, 2)));
			for (int i = 3; i < a.length; i++) {
				if (blockHasTag(state, ClientQueries.str(s, a, i))) {
					return 1;
				}
			}
			return 0;
		});
		// Bedrock's variant index: the texture a variant mob picks from its render controller's array
		value("variant", EntityQueries::variant);
		// the experience orb's size, 0 to 10, by its value
		value("texture_frame_index", e -> e instanceof ExperienceOrb orb ? orb.getIcon() : 0);
		Functions.registerQuery("property", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			String property = a.length == 0 ? "" : ClientQueries.str(s, a, 0);
			String value = e == null ? null : switch (property) {
				case "minecraft:climate_variant" -> variantName(e, DataComponents.CHICKEN_VARIANT, DataComponents.COW_VARIANT, DataComponents.PIG_VARIANT);
				case "minecraft:sound_variant" -> soundVariant(e);
				// Bedrock's names for the weathering stage and the chest the golem is working at
				case "minecraft:oxidation_level" -> e instanceof CopperGolem g ? switch (g.getWeatherState()) {
					case UNAFFECTED -> "unoxidized";
					case EXPOSED -> "exposed";
					case WEATHERED -> "weathered";
					case OXIDIZED -> "oxidized";
				} : null;
				case "minecraft:chest_interaction" -> e instanceof CopperGolem g ? switch (g.getState()) {
					case IDLE -> "none";
					case GETTING_ITEM -> "take";
					case GETTING_NO_ITEM -> "take_fail";
					case DROPPING_ITEM -> "put";
					case DROPPING_NO_ITEM -> "put_fail";
				} : null;
				default -> null;
			};
			return value == null ? (Object) 0.0 : value;
		});
		// the warden: its poses, its sonic boom (Java's attack lasts 60 ticks from the event starting the animation), and
		// its heart and tendrils, which Java pulses with 10-tick countdowns (inferred: Bedrock's scripts give the heart
		// 0.8 * (1 - phase) and light the tendrils for 0.5 s after a vibration, so the phase is the heart's countdown run
		// out and the time since a vibration is the tendrils', reaching 0.5 s when it ends)
		flag("is_emerging", e -> e.hasPose(Pose.EMERGING));
		flag("is_digging", e -> e.hasPose(Pose.DIGGING));
		flag("is_roaring", e -> e.hasPose(Pose.ROARING));
		flag("is_sniffing", e -> e.hasPose(Pose.SNIFFING));
		flag("is_sonic_boom", e -> e instanceof Warden w && w.sonicBoomAnimationState.isStarted() && w.sonicBoomAnimationState.getTimeInMillis(w.tickCount) < 3000);
		smooth("heartbeat_phase", (e, pt) -> e instanceof Warden w ? 1 - w.getHeartAnimation(pt) : 0);
		smooth("time_since_last_vibration_detection", (e, pt) -> e instanceof Warden w ? (1 - w.getTendrilAnimation(pt)) * 0.5 : 0);
		flag("is_dancing", e -> e instanceof Allay al && al.isDancing() || e instanceof Parrot p && p.isPartyParrot());
		// wings flap through an angle (radians) at a speed, as Java's chicken and parrot keep them
		smooth("wing_flap_position", (e, pt) -> switch (e) {
			case Chicken c -> Mth.lerp(pt, c.oFlap, c.flap);
			case Parrot p -> Mth.lerp(pt, p.oFlap, p.flap);
			default -> 0;
		});
		smooth("wing_flap_speed", (e, pt) -> switch (e) {
			case Chicken c -> Mth.lerp(pt, c.oFlapSpeed, c.flapSpeed);
			case Parrot p -> Mth.lerp(pt, p.oFlapSpeed, p.flapSpeed);
			default -> 0;
		});
		flag("is_interested", e -> e instanceof Fox f && f.isInterested());
		flag("is_stalking", e -> e instanceof Fox f && f.isCrouching());
		flag("is_stunned", e -> e instanceof Fox f && f.isFaceplanted());
		// the client knows no AI target: a mob's aggression flag, a fishing hook's hooked entity
		flag("has_target", e -> e instanceof Mob m && m.isAggressive() || e instanceof FishingHook h && h.getHookedIn() != null);
		// an attachable is attached to its holder; another entity, to what it rides
		Functions.registerNumericQuery("is_attached", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			return s.host instanceof EntityHost h && h.attached() || e != null && e.isPassenger() ? 1 : 0;
		});
		// an attachable's owner is its holder; a tamed animal's or a projectile's, the entity that owns it
		Functions.registerNumericQuery("is_owner_identifier_any", (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			Entity owner = s.host instanceof EntityHost h && h.attached() ? e : e instanceof OwnableEntity o ? o.getOwner()
				: e instanceof Projectile p ? p.getOwner() : null;
			if (owner == null) {
				return 0;
			}
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(owner.getType()).toString();
			for (int i = 0; i < a.length; i++) {
				if (ClientQueries.str(s, a, i).equals(id)) {
					return 1;
				}
			}
			return 0;
		});
		smooth("body_x_rotation", Entity::getXRot);
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

	private static void surface(String name, String variable) {
		Functions.registerQuery(name, (s, a) -> {
			Entity e = ClientQueries.entity(s.host);
			return e == null ? (Object) 0.0 : PictureEffects.surface(e).get(variable);
		});
	}

	private static void kinetic(String name, ToDoubleFunction<KineticWeapon> f) {
		value(name, e -> {
			KineticWeapon weapon = e instanceof LivingEntity le ? le.getMainHandItem().get(DataComponents.KINETIC_WEAPON) : null;
			return weapon == null ? 0 : f.applyAsDouble(weapon);
		});
	}

	/** {@code q.kinetic_weapon_*_duration}: a condition's longest duration in ticks, 0 without one. */
	public static double kineticDuration(Optional<KineticWeapon.Condition> condition) {
		return condition.map(KineticWeapon.Condition::maxDurationTicks).orElse(0);
	}

	/**
	 * {@code q.cooldown_time_remaining} with no category (the pack names none): the held item's cooldown left, in seconds.
	 * Bedrock's spears have an attack cooldown, which is Java's attack recharge (each spear's lasts as long as its
	 * Bedrock cooldown); any other item's is Java's item cooldown (inferred for the items Bedrock gives a use cooldown
	 * by code rather than by component).
	 */
	private static double cooldownRemaining(Entity e, float pt) {
		if (!(e instanceof Player player)) {
			return 0;
		}
		ItemStack stack = player.getMainHandItem();
		if ("attack".equals(BedrockResources.item(ClientQueries.itemId(stack)).cooldown())) {
			return (1 - player.getAttackStrengthScale(pt)) * player.getCurrentItemAttackStrengthDelay() / 20;
		}
		ItemCooldowns cooldowns = player.getCooldowns();
		ItemCooldowns.CooldownInstance cooldown = cooldowns.cooldowns.get(cooldowns.getCooldownGroup(stack));
		return cooldown == null ? 0 : Math.max(0, cooldown.endTime() - (cooldowns.tickCount + pt)) / 20;
	}

	/**
	 * A fishing hook resting on the water. Bedrock's floats still on the surface, out of the water (the pack's float
	 * states need it not in water and, for the lantern hook, at no speed), where Java's bobs just under the surface,
	 * touching the water. Java's hook bobbing, not bitten and with its eye above the water is taken as Bedrock's
	 * floating one (inferred): its dive after the cast and a bite pulling it under still count as in the water.
	 */
	private static boolean floatingHook(Entity e) {
		return e instanceof FishingHook h && h.currentState == FishingHook.FishHookState.BOBBING && !h.biting && !h.isUnderWater();
	}

	/** Whether the item in a slot has all (or any) of the Bedrock item tags named after the slot and optional index. */
	private static double itemTags(Scope s, Node[] a, boolean all) {
		Entity e = ClientQueries.entity(s.host);
		if (e == null || a.length < 2) {
			return 0;
		}
		ItemStack stack = ClientQueries.slot(e, ClientQueries.str(s, a, 0));
		if (stack.isEmpty()) {
			return 0;
		}
		Set<String> tags = BedrockResources.item(ClientQueries.itemId(stack)).tags();
		int first = a[1].value(s) instanceof String ? 1 : 2;
		for (int i = first; i < a.length; i++) {
			if (tags.contains(ClientQueries.str(s, a, i)) != all) {
				return all ? 0 : 1;
			}
		}
		return all ? 1 : 0;
	}

	/**
	 * Whether a block has a Bedrock block tag. No published data lists Bedrock's block tags, so each one the pack asks
	 * for is matched here by the Java blocks that carry it: {@code water} is on Bedrock's water and flowing water
	 * (sourced: Bedrock's documented vanilla block tags), Java's water block. A tag not listed is reported once and
	 * matches nothing.
	 */
	private static boolean blockHasTag(BlockState state, String tag) {
		Predicate<BlockState> test = BLOCK_TAGS.get(tag);
		if (test == null) {
			if (UNKNOWN_BLOCK_TAGS.add(tag)) {
				LOGGER.warn("Bedrock block tag {} has no Java blocks: it matches none", tag);
			}
			return false;
		}
		return test.test(state);
	}

	/** Cats name their variant; parrots and foxes number it in Bedrock's order. */
	private static double variant(Entity e) {
		return switch (e) {
			case Cat c -> Math.max(0, CAT_VARIANTS.indexOf(c.getVariant().unwrapKey().map(k -> k.identifier().getPath()).orElse("")));
			case Parrot p -> p.getVariant().getId();
			case Fox f -> f.getVariant().getId();
			case Pufferfish p -> p.getPuffState();
			case AbstractThrownPotion p -> potionVariant(p.getItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY));
			default -> 0;
		};
	}

	/**
	 * A thrown potion's variant: the index of its bottle in the potion render controllers' texture array (vanilla's
	 * order, below), picked by its first effect, the turtle master's by the potion; 30, the plain bottle, for a potion
	 * with no effect. Inferred: the game derives the variant from the potion it is sent, the array names effect
	 * textures, and the pack draws index 30 without the glint, as Bedrock draws the effectless potions.
	 */
	private static double potionVariant(PotionContents contents) {
		if (contents.potion().flatMap(Holder::unwrapKey).map(k -> k.identifier().getPath().endsWith("turtle_master")).orElse(false)) {
			return POTION_TEXTURES.indexOf("turtle_master");
		}
		for (MobEffectInstance effect : contents.getAllEffects()) {
			int i = POTION_TEXTURES.indexOf(effect.getEffect().unwrapKey().map(k -> k.identifier().getPath()).orElse(""));
			return i < 0 ? POTION_TEXTURES.size() : i;
		}
		return POTION_TEXTURES.size();
	}

	/** The Java effects of the potion texture array's entries, by index (moveSlowdown, moveSpeed, digSlowdown...); 30 is the plain bottle. */
	private static final java.util.List<String> POTION_TEXTURES = java.util.List.of("slowness", "speed", "mining_fatigue", "haste", "strength",
		"instant_health", "instant_damage", "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility",
		"blindness", "night_vision", "hunger", "weakness", "poison", "wither", "health_boost", "absorption", "saturation", "levitation", "turtle_master",
		"slow_falling", "wind_charged", "weaving", "oozing", "infested");

	/** Bedrock's cat variants, by index. */
	private static final java.util.List<String> CAT_VARIANTS = java.util.List.of("white", "black", "red", "siamese", "british_shorthair", "calico",
		"persian", "ragdoll", "tabby", "all_black", "jellie");

	/** The name of the first of the variant components the entity has, as Bedrock names its properties' values. */
	@SafeVarargs
	private static String variantName(Entity e, DataComponentType<? extends Holder<?>>... types) {
		for (DataComponentType<? extends Holder<?>> type : types) {
			Holder<?> h = e.get(type);
			if (h != null) {
				return h.unwrapKey().map(k -> k.identifier().getPath()).orElse(null);
			}
		}
		return null;
	}

	/** Java's {@code classic} sounds are Bedrock's {@code default}. */
	private static String soundVariant(Entity e) {
		String name = variantName(e, DataComponents.CHICKEN_SOUND_VARIANT, DataComponents.COW_SOUND_VARIANT, DataComponents.PIG_SOUND_VARIANT,
			DataComponents.CAT_SOUND_VARIANT, DataComponents.WOLF_SOUND_VARIANT);
		return "classic".equals(name) ? "default" : name;
	}

	private static double sq(double v) {
		return v * v;
	}
}
