import { ArrowDownToLine, ArrowLeft, ArrowUpCircle, Link2, Camera, ChevronDown, CopyPlus, ExternalLink, FilePlus2, FileWarning, FolderOpen, Gauge, Globe2, Image, MonitorUp, Plus, Puzzle, RefreshCw, Search, Server as ServerIcon, Sparkles, Terminal, Trash2, Upload } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, onEvent, openUrl } from "../lib/ipc";
import { pendingIcon } from "../lib/icons";
import { loaderLabel, loaderName, playTime, relativeTime } from "../lib/format";
import { useLaunch, useStore, type InstanceTab } from "../lib/store";
import { supportsClientMod, CLIENT_MOD_MC, type BlockedFile, type ContentEntry, type ContentType, type Instance, type Source, type UpdateInfo } from "../lib/types";
import { Button, Chip, EASE, EmptyState, Field, IconButton, InstanceIcon, PlayButton, Segmented, Spinner, Toggle, inputClass, rise, stagger } from "../ui/kit";
import { ShareLogButton, presetSummary } from "../ui/Modals";
import { UpdateReview } from "../ui/UpdateReview";
import { LoaderCard } from "../ui/LoaderCard";
import { loaderUpdate } from "../lib/loaders";
import { DropZone, FilePicker, ScreenshotsTab, ServersTab, WorldsTab } from "./InstanceTabs";

export function InstanceDetail({ id, tab: initialTab }: { id: string; tab?: InstanceTab }) {
  const { overview, navigate, openModal, launching, toast, refresh } = useStore();
  const launch = useLaunch();
  const instance = overview?.instances.find((i) => i.id === id);
  const [tab, setTab] = useState<InstanceTab>(initialTab ?? "mods");
  const running = overview?.running.includes(id) ?? false;
  const [newLoader, setNewLoader] = useState<string | null>(null);
  const [updatingLoader, setUpdatingLoader] = useState(false);
  const loaderKey = instance ? `${instance.mcVersion}:${JSON.stringify(instance.loader)}` : "";
  useEffect(() => {
    setNewLoader(null);
    if (!instance) return;
    let live = true;
    loaderUpdate(instance)
      .then((v) => live && setNewLoader(v))
      .catch(() => {});
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaderKey]);

  async function updateLoader() {
    if (!instance || !newLoader || instance.loader.type === "vanilla") return;
    setUpdatingLoader(true);
    try {
      await api.saveInstance({ ...instance, loader: { type: instance.loader.type, version: newLoader } });
      await refresh();
      toast(`${loaderName(instance.loader)} ${newLoader} wird beim nächsten Start installiert.`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setUpdatingLoader(false);
    }
  }

  useEffect(() => {
    if (running) setTab("log");
  }, [running]);

  if (!instance) return null;
  const preparing = launching.has(id);

  return (
    <div className="flex h-full flex-col">
      <motion.div variants={stagger} initial="hidden" animate="show" className="flex items-center gap-4 px-8 pt-2">
        <motion.div variants={rise}>
          <IconButton label="Zurück" onClick={() => navigate({ page: "instances" })}>
            <ArrowLeft className="size-4" />
          </IconButton>
        </motion.div>
        <motion.div variants={rise}>
          {/* Click or drop an image to change the icon. */}
          <button
            aria-label="Icon ändern"
            onClick={() => openModal({ kind: "icon", instanceId: instance.id })}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              const file = e.dataTransfer.files[0];
              if (file) {
                pendingIcon.file = file;
                openModal({ kind: "icon", instanceId: instance.id });
              }
            }}
            className="group relative rounded-[16px] focus-visible:outline-2 focus-visible:outline-accent-2"
          >
            <InstanceIcon id={instance.id} name={instance.name} icon={instance.icon} size={56} />
            <span className="absolute inset-0 grid place-items-center rounded-[16px] bg-black/55 text-[11.5px] font-semibold text-white opacity-0 transition-opacity duration-200 group-hover:opacity-100">Ändern</span>
          </button>
        </motion.div>
        <motion.div variants={rise} className="min-w-0">
          <h1 className="truncate text-[30px] font-extrabold tracking-[-0.03em]">{instance.name}</h1>
          <p className="mt-0.5 text-[13px] text-muted">
            Minecraft {instance.mcVersion} · {loaderLabel(instance.loader)}
            {instance.lastPlayed ? ` · ${playTime(instance.playTimeSecs)} · zuletzt ${relativeTime(instance.lastPlayed)}` : " · noch nicht gespielt"}
          </p>
          <AnimatePresence>
            {newLoader && (
              <motion.button
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                disabled={running || updatingLoader}
                onClick={updateLoader}
                title="Mods, Welten und Einstellungen bleiben"
                className="mt-1.5 inline-flex items-center gap-1.5 rounded-full bg-accent/15 px-2.5 py-1 text-[11.5px] font-semibold text-accent-2 transition-colors duration-200 hover:bg-accent/25 disabled:opacity-50"
              >
                {updatingLoader ? <Spinner className="size-3" /> : <ArrowDownToLine className="size-3" />}
                {loaderName(instance.loader)} {newLoader} verfügbar · Aktualisieren
              </motion.button>
            )}
          </AnimatePresence>
        </motion.div>
        <motion.div variants={rise} className="ml-auto flex items-center gap-2">
          <Button icon={<FolderOpen className="size-4" />} onClick={() => api.instanceDir(id).then(api.openPath).catch((e) => toast(String(e), "error"))}>
            Ordner
          </Button>
          <Button icon={<CopyPlus className="size-4" />} onClick={() => openModal({ kind: "duplicate", instanceId: id })}>
            Kopieren
          </Button>
          <Button icon={<Upload className="size-4" />} onClick={() => openModal({ kind: "export", instanceId: id })}>
            Exportieren
          </Button>
          <IconButton
            label="Verknüpfung auf dem Desktop"
            onClick={() =>
              api
                .createShortcut(id)
                .then(() => toast("Verknüpfung liegt auf dem Desktop. Ein Doppelklick startet die Instanz direkt.", "success"))
                .catch((e) => toast(String(e), "error"))
            }
          >
            <MonitorUp className="size-4" />
          </IconButton>
          <div className="scale-[0.82] origin-right">
            <PlayButton
              state={running ? "running" : preparing ? "preparing" : "idle"}
              label={running ? "Beenden" : preparing ? "Startet …" : "Spielen"}
              onClick={() => (running ? api.stop(id).catch((e) => toast(String(e), "error")) : launch(id, instance.name))}
            />
          </div>
        </motion.div>
      </motion.div>

      <div className="mt-5 px-8">
        <Segmented
          className="w-[740px]"
          value={tab}
          onChange={setTab}
          options={[
            { value: "mods", label: <><Puzzle className="size-3.5" />Inhalte</> },
            { value: "servers", label: <><ServerIcon className="size-3.5" />Server</> },
            { value: "worlds", label: <><Globe2 className="size-3.5" />Welten</> },
            { value: "screenshots", label: <><Camera className="size-3.5" />Screenshots</> },
            { value: "log", label: <><Terminal className="size-3.5" />Log{running && <span className="size-1.5 animate-pulse rounded-full bg-accent-2" />}</> },
            { value: "settings", label: "Einstellungen" },
          ]}
        />
      </div>

      <div className="relative min-h-0 flex-1">
        {/* CSS enter animation, like the pages: motion's exit handoff can leave tabs invisible in release builds. */}
        <div key={tab} className="page-enter absolute inset-0">
            {tab === "mods" && <ContentTab instance={instance} />}
            {tab === "servers" && <ServersTab instance={instance} />}
            {tab === "worlds" && <WorldsTab instance={instance} />}
            {tab === "screenshots" && <ScreenshotsTab instance={instance} />}
            {tab === "log" && <LogTab instanceId={id} />}
            {tab === "settings" && <SettingsTab instance={instance} />}
        </div>
      </div>
    </div>
  );
}

const KINDS: { value: ContentType; label: string; icon: ReactNode }[] = [
  { value: "mod", label: "Mods", icon: <Puzzle className="size-4" /> },
  { value: "resourcepack", label: "Resourcepacks", icon: <Image className="size-4" /> },
  { value: "shader", label: "Shader", icon: <Sparkles className="size-4" /> },
];

function sourceLabel(source: Source): string {
  switch (source.type) {
    case "modrinth":
      return `Modrinth · ${source.slug ?? source.projectId}`;
    case "curseforge":
      return `CurseForge · ${source.slug ?? source.projectId}`;
    default:
      return "lokal hinzugefügt";
  }
}

/** Green one-click update in a content row; does not open the project page. */
function UpdateButton({ update, busy, disabled, blockedHint, onClick }: { update: UpdateInfo; busy: boolean; disabled: boolean; blockedHint?: string; onClick: () => void }) {
  const { entry, new_version: next } = update;
  const label = `${entry.name} auf ${next.version_number} aktualisieren (jetzt: ${entry.version ?? "unbekannt"})`;
  return (
    // The wrapper keeps clicks on a disabled button from opening the project page.
    <span className="inline-flex items-center gap-1.5" onClick={(ev) => ev.stopPropagation()}>
      {next.version_type !== "release" && <Chip tone="warn">{next.version_type}</Chip>}
      <button
        aria-label={label}
        title={blockedHint ?? label}
        disabled={disabled}
        onClick={(ev) => {
          ev.stopPropagation();
          onClick();
        }}
        onKeyDown={(ev) => ev.stopPropagation()}
        className="inline-flex w-fit items-center gap-1.5 rounded-md bg-on/12 px-2 py-0.5 text-[11.5px] font-semibold text-on transition-colors duration-200 hover:bg-on/25 disabled:opacity-45"
      >
        {busy ? <Spinner className="size-3" /> : <ArrowUpCircle className="size-3" />}
        Update
      </button>
    </span>
  );
}

/** Content as ClickGUI modules: toggle to enable, click to expand details. */
function ContentTab({ instance }: { instance: Instance }) {
  const { navigate, runTask, toast, openProject, overview } = useStore();
  const running = overview?.running.includes(instance.id) ?? false;
  const [entries, setEntries] = useState<ContentEntry[] | null>(null);
  const [kind, setKind] = useState<ContentType>(instance.loader.type === "vanilla" ? "resourcepack" : "mod");
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [updates, setUpdates] = useState<UpdateInfo[] | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [checking, setChecking] = useState(false);
  /** `kind:fileName` of the update being installed, "all" for all of them. */
  const [updating, setUpdating] = useState<string | null>(null);
  const [missing, setMissing] = useState<BlockedFile[]>([]);
  const [showMissing, setShowMissing] = useState(false);
  const [resolving, setResolving] = useState(false);

  const load = useCallback(() => {
    api.contentList(instance.id).then(setEntries).catch((e) => toast(String(e), "error"));
    api.contentBlocked(instance.id).then(setMissing).catch(() => setMissing([]));
  }, [instance.id, toast]);

  async function resolveMissing() {
    setResolving(true);
    try {
      const remaining = await api.contentResolveBlocked(instance.id);
      const found = missing.length - remaining.length;
      setMissing(remaining);
      if (found > 0) {
        toast(`${found} ${found === 1 ? "Datei" : "Dateien"} aus dem Download-Ordner übernommen`, "success");
        load();
      } else {
        toast("Im Download-Ordner liegt noch keine der fehlenden Dateien.");
      }
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setResolving(false);
    }
  }
  useEffect(load, [load]);

  const visible = useMemo(() => (entries ?? []).filter((e) => e.kind === kind && e.name.toLowerCase().includes(query.toLowerCase())), [entries, kind, query]);
  const counts = useMemo(() => {
    const c: Record<string, [number, number]> = {};
    for (const e of entries ?? []) {
      const [n, on] = c[e.kind] ?? [0, 0];
      c[e.kind] = [n + 1, on + (e.enabled ? 1 : 0)];
    }
    return c;
  }, [entries]);
  const updateKey = (e: { kind: ContentType; fileName: string }) => `${e.kind}:${e.fileName}`;
  const updateFor = useMemo(() => new Map((updates ?? []).map((u) => [updateKey(u.entry), u])), [updates]);

  /** `force` asks Modrinth/CurseForge again; otherwise a check younger than 30 minutes is reused. */
  const checkUpdates = useCallback(
    async (force: boolean, quiet = false) => {
      setChecking(true);
      try {
        setUpdates(await api.contentUpdates(instance.id, force));
      } catch (e) {
        if (!quiet) toast(String(e), "error");
      } finally {
        setChecking(false);
      }
    },
    [instance.id, toast],
  );

  // Look for updates when the tab opens, and pick up results of the start-up check
  // or changes to the content (a new mod means a new check).
  const checkingRef = useRef(false);
  checkingRef.current = checking;
  useEffect(() => {
    checkUpdates(false, true);
    const sub = onEvent<null>("updates-changed", () => {
      if (!checkingRef.current) checkUpdates(false, true);
    });
    return () => {
      sub.then((un) => un());
    };
  }, [checkUpdates]);

  const updateBlocked = running ? "Schließ zuerst das Spiel" : undefined;

  async function applyUpdates() {
    setUpdating("all");
    try {
      const n = await runTask(`Updates für ${instance.name}`, (taskId) => api.contentApplyUpdates(instance.id, taskId));
      toast(`${n} ${n === 1 ? "Datei" : "Dateien"} aktualisiert`, "success");
      setUpdates([]);
      load();
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setUpdating(null);
    }
  }

  async function applyOne(u: UpdateInfo) {
    const key = updateKey(u.entry);
    setUpdating(key);
    try {
      const n = await runTask(`Update für ${u.entry.name}`, (taskId) => api.contentApplyUpdates(instance.id, taskId, [{ kind: u.entry.kind, fileName: u.entry.fileName }]));
      toast(n > 0 ? `${u.entry.name} aktualisiert` : `${u.entry.name} ist schon aktuell`, n > 0 ? "success" : "info");
      setUpdates((list) => list?.filter((x) => updateKey(x.entry) !== key) ?? null);
      load();
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setUpdating(null);
    }
  }

  const remote = (e: ContentEntry) => e.source.type === "modrinth" || e.source.type === "curseforge";

  /** Projects from Modrinth or CurseForge open their page; local files just toggle. */
  function openEntry(e: ContentEntry) {
    if (e.source.type === "modrinth") openProject({ source: "modrinth", id: e.source.slug ?? e.source.projectId, target: instance.id });
    else if (e.source.type === "curseforge") openProject({ source: "curseforge", id: String(e.source.projectId), target: instance.id });
    else toggle(e, !e.enabled);
  }

  async function toggle(e: ContentEntry, enabled: boolean) {
    setEntries((list) => list?.map((x) => (x.fileName === e.fileName && x.kind === e.kind ? { ...x, enabled } : x)) ?? null);
    try {
      await api.contentSetEnabled(instance.id, e.fileName, enabled);
      toast(`${e.name} ${enabled ? "aktiviert" : "deaktiviert"}`);
    } catch (err) {
      toast(String(err), "error");
      load();
    }
  }

  async function remove(e: ContentEntry) {
    try {
      await api.contentRemove(instance.id, e.fileName);
      setEntries((list) => list?.filter((x) => !(x.fileName === e.fileName && x.kind === e.kind)) ?? null);
      toast(`${e.name} entfernt`);
    } catch (err) {
      toast(String(err), "error");
    }
  }

  async function installPreset() {
    try {
      const r = await runTask(`Performance-Paket für ${instance.name}`, (taskId) => api.contentInstallPreset(instance.id, taskId));
      toast(presetSummary(r), r.warnings.length ? "info" : "success");
      load();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  async function addFiles(files: File[]) {
    const added: ContentType[] = [];
    for (const f of files) {
      try {
        added.push(await api.contentAddLocal(instance.id, f));
      } catch (e) {
        toast(String(e), "error");
      }
    }
    if (added.length === 0) return;
    const label = (k: ContentType) => KINDS.find((x) => x.value === k)!.label;
    toast(added.length === 1 ? `${files.find(Boolean)!.name} zu ${label(added[0])} hinzugefügt` : `${added.length} Dateien hinzugefügt`, "success");
    // Jump to where the (last) file went.
    setKind(added[added.length - 1]);
    load();
  }

  const blocked = kind === "mod" && instance.loader.type === "vanilla";

  return (
    <div className="flex h-full gap-4 px-8 pt-5 pb-6">
      <div className="flex w-52 shrink-0 flex-col gap-1">
        {KINDS.map((k) => {
          const [n, on] = counts[k.value] ?? [0, 0];
          const active = kind === k.value;
          return (
            <button
              key={k.value}
              onClick={() => setKind(k.value)}
              className={`relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors duration-200 ${active ? "text-white" : "text-muted hover:bg-white/[0.04] hover:text-text"}`}
            >
              {active && <motion.span layoutId="kind-active" transition={{ type: "spring", stiffness: 500, damping: 36 }} className="glass absolute inset-0 rounded-xl" />}
              <span className="relative">{k.icon}</span>
              <span className="relative flex-1 text-[13px] font-semibold">{k.label}</span>
              <span className="relative font-mono text-[11px] text-muted">
                {on}/{n}
              </span>
            </button>
          );
        })}
        <div className="mt-auto flex flex-col gap-2">
          <FilePicker accept=".jar,.zip" onFiles={addFiles}>
            {(pick) => (
              <Button icon={<FilePlus2 className="size-4" />} onClick={pick}>
                Datei hinzufügen
              </Button>
            )}
          </FilePicker>
          <Button icon={<RefreshCw className={`size-4 ${checking ? "animate-spin" : ""}`} />} disabled={checking} onClick={() => checkUpdates(true)}>
            Updates prüfen
          </Button>
          {updates && updates.length > 0 && (
            <button
              disabled={updating !== null || running}
              title={updateBlocked}
              onClick={applyUpdates}
              className="inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-[10px] bg-on/12 px-4 text-[13px] font-semibold text-on transition duration-200 hover:bg-on/20 active:scale-[0.97] disabled:opacity-45 disabled:active:scale-100"
            >
              {updating === "all" ? <Spinner className="size-4" /> : <ArrowUpCircle className="size-4" />}
              Alle aktualisieren ({updates.length})
            </button>
          )}
          {instance.loader.type !== "vanilla" && (
            <Button icon={<Gauge className="size-4" />} onClick={installPreset} title="Sodium, Lithium, FerriteCore und Iris">
              Performance-Paket
            </Button>
          )}
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => navigate({ page: "browse", target: instance.id })}>
            Hinzufügen
          </Button>
        </div>
      </div>

      <DropZone label="Mods, Resourcepacks oder Shader hier ablegen" onFiles={addFiles} className="flex min-w-0 flex-1">
      <div className="glass flex min-w-0 flex-1 flex-col overflow-hidden rounded-[18px]">
        <div className="flex items-center gap-3 border-b border-line px-4 py-3">
          <div className="relative flex-1">
            <Search className="absolute top-3 left-3.5 size-4 text-dim" />
            <input className={`${inputClass} pl-10`} placeholder={`${KINDS.find((k) => k.value === kind)!.label} filtern`} value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
        </div>
        <AnimatePresence>
          {missing.length > 0 && (
            <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.4, ease: EASE }} className="overflow-hidden">
              <div className="border-b border-line bg-warn/[0.08] px-4 py-2.5 text-[13px]">
                <div className="flex items-center gap-3">
                  <FileWarning className="size-4 shrink-0 text-warn" />
                  <span>
                    <b className="text-warn">
                      {missing.length} {missing.length === 1 ? "Datei fehlt" : "Dateien fehlen"}
                    </b>
                    : Die Autoren erlauben den Download nur auf curseforge.com. Lade sie dort herunter, SlopClient holt sie aus deinem Download-Ordner.
                  </span>
                  <div className="ml-auto flex shrink-0 gap-2">
                    <Button size="sm" onClick={() => setShowMissing((v) => !v)}>
                      {showMissing ? "Liste ausblenden" : "Liste zeigen"}
                    </Button>
                    <Button variant="primary" size="sm" loading={resolving} onClick={resolveMissing}>
                      Downloads prüfen
                    </Button>
                  </div>
                </div>
                <AnimatePresence initial={false}>
                  {showMissing && (
                    <motion.ul initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.3, ease: EASE }} className="overflow-hidden pl-7">
                      {missing.map((b) => (
                        <li key={b.fileId} className="flex items-center gap-3 pt-2">
                          <span className="min-w-0 flex-1 truncate">
                            {b.name} <span className="font-mono text-[12.5px] text-muted">{b.fileName}</span>
                          </span>
                          <button className="inline-flex shrink-0 items-center gap-1 text-[13px] font-semibold text-accent-2 hover:underline" onClick={() => openUrl(b.pageUrl)}>
                            Auf CurseForge öffnen <ExternalLink className="size-3" />
                          </button>
                        </li>
                      ))}
                    </motion.ul>
                  )}
                </AnimatePresence>
              </div>
            </motion.div>
          )}
          {updates && updates.length > 0 && (
            <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.4, ease: EASE }} className="overflow-hidden">
              <div className="flex items-center gap-3 border-b border-line bg-accent/[0.08] px-4 py-2.5 text-[13px]">
                <ArrowDownToLine className="size-4 text-accent-2" />
                <span>
                  <b className="text-accent-2">
                    {updates.length} {updates.length === 1 ? "Update" : "Updates"}
                  </b>{" "}
                  verfügbar: {updates.slice(0, 3).map((u) => u.entry.name).join(", ")}
                  {updates.length > 3 && ` und ${updates.length - 3} weitere`}
                </span>
                <Button size="sm" className="ml-auto" onClick={() => setReviewing((r) => !r)} aria-expanded={reviewing}>
                  {reviewing ? "Änderungen ausblenden" : "Änderungen ansehen"}
                </Button>
                <Button variant="primary" size="sm" loading={updating === "all"} disabled={updating !== null || running} title={updateBlocked} onClick={applyUpdates}>
                  Alle aktualisieren
                </Button>
              </div>
              {reviewing && <UpdateReview updates={updates} />}
            </motion.div>
          )}
        </AnimatePresence>

        <div className="min-h-0 flex-1 overflow-auto p-2">
          {entries === null ? (
            <div className="grid place-items-center py-16">
              <Spinner className="size-5" />
            </div>
          ) : blocked || visible.length === 0 ? (
            <EmptyState
              icon={KINDS.find((k) => k.value === kind)!.icon}
              title={blocked ? "Vanilla lädt keine Mods" : query ? "Keine Treffer" : `Keine ${KINDS.find((k) => k.value === kind)!.label}`}
              text={blocked ? "Leg eine Instanz mit Fabric, Quilt, NeoForge oder Forge an, um Mods zu nutzen." : "Durchsuche Modrinth oder CurseForge und installiere mit einem Klick. Abhängigkeiten kommen automatisch mit."}
              action={
                !blocked && (
                  <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => navigate({ page: "browse", target: instance.id })}>
                    Inhalte entdecken
                  </Button>
                )
              }
            />
          ) : (
            <motion.div key={kind} variants={stagger} initial="hidden" animate="show" className="flex flex-col gap-1">
              {visible.map((e) => {
                const key = e.kind + e.fileName;
                const expanded = open === key;
                const update = updateFor.get(updateKey(e));
                return (
                  <motion.div key={key} variants={rise} layout="position" className={`overflow-hidden rounded-xl transition-colors duration-300 ${e.enabled ? "bg-accent/[0.18] shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--color-accent-2)_18%,transparent)]" : "bg-white/[0.02]"}`}>
                    <div
                      role="button"
                      tabIndex={0}
                      aria-pressed={remote(e) ? undefined : e.enabled}
                      aria-label={remote(e) ? `${e.name} ansehen` : `${e.name} ${e.enabled ? "deaktivieren" : "aktivieren"}`}
                      onClick={() => openEntry(e)}
                      onContextMenu={(ev) => {
                        ev.preventDefault();
                        setOpen(expanded ? null : key);
                      }}
                      onKeyDown={(ev) => ev.key === "Enter" && openEntry(e)}
                      className={`flex cursor-pointer items-center gap-3 px-3 py-2.5 transition-opacity duration-300 ${e.enabled ? "" : "opacity-55 hover:opacity-80"}`}
                    >
                      {e.iconUrl ? <img src={e.iconUrl} alt="" className="size-9 rounded-lg bg-white/5 object-cover" /> : <InstanceIcon id={e.fileName} name={e.name} size={36} />}
                      <div className="min-w-0 flex-1">
                        <div className={`truncate text-[13.5px] font-semibold ${e.enabled ? "chroma-text" : "text-muted"}`}>{e.name}</div>
                        <div className="truncate font-mono text-[11.5px] text-muted">{e.version ?? "lokale Datei"}</div>
                      </div>
                      {update && (
                        <UpdateButton
                          update={update}
                          busy={updating === updateKey(e)}
                          disabled={updating !== null || running}
                          blockedHint={updateBlocked}
                          onClick={() => applyOne(update)}
                        />
                      )}
                      {e.dependency && <Chip>Abhängigkeit</Chip>}
                      {e.source.type === "local" && <Chip>Lokal</Chip>}
                      <span onClick={(ev) => ev.stopPropagation()}>
                        <Toggle checked={e.enabled} onChange={(v) => toggle(e, v)} label={`${e.name} aktivieren`} />
                      </span>
                      <button
                        aria-label={`Details zu ${e.name}`}
                        aria-expanded={expanded}
                        onClick={(ev) => {
                          ev.stopPropagation();
                          setOpen(expanded ? null : key);
                        }}
                        className="grid size-7 place-items-center rounded-lg text-muted transition hover:bg-white/[0.08] hover:text-text"
                      >
                        <motion.span animate={{ rotate: expanded ? 180 : 0 }} transition={{ duration: 0.3, ease: EASE }}>
                          <ChevronDown className="size-4" />
                        </motion.span>
                      </button>
                    </div>
                    <AnimatePresence initial={false}>
                      {expanded && (
                        <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.35, ease: EASE }}>
                          <div className="flex items-center gap-6 border-t border-line px-3 py-3 text-xs text-muted">
                            <span>
                              Datei <span className="ml-1 font-mono text-text">{e.fileName}</span>
                            </span>
                            <span>
                              Quelle <span className="ml-1 text-text">{sourceLabel(e.source)}</span>
                            </span>
                            <Button variant="danger" size="sm" className="ml-auto" icon={<Trash2 className="size-3.5" />} onClick={() => remove(e)}>
                              Entfernen
                            </Button>
                          </div>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </motion.div>
                );
              })}
            </motion.div>
          )}
        </div>
      </div>
      </DropZone>
    </div>
  );
}

function LogTab({ instanceId }: { instanceId: string }) {
  const { logs, clearLogs, toast } = useStore();
  const lines = logs[instanceId] ?? [];
  const [filter, setFilter] = useState("");
  const [follow, setFollow] = useState(true);
  const box = useRef<HTMLDivElement>(null);
  const shown = filter ? lines.filter((l) => l.text.toLowerCase().includes(filter.toLowerCase())) : lines;

  useEffect(() => {
    if (follow && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [shown.length, follow]);

  return (
    <div className="flex h-full flex-col px-8 pt-5 pb-6">
      <div className="mb-3 flex items-center gap-3">
        <div className="relative w-72">
          <Search className="absolute top-3 left-3.5 size-4 text-dim" />
          <input className={`${inputClass} pl-10`} placeholder="Log filtern" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </div>
        <span className="font-mono text-xs text-muted">{lines.length} Zeilen</span>
        <label className="ml-auto flex items-center gap-2 text-[13px] text-muted">
          <Toggle checked={follow} onChange={setFollow} label="Automatisch scrollen" />
          Mitlaufen
        </label>
        {lines.length > 0 && <ShareLogButton size="sm" variant="ghost" getText={() => lines.map((l) => l.text).join("\n")} />}
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            navigator.clipboard.writeText(lines.map((l) => l.text).join("\n"));
            toast("Log kopiert");
          }}
        >
          Kopieren
        </Button>
        <Button size="sm" variant="ghost" onClick={() => clearLogs(instanceId)}>
          Leeren
        </Button>
      </div>
      <div ref={box} className="glass min-h-0 flex-1 overflow-auto rounded-[18px] bg-black/50 p-4 font-mono text-[12px] leading-[1.7] select-text">
        {shown.length === 0 ? (
          <p className="text-muted">Noch keine Ausgabe. Starte die Instanz, dann läuft das Log hier live mit.</p>
        ) : (
          shown.map((l, i) => (
            <motion.div key={i} initial={{ opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }} transition={{ duration: 0.25 }} className={`break-all whitespace-pre-wrap ${/\b(ERROR|FATAL)\b|Exception/.test(l.text) ? "text-err" : /\bWARN\b/.test(l.text) || l.stderr ? "text-warn" : "text-[#d6d1e3]"}`}>
              {l.text}
            </motion.div>
          ))
        )}
      </div>
    </div>
  );
}

function SettingsTab({ instance }: { instance: Instance }) {
  const { refresh, toast, navigate, overview } = useStore();
  const groups = useMemo(() => [...new Set((overview?.instances ?? []).map((i) => i.group).filter((g): g is string => !!g))].sort(), [overview]);
  const [draft, setDraft] = useState(instance);
  const [jvmArgs, setJvmArgs] = useState(instance.java.jvmArgs.join(" "));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [saving, setSaving] = useState(false);
  const java = draft.java;
  const hooks = draft.hooks ?? { preLaunch: "", postExit: "" };
  const setJava = (patch: Partial<Instance["java"]>) => setDraft({ ...draft, java: { ...java, ...patch } });

  const runningGame = overview?.running.includes(instance.id) ?? false;
  const [sharedGroup, setSharedGroup] = useState<string | null>(null);
  useEffect(() => {
    api
      .sharedOptionsGroupOf(instance.id)
      .then((g) => setSharedGroup(g?.name ?? null))
      .catch(() => setSharedGroup(null));
  }, [instance.id]);
  // The header's quick update changes the loader outside this form: follow it.
  const savedLoader = JSON.stringify(instance.loader);
  useEffect(() => {
    setDraft((d) => ({ ...d, loader: instance.loader }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [savedLoader]);

  async function save() {
    if (draft.loader.type !== "vanilla" && !draft.loader.version) {
      toast("Wähle zuerst eine Loader-Version.", "error");
      return;
    }
    setSaving(true);
    try {
      await api.saveInstance({ ...draft, name: draft.name.trim() || instance.name, group: draft.group?.trim() || null, hooks: { preLaunch: draft.hooks?.preLaunch.trim() ?? "", postExit: draft.hooks?.postExit.trim() ?? "" }, java: { ...java, path: java.path?.trim() || null, jvmArgs: jvmArgs.split(/\s+/).filter(Boolean) } });
      await refresh();
      toast("Gespeichert", "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    try {
      await api.deleteInstance(instance.id);
      navigate({ page: "instances" });
      await refresh();
      toast(`${instance.name} gelöscht`);
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <div className="h-full overflow-auto px-8 pt-5 pb-8">
      <motion.div variants={stagger} initial="hidden" animate="show" className="grid max-w-2xl gap-5">
        {sharedGroup && (
          <motion.div variants={rise} className="flex items-center gap-3 rounded-[12px] border border-line bg-white/[0.03] px-3.5 py-2.5 text-[12.5px]">
            <Link2 className="size-4 shrink-0 text-on" />
            <span className="flex-1">
              Spieleinstellungen geteilt mit Gruppe <b>„{sharedGroup}“</b>. Grafik, Steuerung und Sound ändern sich für alle Instanzen der Gruppe.
            </span>
            <Button size="sm" onClick={() => navigate({ page: "settings", section: "shared-options" })}>
              Verwalten
            </Button>
          </motion.div>
        )}
        <motion.div variants={rise} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)] gap-4">
          <Field label="Name">
            <input className={inputClass} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          </Field>
          <Field label="Gruppe" hint="Gruppiert die Instanzliste. Leer lassen für keine.">
            <input className={inputClass} list="instance-groups" placeholder="z. B. PvP" value={draft.group ?? ""} onChange={(e) => setDraft({ ...draft, group: e.target.value })} />
            <datalist id="instance-groups">
              {groups.map((g) => (
                <option key={g} value={g} />
              ))}
            </datalist>
          </Field>
        </motion.div>
        <motion.div variants={rise}>
          <LoaderCard instance={instance} loader={draft.loader} disabled={runningGame} onChange={(loader) => setDraft({ ...draft, loader })} />
        </motion.div>
        <motion.div variants={rise} className="grid grid-cols-2 gap-4">
          <Slider label="Min. Arbeitsspeicher" value={java.minMemoryMb} min={512} max={16384} onChange={(v) => setJava({ minMemoryMb: Math.min(v, java.maxMemoryMb) })} />
          <Slider label="Max. Arbeitsspeicher" value={java.maxMemoryMb} min={1024} max={16384} onChange={(v) => setJava({ maxMemoryMb: Math.max(v, java.minMemoryMb) })} hint="4 bis 8 GB reichen für die meisten Modpacks." />
        </motion.div>
        <motion.div variants={rise}>
          <Field label="Java-Pfad" hint="Leer lassen, dann lädt SlopClient automatisch die passende Java-Version.">
            <input className={`${inputClass} font-mono`} placeholder="automatisch" value={java.path ?? ""} onChange={(e) => setJava({ path: e.target.value })} />
          </Field>
        </motion.div>
        <motion.div variants={rise}>
          <Field label="Zusätzliche JVM-Argumente">
            <input className={`${inputClass} font-mono`} placeholder="-XX:+UseZGC" value={jvmArgs} onChange={(e) => setJvmArgs(e.target.value)} />
          </Field>
        </motion.div>
        <motion.div variants={rise} className="glass grid gap-3 rounded-[18px] p-4">
          <div>
            <h3 className="text-[13px] font-semibold">Befehle beim Start</h3>
            <p className="mt-0.5 text-[12.5px] text-muted">
              Laufen über cmd im Spielordner. Verfügbar: <code className="font-mono text-[11.5px] text-text">%INST_NAME% %INST_ID% %INST_DIR% %INST_MC_DIR% %INST_JAVA%</code>
            </p>
          </div>
          <Field label="Vor dem Start" hint="Endet der Befehl mit einem Fehler, startet das Spiel nicht.">
            <input className={`${inputClass} font-mono`} placeholder="z. B. robocopy %INST_MC_DIR%\\saves D:\\Backup /E" value={hooks.preLaunch} onChange={(e) => setDraft({ ...draft, hooks: { ...hooks, preLaunch: e.target.value } })} />
          </Field>
          <Field label="Nach dem Beenden" hint="Das Ergebnis steht danach im Log.">
            <input className={`${inputClass} font-mono`} placeholder="optional" value={hooks.postExit} onChange={(e) => setDraft({ ...draft, hooks: { ...hooks, postExit: e.target.value } })} />
          </Field>
        </motion.div>
        <motion.div variants={rise} className="glass flex items-center gap-4 rounded-[18px] p-4">
          <div className="min-w-0 flex-1">
            <h3 className="text-[13px] font-semibold">SlopClient-Mod</h3>
            <p className="mt-0.5 text-[13px] text-muted">
              {supportsClientMod(draft)
                ? "Titelbildschirm, ClickGUI (Rechts-Shift) und HUD im Spiel. Wird beim Start automatisch installiert, zusammen mit Fabric API."
                : `Läuft mit Fabric oder Quilt auf Minecraft ${CLIENT_MOD_MC.join(", ")}. Diese Instanz startet ohne.`}
            </p>
          </div>
          <Toggle label="SlopClient-Mod" checked={draft.clientMod && supportsClientMod(draft)} onChange={(v) => supportsClientMod(draft) && setDraft({ ...draft, clientMod: v })} />
        </motion.div>
        <motion.div variants={rise}>
          <Button variant="primary" loading={saving} onClick={save}>
            Speichern
          </Button>
        </motion.div>
        <motion.div variants={rise} className="mt-4 rounded-[18px] border border-err/20 bg-err/[0.05] p-5">
          <h3 className="font-semibold">Instanz löschen</h3>
          <p className="mt-1 text-[13px] text-muted">Löscht die Instanz samt Welten, Mods und Screenshots. Das lässt sich nicht rückgängig machen.</p>
          <div className="mt-3 flex gap-2">
            {confirmDelete ? (
              <>
                <Button variant="danger" icon={<Trash2 className="size-4" />} onClick={remove}>
                  Endgültig löschen
                </Button>
                <Button variant="ghost" onClick={() => setConfirmDelete(false)}>
                  Abbrechen
                </Button>
              </>
            ) : (
              <Button variant="danger" onClick={() => setConfirmDelete(true)}>
                Löschen …
              </Button>
            )}
          </div>
        </motion.div>
      </motion.div>
    </div>
  );
}

/** ClickGUI-style value slider with a chroma fill. */
export function Slider({ label, value, min, max, step = 256, onChange, hint, unit = "MB" }: { label: string; value: number; min: number; max: number; step?: number; onChange: (v: number) => void; hint?: string; unit?: string }) {
  const pct = ((value - min) / (max - min)) * 100;
  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <span className="text-xs font-semibold text-muted">{label}</span>
        <span className="font-mono text-xs text-text">
          {value} {unit}
        </span>
      </div>
      <div className="relative h-6">
        <div className="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-white/[0.08]">
          <div className="chroma h-full rounded-full" style={{ width: `${pct}%` }} />
        </div>
        <div className="pointer-events-none absolute top-1/2 size-4 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-[0_2px_10px_color-mix(in_oklab,var(--color-accent)_70%,transparent)]" style={{ left: `${pct}%` }} />
        <input type="range" aria-label={label} min={min} max={max} step={step} value={value} onChange={(e) => onChange(+e.target.value)} className="absolute inset-0 w-full cursor-pointer opacity-0" />
      </div>
      {hint && <p className="mt-1.5 text-xs text-muted">{hint}</p>}
    </div>
  );
}
