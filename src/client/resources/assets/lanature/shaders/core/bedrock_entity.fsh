#version 330
#extension GL_ARB_separate_shader_objects : require

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

// Bedrock's entity materials, after bedrock_entity.vsh (vanilla's core/entity vertex shader: lit, per-face shading,
// Java's overlay or, with BEDROCK_OVERLAY, a Bedrock overlay colour; either is mixed in before the light).
// ALPHA_TEST discards below half alpha; with BEDROCK_COLOR_MASK only zero alpha; with BEDROCK_EMISSIVE only transparent
// black, and a texel glows by one minus its alpha: its light (lightmap and face shading) is mixed toward full by that
// much. ALPHA_CUTOUT, when set without ALPHA_TEST, drops near-transparent texels of blended materials.
// BEDROCK_COLOR_MASK is Bedrock's USE_COLOR_MASK: the change colour (the render controller's color, else the engine's
// collar or wool dye; its alpha is in the vertex colour's) tints a texel as far as the texel's alpha.
// GLINT adds Java's enchantment glint as vanilla's core/entity does.

uniform sampler2D Sampler0;
#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
layout(location = 4) in vec4 lightMapColor;
layout(location = 5) in vec4 overlayColor;
layout(location = 6) in vec2 texCoord0;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif
#ifdef BEDROCK_COLOR_MASK
layout(location = 8) in vec3 changeColor;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);

    #ifdef ALPHA_TEST
    #ifdef BEDROCK_EMISSIVE
    if (color.r + color.g + color.b + color.a < 0.002) {
        discard;
    }
    #elif defined(BEDROCK_COLOR_MASK)
    if (color.a < 0.002) {
        discard;
    }
    #else
    if (color.a < 0.5) {
        discard;
    }
    #endif
    #elif defined(ALPHA_CUTOUT)
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a * faceVertexColor.a * ColorModulator.a);
    #else
    vec4 light = faceVertexColor * lightMapColor;
    #ifdef BEDROCK_COLOR_MASK
    color.rgb = mix(color.rgb, color.rgb * changeColor, color.a);
    #endif
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #ifdef BEDROCK_EMISSIVE
    color.rgb *= mix(vec3(1.0), light.rgb, color.a);
    #else
    color.rgb *= light.rgb;
    #endif
    color.a *= light.a;
    color *= ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // as vanilla's (BlendFunction.GLINT)
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif
}
