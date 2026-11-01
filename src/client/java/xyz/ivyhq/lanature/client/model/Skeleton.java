package xyz.ivyhq.lanature.client.model;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import xyz.ivyhq.lanature.molang.Molang;
import xyz.ivyhq.lanature.molang.Scope;

/**
 * One geometry's bones posed by the animations playing on an entity. Animations apply in play order: each channel
 * adds its value, by the animation's weight, to the bone's rotation (from its rest rotation) and position, and scales
 * its scale; {@code this} in a channel is the component's value so far, so {@code "x - this"} sets it to x.
 * {@code override_previous_animation} first puts the bones it animates back to rest. Bind poses are baked into the
 * geometry's faces, so they are not part of {@code this}: {@code "-this"} leaves a bone in its bind pose.
 *
 * <p>Values are converted from Bedrock's axes to the geometry's (see {@link Geometry}): rotation {@code (x, y, z)} is
 * {@code (-x, -y, z)}, position {@code (-x, y, z)}. A bone's transform is its parent's, then a translation to its
 * pivot plus its position, its rotation (Z·Y·X), its scale, and the translation back from the pivot.
 */
public final class Skeleton {
	private final Geometry geometry;
	private final float[][] rotation;
	private final float[][] position;
	private final float[][] scale;

	public Skeleton(Geometry geometry) {
		this.geometry = geometry;
		int n = geometry.bones().size();
		rotation = new float[n][3];
		position = new float[n][3];
		scale = new float[n][3];
		for (int b = 0; b < n; b++) {
			rest(b);
		}
	}

	private void rest(int b) {
		Vector3f r = geometry.bones().get(b).rotation();
		rotation[b][0] = r.x;
		rotation[b][1] = r.y;
		rotation[b][2] = r.z;
		position[b][0] = position[b][1] = position[b][2] = 0;
		scale[b][0] = scale[b][1] = scale[b][2] = 1;
	}

	/** Applies an animation's channels at {@code time} by {@code weight}, evaluated in the scope (whose {@code q.anim_time} is that time). */
	public void apply(BoneAnimation animation, double time, double weight, Scope s) {
		if (animation.blendWeight() != null) {
			weight *= Molang.eval(animation.blendWeight(), s);
		}
		if (weight == 0) {
			return;
		}
		float w = (float) weight;
		float[] current = new float[3];
		float[] v = new float[3];
		for (BoneAnimation.Track t : animation.tracks()) {
			int b = geometry.bone(t.bone());
			if (b < 0) {
				continue;
			}
			if (animation.overridePrevious()) {
				rest(b);
			}
			if (t.rotation() != null) {
				float[] r = rotation[b];
				current[0] = -r[0];
				current[1] = -r[1];
				current[2] = r[2];
				t.rotation().evaluate(time, s, current, v);
				r[0] -= v[0] * w;
				r[1] -= v[1] * w;
				r[2] += v[2] * w;
			}
			if (t.position() != null) {
				float[] p = position[b];
				current[0] = -p[0];
				current[1] = p[1];
				current[2] = p[2];
				t.position().evaluate(time, s, current, v);
				p[0] -= v[0] * w;
				p[1] += v[1] * w;
				p[2] += v[2] * w;
			}
			if (t.scale() != null) {
				float[] sc = scale[b];
				System.arraycopy(sc, 0, current, 0, 3);
				t.scale().evaluate(time, s, current, v);
				for (int c = 0; c < 3; c++) {
					sc[c] *= 1 + (v[c] - 1) * w;
				}
			}
		}
	}

	/** Each bone's transform from geometry pixels to blocks in the model's frame, parents applied. */
	public Matrix4f[] matrices() {
		int n = geometry.bones().size();
		Matrix4f[] out = new Matrix4f[n];
		for (int b = 0; b < n; b++) {
			Geometry.Bone bone = geometry.bones().get(b);
			Matrix4f m = bone.parent() < 0 ? new Matrix4f().scaling(1 / 16F) : new Matrix4f(out[bone.parent()]);
			out[b] = local(m, bone.pivot(), position[b][0], position[b][1], position[b][2], new Vector3f(rotation[b][0], rotation[b][1], rotation[b][2]),
				scale[b][0], scale[b][1], scale[b][2]);
		}
		return out;
	}

	/** A bone of no parent at rest (its geometry rotation, nothing animated), as {@link #matrices} gives it. */
	public static Matrix4f restMatrix(Geometry.Bone bone) {
		return local(new Matrix4f().scaling(1 / 16F), bone.pivot(), 0, 0, 0, bone.rotation(), 1, 1, 1);
	}

	/** {@code m} followed by a bone's own transform: moved, then turned and scaled about its pivot. */
	private static Matrix4f local(Matrix4f m, Vector3f pivot, float x, float y, float z, Vector3f rotation, float sx, float sy, float sz) {
		m.translate(pivot.x + x, pivot.y + y, pivot.z + z);
		m.mul(new Matrix4f().set(Geometry.rotationMatrix(rotation, new Matrix3f())));
		m.scale(sx, sy, sz);
		return m.translate(-pivot.x, -pivot.y, -pivot.z);
	}
}
