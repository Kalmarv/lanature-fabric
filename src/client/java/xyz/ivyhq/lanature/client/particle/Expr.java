package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import xyz.ivyhq.lanature.molang.Molang;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;

/** Molang values as Bedrock JSON writes them: a number, a boolean, an expression string, or an array of those. */
public final class Expr {
	private Expr() {
	}

	public static Node scalar(JsonElement e, double fallback) {
		if (e == null || e.isJsonNull()) {
			return Molang.constant(fallback);
		}
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			return a.isEmpty() ? Molang.constant(fallback) : scalar(a.get(0), fallback);
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isNumber()) {
			return Molang.constant(p.getAsDouble());
		}
		if (p.isBoolean()) {
			return Molang.constant(p.getAsBoolean() ? 1 : 0);
		}
		return Molang.compile(p.getAsString());
	}

	/** A vector of {@code n}; a single value fills every component. */
	public static Node[] vector(JsonElement e, int n, double fallback) {
		Node[] out = new Node[n];
		if (e != null && e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (int i = 0; i < n; i++) {
				out[i] = i < a.size() ? scalar(a.get(i), fallback) : Molang.constant(fallback);
			}
		} else {
			Node v = scalar(e, fallback);
			java.util.Arrays.fill(out, v);
		}
		return out;
	}

	public static boolean isConstantZero(Node[] v) {
		for (Node n : v) {
			if (n != Molang.ZERO) {
				return false;
			}
		}
		return true;
	}

	public static double eval(Node n, Scope s) {
		return Molang.eval(n, s);
	}

	public static void eval(Node[] v, Scope s, double[] out) {
		for (int i = 0; i < v.length; i++) {
			out[i] = Molang.eval(v[i], s);
		}
	}

	/** Runs a statement expression (an initialization or event expression). */
	public static void run(Node n, Scope s) {
		if (n != null && n != Molang.ZERO) {
			Molang.run(n, s);
		}
	}

	public static Node script(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonArray()) {
			java.util.List<String> lines = new java.util.ArrayList<>();
			e.getAsJsonArray().forEach(x -> lines.add(x.getAsString()));
			return Molang.compileScript(lines);
		}
		return Molang.compile(e.getAsString());
	}
}
