package xyz.ivyhq.lanature.client.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.KineticWeapon;
import xyz.ivyhq.lanature.client.molang.EntityQueries;
import xyz.ivyhq.lanature.molang.Functions;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * The third-person spear variables Bedrock's engine sets on the player (its client entity declares them, and the
 * vanilla humanoid spear animations and the spear attachable read them, but no file assigns them for the player). The
 * vanilla zombie, husk, zombie villager, piglin and zombified piglin compute the same names in their own
 * {@code pre_animation} from their spear's kinetic timings and how long it has been used; this applies that computation
 * to the player (inferred: the engine's own is not published). The first-person and attack variables have no published computation and stay unset.
 */
final class SpearVariables {
	/** The arm's angle while a spear is held, as the mobs' {@code initialize} sets it. */
	private static final double BASE_ARM_ROTATION_X = -30;

	private SpearVariables() {
	}

	/** Sets the variables for an entity holding a kinetic weapon in its main hand, used for {@code useTicks} ticks. */
	static void fill(LivingEntity entity, double useTicks, Variables vars) {
		KineticWeapon spear = entity.getMainHandItem().get(DataComponents.KINETIC_WEAPON);
		if (spear == null) {
			return;
		}
		vars.set("tp_melee_spear_base_arm_rotation_x", BASE_ARM_ROTATION_X);
		double raiseEnd = spear.delayTicks();
		double swayEnd = raiseEnd + EntityQueries.kineticDuration(spear.dismountConditions());
		double lowerEnd = raiseEnd + EntityQueries.kineticDuration(spear.knockbackConditions());
		double raiseBackEnd = raiseEnd + EntityQueries.kineticDuration(spear.damageConditions());
		double raise = progress(0, raiseEnd, useTicks);
		double raiseStart = progress(0, 0.5, raise);
		double raiseMiddle = progress(0.5, 0.8, raise);
		double raiseFinish = progress(0.8, 1, raise);
		double sway = progress(swayEnd - 20, swayEnd, useTicks);
		double lower = Functions.ease("ease_in_out_elastic", 0, 1, progress(lowerEnd - 40, lowerEnd, useTicks - 20));
		double raiseBack = progress(raiseBackEnd - 5, raiseBackEnd, useTicks);
		double swayIntensity = Functions.ease("ease_out_circ", 0, 2, sway) + Functions.ease("ease_in_circ", 0, -2, raiseBack);
		// Molang's sine takes degrees
		double swaySlow = Math.sin(Math.toRadians(useTicks * 19)) * swayIntensity;
		double swayFast = Math.sin(Math.toRadians(useTicks * 30)) * swayIntensity;
		vars.set("tp_melee_spear_use_arm_rotation_x",
			raiseStart * -40 + raiseMiddle * 30 + raiseFinish * -20 + lower * 20 + raiseBack * 10 + swaySlow * 0.6);
		vars.set("tp_melee_spear_use_arm_rotation_y", swayFast);
		vars.set("tp_melee_spear_use_arm_rotation_z", swaySlow * 0.5);
		vars.set("tp_melee_spear_use_item_rotation_x", raise * 60 + raiseBack * -60);
		vars.set("tp_melee_spear_use_attachable_rotation_z", raise * 90 + sway * -90);
	}

	/** {@code math.clamp(math.inverse_lerp(from, to, x), 0, 1)}, 0 for an empty range as Molang's. */
	private static double progress(double from, double to, double x) {
		return to == from ? 0 : Mth.clamp((x - from) / (to - from), 0, 1);
	}
}
