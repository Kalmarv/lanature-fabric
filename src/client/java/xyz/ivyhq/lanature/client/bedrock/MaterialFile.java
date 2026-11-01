package xyz.ivyhq.lanature.client.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bedrock's {@code .material} format, resolved: every material's defines, states, depth function and blend factors
 * after inheritance. A key {@code name:parent} inherits the parent's settings; {@code +defines}, {@code -defines},
 * {@code +states} and {@code -states} add to or remove from them; {@code depthFunc}, {@code blendSrc} and
 * {@code blendDst} replace them. {@code samplerStates} replaces the samplers' filtering and wrapping, and
 * {@code +samplerStates} sets them sampler by sampler ({@code samplerIndex}), keeping what it does not name.
 */
public final class MaterialFile {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/materials");

	private MaterialFile() {
	}

	/** A sampler's {@code textureFilter} ({@code Point}, {@code Bilinear}...) and {@code textureWrap} ({@code Repeat}, {@code Clamp}); null where unset. */
	public record Sampler(@Nullable String filter, @Nullable String wrap) {
		static final Sampler DEFAULT = new Sampler(null, null);

		/** Filtered linearly: {@code Bilinear}, or {@code Trilinear}, which also blends mip levels (textures here have one). */
		public boolean linear() {
			return "Bilinear".equals(filter) || "Trilinear".equals(filter);
		}

		public boolean clamp() {
			return "Clamp".equals(wrap);
		}

		Sampler with(JsonObject state) {
			return new Sampler(state.has("textureFilter") ? state.get("textureFilter").getAsString() : filter,
				state.has("textureWrap") ? state.get("textureWrap").getAsString() : wrap);
		}
	}

	/** One material's resolved settings. */
	public record Settings(Set<String> defines, Set<String> states, String depthFunc, String blendSrc, String blendDst, Map<Integer, Sampler> samplers) {
		/** The sampler of a texture slot (0 is the main texture). */
		public Sampler sampler(int index) {
			return samplers.getOrDefault(index, Sampler.DEFAULT);
		}

		public boolean define(String name) {
			return defines.contains(name);
		}

		public boolean state(String name) {
			return states.contains(name);
		}

		/** The depth test on Java's reversed depth buffer; {@code DisableDepthTest} passes always. */
		public CompareOp depthTest() {
			return state("DisableDepthTest") ? CompareOp.ALWAYS_PASS : compare(depthFunc);
		}

		public BlendFactor source() {
			return factor(blendSrc);
		}

		public BlendFactor destination() {
			return factor(blendDst);
		}
	}

	/** Resolves every material in the files, later files replacing same-named entries of earlier ones. */
	public static Map<String, Settings> resolve(JsonObject... files) {
		Map<String, String> parents = new LinkedHashMap<>();
		Map<String, JsonObject> entries = new LinkedHashMap<>();
		for (JsonObject file : files) {
			JsonObject materials = file.has("materials") ? file.getAsJsonObject("materials") : new JsonObject();
			for (Map.Entry<String, JsonElement> e : materials.entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue; // "version"
				}
				String[] parts = e.getKey().split(":", 2);
				parents.put(parts[0], parts.length > 1 ? parts[1] : null);
				entries.put(parts[0], e.getValue().getAsJsonObject());
			}
		}
		Map<String, Builder> resolved = new HashMap<>();
		Map<String, Settings> out = new HashMap<>();
		for (String name : entries.keySet()) {
			out.put(name, settings(name, parents, entries, resolved, new HashSet<>()).build());
		}
		return Map.copyOf(out);
	}

	private static Builder settings(String name, Map<String, String> parents, Map<String, JsonObject> entries, Map<String, Builder> resolved,
		Set<String> visiting) {
		Builder done = resolved.get(name);
		if (done != null) {
			return done;
		}
		JsonObject entry = entries.get(name);
		String parent = parents.get(name);
		Builder s = parent != null && entries.containsKey(parent) && visiting.add(name)
			? settings(parent, parents, entries, resolved, visiting).copy()
			: new Builder();
		if (parent != null && !entries.containsKey(parent)) {
			LOGGER.warn("Material {} inherits undefined {}", name, parent);
		}
		if (entry != null) {
			edit(entry, "+defines", s.defines, true);
			edit(entry, "-defines", s.defines, false);
			edit(entry, "+states", s.states, true);
			edit(entry, "-states", s.states, false);
			if (entry.has("depthFunc")) {
				s.depthFunc = entry.get("depthFunc").getAsString();
			}
			if (entry.has("blendSrc")) {
				s.blendSrc = entry.get("blendSrc").getAsString();
			}
			if (entry.has("blendDst")) {
				s.blendDst = entry.get("blendDst").getAsString();
			}
			if (entry.has("samplerStates")) {
				s.samplers.clear();
			}
			samplers(entry, "samplerStates", s.samplers);
			samplers(entry, "+samplerStates", s.samplers);
		}
		resolved.put(name, s);
		return s;
	}

	private static void samplers(JsonObject entry, String key, Map<Integer, Sampler> target) {
		if (entry.has(key) && entry.get(key).isJsonArray()) {
			for (JsonElement e : entry.getAsJsonArray(key)) {
				if (e.isJsonObject() && e.getAsJsonObject().has("samplerIndex")) {
					JsonObject state = e.getAsJsonObject();
					target.merge(state.get("samplerIndex").getAsInt(), Sampler.DEFAULT.with(state), (old, x) -> old.with(state));
				}
			}
		}
	}

	private static void edit(JsonObject entry, String key, Set<String> target, boolean add) {
		if (entry.has(key) && entry.get(key).isJsonArray()) {
			entry.getAsJsonArray(key).forEach(v -> {
				if (add) {
					target.add(v.getAsString());
				} else {
					target.remove(v.getAsString());
				}
			});
		}
	}

	private static final class Builder {
		final Set<String> defines = new HashSet<>();
		final Set<String> states = new HashSet<>();
		String depthFunc = "LessEqual";
		String blendSrc = "SourceAlpha";
		String blendDst = "OneMinusSrcAlpha";
		final Map<Integer, Sampler> samplers = new HashMap<>();

		Builder copy() {
			Builder c = new Builder();
			c.defines.addAll(defines);
			c.states.addAll(states);
			c.samplers.putAll(samplers);
			c.depthFunc = depthFunc;
			c.blendSrc = blendSrc;
			c.blendDst = blendDst;
			return c;
		}

		Settings build() {
			return new Settings(Set.copyOf(defines), Set.copyOf(states), depthFunc, blendSrc, blendDst, Map.copyOf(samplers));
		}
	}

	private static BlendFactor factor(String name) {
		return switch (name) {
			case "One" -> BlendFactor.ONE;
			case "Zero" -> BlendFactor.ZERO;
			case "SourceAlpha" -> BlendFactor.SRC_ALPHA;
			case "OneMinusSrcAlpha" -> BlendFactor.ONE_MINUS_SRC_ALPHA;
			case "SourceColor" -> BlendFactor.SRC_COLOR;
			case "OneMinusSrcColor" -> BlendFactor.ONE_MINUS_SRC_COLOR;
			case "DestColor" -> BlendFactor.DST_COLOR;
			case "OneMinusDestColor" -> BlendFactor.ONE_MINUS_DST_COLOR;
			case "DestAlpha" -> BlendFactor.DST_ALPHA;
			case "OneMinusDestAlpha" -> BlendFactor.ONE_MINUS_DST_ALPHA;
			default -> {
				LOGGER.warn("Unknown blend factor {}; using SourceAlpha", name);
				yield BlendFactor.SRC_ALPHA;
			}
		};
	}

	/** Bedrock's depth functions on Java's reversed depth buffer (nearer is greater). */
	private static CompareOp compare(String name) {
		return switch (name) {
			case "Always" -> CompareOp.ALWAYS_PASS;
			case "Never" -> CompareOp.NEVER_PASS;
			case "Equal" -> CompareOp.EQUAL;
			case "NotEqual" -> CompareOp.NOT_EQUAL;
			case "Less" -> CompareOp.GREATER_THAN;
			case "Greater" -> CompareOp.LESS_THAN;
			case "GreaterEqual" -> CompareOp.LESS_THAN_OR_EQUAL;
			default -> CompareOp.GREATER_THAN_OR_EQUAL; // LessEqual
		};
	}
}
