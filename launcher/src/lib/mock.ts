// Fake backend for previewing the UI in a normal browser (no Tauri).
import extrasJson from "../../../cosmetics/extras.json";
import type { CapeInfo, ContentEntry, Instance, Overview, SearchHit } from "./types";

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
  { id: "survival", name: "Survival", group: "Survival", mcVersion: "26.3", loader: { type: "fabric", version: "0.19.5" }, java, clientMod, createdAt: now - 20 * day, lastPlayed: now - 7200, playTimeSecs: 38 * 3600 },
  { id: "vanilla-plus", name: "Vanilla+", group: "Survival", mcVersion: "26.3", loader: { type: "vanilla" }, java, clientMod, createdAt: now - 9 * day, lastPlayed: now - 2 * day, playTimeSecs: 6 * 3600 },
  { id: "create-above", name: "Create: Above", group: "Modpacks", mcVersion: "1.21.10", loader: { type: "neoforge", version: "21.10.64" }, java, clientMod, createdAt: now - 30 * day, lastPlayed: now - 5 * day, playTimeSecs: 71 * 3600 },
  { id: "fabulously-optimized", name: "Fabulously Optimized", mcVersion: "26.2", loader: { type: "fabric", version: "0.19.5" }, java, clientMod, createdAt: now - 3 * day, lastPlayed: null, playTimeSecs: 0, modpack: { name: "Fabulously Optimized", version: "12.0.1" } },
];

let mockWorlds = [
  { id: "Neue Welt", name: "Neue Welt", gameMode: "survival", hardcore: false, version: "26.3", lastPlayed: now - 7300, sizeBytes: 184_320_000, datapacks: 2, icon: null },
  { id: "Kreativ-Bau", name: "Stadtprojekt", gameMode: "creative", hardcore: false, version: "26.3", lastPlayed: now - 3 * day, sizeBytes: 52_000_000, datapacks: 0, icon: null },
  { id: "hc", name: "Hardcore Run", gameMode: "survival", hardcore: true, version: "26.2", lastPlayed: now - 12 * day, sizeBytes: 9_400_000, datapacks: 1, icon: null },
];
const mockPacks: Record<string, { fileName: string; enabled: boolean; folder: boolean; description: string | null }[]> = {
  "Neue Welt": [
    { fileName: "Terralith_26.3_v2.6.zip", enabled: true, folder: false, description: "Explore Over 95 New Biomes" },
    { fileName: "afk-display.zip", enabled: false, folder: false, description: "Zeigt AFK-Spieler in der Tabliste" },
  ],
  hc: [{ fileName: "graves", enabled: true, folder: true, description: "Gräber statt verlorener Items" }],
};
let mockShots = Array.from({ length: 9 }, (_, i) => ({
  fileName: `2026-10-${String(18 - (i >> 1)).padStart(2, "0")}_${String(21 - i).padStart(2, "0")}.14.0${i}.png`,
  path: "",
  takenAt: now - i * 40000 - 600,
  sizeBytes: 2_400_000 + i * 180_000,
}));
/** Stand-in thumbnails: a sky-to-ground gradient per screenshot. */
function shotThumb(name: string): string {
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const hue = 190 + (h % 80);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 9"><defs><linearGradient id="g" x2="0" y2="1"><stop offset="0" stop-color="hsl(${hue} 60% 62%)"/><stop offset=".62" stop-color="hsl(${hue + 20} 45% 78%)"/><stop offset=".63" stop-color="hsl(110 35% 34%)"/><stop offset="1" stop-color="hsl(30 30% 22%)"/></linearGradient></defs><rect width="16" height="9" fill="url(#g)"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

const overview: Overview = {
  instances,
  accounts: [{ uuid: "069a79f444e94726a5befca90e38aaf5", username: "Notch" }],
  activeAccount: "069a79f444e94726a5befca90e38aaf5",
  hasClientId: true,
  dataDir: "C:\\Users\\you\\AppData\\Roaming\\SlopClient",
  running: [],
  devBuild: true,
  version: "0.2.0",
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

// Settings sync groups of the preview.
let mockShared: { id: string; name: string; instances: string[] }[] | null = null;
const sharedGroups = () => (mockShared ??= [{ id: "survival", name: "Survival", instances: [instances[0].id, instances[1].id] }]);
const sharedStatus = () => sharedGroups().map((g) => ({ ...g, instances: undefined, members: g.instances.map((instance) => ({ instance, state: "linked" })) }));

// Known updates of the preview instance, filled by the first check.
let mockUpdates: { entry: ContentEntry; new_version: { id: string; project_id: string; version_number: string; version_type: string } }[] | null = null;

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

const mockIcons = new Map<string, string>();
let mockCapes: { info: CapeInfo; png: string; source: string; ext: string; editor: unknown }[] = [];
let mockActiveCape: string | null = null;
let mockOnline: { server: string | null; uuid: string | null; name: string | null } = { server: "https://cosmetics.slopclient.example", uuid: null, name: null };

let mockExtras: { hat: string | null; pet: string | null; accessory: string | null } = { hat: "zylinder", pet: "drache", accessory: null };

/** 64×64 stand-in skin: a dark outfit with a light face, drawn once. */
function mockSkinTexture(): string {
  if (typeof document === "undefined") return "";
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const g = c.getContext("2d")!;
  const fill = (color: string, x: number, y: number, w: number, h: number) => {
    g.fillStyle = color;
    g.fillRect(x, y, w, h);
  };
  fill("#e0b48f", 8, 8, 8, 8);
  fill("#3b2a20", 8, 8, 8, 2);
  fill("#26213a", 20, 20, 8, 12);
  fill("#7c5cf0", 44, 20, 4, 12);
  fill("#7c5cf0", 36, 52, 4, 12);
  fill("#1b1828", 4, 20, 4, 12);
  fill("#1b1828", 20, 52, 4, 12);
  return c.toDataURL();
}
let mockSkins: { id: string; name: string; variant: "classic" | "slim"; addedAt: number; texture: string }[] = [
  { id: "a1", name: "Nachtviolett", variant: "classic", addedAt: 1760900000, texture: mockSkinTexture() },
];

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
      return delay({ msaClientId: "00000000-0000-0000-0000-000000000000", curseforgeApiKey: "preview", cosmeticsUrl: "https://cosmetics.slopclient.example", downloadConcurrency: 16 });
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
      mockUpdates ??= [
        { entry: content[0], new_version: { id: "a", project_id: "sodium", version_number: "mc26.3-0.9.4", version_type: "release" } },
        { entry: content[1], new_version: { id: "b", project_id: "iris", version_number: "1.10.3+26.3", version_type: "beta" } },
      ];
      return delay(structuredClone(mockUpdates));
    case "content_apply_updates": {
      const only = args.only as { kind: string; fileName: string }[] | null;
      const done = (mockUpdates ?? []).filter((u) => !only || only.some((o) => o.kind === u.entry.kind && o.fileName === u.entry.fileName));
      mockUpdates = (mockUpdates ?? []).filter((u) => !done.includes(u));
      await delay(null, 600);
      mockEmitter.emit("updates-changed", null);
      return done.length;
    }
    case "shared_options_groups":
    case "shared_options_reconcile":
      return delay(sharedStatus());
    case "shared_options_create": {
      const name = String(args.name);
      const group = { id: name.toLowerCase().replace(/[^a-z0-9]+/g, "-"), name, instances: [String(args.sourceInstance)] };
      sharedGroups().push(group);
      return delay(group);
    }
    case "shared_options_rename":
      sharedGroups().forEach((g) => g.id === args.group && (g.name = String(args.name)));
      return delay(null);
    case "shared_options_delete":
      mockShared = sharedGroups().filter((g) => g.id !== args.group);
      return delay(null);
    case "shared_options_add":
      sharedGroups().forEach((g) => g.id === args.group && g.instances.push(String(args.instance)));
      return delay(null);
    case "shared_options_remove":
      sharedGroups().forEach((g) => g.id === args.group && (g.instances = g.instances.filter((i) => i !== args.instance)));
      return delay(null);
    case "shared_options_group_of":
      return delay(sharedGroups().find((g) => g.instances.includes(String(args.instance))) ?? null);
    case "updates_overview":
      return delay(mockUpdates?.length ? { [instances[0].id]: mockUpdates.length } : {});
    case "foreign_scan":
      return delay([
        { launcher: "Prism Launcher", name: "1.21.1", path: "C:\\Users\\you\\AppData\\Roaming\\PrismLauncher\\instances\\1.21.1", mcVersion: "1.21.1", loader: { type: "neoforge", version: "21.1.262" }, mods: 3 },
        { launcher: "Prism Launcher", name: "Skyblock", path: "C:\\Users\\you\\AppData\\Roaming\\PrismLauncher\\instances\\Skyblock", mcVersion: "1.21.10", loader: { type: "fabric", version: "0.19.5" }, mods: 41 },
      ]);
    case "version_changelog":
      return delay("### Neu\n- Schnellere Chunk-Updates\n- Fix für den Absturz beim Beitreten\n\n[Alle Änderungen](https://modrinth.com)", 500);
    case "extras_catalogue":
      return delay(extrasJson);
    case "extras_texture":
      // The preview has no server copy: the shipped textures are used.
      return delay(null);
    case "extras_current":
      return delay(mockExtras);
    case "extras_set":
      mockExtras = { hat: (args.hat as string) ?? null, pet: (args.pet as string) ?? null, accessory: (args.accessory as string) ?? null };
      return delay(null);
    case "skins_list":
      return delay(mockSkins);
    case "skin_add": {
      const entry = { id: Math.random().toString(36).slice(2, 8), name: String(args.fileName).replace(/\.png$/i, ""), variant: "classic" as const, addedAt: Date.now() / 1000, texture: mockSkins[0]?.texture ?? "" };
      mockSkins = [entry, ...mockSkins];
      return delay(entry, 300);
    }
    case "skin_remove":
      mockSkins = mockSkins.filter((s) => s.id !== args.id);
      return delay(null);
    case "skin_set_variant":
      mockSkins = mockSkins.map((s) => (s.id === args.id ? { ...s, variant: args.variant as "classic" | "slim" } : s));
      return delay(null);
    case "skin_apply":
      return delay("https://textures.minecraft.net/texture/x", 900);
    case "skin_import_current":
      return delay(mockSkins[0], 600);
    case "capes_list":
      return delay({ capes: mockCapes.map((c) => c.info), activeId: mockActiveCape });
    case "cape_image":
      return delay(mockCapes.find((c) => c.info.id === args.id)?.png ?? "");
    case "cape_source": {
      const c = mockCapes.find((c) => c.info.id === args.id);
      return delay(c ? { ext: c.ext, dataUrl: c.source, editor: c.editor } : null);
    }
    case "cape_save": {
      const cape = args.cape as { id: string | null; name: string; png: string; meta: CapeInfo["meta"]; sourceExt: string; source: string; editor: unknown };
      const id = cape.id ?? Math.random().toString(36).slice(2, 10);
      const info: CapeInfo = { id, name: cape.name || "Cape", meta: cape.meta, createdAt: now, updatedAt: Math.floor(Date.now() / 1000), hasSource: true };
      const entry = { info, png: `data:image/png;base64,${cape.png}`, source: `data:image/${cape.sourceExt === "jpg" ? "jpeg" : cape.sourceExt};base64,${cape.source}`, ext: cape.sourceExt, editor: cape.editor };
      const i = mockCapes.findIndex((c) => c.info.id === id);
      if (i >= 0) mockCapes[i] = entry;
      else mockCapes.unshift(entry);
      return delay(info);
    }
    case "cape_delete":
      mockCapes = mockCapes.filter((c) => c.info.id !== args.id);
      if (mockActiveCape === args.id) mockActiveCape = null;
      return delay(null);
    case "cape_set_active":
      mockActiveCape = (args.id as string | null) ?? null;
      return delay(null);
    case "cosmetics_status":
      return delay(mockOnline);
    case "cosmetics_sign_in":
      mockOnline = { ...mockOnline, uuid: "069a79f444e94726a5befca90e38aaf5", name: "Notch" };
      return delay(mockOnline, 700);
    case "cosmetics_sign_out":
    case "cosmetics_delete_account":
      mockOnline = { ...mockOnline, uuid: null, name: null };
      return delay(null);
    case "instance_icon":
      return delay(mockIcons.get(args.id as string) ?? null);
    case "set_instance_icon": {
      const bytes = new Uint8Array(args.bytes as number[]);
      let bin = "";
      bytes.forEach((b) => (bin += String.fromCharCode(b)));
      mockIcons.set(args.id as string, `data:image/png;base64,${btoa(bin)}`);
      const inst = overview.instances.find((i) => i.id === args.id)!;
      inst.icon = { updated: Date.now() };
      return delay(structuredClone(inst));
    }
    case "clear_instance_icon": {
      mockIcons.delete(args.id as string);
      const inst = overview.instances.find((i) => i.id === args.id)!;
      inst.icon = null;
      return delay(structuredClone(inst));
    }
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
              reportFile: "C:\\Users\\you\\AppData\\Roaming\\SlopClient\\instances\\create-above\\minecraft\\crash-reports\\crash-2026-10-19_11.02.11-client.txt",
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
    case "duplicate_instance": {
      const source = instances.find((i) => i.id === args.id)!;
      const copy = { ...structuredClone(source), id: `${source.id}-copy`, name: args.name as string, lastPlayed: null, playTimeSecs: 0, createdAt: now };
      instances.push(copy);
      return delay(copy, 900);
    }
    case "content_install_preset":
      return delay({ installed: [mr("lithium", "Lithium", "mc26.3-0.18.0", "mod"), mr("ferrite-core", "FerriteCore", "8.0.2", "mod")], skipped: ["sodium", "iris"], warnings: [], blocked: [] }, 1500);
    case "project_details":
      return delay({
        source: args.source, id: "AANobbMI", slug: "sodium", title: "Sodium", summary: "The fastest and most compatible rendering optimization mod for Minecraft.",
        body: "# Sodium\n\nSodium is a powerful **rendering engine** for Minecraft which improves frame rates and reduces lag spikes.\n\n## Features\n\n- Much faster chunk rendering\n- Fewer stutters\n- Works with [Iris](https://modrinth.com/mod/iris)\n\n> Sodium is free software.\n\n```\nmods/sodium.jar\n```",
        bodyFormat: "markdown", iconUrl: null, author: "jellysquid3", downloads: 248_000_000, followers: 41_991, categories: ["optimization"], license: "Polyform Shield", updated: "2026-10-01T00:00:00Z",
        pageUrl: "https://modrinth.com/mod/sodium", links: [["Quellcode", "https://github.com/CaffeineMC/sodium"], ["Discord", "https://discord.gg/x"]], gallery: [],
      }, 400);
    case "upload_log":
      return delay("https://mclo.gs/x7Qp2Ka", 800);
    case "read_log_file":
      return delay("---- Minecraft Crash Report ----");
    case "content_add_local": {
      const name = String(args.fileName);
      const kind = name.endsWith(".jar") ? "mod" : "resourcepack";
      content = [...content, { fileName: name, kind, name: name.replace(/\.(jar|zip)$/, ""), version: null, sha1: name, source: { type: "local" }, enabled: true, dependency: false }];
      return delay(kind, 500);
    }
    case "worlds_list":
      return delay(mockWorlds);
    case "take_launch_request":
      return delay(null);
    case "create_shortcut":
      return delay("C:\\Users\\you\\Desktop\\Survival – SlopClient.lnk", 400);
    case "servers_list":
      return delay([
        { name: "Hypixel", address: "mc.hypixel.net", icon: null },
        { name: "GommeHD", address: "gommehd.net", icon: null },
        { name: "Lokaler Test", address: "localhost:25566", icon: null },
      ]);
    case "server_ping":
      if (String(args.address).startsWith("localhost")) return new Promise((_, reject) => setTimeout(() => reject("Server antwortet nicht"), 900));
      return delay({ motd: "Hypixel Network [1.8/26.3]\nHALLOWEEN EVENT", version: "Requires MC 1.8 / 26.3", online: 24830, max: 200000, latencyMs: 42, icon: null }, 500);
    case "world_backup":
      return delay("C:\\Users\\you\\AppData\\Roaming\\SlopClient\\instances\\survival\\backups\\Neue Welt-1760880000.zip", 1200);
    case "world_delete":
      mockWorlds = mockWorlds.filter((w) => w.id !== args.world);
      return delay(null);
    case "datapacks_list":
      return delay(mockPacks[args.world as string] ?? []);
    case "datapack_add": {
      const list = (mockPacks[args["x-world"] as string] ??= []);
      list.push({ fileName: String(args.fileName), enabled: true, folder: false, description: null });
      return delay(null, 400);
    }
    case "datapack_set_enabled":
      mockPacks[args.world as string] = (mockPacks[args.world as string] ?? []).map((p) => (p.fileName === args.fileName ? { ...p, enabled: args.enabled as boolean } : p));
      return delay(null);
    case "datapack_remove":
      mockPacks[args.world as string] = (mockPacks[args.world as string] ?? []).filter((p) => p.fileName !== args.fileName);
      return delay(null);
    case "screenshots_list":
      return delay(mockShots);
    case "screenshot_thumb":
      return delay(shotThumb(String(args.name)), 300 + Math.random() * 500);
    case "screenshot_delete":
      mockShots = mockShots.filter((s) => s.fileName !== args.name);
      return delay(null);
    default:
      return delay(null);
  }
}
