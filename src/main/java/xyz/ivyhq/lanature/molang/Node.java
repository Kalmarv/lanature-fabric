package xyz.ivyhq.lanature.molang;

import java.util.List;
import java.util.Objects;
import xyz.ivyhq.lanature.molang.Scope.Builtin;
import xyz.ivyhq.lanature.molang.Scope.Flow;

/** A compiled Molang expression. {@link #eval} is the numeric fast path; {@link #value} keeps strings and structs. */
public abstract class Node {
	public abstract double eval(Scope s);

	public Object value(Scope s) {
		return eval(s);
	}

	static double num(Object v) {
		if (v instanceof Double d) {
			return d;
		}
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		if (v instanceof Boolean b) {
			return b ? 1 : 0;
		}
		return 0;
	}

	static double bool(boolean b) {
		return b ? 1.0 : 0.0;
	}

	static final class Constant extends Node {
		final double value;

		Constant(double value) {
			this.value = value;
		}

		@Override
		public double eval(Scope s) {
			return value;
		}
	}

	static final class Text extends Node {
		final String value;

		Text(String value) {
			this.value = value;
		}

		@Override
		public double eval(Scope s) {
			return 0;
		}

		@Override
		public Object value(Scope s) {
			return value;
		}
	}

	static final class This extends Node {
		@Override
		public double eval(Scope s) {
			return s.thisValue;
		}
	}

	enum Namespace { VARIABLE, TEMP, CONTEXT }

	/** {@code v.a}, {@code t.a.b}: a variable path. Missing reads are null (and 0 numerically). */
	static final class Var extends Node {
		final Namespace ns;
		final String[] path;

		Var(Namespace ns, List<String> path) {
			this.ns = ns;
			this.path = path.toArray(String[]::new);
		}

		Variables store(Scope s) {
			return switch (ns) {
				case VARIABLE -> s.variables;
				case TEMP -> s.temps;
				case CONTEXT -> s.context;
			};
		}

		@Override
		public double eval(Scope s) {
			return num(value(s));
		}

		@Override
		public Object value(Scope s) {
			Variables store = store(s);
			if (store == null) {
				return null;
			}
			Object v = store.get(path[0]);
			for (int i = 1; i < path.length && v != null; i++) {
				v = v instanceof Variables.Struct st ? st.get(path[i]) : null;
			}
			return v;
		}

		void assign(Scope s, Object value) {
			Variables store = store(s);
			if (store == null) {
				return;
			}
			Object v = value instanceof Variables.Struct st ? st.copy() : value;
			if (path.length == 1) {
				store.set(path[0], v);
				return;
			}
			Object root = store.get(path[0]);
			Variables.Struct st;
			if (root instanceof Variables.Struct existing && store.local().containsKey(path[0])) {
				st = existing;
			} else {
				st = root instanceof Variables.Struct inherited ? inherited.copy() : new Variables.Struct();
				store.set(path[0], st);
			}
			for (int i = 1; i < path.length - 1; i++) {
				Variables.Struct child = st.get(path[i]) instanceof Variables.Struct existingChild ? existingChild : null;
				if (child == null) {
					child = new Variables.Struct();
					st.set(path[i], child);
				}
				st = child;
			}
			st.set(path[path.length - 1], v);
		}
	}

	/** {@code v.particle_age} and the other built-ins, read from the scope's slot array. */
	static final class BuiltinVar extends Node {
		final int slot;
		final String name;

		BuiltinVar(Builtin b) {
			this.slot = b.ordinal();
			this.name = b.variable;
		}

		@Override
		public double eval(Scope s) {
			return s.builtins[slot];
		}
	}

	static final class Assign extends Node {
		final Var target;
		final Node value;

		Assign(Var target, Node value) {
			this.target = target;
			this.value = value;
		}

		@Override
		public double eval(Scope s) {
			target.assign(s, value.value(s));
			return 0;
		}
	}

	static final class Neg extends Node {
		final Node a;

		Neg(Node a) {
			this.a = a;
		}

		@Override
		public double eval(Scope s) {
			return -a.eval(s);
		}
	}

	static final class Not extends Node {
		final Node a;

		Not(Node a) {
			this.a = a;
		}

		@Override
		public double eval(Scope s) {
			return bool(a.eval(s) == 0);
		}
	}

	static final class Binary extends Node {
		final char op;
		final Node a;
		final Node b;

		Binary(char op, Node a, Node b) {
			this.op = op;
			this.a = a;
			this.b = b;
		}

		@Override
		public double eval(Scope s) {
			return switch (op) {
				case '+' -> a.eval(s) + b.eval(s);
				case '-' -> a.eval(s) - b.eval(s);
				case '*' -> a.eval(s) * b.eval(s);
				case '/' -> a.eval(s) / b.eval(s);
				case '<' -> bool(a.eval(s) < b.eval(s));
				case '>' -> bool(a.eval(s) > b.eval(s));
				case 'l' -> bool(a.eval(s) <= b.eval(s));
				case 'g' -> bool(a.eval(s) >= b.eval(s));
				case '&' -> bool(a.eval(s) != 0 && b.eval(s) != 0);
				case '|' -> bool(a.eval(s) != 0 || b.eval(s) != 0);
				default -> throw new IllegalStateException("op " + op);
			};
		}
	}

	/** {@code ==} and {@code !=}: numbers numerically, otherwise by value (strings, entity references). */
	static final class Equals extends Node {
		final Node a;
		final Node b;
		final boolean negate;

		Equals(Node a, Node b, boolean negate) {
			this.a = a;
			this.b = b;
			this.negate = negate;
		}

		@Override
		public double eval(Scope s) {
			Object x = a.value(s);
			Object y = b.value(s);
			boolean eq;
			if ((x == null || x instanceof Number) && (y == null || y instanceof Number)) {
				eq = num(x) == num(y);
			} else if (x instanceof String || y instanceof String) {
				eq = Objects.equals(x == null ? "" : x.toString(), y == null ? "" : y.toString());
			} else {
				eq = Objects.equals(x, y);
			}
			return bool(eq != negate);
		}
	}

	/** {@code a ?? b}: a unless it is an undefined variable (null). */
	static final class Coalesce extends Node {
		final Node a;
		final Node b;

		Coalesce(Node a, Node b) {
			this.a = a;
			this.b = b;
		}

		@Override
		public double eval(Scope s) {
			return num(value(s));
		}

		@Override
		public Object value(Scope s) {
			Object v = a.value(s);
			return v != null ? v : b.value(s);
		}
	}

	/** {@code c ? a : b}, and the binary {@code c ? a} (0 when false). */
	static final class Conditional extends Node {
		final Node cond;
		final Node then;
		final Node otherwise;

		Conditional(Node cond, Node then, Node otherwise) {
			this.cond = cond;
			this.then = then;
			this.otherwise = otherwise;
		}

		@Override
		public double eval(Scope s) {
			if (cond.eval(s) != 0) {
				return then.eval(s);
			}
			return otherwise == null ? 0 : otherwise.eval(s);
		}

		@Override
		public Object value(Scope s) {
			if (cond.eval(s) != 0) {
				return then.value(s);
			}
			return otherwise == null ? 0.0 : otherwise.value(s);
		}
	}

	/** Statements separated by {@code ;}. A complex expression's value is what {@code return} gives, else 0. */
	static final class Block extends Node {
		final Node[] statements;
		final boolean scoped; // a { } block: control flow passes out; the top-level program consumes return

		Block(List<Node> statements, boolean scoped) {
			this.statements = statements.toArray(Node[]::new);
			this.scoped = scoped;
		}

		@Override
		public double eval(Scope s) {
			return num(value(s));
		}

		@Override
		public Object value(Scope s) {
			for (Node n : statements) {
				n.eval(s);
				if (s.flow != Flow.NONE) {
					break;
				}
			}
			if (!scoped) {
				Object r = s.flow == Flow.RETURN ? s.returnValue : 0.0;
				s.flow = Flow.NONE;
				s.returnValue = null;
				return r;
			}
			return 0.0;
		}
	}

	static final class Return extends Node {
		final Node value;

		Return(Node value) {
			this.value = value;
		}

		@Override
		public double eval(Scope s) {
			s.returnValue = value == null ? 0.0 : value.value(s);
			s.flow = Flow.RETURN;
			return 0;
		}
	}

	static final class Jump extends Node {
		final Flow flow;

		Jump(Flow flow) {
			this.flow = flow;
		}

		@Override
		public double eval(Scope s) {
			s.flow = flow;
			return 0;
		}
	}

	/** {@code loop(count, {...})}, capped at 1024 iterations as in Bedrock. */
	static final class Loop extends Node {
		final Node count;
		final Node body;

		Loop(Node count, Node body) {
			this.count = count;
			this.body = body;
		}

		@Override
		public double eval(Scope s) {
			int n = (int) Math.min(1024, Math.max(0, count.eval(s)));
			for (int i = 0; i < n; i++) {
				body.eval(s);
				if (s.flow == Flow.BREAK) {
					s.flow = Flow.NONE;
					break;
				}
				if (s.flow == Flow.CONTINUE) {
					s.flow = Flow.NONE;
				}
				if (s.flow == Flow.RETURN) {
					break;
				}
			}
			return 0;
		}
	}

	/** {@code for_each(t.x, collection, {...})} over a host-provided {@link Iterable}. */
	static final class ForEach extends Node {
		final Var variable;
		final Node collection;
		final Node body;

		ForEach(Var variable, Node collection, Node body) {
			this.variable = variable;
			this.collection = collection;
			this.body = body;
		}

		@Override
		public double eval(Scope s) {
			if (!(collection.value(s) instanceof Iterable<?> items)) {
				return 0;
			}
			for (Object item : items) {
				variable.assign(s, item);
				body.eval(s);
				if (s.flow == Flow.BREAK) {
					s.flow = Flow.NONE;
					break;
				}
				if (s.flow == Flow.CONTINUE) {
					s.flow = Flow.NONE;
				}
				if (s.flow == Flow.RETURN) {
					break;
				}
			}
			return 0;
		}
	}

	/**
	 * {@code entity_ref->expression}: evaluates the right side on another host, its queries acting on it and its
	 * variables those the host makes public ({@link VariableHost}; none for a host without scripts).
	 */
	static final class Arrow extends Node {
		final Node target;
		final Node expression;

		Arrow(Node target, Node expression) {
			this.target = target;
			this.expression = expression;
		}

		@Override
		public double eval(Scope s) {
			return num(value(s));
		}

		@Override
		public Object value(Scope s) {
			Object t = target.value(s);
			if (t == null || t instanceof Number) {
				return 0.0;
			}
			// the expression reads the target: its queries and its public variables
			Object previousHost = s.host;
			Variables previousVariables = s.variables;
			s.host = t;
			s.variables = t instanceof VariableHost v ? v.publicVariables() : Variables.NONE;
			try {
				return expression.value(s);
			} finally {
				s.host = previousHost;
				s.variables = previousVariables;
			}
		}
	}

	/** {@code geometry.default}, {@code texture.x}, {@code material.x}, {@code array.x[i]}. */
	static final class Resource extends Node {
		final String kind;
		final String name;
		final Node index;

		Resource(String kind, String name, Node index) {
			this.kind = kind;
			this.name = name;
			this.index = index;
		}

		@Override
		public double eval(Scope s) {
			return num(value(s));
		}

		@Override
		public Object value(Scope s) {
			if (s.resources == null) {
				return null;
			}
			Object r = s.resources.resolve(kind, name);
			if (index != null && r instanceof List<?> list) {
				if (list.isEmpty()) {
					return null;
				}
				// Bedrock: C-style cast, negative indices clamp to 0, larger ones wrap
				int i = Math.max(0, (int) index.eval(s));
				return list.get(i % list.size());
			}
			return r;
		}
	}

	static final class Call extends Node {
		final Function fn;
		final Node[] args;
		final String name;

		Call(String name, Function fn, Node[] args) {
			this.name = name;
			this.fn = fn;
			this.args = args;
		}

		@Override
		public double eval(Scope s) {
			return num(fn.call(s, args));
		}

		@Override
		public Object value(Scope s) {
			return fn.call(s, args);
		}
	}

	/** A query or math function: arguments arrive unevaluated so functions can take strings or skip evaluation. */
	@FunctionalInterface
	public interface Function {
		Object call(Scope s, Node[] args);
	}
}
