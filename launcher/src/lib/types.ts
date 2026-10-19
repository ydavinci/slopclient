// Mirrors the serde types of daclient-core / the Tauri commands.

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
  /** Install the DaClient client mod (title screen, ClickGUI, HUD) on start. */
  clientMod: boolean;
  /** Custom icon; without one the colour tile shows. */
  icon?: { updated: number } | null;
}

/** Minecraft versions the bundled client mod supports (mirrors daclient-core client_mod::SUPPORTED_MC). */
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

export interface Overview {
  instances: Instance[];
  accounts: Account[];
  activeAccount: string | null;
  hasClientId: boolean;
  dataDir: string;
  running: string[];
  devBuild: boolean;
}

export interface Settings {
  msaClientId?: string | null;
  curseforgeApiKey?: string | null;
  downloadConcurrency?: number | null;
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

/** An instance of Prism Launcher, PolyMC or MultiMC that can be copied over. */
export interface ForeignInstance {
  launcher: string;
  name: string;
  path: string;
  mcVersion: string;
  loader: Loader;
  mods: number;
}

/** Analysis of a game that ended abnormally (daclient-core crash.rs). */
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
