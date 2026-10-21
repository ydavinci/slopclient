// Mirrors the serde types of slopclient-core / the Tauri commands.

export type LoaderKind = "fabric" | "quilt" | "neoforge" | "forge";

export type Loader =
  | { type: "vanilla" }
  | { type: LoaderKind; version: string };

export interface JavaSettings {
  path: string | null;
  minMemoryMb: number;
  maxMemoryMb: number;
  jvmArgs: string[];
}

export interface ModpackInfo {
  projectId?: string | null;
  versionId?: string | null;
  name: string;
  version: string;
}

export interface Instance {
  id: string;
  name: string;
  mcVersion: string;
  loader: Loader;
  java: JavaSettings;
  group?: string | null;
  createdAt: number;
  lastPlayed?: number | null;
  playTimeSecs: number;
  modpack?: ModpackInfo | null;
  /** Install the SlopClient client mod (title screen, ClickGUI, HUD) on start. */
  clientMod: boolean;
  /** Custom icon; without one the colour tile shows. */
  icon?: { updated: number } | null;
  /** Shell commands before the start and after the game exited. */
  hooks?: { preLaunch: string; postExit: string };
}

/** What a new instance can take over from an existing one. */
export type CopyPart = "options" | "servers" | "modConfigs" | "slopclient" | "resourcePacks" | "shaderPacks";

/** A saved multiplayer server (servers.dat). */
export interface Server {
  name: string;
  address: string;
  icon: string | null;
}

/** Answer of the server list ping. */
export interface ServerStatus {
  motd: string;
  version: string;
  online: number;
  max: number;
  latencyMs: number;
  icon: string | null;
}

/** Where the game goes right after starting (Quick Play). */
export type Join = { kind: "server"; target: string } | { kind: "world"; target: string };

/** A world in the instance's saves folder. */
export interface World {
  id: string;
  name: string;
  gameMode?: string | null;
  hardcore: boolean;
  version?: string | null;
  lastPlayed?: number | null;
  sizeBytes: number;
  datapacks: number;
  icon?: string | null;
}

export interface Datapack {
  fileName: string;
  enabled: boolean;
  folder: boolean;
  description?: string | null;
}

export interface Screenshot {
  fileName: string;
  path: string;
  takenAt: number;
  sizeBytes: number;
}

/** Minecraft versions the bundled client mod supports (mirrors slopclient-core client_mod::SUPPORTED_MC). */
export const CLIENT_MOD_MC = ["26.3"];

export function supportsClientMod(instance: Pick<Instance, "loader" | "mcVersion">): boolean {
  const fabric = instance.loader.type === "fabric" || instance.loader.type === "quilt";
  return fabric && CLIENT_MOD_MC.some((v) => instance.mcVersion === v || instance.mcVersion.startsWith(`${v}.`));
}

export interface Account {
  uuid: string;
  username: string;
  skinUrl?: string | null;
}

export type SkinVariant = "classic" | "slim";

/** A skin in the launcher's skin library. */
export interface Skin {
  id: string;
  name: string;
  variant: SkinVariant;
  addedAt: number;
  /** PNG as data URL. */
  texture: string;
}

export interface Overview {
  instances: Instance[];
  accounts: Account[];
  activeAccount: string | null;
  hasClientId: boolean;
  dataDir: string;
  running: string[];
  devBuild: boolean;
  /** Launcher version, e.g. "0.2.0". */
  version: string;
  /** Data still lives in %APPDATA%\DaClient (the move did not work yet). */
  legacyDataDir?: boolean;
  /** The old DaClient launcher is still installed. */
  legacyInstall?: boolean;
}

export type HomeBlock = "hero" | "actions" | "strip";
/** Start page block positions as fractions of the free space (x, y). */
export type HomeLayout = Record<HomeBlock, [number, number]>;

export interface Settings {
  homeLayout?: HomeLayout | null;
  /** Accent hue in OKLCH degrees; null keeps the violet. */
  accentHue?: number | null;
  /** Start page and in-game title screen centred. */
  centeredLayout?: boolean;
  msaClientId?: string | null;
  curseforgeApiKey?: string | null;
  cosmeticsUrl?: string | null;
  downloadConcurrency?: number | null;
  /** Discord status; null means on. */
  discordPresence?: boolean | null;
  discordHideServer?: boolean;
}

export interface ManifestEntry {
  id: string;
  type: "release" | "snapshot" | "old_beta" | "old_alpha";
  url: string;
  releaseTime: string;
  sha1: string;
}

export interface VersionList {
  latestRelease: string;
  versions: ManifestEntry[];
}

export interface LoaderVersion {
  version: string;
  stable: boolean;
}

export type ContentType = "mod" | "resourcepack" | "shader";

export type Source =
  | { type: "modrinth"; projectId: string; versionId: string; slug?: string | null }
  | { type: "curseforge"; projectId: number; fileId: number; slug?: string | null }
  | { type: "local" };

export type Platform = "modrinth" | "curseforge";

/** Platform-qualified project key, so Modrinth and CurseForge ids never collide. */
export function projectKey(source: Source): string | null {
  return source.type === "local" ? null : `${source.type}:${source.projectId}`;
}

export interface ContentEntry {
  fileName: string;
  kind: ContentType;
  name: string;
  version?: string | null;
  sha1: string;
  source: Source;
  enabled: boolean;
  dependency: boolean;
  iconUrl?: string | null;
}

export interface SearchHit {
  project_id: string;
  slug: string;
  title: string;
  description: string;
  author: string;
  project_type: string;
  downloads: number;
  follows: number;
  icon_url?: string | null;
  categories: string[];
  source: Platform;
}

/** A project as the launcher's project page shows it. */
export interface ProjectDetails {
  source: Platform;
  id: string;
  slug: string;
  title: string;
  summary: string;
  body: string;
  bodyFormat: "markdown" | "html";
  iconUrl?: string | null;
  author?: string | null;
  downloads: number;
  followers?: number | null;
  categories: string[];
  license?: string | null;
  updated?: string | null;
  pageUrl: string;
  links: [string, string][];
  gallery: { url: string; title?: string | null }[];
}

export interface SearchResult {
  hits: SearchHit[];
  total_hits: number;
  offset: number;
  limit: number;
}

export interface SearchArgs {
  source: Platform;
  query: string;
  projectType: string;
  gameVersion?: string | null;
  loaders: string[];
  sort?: string | null;
  offset: number;
  limit: number;
}

export interface InstallReport {
  installed: ContentEntry[];
  skipped: string[];
  warnings: string[];
  blocked: BlockedFile[];
}

/** CurseForge file whose author only allows downloads on the website. */
export interface BlockedFile {
  kind: ContentType;
  name: string;
  fileName: string;
  projectId: number;
  fileId: number;
  slug?: string | null;
  pageUrl: string;
  sha1?: string | null;
  size: number;
  dependency: boolean;
  iconUrl?: string | null;
}

export interface RemoteVersion {
  id: string;
  project_id: string;
  version_number: string;
  version_type: string;
}

export interface UpdateInfo {
  entry: ContentEntry;
  new_version: RemoteVersion;
}

export interface DeviceCode {
  user_code: string;
  device_code: string;
  verification_uri: string;
  expires_in: number;
  interval: number;
  message: string;
}

/** A cape from the Cape Studio (slopclient-core cosmetics.rs). */
export interface CapeInfo {
  id: string;
  name: string;
  meta: { version: 1; frames: number; frameTimeMs: number; scale: 1 | 2 | 4 | 8 | 16; elytra: boolean };
  createdAt: number;
  updatedAt: number;
  hasSource: boolean;
}

/** Sign-in state at the cosmetics server (other players see your cape). */
export interface OnlineStatus {
  server: string | null;
  uuid: string | null;
  name: string | null;
}

/** An instance of Prism Launcher, PolyMC or MultiMC that can be copied over. */
export interface ForeignInstance {
  launcher: string;
  name: string;
  path: string;
  mcVersion: string;
  loader: Loader;
  mods: number;
}

/** Analysis of a game that ended abnormally (slopclient-core crash.rs). */
export interface CrashReport {
  title: string;
  summary: string;
  details: string[];
  suggestions: string[];
  culprit?: { modId: string; fileName?: string | null; name?: string | null } | null;
  reportFile?: string | null;
  exitCode?: number | null;
}

export type Progress =
  | { type: "stage"; name: string }
  | { type: "download"; finished: number; total: number; bytes: number; totalBytes: number };
