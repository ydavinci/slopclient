// Cape Studio: frame decoding, Minecraft's cape texture layout and spritesheet export.
// The output format is shared with the client mod and the backend (daclient-core cosmetics.rs).

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
  edge: "auto" | "color";
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
    edge: "auto",
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

/** Faces of the 64 × 32 cape texture (Minecraft's box UV for 10 × 16 × 1 and the elytra wing). */
export const FACES = {
  front: { x: 1, y: 1, w: 10, h: 16 },
  back: { x: 12, y: 1, w: 10, h: 16 },
  capeArea: { x: 0, y: 0, w: 22, h: 17 },
  elytraFront: { x: 24, y: 2, w: 10, h: 20 },
  elytraBack: { x: 36, y: 2, w: 10, h: 20 },
  elytraArea: { x: 22, y: 0, w: 24, h: 22 },
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

/** Draws `frame` into a face with the editor's zoom, offset, rotation and fit, clipped to it. */
function drawFace(ctx: CanvasRenderingContext2D, frame: ImageBitmap, face: { x: number; y: number; w: number; h: number }, s: number, st: EditorState, mirror: boolean) {
  const fx = face.x * s, fy = face.y * s, fw = face.w * s, fh = face.h * s;
  const rotated = st.rotation === 90 || st.rotation === 270;
  const iw = rotated ? frame.height : frame.width;
  const ih = rotated ? frame.width : frame.height;
  const base = st.fit === "cover" ? Math.max(fw / iw, fh / ih) : Math.min(fw / iw, fh / ih);
  const k = base * st.zoom;
  ctx.save();
  ctx.beginPath();
  ctx.rect(fx, fy, fw, fh);
  ctx.clip();
  ctx.translate(fx + fw / 2 + st.offsetX * fw * (mirror ? -1 : 1), fy + fh / 2 + st.offsetY * fh);
  if (mirror) ctx.scale(-1, 1);
  ctx.rotate((st.rotation * Math.PI) / 180);
  ctx.scale(k, k);
  ctx.drawImage(frame, -frame.width / 2, -frame.height / 2);
  ctx.restore();
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

  drawFace(ctx, frame, FACES.front, s, st, false);
  if (st.back === "color") fill(FACES.back, st.backColor);
  else drawFace(ctx, frame, FACES.back, s, st, st.back === "mirror");

  if (st.elytra) {
    fill(FACES.elytraArea, edgeFill);
    drawFace(ctx, frame, FACES.elytraFront, s, st, false);
    drawFace(ctx, frame, FACES.elytraBack, s, st, true);
  }
  return canvas;
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
