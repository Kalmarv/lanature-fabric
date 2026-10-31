package xyz.ivyhq.lanature.client.particle;

/** What fires an event or spawns an effect: an emitter or one of its particles. Positions and velocities are in world space. */
interface EffectSource {
	ParticleSystem system();

	Emitter emitter();

	double worldX();

	double worldY();

	double worldZ();

	double velocityX();

	double velocityY();

	double velocityZ();
}
