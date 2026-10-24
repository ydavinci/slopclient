/** Plain stand-in skin when no account skin is available (dark outfit, light face). */
export function fallbackSkin(): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#2a2340";
  ctx.fillRect(0, 0, 64, 64);
  ctx.fillStyle = "#3b3158";
  ctx.fillRect(16, 16, 40, 16);
  ctx.fillStyle = "#c9a68a";
  ctx.fillRect(0, 0, 32, 16);
  ctx.fillStyle = "#3a2a20";
  ctx.fillRect(8, 0, 16, 8);
  ctx.fillRect(0, 8, 32, 2);
  ctx.fillStyle = "#1d1729";
  ctx.fillRect(0, 16, 16, 16);
  ctx.fillRect(16, 48, 32, 16);
  return c;
}
