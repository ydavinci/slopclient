// Fake backend for previewing the UI in a normal browser (no Tauri).
import type { ContentEntry, Instance, Overview, SearchHit } from "./types";

type Handler = (payload: unknown) => void;

export const mockEmitter = {
  handlers: new Map<string, Set<Handler>>(),
  on(name: string, handler: Handler) {
    if (!this.handlers.has(name)) this.handlers.set(name, new Set());
    this.handlers.get(name)!.add(handler);
    return () => this.handlers.get(name)?.delete(handler);
  },
  emit(name: string, payload: unknown) {
    this.handlers.get(name)?.forEach((h) => h(payload));
  },
};

const day = 86400;
const now = Math.floor(Date.now() / 1000);
const java = { path: null, minMemoryMb: 512, maxMemoryMb: 4096, jvmArgs: [] };
const clientMod = true;

const instances: Instance[] = [
  { id: "survival", name: "Survival", mcVersion: "26.3", loader: { type: "fabric", version: "0.19.5" }, java, clientMod, createdAt: now - 20 * day, lastPlayed: now - 7200, playTimeSecs: 38 * 3600 },
  { id: "vanilla-plus", name: "Vanilla+", mcVersion: "26.3", loader: { type: "vanilla" }, java, clientMod, createdAt: now - 9 * day, lastPlayed: now - 2 * day, playTimeSecs: 6 * 3600 },
  { id: "create-above", name: "Create: Above", mcVersion: "1.21.10", loader: { type: "neoforge", version: "21.10.64" }, java, clientMod, createdAt: now - 30 * day, lastPlayed: now - 5 * day, playTimeSecs: 71 * 3600 },
  { id: "fabulously-optimized", name: "Fabulously Optimized", mcVersion: "26.2", loader: { type: "fabric", version: "0.19.5" }, java, clientMod, createdAt: now - 3 * day, lastPlayed: null, playTimeSecs: 0, modpack: { name: "Fabulously Optimized", version: "12.0.1" } },
];

const overview: Overview = {
  instances,
  accounts: [{ uuid: "069a79f444e94726a5befca90e38aaf5", username: "Notch" }],
  activeAccount: "069a79f444e94726a5befca90e38aaf5",
  hasClientId: true,
  dataDir: "C:\\Users\\you\\AppData\\Roaming\\DaClient",
  running: [],
  devBuild: true,
};

const mr = (slug: string, name: string, version: string, kind: ContentEntry["kind"], extra: Partial<ContentEntry> = {}): ContentEntry => ({
  fileName: `${slug}-${version}.${kind === "mod" ? "jar" : "zip"}`,
  kind,
  name,
  version,
  sha1: slug,
  source: { type: "modrinth", projectId: slug, versionId: version, slug },
  enabled: true,
  dependency: false,
  iconUrl: null,
  ...extra,
});

let content: ContentEntry[] = [
  mr("sodium", "Sodium", "mc26.3-0.9.3", "mod"),
  mr("iris", "Iris Shaders", "1.10.2+26.3", "mod"),
  mr("fabric-api", "Fabric API", "0.140.0+26.3", "mod", { dependency: true }),
  mr("modmenu", "Mod Menu", "18.0.0", "mod"),
  mr("replaymod", "ReplayMod", "2.6.24", "mod", { enabled: false }),
  { fileName: "custom-tweaks.jar", kind: "mod", name: "custom-tweaks", version: null, sha1: "x", source: { type: "local" }, enabled: true, dependency: false },
  mr("faithful-32x", "Faithful 32x", "26.3-oct", "resourcepack"),
  mr("complementary-reimagined", "Complementary Reimagined", "r5.10.0", "shader"),
];

const hits: SearchHit[] = [
  ["sodium", "Sodium", "Moderne Rendering-Engine, die die Framerate drastisch erhöht.", 248_000_000],
  ["entityculling", "Entity Culling", "Rendert keine Entities, die nicht sichtbar sind.", 41_000_000],
  ["ferrite-core", "FerriteCore", "Reduziert den Arbeitsspeicherverbrauch deutlich.", 52_000_000],
  ["immediatelyfast", "ImmediatelyFast", "Beschleunigt Immediate-Mode-Rendering für HUD, Text und Items.", 30_000_000],
  ["modernfix", "ModernFix", "Schnellere Ladezeiten und weniger Speicherverbrauch.", 28_000_000],
  ["dynamic-fps", "Dynamic FPS", "Senkt die FPS, wenn das Spiel im Hintergrund läuft.", 25_000_000],
].map(([slug, title, description, downloads]) => ({
  project_id: slug as string,
  slug: slug as string,
  title: title as string,
  description: description as string,
  author: "modrinth",
  project_type: "mod",
  downloads: downloads as number,
  follows: Math.round((downloads as number) / 9000),
  icon_url: null,
  categories: ["optimization", "fabric"],
  source: "modrinth" as const,
}));

const delay = <T,>(value: T, ms = 250) => new Promise<T>((r) => setTimeout(() => r(value), ms));

async function fakeTask(taskId: string, stages: string[]) {
  for (const name of stages) {
    mockEmitter.emit("task-progress", { taskId, event: { type: "stage", name } });
    for (let i = 0; i <= 20; i++) {
      await delay(null, 40);
      mockEmitter.emit("task-progress", { taskId, event: { type: "download", finished: i, total: 20, bytes: i * 2_400_000, totalBytes: 48_000_000 } });
    }
  }
}

export async function mockCall(cmd: string, args: Record<string, unknown> = {}): Promise<unknown> {
  switch (cmd) {
    case "get_overview":
      return delay(structuredClone(overview), 120);
    case "get_settings":
      return delay({ msaClientId: "00000000-0000-0000-0000-000000000000", curseforgeApiKey: "preview", downloadConcurrency: 16 });
    case "list_versions":
      return delay({
        latestRelease: "26.3",
        versions: ["26.4-snapshot-4", "26.3", "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.9", "1.21.4", "1.20.1"].map((id) => ({
          id, type: id.includes("snapshot") ? "snapshot" : "release", url: "", releaseTime: "2026-09-15T00:00:00Z", sha1: "",
        })),
      });
    case "list_loader_versions":
      return delay([{ version: "0.20.0", stable: false }, { version: "0.19.5", stable: true }, { version: "0.19.4", stable: true }]);
    case "content_list":
      return delay(structuredClone(content));
    case "content_set_enabled":
      content = content.map((c) => (c.fileName === args.fileName ? { ...c, enabled: args.enabled as boolean } : c));
      return delay(null);
    case "content_remove":
      content = content.filter((c) => c.fileName !== args.fileName);
      return delay(null);
    case "content_updates":
      return delay([
        { entry: content[0], new_version: { id: "a", project_id: "sodium", version_number: "mc26.3-0.9.4", version_type: "release" } },
        { entry: content[1], new_version: { id: "b", project_id: "iris", version_number: "1.10.3+26.3", version_type: "release" } },
      ]);
    case "foreign_scan":
      return delay([
        { launcher: "Prism Launcher", name: "1.21.1", path: "C:\\Users\\you\\AppData\\Roaming\\PrismLauncher\\instances\\1.21.1", mcVersion: "1.21.1", loader: { type: "neoforge", version: "21.1.262" }, mods: 3 },
        { launcher: "Prism Launcher", name: "Skyblock", path: "C:\\Users\\you\\AppData\\Roaming\\PrismLauncher\\instances\\Skyblock", mcVersion: "1.21.10", loader: { type: "fabric", version: "0.19.5" }, mods: 41 },
      ]);
    case "content_blocked":
    case "content_resolve_blocked":
      return delay(
        args.id === "create-above"
          ? [{ kind: "mod", name: "Create: Steam 'n' Rails", fileName: "Steam_Rails-1.6.9.jar", projectId: 688231, fileId: 5521456, slug: "create-steam-n-rails", pageUrl: "https://www.curseforge.com/minecraft/mc-mods/create-steam-n-rails/files/5521456", size: 1, dependency: false }]
          : [],
      );
    case "search": {
      const a = args.args as { query: string; source: string };
      const q = (a.query || "").toLowerCase();
      const list = hits
        .filter((h) => !q || h.title.toLowerCase().includes(q) || h.slug.includes(q))
        .map((h, i) => (a.source === "curseforge" ? { ...h, project_id: String(394468 + i), follows: 0, source: "curseforge" } : h));
      return delay({ hits: list, total_hits: list.length, offset: 0, limit: 20 });
    }
    case "content_install":
      await fakeTask(args.taskId as string, ["Abhängigkeiten auflösen", "Herunterladen"]);
      return { installed: [mr(args.project as string, args.project as string, "1.0.0", "mod")], skipped: [], warnings: [], blocked: [] };
    case "launch_instance":
      await fakeTask(args.taskId as string, ["Spieldateien herunterladen", "Java vorbereiten"]);
      overview.running.push(args.id as string);
      mockEmitter.emit("game-state", { instanceId: args.id, running: true, exitCode: null });
      ["[Render thread/INFO]: Setting user: Notch", "[Render thread/INFO]: Backend library: LWJGL version 3.4.3", "[Render thread/WARN]: Unable to locate English counter names", "[Render thread/INFO]: Sound engine started"].forEach((line, i) =>
        setTimeout(() => mockEmitter.emit("game-log", { instanceId: args.id, line, stderr: false }), 300 * (i + 1)),
      );
      // Preview of the crash dialog: "Create: Above" crashes a few seconds after the start.
      if (args.id === "create-above") {
        setTimeout(() => {
          overview.running = overview.running.filter((r) => r !== args.id);
          mockEmitter.emit("game-state", { instanceId: args.id, running: false, exitCode: 1 });
          mockEmitter.emit("game-crash", {
            instanceId: args.id,
            report: {
              title: "Mod-Fehler beim Start",
              summary: "Die Mod „create_sa“ ist beim Laden abgestürzt.",
              details: ["java.lang.NoSuchMethodError: 'void com.simibubi.create.content.Foo.bar()'"],
              suggestions: ["Deaktiviere die Mod und starte erneut.", "Suche nach einem Update der Mod oder prüfe, ob sie zu dieser Minecraft-Version passt."],
              culprit: { modId: "create_sa", fileName: "create-stuff-additions-2.1.0.jar", name: "Create Stuff & Additions" },
              reportFile: "C:\\Users\\you\\AppData\\Roaming\\DaClient\\instances\\create-above\\minecraft\\crash-reports\\crash-2026-10-19_11.02.11-client.txt",
              exitCode: 1,
            },
          });
        }, 2500);
      }
      return null;
    case "stop_instance":
      overview.running = overview.running.filter((r) => r !== args.id);
      mockEmitter.emit("game-state", { instanceId: args.id, running: false, exitCode: 0 });
      return null;
    case "create_instance": {
      const instance: Instance = { id: String(args.name).toLowerCase().replace(/\W+/g, "-"), name: String(args.name), mcVersion: String(args.mcVersion), loader: args.loader ? { type: args.loader as never, version: String(args.loaderVersion ?? "0.19.5") } : { type: "vanilla" }, java, clientMod, createdAt: now, playTimeSecs: 0 };
      overview.instances.unshift(instance);
      return delay(instance);
    }
    case "login_start":
      return delay({ user_code: "F7KQ-29XD", device_code: "x", verification_uri: "https://www.microsoft.com/link", expires_in: 900, interval: 5, message: "" });
    case "login_finish":
      return new Promise(() => {});
    default:
      return delay(null);
  }
}
