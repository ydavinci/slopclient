import { ChevronDown, ChevronRight, LayoutGrid, Package, Play, Plus, Search, Square } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useMemo, useState } from "react";
import type { Instance } from "../lib/types";
import { api } from "../lib/ipc";
import { loaderName, playTime, relativeTime } from "../lib/format";
import { useLaunch, useStore } from "../lib/store";
import { Button, Chip, EASE, EmptyState, InstanceIcon, inputClass, rise, stagger } from "../ui/kit";

const COLLAPSED_KEY = "slopclient.collapsedGroups";

function loadCollapsed(): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(COLLAPSED_KEY) ?? "[]"));
  } catch {
    return new Set();
  }
}

const COLS = "grid-cols-[minmax(0,1.6fr)_90px_minmax(0,1fr)_110px_120px_88px]";

/** Instances as a dense ClickGUI-style list, not a card grid. */
export function Instances() {
  const { overview, navigate, openModal, selectedId, select, launching, toast } = useStore();
  const launch = useLaunch();
  const [query, setQuery] = useState("");
  const instances = (overview?.instances ?? []).filter((i) => i.name.toLowerCase().includes(query.toLowerCase()));
  // Same fallback as the start page: without a stored choice the first instance is the selected one.
  const activeId = selectedId ?? overview?.instances[0]?.id;
  const [collapsed, setCollapsed] = useState(loadCollapsed);

  // Named groups alphabetically, instances without a group last.
  const sections = useMemo(() => {
    const by = new Map<string, Instance[]>();
    for (const i of instances) {
      const g = i.group?.trim() || "";
      by.set(g, [...(by.get(g) ?? []), i]);
    }
    return [...by.entries()].sort(([a], [b]) => (a === "" ? 1 : b === "" ? -1 : a.localeCompare(b, "de")));
  }, [instances]);
  const grouped = sections.some(([g]) => g !== "");

  function toggleGroup(g: string) {
    const next = new Set(collapsed);
    if (next.has(g)) next.delete(g);
    else next.add(g);
    setCollapsed(next);
    try {
      localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...next]));
    } catch {
      // Remembering is a convenience only.
    }
  }

  const row = (i: Instance) => {
    const running = overview?.running.includes(i.id) ?? false;
    const selected = i.id === activeId;
    return (
      <motion.div
        key={i.id}
        variants={rise}
        role="button"
        tabIndex={0}
        aria-label={`${i.name} öffnen`}
        onClick={() => {
          select(i.id);
          navigate({ page: "instance", id: i.id });
        }}
        onKeyDown={(e) => e.key === "Enter" && navigate({ page: "instance", id: i.id })}
        className={`group grid ${COLS} cursor-pointer items-center gap-4 rounded-xl px-2.5 py-2 transition-colors duration-200 ${
          selected ? "bg-accent/[0.16] shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--color-accent-2)_18%,transparent)]" : "hover:bg-white/[0.04]"
        }`}
      >
        <div className="flex min-w-0 items-center gap-3">
          <InstanceIcon id={i.id} name={i.name} icon={i.icon} size={36} />
          <span className={`truncate text-[14px] font-semibold ${selected ? "chroma-text" : ""}`}>{i.name}</span>
          {running && <Chip tone="accent">läuft</Chip>}
          {i.modpack && <Chip>Modpack</Chip>}
        </div>
        <span className="font-mono text-[12.5px] text-text">{i.mcVersion}</span>
        <span className="truncate text-[13px] text-muted">
          {loaderName(i.loader)}
          {i.loader.type !== "vanilla" && <span className="ml-1.5 font-mono text-[11.5px]">{i.loader.version}</span>}
        </span>
        <span className="font-mono text-[12.5px] text-muted">{i.lastPlayed ? playTime(i.playTimeSecs) : "–"}</span>
        <span className="text-[12.5px] text-muted">{i.lastPlayed ? relativeTime(i.lastPlayed) : "nie"}</span>
        <div className="flex items-center justify-end gap-1">
          <button
            aria-label={running ? `${i.name} beenden` : `${i.name} starten`}
            disabled={launching.has(i.id)}
            onClick={(e) => {
              e.stopPropagation();
              if (running) api.stop(i.id).catch((err) => toast(String(err), "error"));
              else launch(i.id, i.name);
            }}
            className={`grid size-8 place-items-center rounded-lg transition duration-200 disabled:opacity-50 ${
              running ? "text-err hover:bg-err/15" : "text-muted group-hover:chroma group-hover:text-white hover:brightness-110"
            }`}
          >
            {running ? <Square className="size-3.5 fill-current" /> : <Play className="size-3.5 fill-current" />}
          </button>
          <ChevronRight className="size-4 text-dim transition-transform duration-200 group-hover:translate-x-0.5" />
        </div>
      </motion.div>
    );
  };

  return (
    <div className="flex h-full flex-col">
      <motion.div variants={stagger} initial="hidden" animate="show" className="flex items-end gap-3 px-8 pt-3 pb-5">
        <motion.div variants={rise} className="mr-auto">
          <h1 className="text-[30px] font-extrabold tracking-[-0.03em]">Instanzen</h1>
          <p className="mt-0.5 text-[13px] text-muted">{overview?.instances.length ?? 0} Installationen, jede mit eigenen Mods und Welten.</p>
        </motion.div>
        <motion.div variants={rise} className="relative w-64">
          <Search className="absolute top-3 left-3.5 size-4 text-dim" />
          <input className={`${inputClass} pl-10`} placeholder="Instanzen durchsuchen" value={query} onChange={(e) => setQuery(e.target.value)} />
        </motion.div>
        <motion.div variants={rise}>
          <Button icon={<Package className="size-4" />} onClick={() => openModal({ kind: "import" })}>
            Modpack importieren
          </Button>
        </motion.div>
        <motion.div variants={rise}>
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => openModal({ kind: "new-instance" })}>
            Neue Instanz
          </Button>
        </motion.div>
      </motion.div>

      <div className="min-h-0 flex-1 px-8 pb-6">
        {instances.length === 0 ? (
          <EmptyState
            icon={<LayoutGrid className="size-5" />}
            title={query ? "Keine Treffer" : "Noch keine Instanzen"}
            text={query ? `Keine Instanz heißt so wie „${query}“.` : "Leg eine Instanz an oder importiere ein Modpack von Modrinth."}
          />
        ) : (
          <div className="glass flex max-h-full flex-col overflow-hidden rounded-[18px]">
            <div className={`grid ${COLS} gap-4 border-b border-line px-4 py-2.5 text-[11px] font-semibold text-muted`}>
              <span className="pl-12">Name</span>
              <span>Version</span>
              <span>Loader</span>
              <span>Spielzeit</span>
              <span>Zuletzt</span>
              <span />
            </div>
            <motion.div variants={stagger} initial="hidden" animate="show" className="min-h-0 flex-1 overflow-auto p-1.5">
              {!grouped
                ? instances.map(row)
                : sections.map(([g, list]) => {
                    const shut = collapsed.has(g);
                    return (
                      <div key={g || "-"} className="mb-1">
                        <button
                          onClick={() => toggleGroup(g)}
                          aria-expanded={!shut}
                          className="flex w-full items-center gap-2 rounded-lg px-2.5 pt-2.5 pb-1.5 text-left text-[11.5px] font-semibold tracking-[0.04em] text-muted uppercase transition-colors hover:text-text"
                        >
                          <motion.span animate={{ rotate: shut ? -90 : 0 }} transition={{ duration: 0.25, ease: EASE }}>
                            <ChevronDown className="size-3.5" />
                          </motion.span>
                          {g || "Ohne Gruppe"}
                          <span className="font-mono text-[11px] font-normal text-dim normal-case">{list.length}</span>
                          <span className="ml-2 h-px flex-1 bg-line" />
                        </button>
                        <AnimatePresence initial={false}>
                          {!shut && (
                            <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.3, ease: EASE }} className="overflow-hidden">
                              {list.map(row)}
                            </motion.div>
                          )}
                        </AnimatePresence>
                      </div>
                    );
                  })}
            </motion.div>
          </div>
        )}
      </div>
    </div>
  );
}
