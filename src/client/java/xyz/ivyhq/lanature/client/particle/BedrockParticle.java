package xyz.ivyhq.lanature.client.particle;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import xyz.ivyhq.lanature.molang.Scope;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * One Bedrock particle. Its position is relative to its emitter when the effect has local position space, otherwise in
 * world space. Velocities are in blocks per second, rotations in degrees.
 */
public final class BedrockParticle implements EffectSource {
	final Emitter emitter;
	final Variables vars;
	final double[] random = new double[4];
	double age;
	double lifetime;
	double x;
	double y;
	double z;
	double prevX;
	double prevY;
	double prevZ;
	double vx;
	double vy;
	double vz;
	double rotation;
	double prevRotation;
	double rotationRate;
	/** The last billboard direction derived from velocity, kept while the particle is too slow to have one. */
	float dirX;
	float dirY = 1;
	float dirZ;
	boolean expired;

	BedrockParticle(Emitter emitter) {
		this.emitter = emitter;
		this.vars = new Variables(emitter.vars);
		for (int i = 0; i < 4; i++) {
			random[i] = emitter.system.random.nextDouble();
		}
	}

	void update(double dt) {
		ParticleEffect fx = emitter.fx;
		prevX = x;
		prevY = y;
		prevZ = z;
		prevRotation = rotation;
		double prevAge = age;
		age += dt;
		Scope s = emitter.particleScope(this, age);
		emitter.curves(s);
		Expr.run(fx.particlePerUpdate, s);
		if (age >= lifetime || fx.particleExpiration != null && Expr.eval(fx.particleExpiration, s) != 0) {
			expire();
			return;
		}
		if (fx.expireInBlocks != null || fx.expireNotInBlocks != null) {
			BlockState state = emitter.system.level.getBlockState(BlockPos.containing(worldX(), worldY(), worldZ()));
			if (fx.expireInBlocks != null && fx.expireInBlocks.contains(state)
				|| fx.expireNotInBlocks != null && !fx.expireNotInBlocks.contains(state)) {
				expire();
				return;
			}
		}
		switch (fx.motion) {
			case DYNAMIC -> dynamic(fx, s, dt);
			case PARAMETRIC -> parametric(fx, s, dt);
			default -> {
			}
		}
		if (expired) {
			return;
		}
		if (fx.killPlane != null) {
			double[] k = fx.killPlane;
			double before = k[0] * (prevX - (fx.localPosition ? 0 : emitter.x)) + k[1] * (prevY - (fx.localPosition ? 0 : emitter.y))
				+ k[2] * (prevZ - (fx.localPosition ? 0 : emitter.z)) + k[3];
			double after = k[0] * (x - (fx.localPosition ? 0 : emitter.x)) + k[1] * (y - (fx.localPosition ? 0 : emitter.y))
				+ k[2] * (z - (fx.localPosition ? 0 : emitter.z)) + k[3];
			if (before * after < 0 || before != 0 && after == 0) {
				expire();
				return;
			}
		}
		if (!fx.particleTimeline.isEmpty() && age > prevAge) {
			for (Map.Entry<Double, String[]> e : fx.particleTimeline.subMap(prevAge, prevAge == 0, age, true).entrySet()) {
				fx.fire(e.getValue(), this, emitter.particleScope(this, age));
				if (expired) {
					return;
				}
			}
		}
	}

	private void dynamic(ParticleEffect fx, Scope s, double dt) {
		double[] acc = new double[3];
		Expr.eval(fx.acceleration, s, acc);
		double drag = Expr.eval(fx.drag, s);
		vx += (acc[0] - drag * vx) * dt;
		vy += (acc[1] - drag * vy) * dt;
		vz += (acc[2] - drag * vz) * dt;
		double rotAcc = Expr.eval(fx.rotationAcceleration, s);
		double rotDrag = Expr.eval(fx.rotationDrag, s);
		rotationRate += (rotAcc - rotDrag * rotationRate) * dt;
		rotation += rotationRate * dt;
		double mx = vx * dt;
		double my = vy * dt;
		double mz = vz * dt;
		if (fx.collision && Expr.eval(fx.collisionEnabled, s) != 0) {
			collide(fx, mx, my, mz, dt);
		} else {
			x += mx;
			y += my;
			z += mz;
		}
	}

	private void collide(ParticleEffect fx, double mx, double my, double mz, double dt) {
		double r = fx.collisionRadius;
		double wx = worldX();
		double wy = worldY();
		double wz = worldZ();
		AABB box = new AABB(wx - r, wy - r, wz - r, wx + r, wy + r, wz + r);
		Vec3 wanted = new Vec3(mx, my, mz);
		Vec3 moved = wanted.lengthSqr() > 1e-12 && wanted.lengthSqr() < 10000
			? Entity.collideBoundingBox(CollisionContext.empty(), wanted, box, emitter.system.level, List.of()) : wanted;
		x += moved.x;
		y += moved.y;
		z += moved.z;
		boolean hitX = Math.abs(moved.x - mx) > 1e-7;
		boolean hitY = Math.abs(moved.y - my) > 1e-7;
		boolean hitZ = Math.abs(moved.z - mz) > 1e-7;
		if (!hitX && !hitY && !hitZ) {
			return;
		}
		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (fx.expireOnContact) {
			fireCollisionEvents(fx, speed);
			expire();
			return;
		}
		if (hitX) {
			vx = -vx * fx.restitution;
		}
		if (hitY) {
			vy = -vy * fx.restitution;
		}
		if (hitZ) {
			vz = -vz * fx.restitution;
		}
		if (fx.collisionDrag > 0) {
			// friction while in contact: the speed along the surface drops by collision_drag blocks/s every second
			double tx = hitX ? 0 : vx;
			double ty = hitY ? 0 : vy;
			double tz = hitZ ? 0 : vz;
			double t = Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (t > 0) {
				double scale = Math.max(0, t - fx.collisionDrag * dt) / t;
				vx = hitX ? vx : vx * scale;
				vy = hitY ? vy : vy * scale;
				vz = hitZ ? vz : vz * scale;
			}
		}
		fireCollisionEvents(fx, speed);
	}

	private void fireCollisionEvents(ParticleEffect fx, double speed) {
		for (ParticleEffect.CollisionEvent e : fx.collisionEvents) {
			if (speed >= e.minSpeed()) {
				fx.fire(new String[] {e.event()}, this, emitter.particleScope(this, age));
			}
		}
	}

	private void parametric(ParticleEffect fx, Scope s, double dt) {
		if (fx.relativePosition != null) {
			double[] rel = new double[3];
			Expr.eval(fx.relativePosition, s, rel);
			double ox = fx.localPosition ? 0 : emitter.x;
			double oy = fx.localPosition ? 0 : emitter.y;
			double oz = fx.localPosition ? 0 : emitter.z;
			x = ox + rel[0];
			y = oy + rel[1];
			z = oz + rel[2];
			if (dt > 0) {
				vx = (x - prevX) / dt;
				vy = (y - prevY) / dt;
				vz = (z - prevZ) / dt;
			}
		}
		if (fx.parametricDirection != null) {
			double[] d = new double[3];
			Expr.eval(fx.parametricDirection, s, d);
			vx = d[0];
			vy = d[1];
			vz = d[2];
		}
		if (fx.parametricRotation != null) {
			rotation = Expr.eval(fx.parametricRotation, s);
		}
	}

	void expire() {
		if (!expired) {
			expired = true;
			emitter.fx.fire(emitter.fx.particleExpirationEvents, this, emitter.particleScope(this, age));
		}
	}

	@Override
	public ParticleSystem system() {
		return emitter.system;
	}

	@Override
	public Emitter emitter() {
		return emitter;
	}

	@Override
	public double worldX() {
		return emitter.fx.localPosition ? emitter.x + x : x;
	}

	@Override
	public double worldY() {
		return emitter.fx.localPosition ? emitter.y + y : y;
	}

	@Override
	public double worldZ() {
		return emitter.fx.localPosition ? emitter.z + z : z;
	}

	@Override
	public double velocityX() {
		return vx + (emitter.fx.localPosition ? emitter.velX : 0);
	}

	@Override
	public double velocityY() {
		return vy + (emitter.fx.localPosition ? emitter.velY : 0);
	}

	@Override
	public double velocityZ() {
		return vz + (emitter.fx.localPosition ? emitter.velZ : 0);
	}
}
