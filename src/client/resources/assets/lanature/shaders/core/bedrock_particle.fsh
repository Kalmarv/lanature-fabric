#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

// Bedrock's particle materials: ALPHA_TEST cuts out texels under half the texture's alpha, before the tint, and draws
// the rest at full alpha (the tint's alpha unused); without it only near-transparent fragments go. NO_FOG skips fog.

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef NO_FOG
    #ifdef OIT_ACCUMULATE
    return sampleColorForAccumulation(color);
    #else
    return color;
    #endif
    #endif
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    #ifdef ALPHA_TEST
    if (texel.a < 0.5) {
        discard;
    }
    vec4 color = vec4((texel * vertexColor * ColorModulator).rgb, 1.0);
    #else
    vec4 color = texel * vertexColor * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    #endif
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
