import { useEffect, useState } from "react";
import { api } from "./ipc";
import type { Instance } from "./types";

/** An image dropped on an instance icon, picked up by the icon dialog. */
export const pendingIcon: { file: File | null } = { file: null };

// Icons come from the backend as data URLs; one request per instance and version.
const cache = new Map<string, Promise<string | null>>();

export function iconKey(id: string, updated: number) {
  return `${id}:${updated}`;
}

/** Custom icon URL of an instance, or null while loading / without an icon. */
export function useInstanceIconUrl(id: string, icon: Instance["icon"]): string | null {
  const updated = icon?.updated;
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (updated == null) {
      setUrl(null);
      return;
    }
    const key = iconKey(id, updated);
    let promise = cache.get(key);
    if (!promise) {
      promise = api.instanceIcon(id).catch(() => null);
      cache.set(key, promise);
    }
    let live = true;
    promise.then((u) => live && setUrl(u));
    return () => {
      live = false;
    };
  }, [id, updated]);
  return url;
}

/** Square PNG (256 px) of the visible crop, as bytes for the backend. */
export async function cropToPng(image: HTMLImageElement, sx: number, sy: number, side: number, size = 256): Promise<number[]> {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d")!;
  // Small pixel-art sources stay crisp.
  ctx.imageSmoothingEnabled = side > 64;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(image, sx, sy, side, side, 0, 0, size, size);
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Bild konnte nicht erzeugt werden"))), "image/png"));
  return Array.from(new Uint8Array(await blob.arrayBuffer()));
}
