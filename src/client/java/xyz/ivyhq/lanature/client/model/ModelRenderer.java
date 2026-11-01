package xyz.ivyhq.lanature.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a modelled entity's {@link ModelState}: each part's visible bones, grouped by material into one submission
 * per material and texture. Parts draw in their render controllers' order, each over the ones before (Bedrock layers
 * eyes, cracks and overlays as later passes over the same geometry). The pose stack is the model's frame (the entity's position, turned to its body yaw, the
 * model facing -Z); the faces are quads in the order Java's entity shader expects (counter-clockwise from outside).
 */
public final class ModelRenderer {
	/** A render state's model, set when the entity is modelled. */
	public static final RenderStateDataKey<ModelState> MODEL = RenderStateDataKey.create(() -> "lanature:bedrock_model");

	private ModelRenderer() {
	}

	/** Submits the model in the pose stack's frame, at its scale, without an outline. */
	public static void submit(ModelState model, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int color) {
		submit(model, poseStack, collector, light, overlay, color, 0);
	}

	/**
	 * Submits the model in the pose stack's frame, at its scale, and, when {@code outlineColor} is not 0 (a glowing
	 * entity), its parts again in their materials' outline types in that colour, as Java's {@code submitModel} does.
	 */
	public static void submit(ModelState model, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int color, int outlineColor) {
		poseStack.pushPose();
		float s = model.scale();
		poseStack.scale(s, s, s);
		for (int p = 0; p < model.parts().size(); p++) {
			ModelState.Part part = model.parts().get(p);
			RenderController.Draw draw = part.draw();
			Map<EntityMaterial, List<Integer>> byMaterial = new IdentityHashMap<>();
			for (int b = 0; b < draw.boneMaterials().length; b++) {
				EntityMaterial m = draw.boneMaterials()[b];
				if (m != null && !draw.geometry().bones().get(b).faces().isEmpty()) {
					byMaterial.computeIfAbsent(m, k -> new ArrayList<>()).add(b);
				}
			}
			int partLight = draw.ignoreLighting() ? LightCoordsUtil.FULL_BRIGHT : light;
			OrderedSubmitNodeCollector layer = collector.order(p);
			byMaterial.forEach((material, bones) -> layer.submitCustomGeometry(poseStack, material.renderType(draw.texture()),
				(pose, buffer) -> {
					int overlayCoords = material.overlayCoords(overlay, draw.overlayColor());
					int vertexColor = material.vertexColor(color, draw.changeColor());
					for (int b : bones) {
						emit(draw, material, draw.geometry().bones().get(b), new Matrix4f(pose.pose()).mul(part.bones()[b]), buffer, partLight,
							overlayCoords, vertexColor);
					}
				}));
			if (outlineColor != 0) {
				byMaterial.forEach((material, bones) -> {
					RenderType renderType = material.renderType(draw.texture());
					RenderType outline = renderType.isOutline() ? renderType : renderType.outline().orElse(null);
					if (outline != null) {
						layer.submitCustomGeometry(poseStack, outline, (pose, buffer) -> {
							for (int b : bones) {
								emit(draw, material, draw.geometry().bones().get(b), new Matrix4f(pose.pose()).mul(part.bones()[b]), buffer,
									LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, outlineColor);
							}
						});
					}
				});
			}
		}
		poseStack.popPose();
	}

	private static void emit(RenderController.Draw draw, EntityMaterial material, Geometry.Bone bone, Matrix4f m, VertexConsumer buffer, int light,
		int overlay, int color) {
		Matrix3f normals = new Matrix3f();
		m.normal(normals);
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (Geometry.Face f : bone.faces()) {
			normals.transform(f.normal(), n).normalize();
			// top left, bottom left, bottom right, top right: counter-clockwise seen from outside
			for (int k : CORNERS) {
				m.transformPosition(f.xyz()[k * 3], f.xyz()[k * 3 + 1], f.xyz()[k * 3 + 2], p);
				buffer.addVertex(p.x, p.y, p.z, color, draw.uv(material, 0, f.uv()[k * 2]), draw.uv(material, 1, f.uv()[k * 2 + 1]), overlay, light, n.x, n.y,
					n.z);
			}
		}
	}

	/** Face corners are stored top left, top right, bottom left, bottom right. */
	private static final int[] CORNERS = {0, 2, 3, 1};
}
