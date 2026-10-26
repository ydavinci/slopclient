#version 330
#extension GL_ARB_separate_shader_objects : require

// The launcher's flowing violet shader (launcher/src/ui/Shader.tsx, menu_shader.fsh) as a
// sky. The noise is sampled on the view direction in 3D and the bands follow the azimuth
// with whole periods, so the sky has no seam and stays put while the camera turns.
// ColorModulator carries the inputs: x is the time in seconds, y the accent hue shift
// (0..1 for -180..180 degrees).

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 direction;

layout(location = 0) out vec4 fragColor;

float h(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }

float n(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(h(i), h(i + vec3(1.0, 0.0, 0.0)), f.x), mix(h(i + vec3(0.0, 1.0, 0.0)), h(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
        mix(mix(h(i + vec3(0.0, 0.0, 1.0)), h(i + vec3(1.0, 0.0, 1.0)), f.x), mix(h(i + vec3(0.0, 1.0, 1.0)), h(i + vec3(1.0, 1.0, 1.0)), f.x), f.y),
        f.z);
}

float fbm(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * n(p);
        p = p * 2.02 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}

// Turns a colour around the grey axis: the launcher's accent hue.
vec3 hue(vec3 c, float a) {
    vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

void main() {
    vec3 d = normalize(direction);
    float t = ColorModulator.x;
    float shift = radians(ColorModulator.y * 360.0 - 180.0);
    float tt = t * 0.045;
    vec3 p = d * 1.6;
    vec2 q = vec2(fbm(p * 1.5 + tt), fbm(p * 1.5 - tt + 5.2));
    float f = fbm(p * 1.9 + vec3(q, q.x * q.y) * 1.9 + tt * 0.6);
    vec3 col = vec3(0.034, 0.03, 0.054);
    col = mix(col, vec3(0.24, 0.11, 0.55), smoothstep(0.38, 0.9, f));
    col = mix(col, vec3(0.55, 0.17, 0.6), smoothstep(0.55, 1.0, f * q.x * 1.45));
    // The launcher's two light bands, wrapped around the horizon.
    float az = atan(d.x, d.z);
    float el = asin(clamp(d.y, -1.0, 1.0)) / 1.5708;
    float band = abs(el - 0.12 + 0.16 * sin(az * 2.0 + tt * 7.0) + 0.1 * q.y);
    col += vec3(0.6, 0.45, 1.0) * 0.16 * exp(-band * 14.0);
    float band2 = abs(el - 0.42 - 0.12 * sin(az * 3.0 - tt * 5.0) - 0.08 * q.x);
    col += vec3(0.9, 0.4, 0.95) * 0.08 * exp(-band2 * 20.0);
    // Darker below the horizon and towards the zenith, like the launcher's vignette.
    col *= mix(0.45, 1.0, smoothstep(-0.4, 0.05, d.y)) * (1.0 - 0.35 * smoothstep(0.55, 1.0, d.y));
    // Fine grain so the gradients never band.
    col += (h(floor(d * 900.0) + fract(t) * 91.0) - 0.5) * 0.018;
    fragColor = vec4(max(hue(col, shift), 0.0), 1.0);
}
