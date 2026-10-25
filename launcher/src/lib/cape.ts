// Cape Studio: frame decoding, Minecraft's cape texture layout and spritesheet export.
// The output format is shared with the client mod and the backend (slopclient-core cosmetics.rs).

export interface CapeMeta {
  version: 1;
  frames: number;
  frameTimeMs: number;
  scale: 1 | 2 | 4 | 8 | 16;
  elytra: boolean;
}

export const LIMITS = { maxFrames: 128, minFrameMs: 40, maxBytes: 8 * 1024 * 1024 };
export const SCALES = [1, 2, 4, 8, 16] as const;
/** Tallest spritesheet browsers and PNG decoders handle reliably (same rule as the core). */
export const MAX_SHEET_HEIGHT = 32768;

export function maxFramesFor(scale: number) {
  return Math.min(LIMITS.maxFrames, Math.floor(MAX_SHEET_HEIGHT / (32 * scale)));
}

/** Decoded source: frames with their display time. */
export interface Source {
  frames: ImageBitmap[];
  durations: number[];
  width: number;
  height: number;
  /** Frames were dropped to stay within the limit. */
  sampled: boolean;
}

/** Everything the editor controls; stored with the cape so it can be edited again. */
export interface EditorState {
  zoom: number;
  /** Offset of the image centre in units of the front face size (-1..1). */
  offsetX: number;
  offsetY: number;
  rotation: 0 | 90 | 180 | 270;
  fit: "cover" | "contain";
  back: "mirror" | "same" | "color";
  backColor: string;
  /** "wrap": the picture runs on over the cape's sides, top and bottom; otherwise they get one colour. */
  edge: "wrap" | "auto" | "color";
  edgeColor: string;
  elytra: boolean;
  trimStart: number;
  trimEnd: number;
  speed: number;
  scale: 1 | 2 | 4 | 8 | 16;
}

export function defaultState(source: Source): EditorState {
  return {
    zoom: 1,
    offsetX: 0,
    offsetY: 0,
    rotation: 0,
    fit: "cover",
    back: "mirror",
    backColor: "#15121f",
    edge: "wrap",
    edgeColor: "#15121f",
    elytra: true,
    trimStart: 0,
    trimEnd: source.frames.length - 1,
    // HD by default; small sources stay at the size they have.
    scale: Math.max(source.width, source.height) >= 160 ? 4 : Math.max(source.width, source.height) >= 64 ? 2 : 1,
    speed: 1,
  };
}

/** Decodes all frames of a GIF/APNG/WebP (or the single frame of a still image). */
export async function decode(file: Blob): Promise<Source> {
  const type = file.type || "image/png";
  const Decoder = (globalThis as unknown as { ImageDecoder?: typeof ImageDecoder }).ImageDecoder;
  if (Decoder && (await Decoder.isTypeSupported(type).catch(() => false))) {
    const decoder = new Decoder({ data: await file.arrayBuffer(), type });
    await decoder.tracks.ready;
    const count = decoder.tracks.selectedTrack?.frameCount ?? 1;
    // Keep at most maxFrames, evenly spread over the animation.
    const step = Math.max(1, count / LIMITS.maxFrames);
    const frames: ImageBitmap[] = [];
    const durations: number[] = [];
    for (let i = 0; i < count && frames.length < LIMITS.maxFrames; i += step) {
      const { image } = await decoder.decode({ frameIndex: Math.floor(i) });
      frames.push(await createImageBitmap(image));
      // Duration is in microseconds; GIFs without one play at 100 ms.
      durations.push(Math.max(20, ((image.duration ?? 100_000) / 1000) * step));
      image.close();
    }
    decoder.close();
    return { frames, durations, width: frames[0].width, height: frames[0].height, sampled: step > 1 };
  }
  const bitmap = await createImageBitmap(file);
  return { frames: [bitmap], durations: [100], width: bitmap.width, height: bitmap.height, sampled: false };
}

/** Faces of the 64 × 32 cape texture (Minecraft's box UV for 10 × 16 × 1; the elytra wing see shapeElytra). */
export const FACES = {
  front: { x: 1, y: 1, w: 10, h: 16 },
  back: { x: 12, y: 1, w: 10, h: 16 },
  capeArea: { x: 0, y: 0, w: 22, h: 17 },
};

/** Average colour of the image border, for the cape's sides and edges. */
export function edgeColor(frame: ImageBitmap): string {
  const c = new OffscreenCanvas(32, 32);
  const ctx = c.getContext("2d")!;
  ctx.drawImage(frame, 0, 0, 32, 32);
  const data = ctx.getImageData(0, 0, 32, 32).data;
  let r = 0, g = 0, b = 0, n = 0;
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      if (x > 0 && x < 31 && y > 0 && y < 31) continue;
      const i = (y * 32 + x) * 4;
      if (data[i + 3] < 32) continue;
      r += data[i];
      g += data[i + 1];
      b += data[i + 2];
      n++;
    }
  }
  if (!n) return "#15121f";
  const hex = (v: number) => Math.round(v / n).toString(16).padStart(2, "0");
  return `#${hex(r)}${hex(g)}${hex(b)}`;
}

type Rect = { x: number; y: number; w: number; h: number };

/**
 * Draws `frame` into a face with the editor's zoom, offset, rotation and fit, clipped to it.
 * With `bleed` the picture is laid out on the face grown by that many pixels on every side
 * (the box's edges around it), so the same crop fits the wrapped front.
 */
function drawFace(ctx: CanvasRenderingContext2D, frame: ImageBitmap, face: Rect, s: number, st: EditorState, mirror: boolean, bleed = 0) {
  const fx = face.x * s, fy = face.y * s;
  ctx.save();
  ctx.beginPath();
  ctx.rect(fx, fy, face.w * s, face.h * s);
  ctx.clip();
  layout(ctx, frame, { x: face.x - bleed, y: face.y - bleed, w: face.w + 2 * bleed, h: face.h + 2 * bleed }, s, st, mirror);
  ctx.restore();
}

/** Draws `frame` laid out on `area` (texture pixels), without clipping. */
function layout(ctx: CanvasRenderingContext2D, frame: ImageBitmap, area: Rect, s: number, st: EditorState, mirror: boolean) {
  const fx = area.x * s, fy = area.y * s, fw = area.w * s, fh = area.h * s;
  const rotated = st.rotation === 90 || st.rotation === 270;
  const iw = rotated ? frame.height : frame.width;
  const ih = rotated ? frame.width : frame.height;
  const base = st.fit === "cover" ? Math.max(fw / iw, fh / ih) : Math.min(fw / iw, fh / ih);
  const k = base * st.zoom;
  ctx.save();
  ctx.translate(fx + fw / 2 + st.offsetX * fw * (mirror ? -1 : 1), fy + fh / 2 + st.offsetY * fh);
  if (mirror) ctx.scale(-1, 1);
  ctx.rotate((st.rotation * Math.PI) / 180);
  ctx.scale(k, k);
  ctx.drawImage(frame, -frame.width / 2, -frame.height / 2);
  ctx.restore();
}

/**
 * Draws the picture over a box's front face and on over its four edges (depth `d`), like
 * one sheet wrapped around it: the sides next to the front, the top above it and the
 * bottom (Minecraft's box UV, whose bottom face is flipped so its last row meets the front).
 */
function drawWrapped(ctx: CanvasRenderingContext2D, frame: ImageBitmap, front: Rect, d: number, s: number, st: EditorState) {
  const w = front.w, h = front.h;
  const sheet = document.createElement("canvas");
  sheet.width = (w + 2 * d) * s;
  sheet.height = (h + 2 * d) * s;
  const sc = sheet.getContext("2d")!;
  sc.imageSmoothingEnabled = ctx.imageSmoothingEnabled;
  sc.imageSmoothingQuality = "high";
  layout(sc, frame, { x: 0, y: 0, w: w + 2 * d, h: h + 2 * d }, s, st, false);
  const copy = (sx: number, sy: number, sw: number, sh: number, dx: number, dy: number) => {
    ctx.drawImage(sheet, sx * s, sy * s, sw * s, sh * s, dx * s, dy * s, sw * s, sh * s);
  };
  // Left side, front, right side.
  copy(0, d, w + 2 * d, h, front.x - d, front.y);
  // Top, row by row so the one next to the front stays next to it.
  copy(d, 0, w, d, front.x, front.y - d);
  // Bottom: flipped, its last texture row meets the front's bottom edge.
  for (let r = 0; r < d; r++) copy(d, h + d + r, w, 1, front.x + w, front.y - 1 - r);
}

/** Renders one frame as a 64·s × 32·s cape texture. */
export function renderFrame(frame: ImageBitmap, st: EditorState, edge: string, target?: HTMLCanvasElement): HTMLCanvasElement {
  const s = st.scale;
  const canvas = target ?? document.createElement("canvas");
  canvas.width = 64 * s;
  canvas.height = 32 * s;
  const ctx = canvas.getContext("2d")!;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.imageSmoothingEnabled = Math.min(frame.width, frame.height) > 32 * s;
  ctx.imageSmoothingQuality = "high";

  const edgeFill = st.edge === "auto" ? edge : st.edgeColor;
  const fill = (a: { x: number; y: number; w: number; h: number }, color: string) => {
    ctx.fillStyle = color;
    ctx.fillRect(a.x * s, a.y * s, a.w * s, a.h * s);
  };
  fill(FACES.capeArea, edgeFill);
  // The area outside the faces is unused; keep it clear apart from the cape box.
  ctx.clearRect(0, 0, 1 * s, 1 * s);
  ctx.clearRect(21 * s, 0, 1 * s, 1 * s);

  // Wrapped, the picture is laid out on the front plus its edges, and the back keeps the same crop.
  const wrap = st.edge === "wrap";
  if (wrap) drawWrapped(ctx, frame, FACES.front, 1, s, st);
  else drawFace(ctx, frame, FACES.front, s, st, false);
  if (st.back === "color") fill(FACES.back, st.backColor);
  else drawFace(ctx, frame, FACES.back, s, st, st.back === "mirror", wrap ? 1 : 0);

  // The wings show the cape's front, like the mod makes of capes without them.
  if (st.elytra) shapeElytra(ctx, s, true);
  return canvas;
}

/**
 * The painted pixels of the vanilla elytra texture (x 22..45, y 0..21). The wing is one
 * face of a box that Minecraft inflates to 4 px thickness: vanilla paints only the back
 * face and a few edge pixels and leaves the front face and sides clear. Painting more
 * shows the box as a hollow slab. Same mask as CapeAnimation.ELYTRA_MASK in the mod.
 */
const ELYTRA_MASK = [
  ".........#########......",
  "..........##............",
  "............########....",
  "............#########...",
  ".............#########..",
  ".............#########..",
  ".............#########..",
  ".............##########.",
  ".............##########.",
  ".............##########.",
  ".............##########.",
  "#.............##########",
  "#.............##########",
  "#.............##########",
  "#.............##########",
  "#.............##########",
  "#..............#########",
  "#..............#########",
  "#..............#########",
  "#...............########",
  "#...............########",
  "#................#######",
];

/** Whether a point (in 64 × 32 texture pixels) of the elytra area stays visible. */
function keepElytra(px: number, py: number): boolean {
  const row = ELYTRA_MASK[Math.floor(py)];
  return row !== undefined && row[Math.floor(px) - 22] === "#";
}

/**
 * Turns the elytra part of a cape texture into vanilla-shaped wings. The visible wing is
 * the box's back face (x 36..45, y 2..21). When that face does not cover the wing (capes
 * saved with an older, wrong silhouette, or with nothing there), it is drawn anew from
 * the cape's front, `force` always does so. The few edge pixels vanilla paints take the
 * neighbouring colour of the wing, and everything outside the silhouette is cleared.
 * Same as CapeAnimation.shapeElytra in the mod.
 */
function shapeElytra(ctx: CanvasRenderingContext2D, s: number, force = false) {
  const W = 64 * s;
  const img = ctx.getImageData(0, 0, W, 22 * s);
  const px = new Uint32Array(img.data.buffer);
  const alpha = (v: number) => v >>> 24 > 0; // little-endian RGBA: alpha is the top byte
  let wing = 0, covered = 0;
  for (let y = 2 * s; y < 22 * s; y++) {
    for (let x = 36 * s; x < 46 * s; x++) {
      if (!keepElytra((x + 0.5) / s, (y + 0.5) / s)) continue;
      wing++;
      if (alpha(px[y * W + x])) covered++;
    }
  }
  if (force || covered < wing * 0.95) {
    // The cape's front (10 × 16) stretched over the wing face (10 × 20).
    for (let y = 0; y < 20 * s; y++) {
      const fy = s + Math.floor((y * 16) / 20);
      for (let x = 0; x < 10 * s; x++) px[(2 * s + y) * W + 36 * s + x] = px[fy * W + s + x];
    }
  }
  for (let y = 0; y < 22 * s; y++) {
    for (let x = 22 * s; x < 46 * s; x++) {
      const i = y * W + x;
      if (!keepElytra((x + 0.5) / s, (y + 0.5) / s)) {
        px[i] = 0;
      } else if (x < 36 * s || y < 2 * s) {
        // Edge pixels: the outer side (x 22) wraps round to the face's right edge, the rest
        // touches its left edge or top row.
        const sx = x < 24 * s ? 46 * s - 1 : Math.max(36 * s, Math.min(46 * s - 1, x));
        const sy = Math.max(2 * s, Math.min(22 * s - 1, y));
        if (force || !alpha(px[i])) px[i] = px[sy * W + sx];
      }
    }
  }
  ctx.putImageData(img, 0, 0);
}

/**
 * A stored cape frame as the mod shows it (CapeAnimation.fromSheet): old capes with
 * flat-coloured sides get the front's border wrapped over them, and the elytra part is
 * cut into wings, so capes not made in the Cape Studio do not show as two slabs.
 */
export function asWorn(frame: HTMLCanvasElement, elytra: boolean): HTMLCanvasElement {
  const s = Math.max(1, Math.round(frame.width / 64));
  const out = document.createElement("canvas");
  out.width = frame.width;
  out.height = frame.height;
  const ctx = out.getContext("2d", { willReadFrequently: true })!;
  ctx.drawImage(frame, 0, 0);
  if (out.width < 46 * s || out.height < 22 * s) return out;
  const img = ctx.getImageData(0, 0, out.width, out.height);
  const px = new Uint32Array(img.data.buffer);
  const w = out.width;
  const at = (x: number, y: number) => px[y * w + x];
  let solid = true;
  for (let y = s; y < 17 * s && solid; y++) {
    for (let k = 0; k < s; k++) {
      if (at(k, y) !== at(0, s) || at(11 * s + k, y) !== at(0, s)) solid = false;
    }
  }
  if (solid) {
    for (let k = 0; k < s; k++) {
      for (let y = s; y < 17 * s; y++) {
        px[y * w + s - 1 - k] = at(s + k, y);
        px[y * w + 11 * s + k] = at(11 * s - 1 - k, y);
      }
      for (let x = 0; x < 10 * s; x++) {
        px[(s - 1 - k) * w + s + x] = at(s + x, s + k);
        px[k * w + 11 * s + x] = at(s + x, 17 * s - 1 - k);
      }
    }
    ctx.putImageData(img, 0, 0);
  }
  if (elytra) shapeElytra(ctx, s);
  return out;
}

/**
 * Frames between trimStart and trimEnd, thinned out evenly when the resolution
 * allows fewer (each kept frame then lasts longer, so the animation keeps its length).
 */
export function activeFrames(source: Source, st: EditorState) {
  const start = Math.min(st.trimStart, source.frames.length - 1);
  const end = Math.max(start, Math.min(st.trimEnd, source.frames.length - 1));
  const frames = source.frames.slice(start, end + 1);
  const durations = source.durations.slice(start, end + 1);
  const max = maxFramesFor(st.scale);
  if (frames.length <= max) return { frames, durations, thinned: false };
  const step = frames.length / max;
  const kept = Array.from({ length: max }, (_, i) => Math.floor(i * step));
  return { frames: kept.map((i) => frames[i]), durations: kept.map((i) => durations[i] * step), thinned: true };
}

/** Uniform frame time the mod plays at (average of the GIF's timing, sped up or slowed). */
export function frameTime(source: Source, st: EditorState): number {
  const { durations } = activeFrames(source, st);
  const avg = durations.reduce((a, b) => a + b, 0) / Math.max(1, durations.length);
  return Math.max(LIMITS.minFrameMs, Math.round(avg / st.speed));
}

/** Builds the spritesheet (frames stacked vertically) and its meta data. */
export async function exportCape(source: Source, st: EditorState): Promise<{ png: Blob; meta: CapeMeta }> {
  const { frames } = activeFrames(source, st);
  const edge = edgeColor(source.frames[0]);
  const s = st.scale;
  const sheet = document.createElement("canvas");
  sheet.width = 64 * s;
  sheet.height = 32 * s * frames.length;
  const ctx = sheet.getContext("2d")!;
  const one = document.createElement("canvas");
  frames.forEach((f, i) => {
    renderFrame(f, st, edge, one);
    ctx.drawImage(one, 0, i * 32 * s);
  });
  const png = await new Promise<Blob>((resolve, reject) => sheet.toBlob((b) => (b ? resolve(b) : reject(new Error("Export fehlgeschlagen"))), "image/png"));
  return {
    png,
    meta: { version: 1, frames: frames.length, frameTimeMs: frames.length > 1 ? frameTime(source, st) : 0, scale: s, elytra: st.elytra },
  };
}

export async function blobToBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

export async function dataUrlToBlob(url: string): Promise<Blob> {
  return (await fetch(url)).blob();
}

export function extensionOf(file: Blob & { name?: string }): string {
  const fromName = file.name?.split(".").pop()?.toLowerCase();
  if (fromName && ["gif", "png", "webp", "jpg", "jpeg"].includes(fromName)) return fromName;
  return ({ "image/gif": "gif", "image/webp": "webp", "image/jpeg": "jpg" } as Record<string, string>)[file.type] ?? "png";
}
