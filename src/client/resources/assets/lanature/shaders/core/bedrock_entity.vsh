#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla's core/entity vertex shader, but with BEDROCK_OVERLAY the overlay is a Bedrock overlay colour carried in
// UV1 (red and green in the first value, blue and weight in the second, a byte each) instead of a texel of Java's
// overlay texture; it is passed on as Java's overlay is, the weight turned into the texture's share (1 - weight).
// With BEDROCK_COLOR_MASK the vertex colour's RGB is Bedrock's change colour, passed on unlit for the fragment shader
// to mask by the texel's alpha; only its alpha joins the light.

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#include <minecraft:light.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

#if !defined(NO_OVERLAY) && !defined(BEDROCK_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) out vec4 overlayColor;
#endif

layout(location = 6) out vec2 texCoord0;
#ifdef BEDROCK_COLOR_MASK
layout(location = 8) out vec3 changeColor;
#endif
#ifdef GLINT
layout(location = 7) out vec2 texCoordGlint;
#endif

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

#ifdef BEDROCK_COLOR_MASK
    changeColor = Color.rgb;
    vec4 color = vec4(1.0, 1.0, 1.0, Color.a);
#else
    vec4 color = Color;
#endif

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, color);
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    lightMapColor = sample_lightmap(Sampler2, UV2);
#endif

#if defined(BEDROCK_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    int overlayRg = UV1.x & 0xFFFF;
    int overlayBw = UV1.y & 0xFFFF;
    vec4 bedrockOverlay = vec4(overlayRg & 0xFF, overlayRg >> 8, overlayBw & 0xFF, overlayBw >> 8) / 255.0;
    overlayColor = vec4(bedrockOverlay.rgb, 1.0 - bedrockOverlay.a);
#elif !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif

    texCoord0 = UV0;

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
}
