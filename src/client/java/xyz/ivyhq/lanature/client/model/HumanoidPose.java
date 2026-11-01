package xyz.ivyhq.lanature.client.model;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A Bedrock humanoid geometry's pose carried over to Java's humanoid model: each Java part takes the pose of the
 * bone of the same name, relative to its Java parent. Java's humanoid parts and Bedrock's player geometry have the
 * same pivots and cubes, a Java model point being the Bedrock model point {@code (x, 24 - y, z)}, but not the same
 * hierarchy (Bedrock's arms and head hang from the body, under a waist and a root; Java's hang from the model root),
 * so each part's pose is its bone's whole transform made relative to its Java parent's.
 */
public final class HumanoidPose {
	/** A humanoid entity's render state's Bedrock pose, set when its Java model takes one. */
	public static final RenderStateDataKey<HumanoidPose> KEY = RenderStateDataKey.create(() -> "lanature:humanoid_pose");

	/** Java's humanoid parts: the bone each takes its pose from, its Java parent (null: the model root), and the part. */
	private enum Part {
		HEAD("head", null, m -> m.head),
		HAT("hat", HEAD, m -> m.hat),
		BODY("body", null, m -> m.body),
		RIGHT_ARM("rightArm", null, m -> m.rightArm),
		LEFT_ARM("leftArm", null, m -> m.leftArm),
		RIGHT_LEG("rightLeg", null, m -> m.rightLeg),
		LEFT_LEG("leftLeg", null, m -> m.leftLeg),
		JACKET("jacket", BODY, m -> m instanceof PlayerModel p ? p.jacket : null),
		RIGHT_SLEEVE("rightSleeve", RIGHT_ARM, m -> m instanceof PlayerModel p ? p.rightSleeve : null),
		LEFT_SLEEVE("leftSleeve", LEFT_ARM, m -> m instanceof PlayerModel p ? p.leftSleeve : null),
		RIGHT_PANTS("rightPants", RIGHT_LEG, m -> m instanceof PlayerModel p ? p.rightPants : null),
		LEFT_PANTS("leftPants", LEFT_LEG, m -> m instanceof PlayerModel p ? p.leftPants : null);

		final String bone;
		final @Nullable Part parent;
		final Function<HumanoidModel<?>, @Nullable ModelPart> part;

		Part(String bone, @Nullable Part parent, Function<HumanoidModel<?>, @Nullable ModelPart> part) {
			this.bone = bone;
			this.parent = parent;
			this.part = part;
		}
	}

	/** The bone every other hangs from, which places the whole body. */
	private static final String ROOT = "root";

	/** Each part's pose, null for a part whose bone the geometry lacks. */
	private final @Nullable PartPose[] poses;
	/** Whether each part's bone is drawn (its render controllers' {@code part_visibility}). */
	private final boolean[] shown;

	private HumanoidPose(@Nullable PartPose[] poses, boolean[] shown) {
		this.poses = poses;
		this.shown = shown;
	}

	/**
	 * The pose of a humanoid geometry's bones ({@link Skeleton#matrices}) as Java part poses, with the bones the render
	 * controllers draw ({@code shownBones}, lower-case names). With {@code bodyPlacedByJava} (Java's renderer turns and
	 * moves the whole body itself: swimming, crawling, sleeping, gliding) the {@code root} bone's animation is taken
	 * out, every bone posed as if the root stood at rest: the animations that move it there (vanilla Bedrock's swim,
	 * crawl and sleep, which the pack keeps) place the body for Bedrock's engine, and would turn it a second time.
	 * Otherwise (sneaking leans the body by it, as Java leans its model) the root's pose is kept.
	 */
	public static HumanoidPose of(Geometry geometry, Matrix4f[] bones, java.util.Set<String> shownBones, boolean bodyPlacedByJava) {
		Part[] parts = Part.values();
		int root = geometry.bone(ROOT);
		Matrix4f base = new Matrix4f();
		if (bodyPlacedByJava && root >= 0 && geometry.bones().get(root).parent() < 0) {
			base = Skeleton.restMatrix(geometry.bones().get(root)).mul(new Matrix4f(bones[root]).invert());
		}
		// each bone's whole transform in Java model space: part-local pixels (from the pivot) to model pixels
		Matrix4f[] whole = new Matrix4f[parts.length];
		for (Part p : parts) {
			int b = geometry.bone(p.bone);
			whole[p.ordinal()] = b < 0 ? null : javaTransform(geometry.bones().get(b).pivot(), new Matrix4f(base).mul(bones[b]));
		}
		PartPose[] poses = new PartPose[parts.length];
		boolean[] shown = new boolean[parts.length];
		for (Part p : parts) {
			shown[p.ordinal()] = shownBones.contains(p.bone.toLowerCase(java.util.Locale.ROOT));
			Matrix4f m = whole[p.ordinal()];
			Matrix4f parent = p.parent == null ? new Matrix4f() : whole[p.parent.ordinal()];
			if (m != null && parent != null) {
				poses[p.ordinal()] = partPose(new Matrix4f(parent).invert().mul(m));
			}
		}
		return new HumanoidPose(poses, shown);
	}

	/**
	 * A bone's transform as a Java part's: a point {@code q} in the part's space (Java axes, pixels from the pivot) is
	 * the geometry point {@code pivot + D q}, which the bone moves to {@code bone (pivot + D q)} in blocks, Java model
	 * point {@code D (16 bone (pivot + D q)) + (0, 24, 0)}; {@code D} mirrors X and Y.
	 */
	private static Matrix4f javaTransform(Vector3f pivot, Matrix4f bone) {
		return new Matrix4f().translate(0, 24, 0).scale(-1, -1, 1).scale(16).mul(bone).translate(pivot).scale(-1, -1, 1);
	}

	/** A part-local transform as Java's offset, rotation (Z·Y·X, as {@link ModelPart} applies it) and scale. */
	private static PartPose partPose(Matrix4f local) {
		Vector3f offset = local.getTranslation(new Vector3f());
		Matrix3f linear = local.get3x3(new Matrix3f());
		Vector3f scale = linear.getScale(new Vector3f());
		Matrix3f rotation = new Matrix3f(linear).scale(1 / scale.x, 1 / scale.y, 1 / scale.z);
		Vector3f angles = rotation.getEulerAnglesZYX(new Vector3f());
		return new PartPose(offset.x, offset.y, offset.z, angles.x, angles.y, angles.z, scale.x, scale.y, scale.z);
	}

	/** Each part's offset in pixels, rotation in degrees and scale, for diagnostics. */
	@Override
	public String toString() {
		StringBuilder out = new StringBuilder();
		for (Part p : Part.values()) {
			PartPose q = poses[p.ordinal()];
			if (q != null) {
				out.append(String.format(java.util.Locale.ROOT, "%n  %s at (%.2f, %.2f, %.2f) rot (%.1f, %.1f, %.1f) scale (%.2f, %.2f, %.2f)", p.bone, q.x(), q.y(),
					q.z(), Math.toDegrees(q.xRot()), Math.toDegrees(q.yRot()), Math.toDegrees(q.zRot()), q.xScale(), q.yScale(), q.zScale()));
			}
		}
		return out.toString();
	}

	/**
	 * Poses a humanoid model's parts (those it has: Java's armour models have no sleeves), and hides those whose bones
	 * the render controllers do not draw; what Java hides (a skin's layers left off) stays hidden.
	 */
	public void apply(HumanoidModel<?> model) {
		for (Part p : Part.values()) {
			PartPose pose = poses[p.ordinal()];
			ModelPart part = p.part.apply(model);
			if (pose != null && part != null) {
				part.loadPose(pose);
				part.visible &= shown[p.ordinal()];
			}
		}
	}
}
