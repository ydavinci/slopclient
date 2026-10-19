import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { openUrl as openerOpenUrl } from "@tauri-apps/plugin-opener";
import { open as dialogOpen, save as dialogSave } from "@tauri-apps/plugin-dialog";
import { mockCall, mockEmitter } from "./mock";
import type {
  Account,
  BlockedFile,
  CapeInfo,
  ContentEntry,
  DeviceCode,
  ForeignInstance,
  InstallReport,
  Instance,
  LoaderKind,
  LoaderVersion,
  Overview,
  Platform,
  SearchArgs,
  SearchResult,
  Settings,
  UpdateInfo,
  VersionList,
} from "./types";

/** False when the UI runs in a plain browser (design preview with mock data). */
export const isTauri = "__TAURI_INTERNALS__" in window;

function call<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  return isTauri ? invoke<T>(cmd, args) : (mockCall(cmd, args) as Promise<T>);
}

export function newTaskId(): string {
  return Math.random().toString(36).slice(2, 10);
}

export const api = {
  overview: () => call<Overview>("get_overview"),
  settings: () => call<Settings>("get_settings"),
  saveSettings: (settings: Settings) => call<void>("save_settings", { settings }),
  openPath: (path: string) => call<void>("open_path", { path }),

  versions: () => call<VersionList>("list_versions"),
  loaderVersions: (loader: LoaderKind, mcVersion: string) =>
    call<LoaderVersion[]>("list_loader_versions", { loader, mcVersion }),

  createInstance: (name: string, mcVersion: string, loader: LoaderKind | null, loaderVersion: string | null) =>
    call<Instance>("create_instance", { name, mcVersion, loader, loaderVersion }),
  saveInstance: (instance: Instance) => call<void>("save_instance", { instance }),
  deleteInstance: (id: string) => call<void>("delete_instance", { id }),
  instanceDir: (id: string) => call<string>("instance_dir", { id }),
  instanceIcon: (id: string) => call<string | null>("instance_icon", { id }),
  setInstanceIcon: (id: string, bytes: number[]) => call<Instance>("set_instance_icon", { id, bytes }),
  clearInstanceIcon: (id: string) => call<Instance>("clear_instance_icon", { id }),

  launch: (id: string, taskId: string, offlineName: string | null) =>
    call<void>("launch_instance", { id, taskId, offlineName }),
  stop: (id: string) => call<void>("stop_instance", { id }),

  loginStart: () => call<DeviceCode>("login_start"),
  loginFinish: (code: DeviceCode) => call<Account>("login_finish", { code }),
  setActiveAccount: (uuid: string) => call<void>("set_active_account", { uuid }),
  removeAccount: (uuid: string) => call<void>("remove_account", { uuid }),

  contentList: (id: string) => call<ContentEntry[]>("content_list", { id }),
  contentInstall: (id: string, project: string, source: Platform, taskId: string) =>
    call<InstallReport>("content_install", { id, project, source, taskId }),
  contentBlocked: (id: string) => call<BlockedFile[]>("content_blocked", { id }),
  /** Moves matching files from the Downloads folder in; returns what is still missing. */
  contentResolveBlocked: (id: string) => call<BlockedFile[]>("content_resolve_blocked", { id }),
  contentRemove: (id: string, fileName: string) => call<void>("content_remove", { id, fileName }),
  contentSetEnabled: (id: string, fileName: string, enabled: boolean) =>
    call<void>("content_set_enabled", { id, fileName, enabled }),
  contentUpdates: (id: string) => call<UpdateInfo[]>("content_updates", { id }),
  contentApplyUpdates: (id: string, taskId: string) => call<number>("content_apply_updates", { id, taskId }),

  search: (args: SearchArgs) => call<SearchResult>("search", { args }),

  modpackImport: (source: string, isFile: boolean, platform: Platform, name: string | null, taskId: string) =>
    call<Instance>("modpack_import", { source, isFile, platform, name, taskId }),
  capesList: () => call<{ capes: CapeInfo[]; activeId: string | null }>("capes_list"),
  capeImage: (id: string) => call<string>("cape_image", { id }),
  capeSave: (cape: { id: string | null; name: string; png: string; meta: CapeInfo["meta"]; sourceExt: string; source: string; editor: unknown }) =>
    call<CapeInfo>("cape_save", { cape }),
  capeDelete: (id: string) => call<void>("cape_delete", { id }),
  capeSetActive: (id: string | null) => call<void>("cape_set_active", { id }),
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
