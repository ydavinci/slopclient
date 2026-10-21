#version 330
#extension GL_ARB_separate_shader_objects : require

// Flowing violet menu backdrop, the same shader as the launcher (launcher/src/ui/Shader.tsx).
// The vertex colour carries the inputs: green and blue are a 16-bit time counter in
// 1/20 s, red is the accent hue shift (0..255 for -180..180 degrees), alpha is the fade.

#include <minecraft:globals.glsl>

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

float h(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float n(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + vec2(1.0, 0.0)), f.x), mix(h(i + vec2(0.0, 1.0)), h(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Turns a colour around the grey axis: the launcher's accent hue.
vec3 hue(vec3 c, float a) {
    vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * n(p);
        p *= 2.02;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec3 c = floor(vertexColor.rgb * 255.0 + 0.5);
    float t = (c.g * 256.0 + c.b) / 20.0;
    float shift = radians(c.r / 255.0 * 360.0 - 180.0);
    vec2 r = ScreenSize;
    vec2 uv = gl_FragCoord.xy / r;
    vec2 p = (gl_FragCoord.xy - 0.5 * r) / r.y;
    float tt = t * 0.045;
    vec2 q = vec2(fbm(p * 1.5 + tt), fbm(p * 1.5 - tt + 5.2));
    float f = fbm(p * 1.9 + q * 1.9 + tt * 0.6);
    vec3 col = vec3(0.034, 0.03, 0.054);
    col = mix(col, vec3(0.24, 0.11, 0.55), smoothstep(0.38, 0.9, f));
    col = mix(col, vec3(0.55, 0.17, 0.6), smoothstep(0.55, 1.0, f * q.x * 1.45));
    float band = abs(p.y + 0.22 * sin(p.x * 2.1 + tt * 7.0) + 0.12 * q.y + 0.08);
    col += vec3(0.6, 0.45, 1.0) * 0.16 * exp(-band * 16.0);
    float band2 = abs(p.y - 0.18 * sin(p.x * 1.6 - tt * 5.0) - 0.1 * q.x - 0.32);
    col += vec3(0.9, 0.4, 0.95) * 0.08 * exp(-band2 * 22.0);
    col *= 1.0 - 0.6 * length(uv - vec2(0.5, 0.45));
    // Fine grain so the gradients never band.
    col += (h(gl_FragCoord.xy + fract(t) * 91.0) - 0.5) * 0.018;
    fragColor = vec4(max(hue(col, shift), 0.0), vertexColor.a);
}
