package xyz.ivyhq.lanature.client.model;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.bedrock.MaterialFile;

/**
 * A Bedrock entity material reduced to what the port draws: blending (null for opaque), depth write and test, alpha
 * write, culling, the {@code ALPHA_TEST} cut-out (for a colour-mask material, only fully transparent texels: the
 * others' alpha is how far the change colour tints them), {@code USE_EMISSIVE}, {@code USE_OVERLAY}, {@code USE_COLOR_MASK} and
 * {@code USE_UV_ANIM}. Each distinct state is one pipeline
 * over the port's {@code lanature:core/bedrock_entity} shaders (vanilla's {@code core/entity} vertex shader; a fragment
 * shader with Bedrock's alpha test and emissive rule, a texel glowing by one minus its alpha), compiled by the game on
 * first use, with an order-independent transparency set when it blends; render types are made per state and texture.
 * A material with {@code USE_OVERLAY} takes its overlay colour (the render controller's {@code overlay_color}, or Java's
 * hurt and white flashes) from the vertices' overlay coordinates, see {@link #overlayCoords}; the others take Java's
 * overlay texture. A material with {@code USE_COLOR_MASK} tints each texel by the change colour (the render
 * controller's {@code color}, else the engine's: a collar's or wool's dye) as far as the texel's alpha, and scales its
 * alpha by the colour's (Bedrock's 1.16 entity shader; the colour reaches the shader as the vertex colour, see
 * {@link #vertexColor}); the others leave the change colour unused, as Bedrock does. A
 * material with {@code USE_UV_ANIM} draws its texture coordinates moved and scaled by the render controller's
 * {@code uv_anim} (applied to the vertices; Bedrock's vertex shader does the same per vertex). Multi-texture materials
 * draw their first texture only.
 */
public record EntityMaterial(@Nullable BlendFunction blend, boolean additive, boolean depthWrite, CompareOp depthTest, boolean alphaWrite, boolean cull,
	boolean alphaTest, boolean emissive, boolean overlay, boolean colorMask, boolean uvAnim) {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/entities");
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("lanature", "core/bedrock_entity");
	public static final EntityMaterial DEFAULT = new EntityMaterial(null, false, true, CompareOp.GREATER_THAN_OR_EQUAL, true, false, true, false, false, false, false);
	private static final Map<EntityMaterial, Pipelines> PIPELINES = new ConcurrentHashMap<>();
	private static final Map<Key, RenderType> TYPES = new ConcurrentHashMap<>();

	private record Pipelines(RenderPipeline main, @Nullable OitPipelineSet oit) {
	}

	private record Key(EntityMaterial material, Identifier texture) {
	}

	/** The render type drawing this material with a texture. */
	public RenderType renderType(Identifier texture) {
		return TYPES.computeIfAbsent(new Key(this, texture), k -> {
			Pipelines p = PIPELINES.computeIfAbsent(this, EntityMaterial::createPipelines);
			RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(p.main()).withTexture("Sampler0", texture).useLightmap();
			if (!overlay) {
				setup.useOverlay();
			}
			if (p.oit() != null) {
				setup.setOitPipelines(p.oit()).sortOnUpload();
			} else {
				setup.affectsCrumbling().setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE);
			}
			return RenderType.create("lanature_bedrock_entity_" + key(), setup.createRenderSetup());
		});
	}

	private Pipelines createPipelines() {
		String key = key();
		RenderPipeline.Builder main = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("lanature", "pipeline/bedrock_entity_" + key))
			.withDepthStencilState(new DepthStencilState(depthTest, depthWrite))
			.withColorTargetState(new ColorTargetState(Optional.ofNullable(blend), GpuFormat.RGBA8_UNORM,
				alphaWrite ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_COLOR));
		if (!overlay) {
			main.withBindGroupLayout(BindGroupLayouts.SAMPLER1);
		}
		shader(main);
		if (blend != null) {
			// as vanilla's translucent entities (its OIT snippet sets the same): faint texels write nothing
			main.withShaderDefine("ALPHA_CUTOUT", 0.1F);
		}
		if (blend == null) {
			return new Pipelines(main.build(), null);
		}
		RenderPipeline.Builder oit = RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET);
		shader(oit);
		if (additive) {
			oit.withShaderDefine("OIT_ADDITIVE");
		}
		OitPipelineSet.Builder set = OitPipelineSet.builder("lanature_bedrock_entity_" + key, oit);
		// the accumulate pass shades, with the overlay and the lightmap
		set.withAccumulateModifier(a -> {
			if (!overlay) {
				a.withBindGroupLayout(BindGroupLayouts.SAMPLER1);
			}
			a.withBindGroupLayout(BindGroupLayouts.SAMPLER2);
		});
		if (depthTest == CompareOp.ALWAYS_PASS) {
			set.withoutDepthTest();
		}
		return new Pipelines(main.build(), set.build());
	}

	private void shader(RenderPipeline.Builder b) {
		b.withVertexShader(SHADER).withFragmentShader(SHADER).withCull(cull).withShaderDefine("PER_FACE_LIGHTING");
		if (alphaTest) {
			b.withShaderDefine("ALPHA_TEST");
		}
		if (emissive) {
			b.withShaderDefine("BEDROCK_EMISSIVE");
		}
		if (overlay) {
			b.withShaderDefine("BEDROCK_OVERLAY");
		}
		if (colorMask) {
			b.withShaderDefine("BEDROCK_COLOR_MASK");
		}
	}

	/**
	 * The vertex colour to draw this material with: Java's {@code javaColor} (ARGB), times the render controller's
	 * change colour ({@code changeColor}, ARGB) for a {@code USE_COLOR_MASK} material, whose shader takes the RGB as the
	 * mask's tint (so a Java tint is masked too) and multiplies the alpha in.
	 */
	public int vertexColor(int javaColor, int changeColor) {
		return colorMask ? ARGB.multiply(javaColor, changeColor) : javaColor;
	}

	/**
	 * The overlay coordinates to draw this material with: Java's {@code javaOverlay} unchanged for a material without
	 * {@code USE_OVERLAY}; for one with it, a Bedrock overlay colour (RGB and weight, packed as {@code 0xWWBBGGRR}, which
	 * the vertex format splits into its two 16-bit overlay values): Java's hurt or white flash when there is one, as
	 * Bedrock's hurt colour takes over the overlay, else the render controller's {@code overlayColor} (0 for none).
	 */
	public int overlayCoords(int javaOverlay, int overlayColor) {
		if (!overlay) {
			return javaOverlay;
		}
		int u = javaOverlay & 0xFFFF;
		int v = javaOverlay >>> 16;
		if (v < 8) {
			// Java's hurt red: its texel is 0xB2FF0000, mixed as mix(red, texel, 178 / 255)
			return 0xFF | ((255 - 178) << 24);
		}
		// Java's white flash: alpha 1 - u / 15 * 0.75 for the texel, so the white's weight is u / 15 * 0.75
		int white = (int) (u / 15.0F * 0.75F * 255.0F);
		return white > 0 ? 0xFFFFFF | (white << 24) : overlayColor;
	}

	@Override
	public String toString() {
		return key();
	}

	/** A resource-path-safe name for this state, e.g. {@code src_alpha-one_nw_greater_than_or_equal_nc_e}. */
	private String key() {
		StringBuilder k = new StringBuilder();
		k.append(blend == null ? "opaque" : (blend.color().sourceFactor() + "-" + blend.color().destFactor()));
		k.append(depthWrite ? "_w" : "_nw").append('_').append(depthTest.name()).append(alphaWrite ? "" : "_naw");
		k.append(cull ? "_c" : "_nc").append(alphaTest ? "_at" : "").append(emissive ? "_e" : "").append(overlay ? "_o" : "")
			.append(colorMask ? "_cm" : "").append(uvAnim ? "_uv" : "");
		return k.toString().toLowerCase(Locale.ROOT);
	}

	/** The entity state of every material in the given {@code .material} files. */
	public static Map<String, EntityMaterial> table(JsonObject... files) {
		Map<String, EntityMaterial> out = new HashMap<>();
		MaterialFile.resolve(files).forEach((name, s) -> out.put(name, of(s)));
		return Map.copyOf(out);
	}

	private static EntityMaterial of(MaterialFile.Settings s) {
		BlendFunction blend = null;
		boolean additive = false;
		if (s.state("Blending")) {
			BlendFactor src = s.source();
			BlendFactor dst = s.destination();
			blend = src == BlendFactor.SRC_ALPHA && dst == BlendFactor.ONE_MINUS_SRC_ALPHA ? BlendFunction.TRANSLUCENT : new BlendFunction(src, dst);
			additive = dst == BlendFactor.ONE;
		}
		return new EntityMaterial(blend, additive, !s.state("DisableDepthWrite"), s.depthTest(), !s.state("DisableAlphaWrite"), !s.state("DisableCulling"),
			s.define("ALPHA_TEST"),
			s.define("USE_EMISSIVE"), s.define("USE_OVERLAY"), s.define("USE_COLOR_MASK"), s.define("USE_UV_ANIM"));
	}

	/** A material by name, or the default cut-out one (with a warning) when no file defines it. */
	public static EntityMaterial lookup(Map<String, EntityMaterial> table, String name) {
		EntityMaterial m = table.get(name);
		if (m == null) {
			LOGGER.warn("Unknown entity material {}; drawing it as entity_alphatest", name);
			return table.getOrDefault("entity_alphatest", DEFAULT);
		}
		return m;
	}
}
