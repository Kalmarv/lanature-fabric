package xyz.ivyhq.lanature.molang;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * One Molang variable namespace. Reads fall back to the parent (a particle sees its emitter's variables); writes
 * always land here. A {@link #view} shows some of another namespace's variables and takes no writes. Values are
 * {@link Double}, {@link String}, {@link Struct} or other host objects.
 */
public final class Variables {
	/** A namespace with nothing in it, which takes no writes. */
	public static final Variables NONE = new Variables(null, Set.of());

	private final Map<String, Object> values = new HashMap<>();
	private final Variables parent;
	/** The names reads fall back to the parent for, null for all; a view (non-null) takes no writes. */
	private final Set<String> shown;

	public Variables() {
		this(null);
	}

	public Variables(Variables parent) {
		this(parent, null);
	}

	private Variables(Variables parent, Set<String> shown) {
		this.parent = parent;
		this.shown = shown;
	}

	/** A read-only view of {@code source} showing only the variables named (lower-case names, without {@code v.}). */
	public static Variables view(Variables source, Set<String> names) {
		return new Variables(source, Set.copyOf(names));
	}

	private boolean inherits(String name) {
		return parent != null && (shown == null || shown.contains(name));
	}

	public Object get(String name) {
		Object v = values.get(name);
		if (v == null && inherits(name)) {
			return parent.get(name);
		}
		return v;
	}

	/** Sets a variable; a view ignores it. */
	public void set(String name, Object value) {
		if (shown == null) {
			values.put(name, value);
		}
	}

	public boolean has(String name) {
		return values.containsKey(name) || (inherits(name) && parent.has(name));
	}

	public void clear() {
		values.clear();
	}

	public Map<String, Object> local() {
		return values;
	}

	/** A Molang struct ({@code v.color.r}): a named bag of values, copied on assignment. */
	public static final class Struct {
		final Map<String, Object> fields = new HashMap<>();

		public Object get(String name) {
			return fields.get(name);
		}

		public void set(String name, Object value) {
			fields.put(name, value);
		}

		public Struct copy() {
			Struct s = new Struct();
			fields.forEach((k, v) -> s.fields.put(k, v instanceof Struct st ? st.copy() : v));
			return s;
		}

		@Override
		public String toString() {
			return fields.toString();
		}
	}
}
