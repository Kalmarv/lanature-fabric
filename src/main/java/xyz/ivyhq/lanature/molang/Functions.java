package xyz.ivyhq.lanature.molang;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code math.*} (angles in degrees, as in Molang) and the {@code query.*} registry. Queries are bound by name at
 * parse time to a slot the host fills in, so a script parsed before the host registers still finds its query.
 */
public final class Functions {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/molang");
	private static final Map<String, Node.Function> MATH = new HashMap<>();
	private static final Map<String, DoubleUnaryOperator> EASES = new HashMap<>();
	private static final Map<String, QuerySlot> QUERIES = new ConcurrentHashMap<>();
	private static final Random FALLBACK_RANDOM = new Random();

	private Functions() {
	}

	/** A query's binding. Unregistered queries answer 0 and are logged once. */
	static final class QuerySlot implements Node.Function {
		final String name;
		volatile Node.Function impl;
		volatile boolean warned;

		QuerySlot(String name) {
			this.name = name;
		}

		@Override
		public Object call(Scope s, Node[] args) {
			Node.Function f = impl;
			if (f == null) {
				if (!warned) {
					warned = true;
					LOGGER.warn("unsupported Molang query: query.{}", name);
				}
				return 0.0;
			}
			return f.call(s, args);
		}
	}

	static Node.Function query(String name) {
		return QUERIES.computeIfAbsent(name, QuerySlot::new);
	}

	/** Registers (or replaces) a query implementation. */
	public static void registerQuery(String name, Node.Function impl) {
		QUERIES.computeIfAbsent(name, QuerySlot::new).impl = impl;
	}

	public static void registerNumericQuery(String name, NumericQuery impl) {
		registerQuery(name, (s, args) -> impl.get(s, args));
	}

	@FunctionalInterface
	public interface NumericQuery {
		double get(Scope s, Node[] args);
	}

	static Node.Function math(String name) {
		return MATH.get(name);
	}

	private static Random random(Scope s) {
		return s.random != null ? s.random : FALLBACK_RANDOM;
	}

	private static double arg(Scope s, Node[] args, int i) {
		return i < args.length ? args[i].eval(s) : 0;
	}

	private static void unary(String name, DoubleUnaryOperator f) {
		MATH.put(name, (s, a) -> f.applyAsDouble(arg(s, a, 0)));
	}

	private static void ease(String name, DoubleUnaryOperator curve) {
		EASES.put(name, curve);
		MATH.put(name, (s, a) -> ease(name, arg(s, a, 0), arg(s, a, 1), arg(s, a, 2)));
	}

	/** {@code math.<name>(start, end, t)} of an easing function ({@code ease_out_circ}...), for engine code that mirrors a script. */
	public static double ease(String name, double start, double end, double t) {
		return start + (end - start) * EASES.get(name).applyAsDouble(Math.clamp(t, 0, 1));
	}

	/** In, out and in-out variants of an easing curve given as its "in" form. */
	private static void eases(String name, DoubleUnaryOperator in) {
		ease("ease_in_" + name, in);
		ease("ease_out_" + name, t -> 1 - in.applyAsDouble(1 - t));
		ease("ease_in_out_" + name, t -> t < 0.5 ? in.applyAsDouble(2 * t) / 2 : 1 - in.applyAsDouble(2 - 2 * t) / 2);
	}

	private static double bounceOut(double t) {
		double n = 7.5625;
		double d = 2.75;
		if (t < 1 / d) {
			return n * t * t;
		} else if (t < 2 / d) {
			return n * (t -= 1.5 / d) * t + 0.75;
		} else if (t < 2.5 / d) {
			return n * (t -= 2.25 / d) * t + 0.9375;
		}
		return n * (t -= 2.625 / d) * t + 0.984375;
	}

	static {
		unary("abs", Math::abs);
		unary("acos", x -> Math.toDegrees(Math.acos(x)));
		unary("asin", x -> Math.toDegrees(Math.asin(x)));
		unary("atan", x -> Math.toDegrees(Math.atan(x)));
		MATH.put("atan2", (s, a) -> Math.toDegrees(Math.atan2(arg(s, a, 0), arg(s, a, 1))));
		unary("ceil", Math::ceil);
		MATH.put("clamp", (s, a) -> {
			double v = arg(s, a, 0);
			double lo = arg(s, a, 1);
			double hi = arg(s, a, 2);
			return Math.max(lo, Math.min(hi, v));
		});
		unary("cos", x -> Math.cos(Math.toRadians(x)));
		unary("sin", x -> Math.sin(Math.toRadians(x)));
		unary("exp", Math::exp);
		unary("floor", Math::floor);
		unary("ln", Math::log);
		unary("round", x -> (double) Math.round(x));
		unary("sqrt", Math::sqrt);
		unary("trunc", x -> x < 0 ? Math.ceil(x) : Math.floor(x));
		unary("sign", x -> x >= 0 ? 1.0 : -1.0);
		unary("hermite_blend", t -> 3 * t * t - 2 * t * t * t);
		MATH.put("copy_sign", (s, a) -> Math.copySign(arg(s, a, 0), arg(s, a, 1)));
		MATH.put("max", (s, a) -> Math.max(arg(s, a, 0), arg(s, a, 1)));
		MATH.put("min", (s, a) -> Math.min(arg(s, a, 0), arg(s, a, 1)));
		MATH.put("mod", (s, a) -> {
			double x = arg(s, a, 0);
			double y = arg(s, a, 1);
			return y == 0 ? 0.0 : x % y;
		});
		MATH.put("pow", (s, a) -> Math.pow(arg(s, a, 0), arg(s, a, 1)));
		MATH.put("lerp", (s, a) -> {
			double x = arg(s, a, 0);
			return x + (arg(s, a, 1) - x) * arg(s, a, 2);
		});
		MATH.put("inverse_lerp", (s, a) -> {
			double lo = arg(s, a, 0);
			double hi = arg(s, a, 1);
			return hi == lo ? 0.0 : (arg(s, a, 2) - lo) / (hi - lo);
		});
		MATH.put("lerprotate", (s, a) -> {
			double from = arg(s, a, 0);
			double to = arg(s, a, 1);
			double t = arg(s, a, 2);
			double diff = ((to - from) % 360 + 540) % 360 - 180;
			return from + diff * t;
		});
		MATH.put("min_angle", (s, a) -> {
			double x = arg(s, a, 0) % 360;
			if (x >= 180) {
				x -= 360;
			}
			if (x < -180) {
				x += 360;
			}
			return x;
		});
		MATH.put("random", (s, a) -> {
			double lo = arg(s, a, 0);
			double hi = arg(s, a, 1);
			return lo + random(s).nextDouble() * (hi - lo);
		});
		MATH.put("random_integer", (s, a) -> {
			long lo = Math.round(arg(s, a, 0));
			long hi = Math.round(arg(s, a, 1));
			if (hi < lo) {
				long t = lo;
				lo = hi;
				hi = t;
			}
			return (double) (lo + (long) (random(s).nextDouble() * (hi - lo + 1)));
		});
		MATH.put("die_roll", (s, a) -> {
			int n = (int) arg(s, a, 0);
			double lo = arg(s, a, 1);
			double hi = arg(s, a, 2);
			double sum = 0;
			for (int i = 0; i < n; i++) {
				sum += lo + random(s).nextDouble() * (hi - lo);
			}
			return sum;
		});
		MATH.put("die_roll_integer", (s, a) -> {
			int n = (int) arg(s, a, 0);
			long lo = Math.round(arg(s, a, 1));
			long hi = Math.round(arg(s, a, 2));
			double sum = 0;
			for (int i = 0; i < n; i++) {
				sum += lo + (long) (random(s).nextDouble() * (hi - lo + 1));
			}
			return sum;
		});
		eases("quad", t -> t * t);
		eases("cubic", t -> t * t * t);
		eases("quart", t -> t * t * t * t);
		eases("quint", t -> t * t * t * t * t);
		eases("sine", t -> 1 - Math.cos(t * Math.PI / 2));
		eases("expo", t -> t == 0 ? 0 : Math.pow(2, 10 * t - 10));
		eases("circ", t -> 1 - Math.sqrt(1 - t * t));
		eases("back", t -> 2.70158 * t * t * t - 1.70158 * t * t);
		eases("elastic", t -> t == 0 || t == 1 ? t : -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * (2 * Math.PI) / 3));
		eases("bounce", t -> 1 - bounceOut(1 - t));
	}
}
