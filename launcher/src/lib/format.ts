import type { Instance, Loader } from "./types";

const LOADER_NAMES: Record<string, string> = {
  vanilla: "Vanilla",
  fabric: "Fabric",
  quilt: "Quilt",
  neoforge: "NeoForge",
  forge: "Forge",
};

export function loaderName(loader: Loader): string {
  return LOADER_NAMES[loader.type] ?? loader.type;
}

export function loaderLabel(loader: Loader): string {
  return loader.type === "vanilla" ? "Vanilla" : `${loaderName(loader)} ${loader.version}`;
}

export function playTime(secs: number): string {
  if (secs < 60) return "noch nicht gespielt";
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

export function relativeTime(unix?: number | null): string {
  if (!unix) return "nie";
  const diff = Date.now() / 1000 - unix;
  if (diff < 90) return "gerade eben";
  if (diff < 3600) return `vor ${Math.round(diff / 60)} min`;
  if (diff < 86400) return `vor ${Math.round(diff / 3600)} h`;
  const days = Math.round(diff / 86400);
  return days === 1 ? "gestern" : `vor ${days} Tagen`;
}

export function compactNumber(n: number): string {
  return new Intl.NumberFormat("de-DE", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

export function megabytes(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(bytes > 100_000_000 ? 0 : 1)} MB`;
}

/** Stable accent pair for an instance tile, derived from its id. */
export function instanceColors(instance: Pick<Instance, "id">): { bg: string; fg: string } {
  const palette = [
    { bg: "#2a1f45", fg: "#c4a8ff" },
    { bg: "#1f2c3d", fg: "#8fc2ff" },
    { bg: "#1d3329", fg: "#7fdcab" },
    { bg: "#3a2a1c", fg: "#f2b27a" },
    { bg: "#3a1f32", fg: "#f59bd0" },
    { bg: "#2d2d1c", fg: "#e8d27a" },
  ];
  let hash = 0;
  for (const c of instance.id) hash = (hash * 31 + c.charCodeAt(0)) >>> 0;
  return palette[hash % palette.length];
}

export function initials(name: string): string {
  const words = name.replace(/[^\p{L}\p{N} ]/gu, " ").trim().split(/\s+/);
  return (words.length > 1 ? words[0][0] + words[1][0] : name.slice(0, 2)).toUpperCase();
}
