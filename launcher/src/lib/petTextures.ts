// Pet textures shipped with the launcher (cosmetics/pets, shared with the mod and the server),
// used when there is no copy of the server's catalogue yet.
import { setTextureSource } from "./extras";
import { api } from "./ipc";

const BUNDLED = import.meta.glob("../../../cosmetics/pets/*.png", { eager: true, query: "?url", import: "default" }) as Record<string, string>;

function bundled(file: string): string | undefined {
  return Object.entries(BUNDLED).find(([path]) => path.endsWith(`/${file}`))?.[1];
}

/** Pets in the launcher: the texture from the server's catalogue, else the shipped one. */
export function installLauncherTextures() {
  setTextureSource(async (file) => (await api.extrasTexture(file).catch(() => null)) ?? bundled(file));
}
