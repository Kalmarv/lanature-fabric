package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import xyz.ivyhq.lanature.molang.Node;
import xyz.ivyhq.lanature.molang.Scope;

/**
 * A particle effect curve: {@code input / horizontal_range} looked up on nodes, written to its variable before the
 * effect's expressions run. Linear and Catmull-Rom nodes are evenly spaced; bezier takes four control values.
 */
final class Curve {
	enum Type { LINEAR, CATMULL_ROM, BEZIER, BEZIER_CHAIN }

	final String variable;
	final Type type;
	final Node input;
	final Node range;
	final Node[] nodes;
	/** bezier_chain: node times and their (left value, right value, left slope, right slope). */
	final double[] chainTimes;
	final double[][] chainValues;

	private Curve(String variable, Type type, Node input, Node range, Node[] nodes, double[] chainTimes, double[][] chainValues) {
		this.variable = variable;
		this.type = type;
		this.input = input;
		this.range = range;
		this.nodes = nodes;
		this.chainTimes = chainTimes;
		this.chainValues = chainValues;
	}

	static Curve parse(String name, JsonObject o) {
		String n = name.toLowerCase(Locale.ROOT);
		String variable = n.startsWith("variable.") ? n.substring(9) : n.startsWith("v.") ? n.substring(2) : n;
		Type type = switch (o.has("type") ? o.get("type").getAsString() : "linear") {
			case "catmull_rom" -> Type.CATMULL_ROM;
			case "bezier" -> Type.BEZIER;
			case "bezier_chain" -> Type.BEZIER_CHAIN;
			default -> Type.LINEAR;
		};
		Node input = Expr.scalar(o.get("input"), 0);
		Node range = Expr.scalar(o.get("horizontal_range"), 1);
		JsonElement nodesJson = o.get("nodes");
		if (type == Type.BEZIER_CHAIN && nodesJson != null && nodesJson.isJsonObject()) {
			List<double[]> entries = new ArrayList<>();
			nodesJson.getAsJsonObject().entrySet().forEach(e -> {
				JsonObject v = e.getValue().getAsJsonObject();
				double value = v.has("value") ? v.get("value").getAsDouble() : 0;
				double slope = v.has("slope") ? v.get("slope").getAsDouble() : 0;
				entries.add(new double[] {Double.parseDouble(e.getKey()),
					v.has("left_value") ? v.get("left_value").getAsDouble() : value,
					v.has("right_value") ? v.get("right_value").getAsDouble() : value,
					v.has("left_slope") ? v.get("left_slope").getAsDouble() : slope,
					v.has("right_slope") ? v.get("right_slope").getAsDouble() : slope});
			});
			entries.sort((a, b) -> Double.compare(a[0], b[0]));
			double[] times = new double[entries.size()];
			double[][] values = new double[entries.size()][];
			for (int i = 0; i < times.length; i++) {
				times[i] = entries.get(i)[0];
				values[i] = java.util.Arrays.copyOfRange(entries.get(i), 1, 5);
			}
			return new Curve(variable, type, input, range, new Node[0], times, values);
		}
		Node[] nodes = nodesJson == null ? new Node[0] : Expr.vector(nodesJson, nodesJson.isJsonArray() ? nodesJson.getAsJsonArray().size() : 1, 0);
		return new Curve(variable, type, input, range, nodes, null, null);
	}

	double evaluate(Scope s) {
		double r = Expr.eval(range, s);
		double t = Expr.eval(input, s) / (r == 0 ? 1 : r);
		return switch (type) {
			case LINEAR -> linear(s, t);
			case CATMULL_ROM -> catmullRom(s, t);
			case BEZIER -> bezier(s, t);
			case BEZIER_CHAIN -> bezierChain(t);
		};
	}

	private double node(Scope s, int i) {
		return Expr.eval(nodes[Math.clamp(i, 0, nodes.length - 1)], s);
	}

	private double linear(Scope s, double t) {
		if (nodes.length == 0) {
			return 0;
		}
		if (nodes.length == 1) {
			return node(s, 0);
		}
		double x = Math.clamp(t, 0, 1) * (nodes.length - 1);
		int i = Math.min((int) x, nodes.length - 2);
		double f = x - i;
		return node(s, i) * (1 - f) + node(s, i + 1) * f;
	}

	/** The first and last nodes only shape the ends; the curve runs from node 1 to node n-2. */
	private double catmullRom(Scope s, double t) {
		if (nodes.length < 4) {
			return linear(s, t);
		}
		int segments = nodes.length - 3;
		double x = Math.clamp(t, 0, 1) * segments;
		int i = Math.min((int) x, segments - 1);
		double f = x - i;
		double p0 = node(s, i);
		double p1 = node(s, i + 1);
		double p2 = node(s, i + 2);
		double p3 = node(s, i + 3);
		double f2 = f * f;
		double f3 = f2 * f;
		return 0.5 * (2 * p1 + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (-p0 + 3 * p1 - 3 * p2 + p3) * f3);
	}

	private double bezier(Scope s, double t) {
		if (nodes.length < 4) {
			return linear(s, t);
		}
		double u = Math.clamp(t, 0, 1);
		double v = 1 - u;
		return v * v * v * node(s, 0) + 3 * v * v * u * node(s, 1) + 3 * v * u * u * node(s, 2) + u * u * u * node(s, 3);
	}

	private double bezierChain(double t) {
		if (chainTimes.length == 0) {
			return 0;
		}
		if (t <= chainTimes[0]) {
			return chainValues[0][0];
		}
		int last = chainTimes.length - 1;
		if (t >= chainTimes[last]) {
			return chainValues[last][1];
		}
		int i = 0;
		while (i < last - 1 && t > chainTimes[i + 1]) {
			i++;
		}
		double t0 = chainTimes[i];
		double t1 = chainTimes[i + 1];
		double width = t1 - t0;
		double u = (t - t0) / width;
		double p0 = chainValues[i][1];
		double p3 = chainValues[i + 1][0];
		double p1 = p0 + chainValues[i][3] * width / 3;
		double p2 = p3 - chainValues[i + 1][2] * width / 3;
		double v = 1 - u;
		return v * v * v * p0 + 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u * p3;
	}
}
