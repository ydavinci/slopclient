import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { openUrl as openerOpenUrl } from "@tauri-apps/plugin-opener";
import { open as dialogOpen, save as dialogSave } from "@tauri-apps/plugin-dialog";
import { mockCall, mockEmitter } from "./mock";
import type { ExtrasCatalogue, ExtrasChoice } from "./extras";
import type {
  Account,
  BlockedFile,
  CapeInfo,
  ContentEntry,
  ContentType,
  Join,
  Server,
  ServerStatus,
  Skin,
  SkinVariant,
  CopyPart,
  Datapack,
  DeviceCode,
  ForeignInstance,
  InstallReport,
  Instance,
  LoaderKind,
  LoaderVersion,
  OnlineStatus,
  Overview,
  HomeLayout,
  Platform,
  ProjectDetails,
  Screenshot,
  SearchArgs,
  SearchResult,
  Settings,
  UpdateInfo,
  VersionList,
  World,
} from "./types";

/** False when the UI runs in a plain browser (design preview with mock data). */
export const isTauri = "__TAURI_INTERNALS__" in window;

function call<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  return isTauri ? invoke<T>(cmd, args) : (mockCall(cmd, args) as Promise<T>);
}

/** Sends a file as a raw body (cheap even for big mods); metadata goes in headers. */
async function upload<T>(cmd: string, file: File, headers: Record<string, string>): Promise<T> {
  if (!isTauri) return mockCall(cmd, { ...headers, fileName: file.name }) as Promise<T>;
  const h: Record<string, string> = { "x-file-name": encodeURIComponent(file.name) };
  for (const [k, v] of Object.entries(headers)) h[k] = encodeURIComponent(v);
  return invoke<T>(cmd, new Uint8Array(await file.arrayBuffer()), { headers: h });
}

export function newTaskId(): string {
  return Math.random().toString(36).slice(2, 10);
}

export const api = {
  overview: () => call<Overview>("get_overview"),
  settings: () => call<Settings>("get_settings"),
  saveSettings: (settings: Settings) => call<void>("save_settings", { settings }),
  setAccentHue: (hue: number | null) => call<void>("set_accent_hue", { hue }),
  setCenteredLayout: (centered: boolean) => call<void>("set_centered_layout", { centered }),
  setDiscord: (enabled: boolean, hideServer: boolean) => call<void>("set_discord", { enabled, hideServer }),
  setHomeLayout: (layout: HomeLayout | null) => call<void>("set_home_layout", { layout }),
  openPath: (path: string) => call<void>("open_path", { path }),

  versions: () => call<VersionList>("list_versions"),
  loaderVersions: (loader: LoaderKind, mcVersion: string) =>
    call<LoaderVersion[]>("list_loader_versions", { loader, mcVersion }),

  createInstance: (name: string, mcVersion: string, loader: LoaderKind | null, loaderVersion: string | null) =>
    call<Instance>("create_instance", { name, mcVersion, loader, loaderVersion }),
  saveInstance: (instance: Instance) => call<void>("save_instance", { instance }),
  deleteInstance: (id: string) => call<void>("delete_instance", { id }),
  duplicateInstance: (id: string, name: string) => call<Instance>("duplicate_instance", { id, name }),
  /** Copies settings (options, servers, configs, packs) from another instance; resolves to how many were found. */
  copyInstanceSettings: (id: string, from: string, parts: CopyPart[]) => call<number>("copy_instance_settings", { id, from, parts }),
  /** Shares text on mclo.gs; resolves to the public link. */
  uploadLog: (text: string) => call<string>("upload_log", { text }),
  readLogFile: (path: string) => call<string>("read_log_file", { path }),
  /** Adds a dropped .jar/.zip; resolves to the kind it was sorted into. */
  contentAddLocal: (id: string, file: File) => upload<ContentType>("content_add_local", file, { "x-instance": id }),
  worlds: (id: string) => call<World[]>("worlds_list", { id }),
  worldDelete: (id: string, world: string) => call<void>("world_delete", { id, world }),
  worldBackup: (id: string, world: string) => call<string>("world_backup", { id, world }),
  datapacks: (id: string, world: string) => call<Datapack[]>("datapacks_list", { id, world }),
  datapackAdd: (id: string, world: string, file: File) => upload<void>("datapack_add", file, { "x-instance": id, "x-world": world }),
  datapackSetEnabled: (id: string, world: string, fileName: string, enabled: boolean) =>
    call<void>("datapack_set_enabled", { id, world, fileName, enabled }),
  datapackRemove: (id: string, world: string, fileName: string) => call<void>("datapack_remove", { id, world, fileName }),
  screenshots: (id: string) => call<Screenshot[]>("screenshots_list", { id }),
  screenshotThumb: (id: string, name: string) => call<string>("screenshot_thumb", { id, name }),
  screenshotDelete: (id: string, name: string) => call<void>("screenshot_delete", { id, name }),
  instanceDir: (id: string) => call<string>("instance_dir", { id }),
  instanceIcon: (id: string) => call<string | null>("instance_icon", { id }),
  setInstanceIcon: (id: string, bytes: number[]) => call<Instance>("set_instance_icon", { id, bytes }),
  clearInstanceIcon: (id: string) => call<Instance>("clear_instance_icon", { id }),

  launch: (id: string, taskId: string, join?: Join) => call<void>("launch_instance", { id, taskId, join: join ?? null }),
  takeLaunchRequest: () => call<string | null>("take_launch_request"),
  createShortcut: (id: string) => call<string>("create_shortcut", { id }),
  servers: (id: string) => call<Server[]>("servers_list", { id }),
  serverPing: (address: string) => call<ServerStatus>("server_ping", { address }),
  stop: (id: string) => call<void>("stop_instance", { id }),

  loginStart: () => call<DeviceCode>("login_start"),
  loginFinish: (code: DeviceCode) => call<Account>("login_finish", { code }),
  setActiveAccount: (uuid: string) => call<void>("set_active_account", { uuid }),
  removeAccount: (uuid: string) => call<void>("remove_account", { uuid }),

  contentList: (id: string) => call<ContentEntry[]>("content_list", { id }),
  contentInstall: (id: string, project: string, source: Platform, taskId: string) =>
    call<InstallReport>("content_install", { id, project, source, taskId }),
  /** Sodium, Lithium, FerriteCore and Iris. */
  contentInstallPreset: (id: string, taskId: string) => call<InstallReport>("content_install_preset", { id, taskId }),
  contentBlocked: (id: string) => call<BlockedFile[]>("content_blocked", { id }),
  /** Moves matching files from the Downloads folder in; returns what is still missing. */
  contentResolveBlocked: (id: string) => call<BlockedFile[]>("content_resolve_blocked", { id }),
  contentRemove: (id: string, fileName: string) => call<void>("content_remove", { id, fileName }),
  contentSetEnabled: (id: string, fileName: string, enabled: boolean) =>
    call<void>("content_set_enabled", { id, fileName, enabled }),
  versionChangelog: (source: string, projectId: string, versionId: string) => call<string>("version_changelog", { source, projectId, versionId }),
  contentUpdates: (id: string) => call<UpdateInfo[]>("content_updates", { id }),
  contentApplyUpdates: (id: string, taskId: string) => call<number>("content_apply_updates", { id, taskId }),

  search: (args: SearchArgs) => call<SearchResult>("search", { args }),
  projectDetails: (source: Platform, id: string) => call<ProjectDetails>("project_details", { source, id }),

  modpackImport: (source: string, isFile: boolean, platform: Platform, name: string | null, taskId: string) =>
    call<Instance>("modpack_import", { source, isFile, platform, name, taskId }),
  extrasCatalogue: () => call<ExtrasCatalogue>("extras_catalogue"),
  extrasCurrent: () => call<ExtrasChoice>("extras_current"),
  extrasSet: (hat: string | null, pet: string | null) => call<string | null>("extras_set", { hat, pet }),
  skins: () => call<Skin[]>("skins_list"),
  skinAdd: (file: File) => upload<Omit<Skin, "texture">>("skin_add", file, {}),
  skinRemove: (id: string) => call<void>("skin_remove", { id }),
  skinSetVariant: (id: string, variant: SkinVariant) => call<void>("skin_set_variant", { id, variant }),
  skinApply: (id: string) => call<string>("skin_apply", { id }),
  skinImportCurrent: () => call<Omit<Skin, "texture">>("skin_import_current"),
  capesList: () => call<{ capes: CapeInfo[]; activeId: string | null }>("capes_list"),
  capeImage: (id: string) => call<string>("cape_image", { id }),
  capeSave: (cape: { id: string | null; name: string; png: string; meta: CapeInfo["meta"]; sourceExt: string; source: string; editor: unknown }) =>
    call<CapeInfo>("cape_save", { cape }),
  capeDelete: (id: string) => call<void>("cape_delete", { id }),
  /** Resolves to an error text when only publishing online failed. */
  capeSetActive: (id: string | null) => call<string | null>("cape_set_active", { id }),
  cosmeticsStatus: () => call<OnlineStatus>("cosmetics_status"),
  cosmeticsSignIn: () => call<OnlineStatus>("cosmetics_sign_in"),
  cosmeticsSignOut: () => call<void>("cosmetics_sign_out"),
  cosmeticsDeleteAccount: () => call<void>("cosmetics_delete_account"),
  capeSource: (id: string) => call<{ ext: string; dataUrl: string; editor: unknown } | null>("cape_source", { id }),
  foreignScan: (extraDir: string | null) => call<ForeignInstance[]>("foreign_scan", { extraDir }),
  foreignImport: (path: string, name: string | null, taskId: string) => call<Instance>("foreign_import", { path, name, taskId }),
  modpackExport: (id: string, output: string, name: string, version: string, include: string[]) =>
    call<[number, number]>("modpack_export", { id, output, name, version, include }),
};

export function onEvent<T>(name: string, handler: (payload: T) => void): Promise<UnlistenFn> {
  if (isTauri) return listen<T>(name, (e) => handler(e.payload));
  return Promise.resolve(mockEmitter.on(name, handler as (p: unknown) => void));
}

export function openUrl(url: string) {
  if (isTauri) return openerOpenUrl(url);
  window.open(url, "_blank");
}

export async function pickModpackFile(): Promise<string | null> {
  if (!isTauri) return null;
  const file = await dialogOpen({ filters: [{ name: "Modpack (Modrinth, CurseForge)", extensions: ["mrpack", "zip"] }] });
  return typeof file === "string" ? file : null;
}

export async function pickFolder(title: string): Promise<string | null> {
  if (!isTauri) return null;
  const dir = await dialogOpen({ directory: true, title });
  return typeof dir === "string" ? dir : null;
}

export async function pickExportPath(defaultName: string): Promise<string | null> {
  if (!isTauri) return null;
  return dialogSave({ defaultPath: defaultName, filters: [{ name: "Modrinth-Modpack", extensions: ["mrpack"] }] });
}

export const appWindow = {
  minimize: () => isTauri && getCurrentWindow().minimize(),
  toggleMaximize: () => isTauri && getCurrentWindow().toggleMaximize(),
  close: () => isTauri && getCurrentWindow().close(),
};
