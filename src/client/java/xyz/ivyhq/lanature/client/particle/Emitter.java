package xyz.ivyhq.lanature.client.particle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.molang.Scope;
import xyz.ivyhq.lanature.molang.Scope.Builtin;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * A running Bedrock emitter. It updates on the client tick: age, curves and {@code per_update_expression}, then its
 * lifetime decides whether it emits; an expired emitter stays until its last particle is gone. A one-shot emitter is
 * what Bedrock's {@code particle} spawns and its engine's manual emitters create: it emits a single particle at once and
 * goes with that particle.
 */
public final class Emitter implements EffectSource {
	final ParticleEffect fx;
	final ParticleSystem system;
	final Variables vars = new Variables();
	final Scope scope;
	private final Variables temps = new Variables();
	final double[] random = new double[4];
	final List<BedrockParticle> particles = new ArrayList<>();
	final boolean oneShot;

	/**
	 * Where a locator emitter is on its entity's model, asked each tick: Java model axes and blocks, for the model facing
	 * +Z before the body's yaw (a bone's locator moves with the bone's animation).
	 */
	@FunctionalInterface
	public interface Locator {
		Vector3f at();
	}

	/**
	 * The entity this emitter follows, and its offset from the entity's position: fixed in world axes, or a
	 * {@code locator} on the entity's model that turns with its body.
	 */
	final Entity entity;
	private final Vector3f offset;
	private final @Nullable Locator locator;
	double x;
	double y;
	double z;
	double prevX;
	double prevY;
	double prevZ;
	double velX;
	double velY;
	double velZ;
	final Quaternionf rotation = new Quaternionf();

	double age;
	private double activeTime;
	private boolean wasActive;
	private boolean burstDone;
	private boolean sleeping;
	private double sleepEnd;
	private double spawnDebt;
	boolean expired;
	private boolean initialized;

	Emitter(ParticleSystem system, ParticleEffect fx, double x, double y, double z, Entity entity, boolean oneShot) {
		this(system, fx, x, y, z, entity, entity == null ? new Vector3f()
			: new Vector3f((float) (x - entity.getX()), (float) (y - entity.getY()), (float) (z - entity.getZ())), null, oneShot);
	}

	/** An emitter at a locator of an entity's model. */
	Emitter(ParticleSystem system, ParticleEffect fx, Entity entity, Locator locator, boolean oneShot) {
		this(system, fx, entity.getX(), entity.getY(), entity.getZ(), entity, new Vector3f(), locator, oneShot);
		Vector3f p = offset();
		this.x = this.prevX = entity.getX() + p.x;
		this.y = this.prevY = entity.getY() + p.y;
		this.z = this.prevZ = entity.getZ() + p.z;
	}

	private Emitter(ParticleSystem system, ParticleEffect fx, double x, double y, double z, Entity entity, Vector3f offset, @Nullable Locator locator,
		boolean oneShot) {
		this.system = system;
		this.fx = fx;
		this.oneShot = oneShot;
		this.entity = entity;
		this.offset = offset;
		this.locator = locator;
		this.scope = new Scope(vars, temps, null, this, system.random);
		this.x = this.prevX = x;
		this.y = this.prevY = y;
		this.z = this.prevZ = z;
		for (int i = 0; i < 4; i++) {
			random[i] = system.random.nextDouble();
		}
	}

	/** Prepares the shared scope for an emitter expression. */
	Scope emitterScope() {
		scope.variables = vars;
		scope.host = this;
		scope.temps = temps;
		temps.clear();
		builtins();
		scope.set(Builtin.PARTICLE_AGE, 0);
		scope.set(Builtin.PARTICLE_LIFETIME, 0);
		for (int i = 0; i < 4; i++) {
			scope.builtins[Builtin.PARTICLE_RANDOM_1.ordinal() + i] = 0;
		}
		return scope;
	}

	Scope particleScope(BedrockParticle p, double particleAge) {
		scope.variables = p.vars;
		scope.host = p;
		scope.temps = temps;
		temps.clear();
		builtins();
		scope.set(Builtin.PARTICLE_AGE, particleAge);
		scope.set(Builtin.PARTICLE_LIFETIME, p.lifetime);
		for (int i = 0; i < 4; i++) {
			scope.builtins[Builtin.PARTICLE_RANDOM_1.ordinal() + i] = p.random[i];
		}
		return scope;
	}

	private void builtins() {
		scope.set(Builtin.EMITTER_AGE, age);
		scope.set(Builtin.EMITTER_LIFETIME, activeTime);
		for (int i = 0; i < 4; i++) {
			scope.builtins[Builtin.EMITTER_RANDOM_1.ordinal() + i] = random[i];
		}
	}

	void curves(Scope s) {
		for (Curve c : fx.curves) {
			s.variables.set(c.variable, c.evaluate(s));
		}
	}

	/** Creation: {@code creation_expression}, lifetime, creation events, then the first update (an instant burst). */
	void initialize() {
		Scope s = emitterScope();
		curves(s);
		Expr.run(fx.emitterCreation, s);
		startLoop();
		initialized = true;
		fx.fire(fx.emitterCreationEvents, this, emitterScope());
		if (oneShot) {
			emit();
		}
		update(0);
		timeline(fx.emitterTimeline.headMap(0.0, true));
	}

	private void startLoop() {
		Scope s = emitterScope();
		activeTime = fx.lifetime == ParticleEffect.Lifetime.EXPRESSION ? Double.POSITIVE_INFINITY : Expr.eval(fx.activeTime, s);
		burstDone = false;
		spawnDebt = 0;
	}

	boolean done() {
		return initialized && (expired || oneShot) && particles.isEmpty();
	}

	void follow() {
		prevX = x;
		prevY = y;
		prevZ = z;
		if (entity != null) {
			if (entity.isRemoved()) {
				expire();
			} else {
				Vector3f p = offset();
				x = entity.getX() + p.x;
				y = entity.getY() + p.y;
				z = entity.getZ() + p.z;
				if (fx.localRotation) {
					rotation.rotationY((float) Math.toRadians(-bodyYaw(entity)));
				}
			}
		}
		velX = (x - prevX) / ParticleSystem.DT;
		velY = (y - prevY) / ParticleSystem.DT;
		velZ = (z - prevZ) / ParticleSystem.DT;
	}

	/** The followed point's current offset from the entity's position, in world axes. */
	private Vector3f offset() {
		return locator != null ? locator.at().rotateY((float) Math.toRadians(-bodyYaw(entity))) : new Vector3f(offset);
	}

	/**
	 * The locator of an emitter bound to this one and started at a world point: this emitter's locator plus the point's
	 * offset from it, so it moves with the same bone; null when this emitter has no locator.
	 */
	@Nullable Locator boundLocator(double wx, double wy, double wz) {
		if (locator == null) {
			return null;
		}
		Vector3f delta = new Vector3f((float) (wx - x), (float) (wy - y), (float) (wz - z)).rotateY((float) Math.toRadians(bodyYaw(entity)));
		return () -> locator.at().add(delta);
	}

	static float bodyYaw(Entity e) {
		return e instanceof LivingEntity living ? living.yBodyRot : e.getYRot();
	}

	/** For diagnostics: the effect, its live particles and its position relative to {@code entity}. */
	public String describe() {
		return String.format(java.util.Locale.ROOT, "%s %d at %+.2f %+.2f %+.2f", fx.id, particles.size(),
			x - (entity == null ? 0 : entity.getX()), y - (entity == null ? 0 : entity.getY()), z - (entity == null ? 0 : entity.getZ()));
	}

	/** Whether the emitter has stopped and its last particle is gone. */
	public boolean finished() {
		return done();
	}

	/** Stops emitting; the emitter goes once its particles have. */
	public void stop() {
		expire();
	}

	void update(double dt) {
		double prevAge = age;
		age += dt;
		Scope s = emitterScope();
		curves(s);
		Expr.run(fx.emitterPerUpdate, s);
		if (!expired) {
			lifetime(s);
		}
		if (age > prevAge) {
			timeline(fx.emitterTimeline.subMap(prevAge, false, age, true));
		}
	}

	private void timeline(Map<Double, String[]> due) {
		for (String[] events : due.values()) {
			fx.fire(events, this, emitterScope());
		}
	}

	private void lifetime(Scope s) {
		boolean active;
		switch (fx.lifetime) {
			case ONCE -> active = age <= activeTime;
			case LOOPING -> {
				if (sleeping) {
					if (age < sleepEnd) {
						return;
					}
					sleeping = false;
					age = 0;
					startLoop();
					s = emitterScope();
				} else if (age > activeTime) {
					double sleep = Expr.eval(fx.sleepTime, s);
					sleeping = true;
					sleepEnd = age + sleep;
					return;
				}
				active = true;
			}
			default -> active = Expr.eval(fx.activation, s) != 0;
		}
		if (active && !oneShot) {
			spawn(s);
		}
		if (!active && wasActive && fx.lifetime == ParticleEffect.Lifetime.EXPRESSION) {
			burstDone = false;
		}
		wasActive = active;
		if (fx.lifetime == ParticleEffect.Lifetime.EXPRESSION ? Expr.eval(fx.expiration, emitterScope()) != 0
			: fx.lifetime == ParticleEffect.Lifetime.ONCE && age >= activeTime) {
			expire();
		}
	}

	private void spawn(Scope s) {
		switch (fx.rate) {
			case INSTANT -> {
				if (!burstDone) {
					burstDone = true;
					int n = (int) Expr.eval(fx.numParticles, s);
					for (int i = 0; i < n; i++) {
						emit();
					}
				}
			}
			case STEADY -> {
				double rate = Expr.eval(fx.spawnRate, s);
				int max = (int) Expr.eval(fx.maxParticles, emitterScope());
				spawnDebt += rate * ParticleSystem.DT;
				while (spawnDebt >= 1) {
					spawnDebt -= 1;
					if (particles.size() < max) {
						emit();
					}
				}
			}
			case MANUAL -> {
			}
		}
	}

	void expire() {
		if (!expired) {
			expired = true;
			fx.fire(fx.emitterExpirationEvents, this, emitterScope());
		}
	}

	/** Emits one particle: shape, lifetime, speed and spin, then its creation events. */
	BedrockParticle emit() {
		if (!system.hasRoom()) {
			return null;
		}
		BedrockParticle p = new BedrockParticle(this);
		Scope s = particleScope(p, 0);
		curves(s);
		double[] offset = new double[3];
		double[] dir = new double[3];
		shape(s, offset, dir);
		if (fx.localPosition) {
			p.x = offset[0];
			p.y = offset[1];
			p.z = offset[2];
		} else {
			Vector3f o = rotation.transform(new Vector3f((float) offset[0], (float) offset[1], (float) offset[2]));
			p.x = x + o.x;
			p.y = y + o.y;
			p.z = z + o.z;
			Vector3f d = rotation.transform(new Vector3f((float) dir[0], (float) dir[1], (float) dir[2]));
			dir[0] = d.x;
			dir[1] = d.y;
			dir[2] = d.z;
		}
		p.prevX = p.x;
		p.prevY = p.y;
		p.prevZ = p.z;
		p.lifetime = fx.maxLifetime != null ? Expr.eval(fx.maxLifetime, s) : fx.particleExpiration != null ? Double.POSITIVE_INFINITY : 1;
		s = particleScope(p, 0);
		double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
		if (len > 1e-9) {
			dir[0] /= len;
			dir[1] /= len;
			dir[2] /= len;
		}
		if (fx.speedVector != null) {
			double[] sp = new double[3];
			Expr.eval(fx.speedVector, s, sp);
			p.vx = dir[0] * sp[0];
			p.vy = dir[1] * sp[1];
			p.vz = dir[2] * sp[2];
		} else {
			double sp = Expr.eval(fx.speed, s);
			p.vx = dir[0] * sp;
			p.vy = dir[1] * sp;
			p.vz = dir[2] * sp;
		}
		if (fx.localVelocity && !fx.localPosition) {
			p.vx += velX;
			p.vy += velY;
			p.vz += velZ;
		}
		p.rotation = p.prevRotation = Expr.eval(fx.spin, s);
		p.rotationRate = Expr.eval(fx.spinRate, s);
		particles.add(p);
		system.particleAdded(this);
		fx.fire(fx.particleCreationEvents, p, particleScope(p, 0));
		return p;
	}

	private void shape(Scope s, double[] offset, double[] dir) {
		double[] base = new double[3];
		Expr.eval(fx.shapeOffset, s, base);
		double[] local = new double[3];
		var r = system.random;
		switch (fx.shape) {
			case SPHERE -> {
				double radius = Expr.eval(fx.shapeRadius, s);
				double ux;
				double uy;
				double uz;
				double l;
				do {
					ux = r.nextDouble() * 2 - 1;
					uy = r.nextDouble() * 2 - 1;
					uz = r.nextDouble() * 2 - 1;
					l = ux * ux + uy * uy + uz * uz;
				} while (l > 1 || l < 1e-6);
				l = Math.sqrt(l);
				double d = fx.surfaceOnly ? radius : radius * Math.cbrt(r.nextDouble());
				local[0] = ux / l * d;
				local[1] = uy / l * d;
				local[2] = uz / l * d;
			}
			case BOX -> {
				double[] half = new double[3];
				Expr.eval(fx.halfDimensions, s, half);
				for (int i = 0; i < 3; i++) {
					local[i] = (r.nextDouble() * 2 - 1) * half[i];
				}
				if (fx.surfaceOnly) {
					int face = r.nextInt(3);
					local[face] = r.nextBoolean() ? half[face] : -half[face];
				}
			}
			case DISC -> {
				double radius = Expr.eval(fx.shapeRadius, s);
				double[] n = new double[3];
				Expr.eval(fx.planeNormal, s, n);
				Vector3f normal = new Vector3f((float) n[0], (float) n[1], (float) n[2]);
				if (normal.lengthSquared() < 1e-9) {
					normal.set(0, 1, 0);
				}
				normal.normalize();
				Vector3f a = Math.abs(normal.y) < 0.99f ? new Vector3f(0, 1, 0).cross(normal).normalize() : new Vector3f(1, 0, 0).cross(normal).normalize();
				Vector3f b = new Vector3f(normal).cross(a);
				double angle = r.nextDouble() * Math.PI * 2;
				double d = fx.surfaceOnly ? radius : radius * Math.sqrt(r.nextDouble());
				for (int i = 0; i < 3; i++) {
					local[i] = (a.get(i) * Math.cos(angle) + b.get(i) * Math.sin(angle)) * d;
				}
			}
			case ENTITY_AABB -> {
				if (entity != null) {
					AABB box = entity.getBoundingBox();
					double[] min = {box.minX - entity.getX(), box.minY - entity.getY(), box.minZ - entity.getZ()};
					double[] size = {box.getXsize(), box.getYsize(), box.getZsize()};
					for (int i = 0; i < 3; i++) {
						local[i] = min[i] + r.nextDouble() * size[i];
					}
					if (fx.surfaceOnly) {
						int face = r.nextInt(3);
						local[face] = min[face] + (r.nextBoolean() ? size[face] : 0);
					}
				}
			}
			default -> {
			}
		}
		for (int i = 0; i < 3; i++) {
			offset[i] = base[i] + local[i];
		}
		switch (fx.direction) {
			case OUTWARDS -> System.arraycopy(local, 0, dir, 0, 3);
			case INWARDS -> {
				for (int i = 0; i < 3; i++) {
					dir[i] = -local[i];
				}
			}
			case VECTOR -> Expr.eval(fx.directionVector, s, dir);
		}
	}

	@Override
	public ParticleSystem system() {
		return system;
	}

	@Override
	public Emitter emitter() {
		return this;
	}

	@Override
	public double worldX() {
		return x;
	}

	@Override
	public double worldY() {
		return y;
	}

	@Override
	public double worldZ() {
		return z;
	}

	@Override
	public double velocityX() {
		return velX;
	}

	@Override
	public double velocityY() {
		return velY;
	}

	@Override
	public double velocityZ() {
		return velZ;
	}

	public String effectId() {
		return fx.id;
	}

	public Entity entity() {
		return entity;
	}
}
