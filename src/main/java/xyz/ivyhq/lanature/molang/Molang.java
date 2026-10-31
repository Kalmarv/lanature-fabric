package xyz.ivyhq.lanature.molang;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: compiles Molang source to {@link Node}s, caching by source text. */
public final class Molang {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/molang");
	private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();
	public static final Node ZERO = new Node.Constant(0);
	public static final Node ONE = new Node.Constant(1);

	private Molang() {
	}

	/** Parses source; on a syntax error logs it once and yields 0, as Bedrock does for a broken expression. */
	public static Node compile(String source) {
		if (source == null || source.isBlank()) {
			return ZERO;
		}
		return CACHE.computeIfAbsent(source, src -> {
			try {
				return Parser.parse(src);
			} catch (MolangException e) {
				LOGGER.warn("Molang syntax error: {}", e.getMessage());
				return ZERO;
			}
		});
	}

	/** Parses, throwing on syntax errors (for tests and tools). */
	public static Node parseStrict(String source) {
		return Parser.parse(source);
	}

	public static Node constant(double value) {
		return value == 0 ? ZERO : value == 1 ? ONE : new Node.Constant(value);
	}

	/**
	 * Bedrock runs a script array (an entity's {@code pre_animation}, a state's {@code on_entry}) as one program
	 * made of its entries joined together; packs split a block across entries.
	 */
	public static Node compileScript(List<String> lines) {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			sb.append(line).append('\n');
		}
		return compile(sb.toString());
	}

	/** Evaluates as a statement list: runs it and consumes any {@code return}. */
	public static void run(Node n, Scope s) {
		n.value(s);
		s.flow = Scope.Flow.NONE;
		s.returnValue = null;
	}

	public static double eval(Node n, Scope s) {
		double v = n.eval(s);
		s.flow = Scope.Flow.NONE;
		s.returnValue = null;
		return v;
	}

	public static boolean isConstant(Node n) {
		return n instanceof Node.Constant;
	}
}
