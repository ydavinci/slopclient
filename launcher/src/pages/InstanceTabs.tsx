import { Archive, Camera, ChevronDown, FolderOpen, Globe2, Package, Plus, Trash2 } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { api } from "../lib/ipc";
import { relativeTime } from "../lib/format";
import { useStore } from "../lib/store";
import type { Datapack, Instance, Screenshot, World } from "../lib/types";
import { Button, Chip, EASE, EmptyState, IconButton, Spinner, Toggle, rise, stagger } from "../ui/kit";

const MODES: Record<string, string> = { survival: "Überleben", creative: "Kreativ", adventure: "Abenteuer", spectator: "Zuschauer" };

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1).replace(".", ",")} MB`;
  return `${(n / 1024 ** 3).toFixed(2).replace(".", ",")} GB`;
}

/**
 * Wraps a list in a drop target: dragging files over it shows a glowing outline
 * and a label, dropping hands the files over.
 */
export function DropZone({ label, onFiles, children, className = "" }: { label: string; onFiles: (files: File[]) => void; children: ReactNode; className?: string }) {
  const [over, setOver] = useState(false);
  const depth = useRef(0);
  const hasFiles = (e: React.DragEvent) => Array.from(e.dataTransfer.types).includes("Files");
  return (
    <div
      className={`relative ${className}`}
      onDragEnter={(e) => {
        if (!hasFiles(e)) return;
        depth.current += 1;
        setOver(true);
      }}
      onDragOver={(e) => hasFiles(e) && e.preventDefault()}
      onDragLeave={() => {
        depth.current = Math.max(0, depth.current - 1);
        if (depth.current === 0) setOver(false);
      }}
      onDrop={(e) => {
        if (!hasFiles(e)) return;
        e.preventDefault();
        depth.current = 0;
        setOver(false);
        const files = Array.from(e.dataTransfer.files);
        if (files.length) onFiles(files);
      }}
    >
      {children}
      <AnimatePresence>
        {over && (
          <motion.div
            initial={{ opacity: 0, scale: 0.985 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.985 }}
            transition={{ duration: 0.2, ease: EASE }}
            className="pointer-events-none absolute inset-0 z-10 grid place-items-center rounded-[18px] border-2 border-dashed border-accent-2/70 bg-accent/[0.14] backdrop-blur-[3px]"
          >
            <div className="flex flex-col items-center gap-2 text-center">
              <motion.span animate={{ y: [0, -5, 0] }} transition={{ duration: 1.4, repeat: Infinity, ease: "easeInOut" }} className="chroma grid size-11 place-items-center rounded-2xl text-white shadow-[0_10px_30px_-8px_rgb(168_85_247/0.8)]">
                <Plus className="size-5" />
              </motion.span>
              <span className="text-[14px] font-semibold">{label}</span>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

/** Hidden file input behind a button. */
export function FilePicker({ accept, onFiles, children }: { accept: string; onFiles: (files: File[]) => void; children: (open: () => void) => ReactNode }) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        ref={input}
        type="file"
        multiple
        accept={accept}
        className="hidden"
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          e.target.value = "";
          if (files.length) onFiles(files);
        }}
      />
      {children(() => input.current?.click())}
    </>
  );
}

// ---------- worlds ----------

export function WorldsTab({ instance }: { instance: Instance }) {
  const { toast } = useStore();
  const [worlds, setWorlds] = useState<World[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const load = useCallback(() => {
    api.worlds(instance.id).then(setWorlds).catch((e) => toast(String(e), "error"));
  }, [instance.id, toast]);
  useEffect(load, [load]);

  async function openSaves() {
    try {
      const dir = await api.instanceDir(instance.id);
      await api.openPath(`${dir}\\saves`);
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <div className="h-full overflow-auto px-8 pt-5 pb-6">
      <div className="mb-3 flex items-center gap-3">
        <p className="mr-auto text-[13px] text-muted">{worlds ? `${worlds.length} ${worlds.length === 1 ? "Welt" : "Welten"}. Klapp eine auf für Datapacks und Backups.` : " "}</p>
        <Button size="sm" icon={<FolderOpen className="size-3.5" />} onClick={openSaves}>
          Ordner
        </Button>
      </div>
      {worlds === null ? (
        <div className="grid place-items-center py-16">
          <Spinner className="size-5" />
        </div>
      ) : worlds.length === 0 ? (
        <EmptyState icon={<Globe2 className="size-5" />} title="Noch keine Welten" text="Starte die Instanz und erstelle eine Welt. Sie erscheint dann hier, mit Datapacks und Backups." />
      ) : (
        <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-2">
          {worlds.map((w) => (
            <WorldRow key={w.id} instance={instance} world={w} expanded={open === w.id} onToggle={() => setOpen(open === w.id ? null : w.id)} onChanged={load} />
          ))}
        </motion.div>
      )}
    </div>
  );
}

function WorldRow({ instance, world, expanded, onToggle, onChanged }: { instance: Instance; world: World; expanded: boolean; onToggle: () => void; onChanged: () => void }) {
  const { toast, overview } = useStore();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [backingUp, setBackingUp] = useState(false);
  const running = overview?.running.includes(instance.id) ?? false;

  async function backup() {
    setBackingUp(true);
    try {
      const file = await api.worldBackup(instance.id, world.id);
      toast(`Backup gespeichert: ${file.split(/[\\/]/).pop()}`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setBackingUp(false);
    }
  }

  async function remove() {
    try {
      await api.worldDelete(instance.id, world.id);
      toast(`${world.name} gelöscht`);
      onChanged();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <motion.div variants={rise} layout="position" className={`glass overflow-hidden rounded-[16px] transition-shadow duration-300 ${expanded ? "shadow-[inset_0_0_0_1px_rgb(192_132_252/0.22)]" : ""}`}>
      <button onClick={onToggle} aria-expanded={expanded} className="flex w-full items-center gap-3.5 px-3.5 py-3 text-left transition-colors duration-200 hover:bg-white/[0.03]">
        {world.icon ? (
          <img src={world.icon} alt="" className="size-12 rounded-[10px] bg-white/5 object-cover [image-rendering:pixelated]" />
        ) : (
          <span className="grid size-12 place-items-center rounded-[10px] bg-white/[0.05] text-muted">
            <Globe2 className="size-5" />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[14px] font-semibold">{world.name}</span>
            {world.hardcore && <Chip tone="warn">Hardcore</Chip>}
            {world.gameMode && <Chip>{MODES[world.gameMode] ?? world.gameMode}</Chip>}
          </div>
          <p className="mt-0.5 truncate text-[12px] text-muted">
            {world.lastPlayed ? `zuletzt ${relativeTime(world.lastPlayed)}` : "nie gespielt"}
            {world.version && <span> · {world.version}</span>}
            <span> · {formatBytes(world.sizeBytes)}</span>
            {world.datapacks > 0 && <span> · {world.datapacks} {world.datapacks === 1 ? "Datapack" : "Datapacks"}</span>}
          </p>
        </div>
        <span className="font-mono text-[11px] text-dim">{world.id !== world.name ? world.id : ""}</span>
        <motion.span animate={{ rotate: expanded ? 180 : 0 }} transition={{ duration: 0.3, ease: EASE }} className="text-muted">
          <ChevronDown className="size-4" />
        </motion.span>
      </button>
      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.35, ease: EASE }}>
            <div className="border-t border-line px-3.5 py-3">
              <Datapacks instance={instance} world={world} onChanged={onChanged} />
              <div className="mt-3 flex items-center gap-2 border-t border-line pt-3">
                <Button size="sm" icon={<Archive className="size-3.5" />} loading={backingUp} onClick={backup}>
                  Backup als ZIP
                </Button>
                <span className="text-[12px] text-muted">landet im Instanzordner unter backups</span>
                <div className="ml-auto flex gap-1.5">
                  {confirmDelete ? (
                    <>
                      <Button size="sm" variant="danger" icon={<Trash2 className="size-3.5" />} disabled={running} onClick={remove}>
                        Endgültig löschen
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(false)}>
                        Abbrechen
                      </Button>
                    </>
                  ) : (
                    <Button size="sm" variant="ghost" disabled={running} onClick={() => setConfirmDelete(true)}>
                      Welt löschen …
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}

function Datapacks({ instance, world, onChanged }: { instance: Instance; world: World; onChanged: () => void }) {
  const { toast } = useStore();
  const [packs, setPacks] = useState<Datapack[] | null>(null);

  const load = useCallback(() => {
    api.datapacks(instance.id, world.id).then(setPacks).catch((e) => toast(String(e), "error"));
  }, [instance.id, world.id, toast]);
  useEffect(load, [load]);

  async function add(files: File[]) {
    let added = 0;
    for (const f of files) {
      try {
        await api.datapackAdd(instance.id, world.id, f);
        added += 1;
      } catch (e) {
        toast(String(e), "error");
      }
    }
    if (added) {
      toast(`${added} ${added === 1 ? "Datapack" : "Datapacks"} hinzugefügt. Wirkt beim nächsten Laden der Welt.`, "success");
      load();
      onChanged();
    }
  }

  async function toggle(p: Datapack, enabled: boolean) {
    setPacks((list) => list?.map((x) => (x.fileName === p.fileName ? { ...x, enabled } : x)) ?? null);
    try {
      await api.datapackSetEnabled(instance.id, world.id, p.fileName, enabled);
    } catch (e) {
      toast(String(e), "error");
      load();
    }
  }

  async function remove(p: Datapack) {
    try {
      await api.datapackRemove(instance.id, world.id, p.fileName);
      load();
      onChanged();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <DropZone label="Datapack in diese Welt legen" onFiles={add}>
      <div className="mb-2 flex items-center gap-2">
        <Package className="size-3.5 text-accent-2" />
        <span className="text-[12.5px] font-semibold">Datapacks</span>
        <FilePicker accept=".zip" onFiles={add}>
          {(pick) => (
            <Button size="sm" variant="ghost" className="ml-auto" icon={<Plus className="size-3.5" />} onClick={pick}>
              ZIP hinzufügen
            </Button>
          )}
        </FilePicker>
      </div>
      {packs === null ? (
        <Spinner />
      ) : packs.length === 0 ? (
        <p className="rounded-xl border border-dashed border-line-2 px-3 py-4 text-center text-[12.5px] text-muted">Keine Datapacks. Zieh eine ZIP-Datei hierher.</p>
      ) : (
        <div className="grid gap-1">
          {packs.map((p) => (
            <div key={p.fileName} className={`group flex items-center gap-3 rounded-xl px-2.5 py-2 transition-colors duration-300 ${p.enabled ? "bg-accent/[0.14]" : "bg-white/[0.02] opacity-60"}`}>
              <div className="min-w-0 flex-1">
                <div className="truncate text-[13px] font-semibold">{p.fileName.replace(/\.zip$/i, "")}</div>
                {p.description && <div className="truncate text-[11.5px] text-muted">{p.description}</div>}
              </div>
              {p.folder && <Chip>Ordner</Chip>}
              <Toggle checked={p.enabled} onChange={(v) => toggle(p, v)} label={`${p.fileName} aktivieren`} />
              <IconButton label={`${p.fileName} entfernen`} className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" onClick={() => remove(p)}>
                <Trash2 className="size-3.5" />
              </IconButton>
            </div>
          ))}
        </div>
      )}
    </DropZone>
  );
}

// ---------- screenshots ----------

export function ScreenshotsTab({ instance }: { instance: Instance }) {
  const { toast } = useStore();
  const [shots, setShots] = useState<Screenshot[] | null>(null);

  const load = useCallback(() => {
    api.screenshots(instance.id).then(setShots).catch((e) => toast(String(e), "error"));
  }, [instance.id, toast]);
  useEffect(load, [load]);

  async function remove(s: Screenshot) {
    try {
      await api.screenshotDelete(instance.id, s.fileName);
      setShots((list) => list?.filter((x) => x.fileName !== s.fileName) ?? null);
      toast("Screenshot gelöscht");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  async function openFolder() {
    try {
      const dir = await api.instanceDir(instance.id);
      await api.openPath(`${dir}\\screenshots`);
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <div className="h-full overflow-auto px-8 pt-5 pb-6">
      <div className="mb-3 flex items-center gap-3">
        <p className="mr-auto text-[13px] text-muted">{shots ? `${shots.length} Screenshots. Klick öffnet das Bild, F2 im Spiel macht neue.` : " "}</p>
        <Button size="sm" icon={<FolderOpen className="size-3.5" />} onClick={openFolder}>
          Ordner
        </Button>
      </div>
      {shots === null ? (
        <div className="grid place-items-center py-16">
          <Spinner className="size-5" />
        </div>
      ) : shots.length === 0 ? (
        <EmptyState icon={<Camera className="size-5" />} title="Noch keine Screenshots" text="Drück im Spiel F2. Deine Screenshots erscheinen dann hier als Galerie." />
      ) : (
        <motion.div variants={stagger} initial="hidden" animate="show" className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
          {shots.map((s) => (
            <Shot key={s.fileName} instanceId={instance.id} shot={s} onDelete={() => remove(s)} />
          ))}
        </motion.div>
      )}
    </div>
  );
}

function Shot({ instanceId, shot, onDelete }: { instanceId: string; shot: Screenshot; onDelete: () => void }) {
  const { toast } = useStore();
  const [url, setUrl] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const el = useRef<HTMLDivElement>(null);

  // Deleting asks for a second click within a few seconds.
  useEffect(() => {
    if (!confirm) return;
    const t = setTimeout(() => setConfirm(false), 3000);
    return () => clearTimeout(t);
  }, [confirm]);

  // Thumbnails load once the tile scrolls into view.
  useEffect(() => {
    const node = el.current;
    if (!node) return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return;
        io.disconnect();
        api.screenshotThumb(instanceId, shot.fileName).then(setUrl).catch(() => setUrl(""));
      },
      { rootMargin: "200px" },
    );
    io.observe(node);
    return () => io.disconnect();
  }, [instanceId, shot.fileName]);

  return (
    <motion.div ref={el} variants={rise} className="group glass relative overflow-hidden rounded-[14px]">
      <button className="block aspect-video w-full overflow-hidden bg-black/40" onClick={() => api.openPath(shot.path).catch((e) => toast(String(e), "error"))} aria-label={`${shot.fileName} öffnen`}>
        {url ? (
          <motion.img initial={{ opacity: 0, scale: 1.04 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.5, ease: EASE }} src={url} alt="" className="size-full object-cover transition-transform duration-500 group-hover:scale-[1.04]" />
        ) : (
          <div className="size-full animate-pulse bg-white/[0.03]" />
        )}
      </button>
      <div className="flex items-center gap-2 px-3 py-2">
        <div className="min-w-0 flex-1">
          <div className="truncate font-mono text-[11.5px]">{shot.fileName.replace(/\.png$/i, "")}</div>
          <div className="text-[11px] text-muted">
            {relativeTime(shot.takenAt)} · {formatBytes(shot.sizeBytes)}
          </div>
        </div>
        {confirm ? (
          <Button size="sm" variant="danger" onClick={onDelete}>
            Löschen
          </Button>
        ) : (
          <IconButton label="Screenshot löschen" className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" onClick={() => setConfirm(true)}>
            <Trash2 className="size-3.5" />
          </IconButton>
        )}
      </div>
    </motion.div>
  );
}
