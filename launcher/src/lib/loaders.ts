import { api } from "./ipc";
import type { Instance, LoaderKind, LoaderVersion } from "./types";

export type LoaderChoice = "vanilla" | LoaderKind;

export const LOADERS: { value: LoaderChoice; label: string }[] = [
  { value: "vanilla", label: "Vanilla" },
  { value: "fabric", label: "Fabric" },
  { value: "quilt", label: "Quilt" },
  { value: "neoforge", label: "NeoForge" },
  { value: "forge", label: "Forge" },
];

const cache = new Map<string, Promise<LoaderVersion[]>>();

/** Loader versions for a Minecraft version, newest first; fetched once per session. */
export function loaderVersions(kind: LoaderKind, mc: string): Promise<LoaderVersion[]> {
  const key = `${kind}:${mc}`;
  let p = cache.get(key);
  if (!p) {
    p = api.loaderVersions(kind, mc);
    // A failed lookup (offline) may be retried later.
    p.catch(() => cache.delete(key));
    cache.set(key, p);
  }
  return p;
}

export function latestStable(list: LoaderVersion[]): LoaderVersion | undefined {
  return list.find((v) => v.stable) ?? list[0];
}

/** Compares dotted versions numerically ("0.19.10" > "0.19.9"); other parts as text. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split(/[.+-]/), pb = b.split(/[.+-]/);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? "", y = pb[i] ?? "";
    const nx = Number(x), ny = Number(y);
    const c = !Number.isNaN(nx) && !Number.isNaN(ny) ? nx - ny : x.localeCompare(y);
    if (c !== 0) return c;
  }
  return 0;
}

/** The newer stable loader version for an instance, if there is one. */
export async function loaderUpdate(instance: Instance): Promise<string | null> {
  if (instance.loader.type === "vanilla") return null;
  const latest = latestStable(await loaderVersions(instance.loader.type, instance.mcVersion));
  return latest && compareVersions(latest.version, instance.loader.version) > 0 ? latest.version : null;
}
