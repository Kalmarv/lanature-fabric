package xyz.ivyhq.lanature.client.molang;

import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
import xyz.ivyhq.lanature.client.bedrock.BedrockResources;
import xyz.ivyhq.lanature.client.particle.BedrockParticle;
import xyz.ivyhq.lanature.client.particle.Emitter;
import xyz.ivyhq.lanature.client.particle.ParticleSystem;
import xyz.ivyhq.lanature.client.settings.PackSettings;
import xyz.ivyhq.lanature.molang.Functions;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;

/**
 * The Bedrock queries the pack calls, bound to the Java client. A query acts on the scope's host: an entity, an
 * emitter, or a particle (whose entity queries go to the entity its emitter follows).
 */
public final class ClientQueries {
	private static final double[] MOON_BRIGHTNESS = {1, 0.75, 0.5, 0.25, 0, 0.25, 0.5, 0.75};

	private ClientQueries() {
	}

	static String str(Scope s, Node[] a, int i) {
		if (i >= a.length) {
			return "";
		}
		Object v = a[i].value(s);
		return v instanceof String str ? str : String.valueOf(v);
	}

	static double num(Scope s, Node[] a, int i) {
		return i < a.length ? a[i].eval(s) : 0;
	}

	static double bool(boolean b) {
		return b ? 1 : 0;
	}

	/** The entity a host stands for: itself, or the entity its emitter follows. */
	public static Entity entity(Object host) {
		return switch (host) {
			case Entity e -> e;
			case EntityHost h -> h.entity();
			case BedrockParticle p -> p.emitter().entity();
			case Emitter e -> e.entity();
			case null, default -> null;
		};
	}

	static Vec3 position(Object host) {
		return switch (host) {
			case Entity e -> e.position();
			case EntityHost h -> h.entity().position();
			case BedrockParticle p -> new Vec3(p.worldX(), p.worldY(), p.worldZ());
			case Emitter e -> new Vec3(e.worldX(), e.worldY(), e.worldZ());
			case null, default -> Vec3.ZERO;
		};
	}

	static ClientLevel level() {
		return Minecraft.getInstance().level;
	}

	static Camera camera() {
		return Minecraft.getInstance().gameRenderer.mainCamera();
	}

	private static String effectId(Object host) {
		return switch (host) {
			case BedrockParticle p -> p.emitter().effectId();
			case Emitter e -> e.effectId();
			case null, default -> "";
		};
	}

	public static void register() {
		// pack settings
		Functions.registerNumericQuery("get_pack_setting", (s, a) -> PackSettings.get().number(str(s, a, 0)));
		Functions.registerNumericQuery("is_pack_setting_enabled", (s, a) -> bool(PackSettings.get().enabled(str(s, a, 0))));
		Functions.registerNumericQuery("is_pack_setting_selected", (s, a) -> bool(PackSettings.get().selected(str(s, a, 0), str(s, a, 1))));

		// time and world
		Functions.registerNumericQuery("time_of_day", (s, a) -> {
			ClientLevel level = level();
			// Bedrock: midnight 0, sunrise 0.25, noon 0.5; Java's clock is 0 at sunrise
			return level == null ? 0 : Math.floorMod(level.getDefaultClockTime() + 6000, 24000) / 24000.0;
		});
		Functions.registerNumericQuery("day", (s, a) -> level() == null ? 0 : Math.floorDiv(level().getDefaultClockTime(), 24000));
		Functions.registerNumericQuery("time_stamp", (s, a) -> level() == null ? 0 : level().getGameTime());
		Functions.registerNumericQuery("moon_phase", (s, a) -> moonPhase().index());
		Functions.registerNumericQuery("moon_brightness", (s, a) -> MOON_BRIGHTNESS[moonPhase().index()]);
		Functions.registerNumericQuery("frame_alpha", (s, a) -> Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
		Functions.registerNumericQuery("delta_time", (s, a) -> Minecraft.getInstance().getDeltaTracker().getRealtimeDeltaTicks() / 20.0);
		// Java has no deferred (Vibrant Visuals) renderer; it draws like Bedrock's fancy mode
		Functions.registerNumericQuery("graphics_mode_is_any", (s, a) -> {
			for (int i = 0; i < a.length; i++) {
				if (str(s, a, i).equals("fancy")) {
					return 1;
				}
			}
			return 0;
		});

		// camera
		Functions.registerNumericQuery("distance_from_camera", (s, a) -> camera().position().distanceTo(position(s.host)));
		Functions.registerNumericQuery("camera_distance_range_lerp", (s, a) -> {
			double lo = num(s, a, 0);
			double hi = num(s, a, 1);
			double d = camera().position().distanceTo(position(s.host));
			return hi == lo ? (d >= hi ? 1 : 0) : Math.clamp((d - lo) / (hi - lo), 0, 1);
		});
		Functions.registerNumericQuery("camera_rotation", (s, a) -> num(s, a, 0) == 0 ? camera().xRot() : camera().yRot());
		Functions.registerNumericQuery("rotation_to_camera", (s, a) -> {
			Vec3 d = camera().position().subtract(position(s.host));
			if (num(s, a, 0) == 0) {
				return -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			}
			return Math.toDegrees(Math.atan2(-d.x, d.z));
		});
		Functions.registerNumericQuery("is_first_person", (s, a) -> bool(Minecraft.getInstance().options.getCameraType().isFirstPerson()));

		// the host's place
		Functions.registerNumericQuery("position", (s, a) -> {
			Vec3 p = position(s.host);
			int axis = (int) num(s, a, 0);
			return axis == 0 ? p.x : axis == 1 ? p.y : p.z;
		});
		Functions.registerNumericQuery("position_delta", (s, a) -> {
			Entity e = entity(s.host);
			if (e == null) {
				return 0;
			}
			int axis = (int) num(s, a, 0);
			return axis == 0 ? e.getX() - e.xo : axis == 1 ? e.getY() - e.yo : e.getZ() - e.zo;
		});

		// particle counts, per effect type
		Functions.registerNumericQuery("effect_particle_count", (s, a) -> ParticleSystem.get().particleCount(effectId(s.host)));
		Functions.registerNumericQuery("effect_emitter_count", (s, a) -> ParticleSystem.get().emitterCount(effectId(s.host)));
		Functions.registerNumericQuery("total_particle_count", (s, a) -> ParticleSystem.get().totalParticles());
		Functions.registerNumericQuery("total_emitter_count", (s, a) -> ParticleSystem.get().totalEmitters());

		// biome at the entity (or the host's position)
		Functions.registerNumericQuery("entity_biome_has_any_tags", (s, a) -> {
			BedrockResources.BiomeInfo b = biome(s.host);
			if (b != null) {
				for (int i = 0; i < a.length; i++) {
					if (b.tags().contains(str(s, a, i))) {
						return 1;
					}
				}
			}
			return 0;
		});
		Functions.registerNumericQuery("entity_biome_has_any_identifier", (s, a) -> {
			BedrockResources.BiomeInfo b = biome(s.host);
			if (b != null) {
				for (int i = 0; i < a.length; i++) {
					if (b.bedrock().equals(str(s, a, i))) {
						return 1;
					}
				}
			}
			return 0;
		});

		EntityQueries.register();
	}

	private static MoonPhase moonPhase() {
		ClientLevel level = level();
		return level == null ? MoonPhase.FULL_MOON : level.environmentAttributes().getDimensionValue(EnvironmentAttributes.MOON_PHASE);
	}

	private static BedrockResources.BiomeInfo biome(Object host) {
		ClientLevel level = level();
		if (level == null) {
			return null;
		}
		Entity e = entity(host);
		Vec3 p = e != null ? e.position() : position(host);
		Holder<Biome> biome = level.getBiome(BlockPos.containing(p));
		return biome.unwrapKey().map(k -> BedrockResources.biomes().get(k.identifier().toString())).orElse(null);
	}

	/** {@code slot.weapon.mainhand} and the other Bedrock equipment slot names. */
	static ItemStack slot(Entity e, String slot) {
		if (!(e instanceof LivingEntity le)) {
			return ItemStack.EMPTY;
		}
		return switch (slot.toLowerCase(Locale.ROOT)) {
			case "slot.weapon.mainhand", "main_hand", "0" -> le.getItemInHand(InteractionHand.MAIN_HAND);
			case "slot.weapon.offhand", "off_hand", "1" -> le.getItemInHand(InteractionHand.OFF_HAND);
			case "slot.armor.head" -> le.getItemBySlot(EquipmentSlot.HEAD);
			case "slot.armor.chest" -> le.getItemBySlot(EquipmentSlot.CHEST);
			case "slot.armor.legs" -> le.getItemBySlot(EquipmentSlot.LEGS);
			case "slot.armor.feet" -> le.getItemBySlot(EquipmentSlot.FEET);
			case "slot.armor.body" -> le.getItemBySlot(EquipmentSlot.BODY);
			default -> ItemStack.EMPTY;
		};
	}

	static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
