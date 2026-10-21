/**
 * Accent colour: the user picks a hue, and every tinted token (background, panels,
 * text greys, the three accent stops) is turned to it in OKLCH, which keeps lightness
 * and chroma, so contrast stays the same. The client mod applies the same shift
 * (client-mod/.../render/Theme.java).
 */

/** Hue (OKLCH degrees) of the original violet accent #8b5cf6. */
export const DEFAULT_HUE = 293;

export const HUE_PRESETS: { name: string; hue: number }[] = [
  { name: "Lila", hue: 293 },
  { name: "Blau", hue: 258 },
  { name: "Cyan", hue: 220 },
  { name: "Grün", hue: 150 },
  { name: "Gelb", hue: 95 },
  { name: "Orange", hue: 55 },
  { name: "Rot", hue: 22 },
  { name: "Pink", hue: 345 },
];

/** Tokens that carry the violet tint, with their original value and alpha. */
const TOKENS: [string, string, number?][] = [
  ["--color-bg", "#09080e"],
  ["--color-panel", "#14111e", 0.72],
  ["--color-panel-solid", "#15121f"],
  ["--color-raised", "#262036", 0.72],
  ["--color-text", "#efecf7"],
  ["--color-muted", "#a39cb6"],
  ["--color-dim", "#726b86"],
  ["--color-accent", "#8b5cf6"],
  ["--color-accent-2", "#c084fc"],
  ["--color-accent-3", "#e879f9"],
];

const toLinear = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const toGamma = (c: number) => (c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055);

export function hexToOklch(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => toLinear(v / 255));
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
  const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
  return [L, Math.hypot(A, B), ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360];
}

export function oklchToRgb(L: number, C: number, H: number): [number, number, number] {
  const h = (H * Math.PI) / 180;
  const a = C * Math.cos(h), b = C * Math.sin(h);
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const rgb = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
  return rgb.map((c) => Math.round(Math.min(1, Math.max(0, toGamma(c))) * 255)) as [number, number, number];
}

/** A token's colour turned by `shift` degrees. */
function shifted(hex: string, shift: number): [number, number, number] {
  const [L, C, H] = hexToOklch(hex);
  return oklchToRgb(L, C, H + shift);
}

/** Preview colour for a hue (the main accent at that hue). */
export function accentFor(hue: number): string {
  const [r, g, b] = shifted("#8b5cf6", hue - DEFAULT_HUE);
  return `rgb(${r} ${g} ${b})`;
}

/** Sets the CSS tokens for the hue; the default hue restores the stylesheet's values. */
export function applyAccent(hue: number | null | undefined) {
  const root = document.documentElement.style;
  const shift = ((hue ?? DEFAULT_HUE) - DEFAULT_HUE + 540) % 360 - 180;
  for (const [name, hex, alpha] of TOKENS) {
    if (shift === 0) {
      root.removeProperty(name);
      continue;
    }
    const [r, g, b] = shifted(hex, shift);
    root.setProperty(name, alpha === undefined ? `rgb(${r} ${g} ${b})` : `rgb(${r} ${g} ${b} / ${alpha})`);
  }
  // Instance tiles and the shader backdrop follow by rotating their hue.
  root.setProperty("--accent-shift", `${shift}deg`);
  accentShiftRad = (shift * Math.PI) / 180;
}

/** Current hue shift in radians, read by the WebGL backdrop each frame. */
export let accentShiftRad = 0;
