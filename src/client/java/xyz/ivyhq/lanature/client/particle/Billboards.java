package xyz.ivyhq.lanature.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import xyz.ivyhq.lanature.molang.Scope;

/**
 * Turns particles into camera-relative quads each frame: per-render expressions, size (Bedrock gives half extents),
 * facing, UV or flipbook frame, tint and light.
 */
final class Billboards {
	private Frustum frustum;
	private float partial;
	private ClientLevel level;
	private double camX;
	private double camY;
	private double camZ;
	private final Vector3f camRight = new Vector3f();
	private final Vector3f camUp = new Vector3f();
	private final Vector3f right = new Vector3f();
	private final Vector3f up = new Vector3f();
	private final Vector3f n = new Vector3f();
	private final Vector3f dir = new Vector3f();
	private final Quaternionf q = new Quaternionf();
	private final float[] corners = new float[12];
	private final double[] v2 = new double[2];
	private final double[] v3 = new double[3];
	private final double[] v4 = new double[4];

	void begin(Camera camera, Frustum frustum, float partial, ClientLevel level) {
		this.frustum = frustum;
		this.partial = partial;
		this.level = level;
		Vec3 pos = camera.position();
		camX = pos.x;
		camY = pos.y;
		camZ = pos.z;
		camRight.set(camera.leftVector()).negate();
		camUp.set(camera.upVector());
	}

	void render(BedrockParticle p, TextureAtlasSprite sprite, BedrockParticleRenderState out) {
		Emitter e = p.emitter;
		ParticleEffect fx = e.fx;
		double wx = p.prevX + (p.x - p.prevX) * partial;
		double wy = p.prevY + (p.y - p.prevY) * partial;
		double wz = p.prevZ + (p.z - p.prevZ) * partial;
		if (fx.localPosition) {
			if (fx.localRotation) {
				Vector3f r = e.rotation.transform(new Vector3f((float) wx, (float) wy, (float) wz));
				wx = r.x;
				wy = r.y;
				wz = r.z;
			}
			wx += e.prevX + (e.x - e.prevX) * partial;
			wy += e.prevY + (e.y - e.prevY) * partial;
			wz += e.prevZ + (e.z - e.prevZ) * partial;
		}
		if (!frustum.pointInFrustum(wx, wy, wz)) {
			return;
		}
		double age = Math.max(0, p.age - ParticleSystem.DT * (1 - partial));
		Scope s = e.particleScope(p, age);
		e.curves(s);
		Expr.run(fx.particlePerRender, s);
		Expr.eval(fx.size, s, v2);
		float w = (float) v2[0];
		float h = (float) v2[1];
		if (w == 0 && h == 0) {
			return;
		}
		float x = (float) (wx - camX);
		float y = (float) (wy - camY);
		float z = (float) (wz - camZ);
		basis(fx, p, e, s, x, y, z);
		double roll = Math.toRadians(p.prevRotation + (p.rotation - p.prevRotation) * partial);
		if (roll != 0) {
			float c = (float) Math.cos(roll);
			float sn = (float) Math.sin(roll);
			float rx = right.x * c + up.x * sn;
			float ry = right.y * c + up.y * sn;
			float rz = right.z * c + up.z * sn;
			up.set(up.x * c - right.x * sn, up.y * c - right.y * sn, up.z * c - right.z * sn);
			right.set(rx, ry, rz);
		}
		corner(0, x, y, z, -w, h);
		corner(3, x, y, z, w, h);
		corner(6, x, y, z, w, -h);
		corner(9, x, y, z, -w, -h);

		// UV, in the texture's own units
		double u;
		double v;
		double uw;
		double vh;
		if (fx.flipbook != null) {
			ParticleEffect.Flipbook f = fx.flipbook;
			int maxFrame = Math.max(1, (int) Expr.eval(f.maxFrame(), s));
			int frame = f.stretch() ? (int) Math.floor(age / p.lifetime * maxFrame) : (int) Math.floor(age * Expr.eval(f.fps(), s));
			frame = f.loop() ? Math.floorMod(frame, maxFrame) : Math.clamp(frame, 0, maxFrame - 1);
			Expr.eval(f.base(), s, v2);
			u = v2[0] + f.step()[0] * frame;
			v = v2[1] + f.step()[1] * frame;
			uw = f.size()[0];
			vh = f.size()[1];
		} else {
			Expr.eval(fx.uv, s, v2);
			u = v2[0];
			v = v2[1];
			Expr.eval(fx.uvSize, s, v2);
			uw = v2[0];
			vh = v2[1];
		}
		float u0 = sprite.getU((float) Math.clamp(u / fx.textureWidth, 0, 1));
		float u1 = sprite.getU((float) Math.clamp((u + uw) / fx.textureWidth, 0, 1));
		float v0 = sprite.getV((float) Math.clamp(v / fx.textureHeight, 0, 1));
		float v1 = sprite.getV((float) Math.clamp((v + vh) / fx.textureHeight, 0, 1));

		int color = color(fx, s);
		if (ARGB.alpha(color) == 0) {
			return;
		}
		int light = fx.lighting ? LightCoordsUtil.getLightCoords(level, BlockPos.containing(wx, wy, wz)) : LightCoordsUtil.FULL_BRIGHT;
		out.add(fx.material.layer(), corners, u0, v0, u1, v1, color, light);
	}

	private void corner(int i, float x, float y, float z, float a, float b) {
		corners[i] = x + right.x * a + up.x * b;
		corners[i + 1] = y + right.y * a + up.y * b;
		corners[i + 2] = z + right.z * a + up.z * b;
	}

	/** The quad's right and up axes for the effect's facing mode, before the particle's own rotation. */
	private void basis(ParticleEffect fx, BedrockParticle p, Emitter e, Scope s, float x, float y, float z) {
		switch (fx.facing) {
			case ROTATE_XYZ -> {
				right.set(camRight);
				up.set(camUp);
			}
			case ROTATE_Y -> {
				right.set(camRight.x, 0, camRight.z);
				if (right.lengthSquared() < 1e-8f) {
					right.set(1, 0, 0);
				}
				right.normalize();
				up.set(0, 1, 0);
			}
			case LOOKAT_XYZ -> {
				n.set(-x, -y, -z);
				if (n.lengthSquared() < 1e-8f) {
					right.set(camRight);
					up.set(camUp);
					return;
				}
				n.normalize();
				right.set(0, 1, 0).cross(n);
				if (right.lengthSquared() < 1e-8f) {
					right.set(camRight);
				}
				right.normalize();
				up.set(n).cross(right).normalize();
			}
			case LOOKAT_Y -> {
				n.set(-x, 0, -z);
				if (n.lengthSquared() < 1e-8f) {
					n.set(camRight.z, 0, -camRight.x);
				}
				n.normalize();
				right.set(0, 1, 0).cross(n).normalize();
				up.set(0, 1, 0);
			}
			case LOOKAT_DIRECTION -> {
				direction(fx, p, s);
				n.set(-x, -y, -z);
				right.set(dir);
				up.set(n).cross(right);
				if (up.lengthSquared() < 1e-8f) {
					up.set(camUp);
				}
				up.normalize();
			}
			case DIRECTION_X -> {
				direction(fx, p, s);
				q.rotationTo(1, 0, 0, dir.x, dir.y, dir.z);
				right.set(dir);
				q.transform(up.set(0, 1, 0));
			}
			case DIRECTION_Y -> {
				direction(fx, p, s);
				q.rotationTo(0, 1, 0, dir.x, dir.y, dir.z);
				q.transform(right.set(1, 0, 0));
				up.set(dir);
			}
			case DIRECTION_Z -> {
				direction(fx, p, s);
				q.rotationTo(0, 0, 1, dir.x, dir.y, dir.z);
				q.transform(right.set(1, 0, 0));
				q.transform(up.set(0, 1, 0));
			}
			case EMITTER_TRANSFORM_XY -> {
				e.rotation.transform(right.set(1, 0, 0));
				e.rotation.transform(up.set(0, 1, 0));
			}
			case EMITTER_TRANSFORM_XZ -> {
				e.rotation.transform(right.set(1, 0, 0));
				e.rotation.transform(up.set(0, 0, -1));
			}
			case EMITTER_TRANSFORM_YZ -> {
				e.rotation.transform(right.set(0, 0, -1));
				e.rotation.transform(up.set(0, 1, 0));
			}
		}
	}

	/** The billboard direction: the custom expression, or the velocity while it is above the threshold. */
	private void direction(ParticleEffect fx, BedrockParticle p, Scope s) {
		if (fx.customDirection != null) {
			Expr.eval(fx.customDirection, s, v3);
			dir.set((float) v3[0], (float) v3[1], (float) v3[2]);
		} else {
			double vx = p.velocityX();
			double vy = p.velocityY();
			double vz = p.velocityZ();
			double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
			if (speed > fx.minSpeed) {
				p.dirX = (float) (vx / speed);
				p.dirY = (float) (vy / speed);
				p.dirZ = (float) (vz / speed);
			}
			dir.set(p.dirX, p.dirY, p.dirZ);
		}
		if (dir.lengthSquared() < 1e-10f) {
			dir.set(0, 1, 0);
		}
		dir.normalize();
	}

	private int color(ParticleEffect fx, Scope s) {
		if (fx.tint != null) {
			Expr.eval(fx.tint, s, v4);
		} else if (fx.gradientInterpolant != null) {
			double t = Expr.eval(fx.gradientInterpolant, s);
			double[] keys = fx.gradientKeys;
			int hi = 0;
			while (hi < keys.length && keys[hi] < t) {
				hi++;
			}
			if (hi == 0 || hi == keys.length) {
				Expr.eval(fx.gradientColors[hi == 0 ? 0 : keys.length - 1], s, v4);
			} else {
				double f = (t - keys[hi - 1]) / (keys[hi] - keys[hi - 1]);
				double[] a = new double[4];
				Expr.eval(fx.gradientColors[hi - 1], s, a);
				Expr.eval(fx.gradientColors[hi], s, v4);
				for (int i = 0; i < 4; i++) {
					v4[i] = a[i] + (v4[i] - a[i]) * f;
				}
			}
		} else {
			return 0xFFFFFFFF;
		}
		return ARGB.colorFromFloat((float) Math.clamp(v4[3], 0, 1), (float) Math.clamp(v4[0], 0, 1),
			(float) Math.clamp(v4[1], 0, 1), (float) Math.clamp(v4[2], 0, 1));
	}
}
