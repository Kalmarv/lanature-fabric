package xyz.ivyhq.lanature.molang;

import java.util.HashMap;
import java.util.Map;

/**
 * One Molang variable namespace. Reads fall back to the parent (a particle sees its emitter's variables); writes
 * always land here. Values are {@link Double}, {@link String}, {@link Struct} or other host objects.
 */
public final class Variables {
	private final Map<String, Object> values = new HashMap<>();
	private final Variables parent;

	public Variables() {
		this(null);
	}

	public Variables(Variables parent) {
		this.parent = parent;
	}

	public Object get(String name) {
		Object v = values.get(name);
		if (v == null && parent != null) {
			return parent.get(name);
		}
		return v;
	}

	public void set(String name, Object value) {
		values.put(name, value);
	}

	public boolean has(String name) {
		return values.containsKey(name) || (parent != null && parent.has(name));
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
