package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Bedrock particle material reduced to the render state Java can reproduce: blending (null for an opaque quad),
 * depth write and test, culling, the {@code ALPHA_TEST} cut-out and the {@code ENABLE_FOG} fog. Materials come from
 * Bedrock's {@code .material} format ({@link #table}); each distinct state gets one particle layer on the particle atlas,
 * whose pipelines the game compiles on first use.
 */
public record Material(BlendFunction blend, boolean additive, boolean depthWrite, CompareOp depthTest, boolean cull, boolean alphaTest, boolean fog) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/particles");
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("lanature", "core/bedrock_particle");
	/** Bedrock's {@code ALPHA_TEST} discards below half alpha; without it, only near-transparent texels go. */
	private static final float ALPHA_TEST_CUTOFF = 0.5F;
	private static final Map<Material, SingleQuadParticle.Layer> LAYERS = new ConcurrentHashMap<>();

	/** The render layer for this state: one instance per state, as the particle renderer groups layers by identity. */
	SingleQuadParticle.Layer layer() {
		return LAYERS.computeIfAbsent(this, Material::createLayer);
	}

	// Layer takes the atlas's texture location, which only this (deprecated, replacement-less) constant names
	@SuppressWarnings("deprecation")
	private SingleQuadParticle.Layer createLayer() {
		String key = key();
		RenderPipeline.Builder pipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("lanature", "pipeline/bedrock_particle_" + key))
			.withDepthStencilState(new DepthStencilState(depthTest, depthWrite));
		shader(pipeline);
		if (blend == null) {
			pipeline.withColorTargetState(ColorTargetState.DEFAULT);
			return new SingleQuadParticle.Layer(false, TextureAtlas.LOCATION_PARTICLES, pipeline.build());
		}
		pipeline.withColorTargetState(new ColorTargetState(blend));
		// order-independent transparency accumulates either blended or additive colour; other blend factors fall back to the nearer
		RenderPipeline.Builder oit = RenderPipeline.builder(RenderPipelines.OIT_PARTICLE_SNIPPET);
		shader(oit);
		if (additive) {
			oit.withShaderDefine("OIT_ADDITIVE");
		}
		OitPipelineSet.Builder oitSet = OitPipelineSet.builder("lanature_bedrock_particle_" + key, oit);
		if (depthTest == CompareOp.ALWAYS_PASS) {
			oitSet.withoutDepthTest();
		}
		return new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, pipeline.build(), oitSet.build());
	}

	private void shader(RenderPipeline.Builder b) {
		b.withFragmentShader(SHADER).withCull(cull);
		if (alphaTest) {
			b.withShaderDefine("ALPHA_CUTOFF", ALPHA_TEST_CUTOFF);
		}
		if (!fog) {
			b.withShaderDefine("NO_FOG");
		}
	}

	@Override
	public String toString() {
		return key();
	}

	/** A resource-path-safe name for this state, e.g. {@code src_alpha-one_nw_ge_nc_f}. */
	private String key() {
		StringBuilder k = new StringBuilder();
		k.append(blend == null ? "opaque" : (blend.color().sourceFactor() + "-" + blend.color().destFactor()));
		k.append(depthWrite ? "_w" : "_nw").append('_').append(depthTest.name());
		k.append(cull ? "_c" : "_nc").append(alphaTest ? "_at" : "").append(fog ? "_f" : "");
		return k.toString().toLowerCase(Locale.ROOT);
	}

	// the .material format

	/**
	 * Resolves every material in the given {@code .material} files, later files overriding same-named entries of
	 * earlier ones. A key {@code name:parent} inherits the parent's settings; {@code +defines}, {@code -defines},
	 * {@code +states} and {@code -states} add to or remove from them; {@code depthFunc}, {@code blendSrc} and
	 * {@code blendDst} replace them.
	 */
	public static Map<String, Material> table(JsonObject... files) {
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
		Map<String, Settings> resolved = new HashMap<>();
		Map<String, Material> out = new HashMap<>();
		for (String name : entries.keySet()) {
			out.put(name, settings(name, parents, entries, resolved, new HashSet<>()).material());
		}
		return Map.copyOf(out);
	}

	/** The fallback for an effect naming a material no file defines. */
	static Material lookup(Map<String, Material> table, String name) {
		Material m = table.get(name);
		if (m == null) {
			LOGGER.warn("Unknown particle material {}; drawing it as particles_blend", name);
			m = table.getOrDefault("particles_blend", new Material(BlendFunction.TRANSLUCENT, false, true, CompareOp.GREATER_THAN_OR_EQUAL, false, false, true));
		}
		return m;
	}

	private static Settings settings(String name, Map<String, String> parents, Map<String, JsonObject> entries, Map<String, Settings> resolved, Set<String> visiting) {
		Settings done = resolved.get(name);
		if (done != null) {
			return done;
		}
		JsonObject entry = entries.get(name);
		String parent = parents.get(name);
		Settings s = parent != null && entries.containsKey(parent) && visiting.add(name)
			? settings(parent, parents, entries, resolved, visiting).copy()
			: new Settings();
		if (parent != null && !entries.containsKey(parent)) {
			LOGGER.warn("Particle material {} inherits undefined {}", name, parent);
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
		}
		resolved.put(name, s);
		return s;
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

	private static final class Settings {
		final Set<String> defines = new HashSet<>();
		final Set<String> states = new HashSet<>();
		String depthFunc = "LessEqual";
		String blendSrc = "SourceAlpha";
		String blendDst = "OneMinusSrcAlpha";

		Settings copy() {
			Settings c = new Settings();
			c.defines.addAll(defines);
			c.states.addAll(states);
			c.depthFunc = depthFunc;
			c.blendSrc = blendSrc;
			c.blendDst = blendDst;
			return c;
		}

		Material material() {
			BlendFunction blend = null;
			boolean additive = false;
			if (states.contains("Blending")) {
				BlendFactor src = factor(blendSrc);
				BlendFactor dst = factor(blendDst);
				blend = src == BlendFactor.SRC_ALPHA && dst == BlendFactor.ONE_MINUS_SRC_ALPHA ? BlendFunction.TRANSLUCENT : new BlendFunction(src, dst);
				additive = dst == BlendFactor.ONE;
			}
			CompareOp depth = states.contains("DisableDepthTest") ? CompareOp.ALWAYS_PASS : compare(depthFunc);
			return new Material(blend, additive, !states.contains("DisableDepthWrite"), depth, !states.contains("DisableCulling"),
				defines.contains("ALPHA_TEST"), defines.contains("ENABLE_FOG"));
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
