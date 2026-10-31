package xyz.ivyhq.lanature.molang;

import java.util.Random;

/**
 * Everything a Molang evaluation can see: its variable namespaces, the host object queries read ({@link #host}),
 * the built-in particle and emitter values, and the control-flow state of the running script.
 */
public class Scope {
	/** Built-in values particle code reads every frame, kept out of the variable map. See {@link Builtin}. */
	public final double[] builtins = new double[Builtin.values().length];
	public Variables variables;
	public Variables temps;
	public Variables context;
	/** What queries act on: an entity, an emitter or a particle (whatever the host's query handlers expect). */
	public Object host;
	/** Resolves {@code geometry.}, {@code texture.}, {@code material.} and {@code array.} names; may be null. */
	public ResourceResolver resources;
	/** The value of {@code this} (an animation channel's current value). */
	public double thisValue;
	public Random random;

	// control flow of the script being run
	Flow flow = Flow.NONE;
	Object returnValue;

	public enum Flow { NONE, RETURN, BREAK, CONTINUE }

	/** The particle and emitter variables Bedrock fills in, addressed by slot rather than by name. */
	public enum Builtin {
		PARTICLE_AGE, PARTICLE_LIFETIME, PARTICLE_RANDOM_1, PARTICLE_RANDOM_2, PARTICLE_RANDOM_3, PARTICLE_RANDOM_4,
		EMITTER_AGE, EMITTER_LIFETIME, EMITTER_RANDOM_1, EMITTER_RANDOM_2, EMITTER_RANDOM_3, EMITTER_RANDOM_4;

		final String variable = name().toLowerCase(java.util.Locale.ROOT);

		static Builtin byVariable(String name) {
			for (Builtin b : values()) {
				if (b.variable.equals(name)) {
					return b;
				}
			}
			return null;
		}
	}

	public Scope(Variables variables, Variables temps, Variables context, Object host, Random random) {
		this.variables = variables;
		this.temps = temps;
		this.context = context;
		this.host = host;
		this.random = random;
	}

	public void set(Builtin b, double value) {
		builtins[b.ordinal()] = value;
	}

	public double get(Builtin b) {
		return builtins[b.ordinal()];
	}

	public interface ResourceResolver {
		Object resolve(String kind, String name);
	}
}
