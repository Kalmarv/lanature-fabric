package xyz.ivyhq.lanature.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws a held item's attachable model in its holder's hand, where Bedrock binds it: an attachable's root bones bind
 * to the holder's {@code rightitem} or {@code leftitem} bone ({@code q.item_slot_to_bone_name(c.item_slot)}), which
 * takes them to that bone's pivot, 24 pixels lower (the attachable's own animations raise the item back into the
 * hand). The humanoid geometries place both item bones one pixel out from the arm, 7 below the shoulder and one
 * forward ({@code rightitem} (-6, 15, 1) on the right arm's (-5, 22, 0), the slim arms' half a pixel lower with their
 * shoulders), so in a Java humanoid's arm frame, whose shoulder is the same as Bedrock's, the model's origin is that
 * offset from the shoulder.
 */
public final class AttachableRenderer {
	/** The attachable models a holder's render state carries, for the hand each is held in. */
	public static final RenderStateDataKey<ModelState> RIGHT_HAND = RenderStateDataKey.create(() -> "lanature:attachable_right_hand");
	public static final RenderStateDataKey<ModelState> LEFT_HAND = RenderStateDataKey.create(() -> "lanature:attachable_left_hand");

	/** The player's size in Java and Bedrock alike ({@code AvatarRenderer}'s scale, the client entity's {@code scripts.scale}). */
	private static final float PLAYER_SCALE = 0.9375F;

	/**
	 * Player models of the port's own for first-person arms, wide and slim, baked from the model set they were made
	 * with: Java's first-person hand code moves the arms of its renderer's model, which a submitted part is drawn with
	 * later.
	 */
	private static @Nullable EntityModelSet bakedFrom;
	private static @Nullable PlayerModel wide;
	private static @Nullable PlayerModel slim;

	private AttachableRenderer() {
	}

	public static RenderStateDataKey<ModelState> key(HumanoidArm arm) {
		return arm == HumanoidArm.RIGHT ? RIGHT_HAND : LEFT_HAND;
	}

	/**
	 * Submits an attachable in a humanoid's hand; the pose stack is the arm's frame (Java model axes, Y down, the
	 * shoulder at the origin, the arm's rotation applied), as {@code translateToHand} leaves it.
	 */
	public static void submitInHand(ModelState model, HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		poseStack.pushPose();
		// to the geometry's axes (X mirrored from Bedrock's, Y up), then to the item bone's pivot less 24 pixels
		poseStack.scale(-1, -1, 1);
		poseStack.translate((arm == HumanoidArm.RIGHT ? 1 : -1) / 16F, -31 / 16F, 1 / 16F);
		ModelRenderer.submit(model, poseStack, collector, light, OverlayTexture.NO_OVERLAY, -1);
		poseStack.popPose();
	}

	/**
	 * Submits a first-person hand the way Bedrock draws it: the player model where it stands, seen from the eye, with
	 * only the arm and sleeve of {@code arm} drawn (those the pose's first-person render controller shows), and the
	 * attachable in its hand when it holds one. The pose stack is the hand pass's, in view space.
	 */
	public static void submitFirstPerson(AvatarRenderState avatar, @Nullable ModelState attachable, HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector collector,
		int light) {
		Minecraft mc = Minecraft.getInstance();
		PlayerModel model = firstPersonModel(mc, avatar.skin.model() == PlayerModelType.SLIM);
		model.setupAnim(avatar);
		ModelPart armPart = model.getArm(arm);
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		poseStack.pushPose();
		// the model where LivingEntityRenderer puts it, with the body's pivot at the eye, then turned a half turn about
		// the view's vertical: Bedrock's first-person base pose pitches and turns the body, and the arms on it, with the
		// view about that pivot, which holds the arms still in view, and its arm poses put them behind the body (behind
		// the eye), on the far side; the half turn about the view's vertical brings them before the eye on their own side
		// and keeps them still in view as it pitches, where one about the world's would turn the pitch the other way
		poseStack.rotateDegrees(Axis.YP, 180);
		poseStack.rotate(new Quaternionf(camera.rotation()).conjugate());
		poseStack.translate(avatar.x - eye.x, 0, avatar.z - eye.z);
		poseStack.scale(avatar.scale, avatar.scale, avatar.scale);
		poseStack.rotateDegrees(Axis.YP, 180 - avatar.bodyRot);
		poseStack.scale(-1, -1, 1);
		poseStack.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
		// Java's model space has its origin at the body's pivot
		model.root().translateAndRotate(poseStack);
		if (armPart.visible) {
			collector.submitModelPart(armPart, poseStack, RenderTypes.entityTranslucent(avatar.skin.body().texturePath()), light, OverlayTexture.NO_OVERLAY,
				null);
		}
		if (attachable != null) {
			armPart.translateAndRotate(poseStack);
			submitInHand(attachable, arm, poseStack, collector, light);
		}
		poseStack.popPose();
	}

	private static PlayerModel firstPersonModel(Minecraft mc, boolean slimArms) {
		EntityModelSet models = mc.getEntityModels();
		if (models != bakedFrom) {
			bakedFrom = models;
			wide = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
			slim = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
		}
		return slimArms ? slim : wide;
	}
}
