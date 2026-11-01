package xyz.ivyhq.lanature.client.particle;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.bedrock.MaterialFile;

/**
 * A Bedrock particle material reduced to the render state Java can reproduce: blending (null for an opaque quad),
 * depth write and test, culling, the {@code ALPHA_TEST} cut-out and the {@code ENABLE_FOG} fog, and its texture's
 * sampler (linear filtering, clamped). Materials come from Bedrock's {@code .material} format ({@link #table}); each
 * distinct state gets one particle layer on each texture an effect draws from, whose pipelines the game compiles on
 * first use (the sampler goes with the texture, not the pipeline).
 */
public record Material(BlendFunction blend, boolean additive, boolean depthWrite, CompareOp depthTest, boolean cull, boolean alphaTest, boolean fog,
	boolean linear, boolean clamp) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/particles");
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("lanature", "core/bedrock_particle");
	/** Pipelines by {@link #key()}: materials differing only in their sampler share them. */
	private static final Map<String, Pipelines> PIPELINES = new ConcurrentHashMap<>();
	private static final Map<LayerKey, SingleQuadParticle.Layer> LAYERS = new ConcurrentHashMap<>();

	/** A state's pipeline, and its order-independent transparency set when it blends. */
	private record Pipelines(RenderPipeline pipeline, OitPipelineSet oit) {
	}

	private record LayerKey(Material material, Identifier atlas) {
	}

	/**
	 * The render layer for this state on a texture (an atlas, or a texture of its own): one instance per pair, as the
	 * particle renderer groups layers by identity. The textures share the state's pipelines.
	 */
	SingleQuadParticle.Layer layer(Identifier atlas) {
		return LAYERS.computeIfAbsent(new LayerKey(this, atlas), k -> {
			Pipelines p = PIPELINES.computeIfAbsent(key(), x -> createPipelines());
			return new SingleQuadParticle.Layer(p.oit != null, atlas, p.pipeline, p.oit);
		});
	}

	private Pipelines createPipelines() {
		String key = key();
		RenderPipeline.Builder pipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("lanature", "pipeline/bedrock_particle_" + key))
			.withDepthStencilState(new DepthStencilState(depthTest, depthWrite));
		shader(pipeline);
		if (blend == null) {
			pipeline.withColorTargetState(ColorTargetState.DEFAULT);
			return new Pipelines(pipeline.build(), null);
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
		return new Pipelines(pipeline.build(), oitSet.build());
	}

	private void shader(RenderPipeline.Builder b) {
		b.withFragmentShader(SHADER).withCull(cull);
		if (alphaTest) {
			b.withShaderDefine("ALPHA_TEST");
		}
		if (!fog) {
			b.withShaderDefine("NO_FOG");
		}
	}

	@Override
	public String toString() {
		return key();
	}

	/** A resource-path-safe name for this state's pipeline, e.g. {@code src_alpha-one_nw_ge_nc_f} (the sampler left out). */
	private String key() {
		StringBuilder k = new StringBuilder();
		k.append(blend == null ? "opaque" : (blend.color().sourceFactor() + "-" + blend.color().destFactor()));
		k.append(depthWrite ? "_w" : "_nw").append('_').append(depthTest.name());
		k.append(cull ? "_c" : "_nc").append(alphaTest ? "_at" : "").append(fog ? "_f" : "");
		return k.toString().toLowerCase(Locale.ROOT);
	}

	// the .material format

	/**
	 * The particle state of every material in the given {@code .material} files ({@link MaterialFile}): blending with
	 * {@code Blending}, depth write and test, culling, {@code ALPHA_TEST} and {@code ENABLE_FOG}.
	 */
	public static Map<String, Material> table(JsonObject... files) {
		Map<String, Material> out = new HashMap<>();
		MaterialFile.resolve(files).forEach((name, settings) -> out.put(name, of(settings)));
		return Map.copyOf(out);
	}

	private static Material of(MaterialFile.Settings s) {
		BlendFunction blend = null;
		boolean additive = false;
		if (s.state("Blending")) {
			BlendFactor src = s.source();
			BlendFactor dst = s.destination();
			blend = src == BlendFactor.SRC_ALPHA && dst == BlendFactor.ONE_MINUS_SRC_ALPHA ? BlendFunction.TRANSLUCENT : new BlendFunction(src, dst);
			additive = dst == BlendFactor.ONE;
		}
		return new Material(blend, additive, !s.state("DisableDepthWrite"), s.depthTest(), !s.state("DisableCulling"), s.define("ALPHA_TEST"),
			s.define("ENABLE_FOG"), s.sampler(0).linear(), s.sampler(0).clamp());
	}

	/** The fallback for an effect naming a material no file defines. */
	static Material lookup(Map<String, Material> table, String name) {
		Material m = table.get(name);
		if (m == null) {
			LOGGER.warn("Unknown particle material {}; drawing it as particles_blend", name);
			m = table.getOrDefault("particles_blend",
				new Material(BlendFunction.TRANSLUCENT, false, true, CompareOp.GREATER_THAN_OR_EQUAL, false, false, true, false, false));
		}
		return m;
	}
}
