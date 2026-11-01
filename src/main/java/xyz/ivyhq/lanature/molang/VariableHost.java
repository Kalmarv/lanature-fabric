package xyz.ivyhq.lanature.molang;

/**
 * A host whose variables another scope can read through {@code ->} ({@code c.owning_entity->v.attack_time}): an
 * entity's running scripts, which show only the variables they declare public.
 */
public interface VariableHost {
	/** The variables an expression evaluated on this host with {@code ->} reads: a read-only view. */
	Variables publicVariables();
}
