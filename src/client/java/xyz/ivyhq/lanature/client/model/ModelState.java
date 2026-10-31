package xyz.ivyhq.lanature.client.model;

import java.util.List;
import org.joml.Matrix4f;

/**
 * A modelled entity's look for one rendered frame, carried in its render state: what each render controller draws
 * with the pose of its geometry's bones, the entity's scale, and its body yaw (what turns the model of an entity whose
 * Java renderer does not turn it).
 */
public record ModelState(List<Part> parts, float scale, float bodyYaw) {
	/** One render controller's draw; {@code bones} maps each bone's geometry pixels to blocks in the model's frame. */
	public record Part(RenderController.Draw draw, Matrix4f[] bones) {
	}
}
