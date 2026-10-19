import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, newTaskId, onEvent } from "./ipc";
import type { CrashReport, Overview, Progress } from "./types";

export type Route =
  | { page: "home" }
  | { page: "instances" }
  | { page: "instance"; id: string; tab?: InstanceTab }
  | { page: "browse"; target?: string }
  | { page: "settings" };

export type InstanceTab = "mods" | "log" | "settings";

export type ModalState =
  | null
  | { kind: "new-instance" }
  | { kind: "import" }
  | { kind: "login" }
  | { kind: "export"; instanceId: string }
  | { kind: "icon"; instanceId: string }
  | { kind: "crash"; instanceId: string; report: CrashReport };

export interface Task {
  id: string;
  label: string;
  stage: string;
  finished: number;
  total: number;
  bytes: number;
  totalBytes: number;
  done: boolean;
  error?: string;
}

export interface Toast {
  id: number;
  message: string;
  kind: "info" | "success" | "error";
}

export interface LogLine {
  text: string;
  stderr: boolean;
}

interface Store {
  overview: Overview | null;
  refresh: () => Promise<void>;
  route: Route;
  navigate: (route: Route) => void;
  /** Instance highlighted on the start page (remembered across restarts). */
  selectedId: string | null;
  select: (id: string) => void;
  modal: ModalState;
  openModal: (modal: ModalState) => void;
  closeModal: () => void;
  tasks: Task[];
  /** Runs `fn` as a tracked task; progress events with its id update the dock. */
  runTask: <T>(label: string, fn: (taskId: string) => Promise<T>) => Promise<T>;
  /** Instance ids whose launch is being prepared. */
  launching: Set<string>;
  markLaunching: (id: string, on: boolean) => void;
  logs: Record<string, LogLine[]>;
  clearLogs: (id: string) => void;
  toasts: Toast[];
  toast: (message: string, kind?: Toast["kind"]) => void;
  dismissToast: (id: number) => void;
}

function storedSelection(): string | null {
  try {
    return localStorage.getItem("daclient.selected");
  } catch {
    return null;
  }
}

const MAX_LOG_LINES = 5000;

const StoreContext = createContext<Store | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [route, setRoute] = useState<Route>({ page: "home" });
  const [modal, setModal] = useState<ModalState>(null);
  const closeModal = useCallback(() => setModal(null), []);
  const [selectedId, setSelectedId] = useState<string | null>(storedSelection);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [logs, setLogs] = useState<Record<string, LogLine[]>>({});
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [launching, setLaunching] = useState<Set<string>>(new Set());
  const toastId = useRef(0);

  const refresh = useCallback(async () => {
    setOverview(await api.overview());
  }, []);

  const toast = useCallback((message: string, kind: Toast["kind"] = "info") => {
    const id = ++toastId.current;
    setToasts((t) => [...t, { id, message, kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "error" ? 8000 : 4000);
  }, []);

  const runTask = useCallback(async <T,>(label: string, fn: (taskId: string) => Promise<T>) => {
    const id = newTaskId();
    setTasks((t) => [...t, { id, label, stage: "Vorbereiten", finished: 0, total: 0, bytes: 0, totalBytes: 0, done: false }]);
    const finish = (error?: string) => {
      setTasks((t) => t.map((x) => (x.id === id ? { ...x, done: true, error } : x)));
      setTimeout(() => setTasks((t) => t.filter((x) => x.id !== id)), error ? 6000 : 1500);
    };
    try {
      const result = await fn(id);
      finish();
      return result;
    } catch (e) {
      finish(String(e));
      throw e;
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => toast(String(e), "error"));
    const subs = [
      onEvent<{ taskId: string; event: Progress }>("task-progress", ({ taskId, event }) => {
        setTasks((t) =>
          t.map((x) => {
            if (x.id !== taskId) return x;
            return event.type === "stage"
              ? { ...x, stage: translateStage(event.name), finished: 0, total: 0, bytes: 0, totalBytes: 0 }
              : { ...x, finished: event.finished, total: event.total, bytes: event.bytes, totalBytes: event.totalBytes };
          }),
        );
      }),
      onEvent<{ instanceId: string; line: string; stderr: boolean }>("game-log", ({ instanceId, line, stderr }) => {
        setLogs((l) => {
          const lines = l[instanceId] ?? [];
          const next = lines.length >= MAX_LOG_LINES ? lines.slice(-MAX_LOG_LINES + 1) : lines.slice();
          next.push({ text: line, stderr });
          return { ...l, [instanceId]: next };
        });
      }),
      onEvent<{ instanceId: string; report: CrashReport }>("game-crash", ({ instanceId, report }) => setModal({ kind: "crash", instanceId, report })),
      onEvent<{ instanceId: string; running: boolean; exitCode: number | null }>("game-state", ({ instanceId, running, exitCode }) => {
        setLaunching((s) => {
          const n = new Set(s);
          n.delete(instanceId);
          return n;
        });
        setOverview((o) =>
          o && {
            ...o,
            running: running ? [...new Set([...o.running, instanceId])] : o.running.filter((r) => r !== instanceId),
          },
        );
        if (!running) {
          // Remember the end of the session so the overlay's log corner survives a restart.
          setLogs((l) => {
            const tail = (l[instanceId] ?? []).slice(-8);
            try {
              if (tail.length) localStorage.setItem(`daclient.log.${instanceId}`, JSON.stringify(tail));
            } catch {
              /* storage unavailable: the corner just stays empty next time */
            }
            return l;
          });
          refresh();
          if (exitCode !== null && exitCode !== 0 && exitCode !== 1) {
            toast(`Das Spiel wurde mit Code ${exitCode} beendet. Details im Log.`, "error");
          }
        }
      }),
    ];
    return () => subs.forEach((s) => s.then((un) => un()));
  }, [refresh, toast]);

  const store = useMemo<Store>(
    () => ({
      overview,
      refresh,
      route,
      navigate: setRoute,
      selectedId,
      select: (id) => {
        setSelectedId(id);
        try {
          localStorage.setItem("daclient.selected", id);
        } catch {
          /* private mode: selection just isn't remembered */
        }
      },
      tasks,
      runTask,
      launching,
      markLaunching: (id, on) =>
        setLaunching((s) => {
          const n = new Set(s);
          if (on) n.add(id);
          else n.delete(id);
          return n;
        }),
      logs,
      clearLogs: (id) => setLogs((l) => ({ ...l, [id]: [] })),
      toasts,
      toast,
      dismissToast: (id) => setToasts((t) => t.filter((x) => x.id !== id)),
      modal,
      openModal: setModal,
      closeModal,
    }),
    [overview, refresh, route, selectedId, tasks, runTask, launching, logs, toasts, toast, modal, closeModal],
  );

  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}

/** The last lines of the previous session, saved when the game exited. */
export function lastSessionLog(id: string): LogLine[] {
  try {
    return JSON.parse(localStorage.getItem(`daclient.log.${id}`) ?? "[]");
  } catch {
    return [];
  }
}

export function useStore(): Store {
  const store = useContext(StoreContext);
  if (!store) throw new Error("useStore outside StoreProvider");
  return store;
}

/** Starts an instance (installing whatever is missing) and tracks the preparation. */
export function useLaunch() {
  const { runTask, toast, overview, clearLogs, markLaunching } = useStore();
  return useCallback(
    async (id: string, name: string, offlineName: string | null = null) => {
      if (!overview) return;
      if (!offlineName && overview.accounts.length === 0) {
        toast("Melde dich zuerst mit deinem Microsoft-Account an.", "error");
        return;
      }
      clearLogs(id);
      markLaunching(id, true);
      try {
        await runTask(`${name} starten`, (taskId) => api.launch(id, taskId, offlineName));
      } catch (e) {
        markLaunching(id, false);
        toast(String(e), "error");
      }
    },
    [overview, runTask, toast, clearLogs, markLaunching],
  );
}

const STAGES: Record<string, string> = {
  "Fetching version metadata": "Versionsdaten laden",
  "Downloading game files": "Spieldateien herunterladen",
  "Extracting natives": "Native Bibliotheken entpacken",
  "Downloading assets": "Assets herunterladen",
  "Starting game": "Spiel starten",
  "Fetching loader profile": "Loader-Profil laden",
  "Resolving project": "Projekt laden",
  "Resolving dependencies": "Abhängigkeiten auflösen",
  Downloading: "Herunterladen",
  "Downloading updates": "Updates herunterladen",
  "Resolving modpack": "Modpack laden",
  "Downloading modpack": "Modpack herunterladen",
  "Downloading modpack files": "Modpack-Dateien herunterladen",
  "Applying overrides": "Konfiguration übernehmen",
  "Linking files to Modrinth": "Dateien mit Modrinth verknüpfen",
};

function translateStage(name: string): string {
  if (STAGES[name]) return STAGES[name];
  const java = name.match(/^(Preparing|Downloading) Java \((.+)\)$/);
  if (java) return java[1] === "Preparing" ? "Java prüfen" : "Java herunterladen";
  const installer = name.match(/^(Downloading|Running) (NeoForge|Forge) installer/);
  if (installer) return installer[1] === "Downloading" ? `${installer[2]}-Installer laden` : `${installer[2]} installieren (kann etwas dauern)`;
  return name;
}
