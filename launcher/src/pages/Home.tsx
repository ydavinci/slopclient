import { Check, ChevronLeft, ChevronRight, FolderOpen, LayoutTemplate, LogIn, Package, Plus, Puzzle } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { api } from "../lib/ipc";
import { loaderLabel, playTime, relativeTime } from "../lib/format";
import { useLaunch, useStore } from "../lib/store";
import type { Instance } from "../lib/types";
import { Button, Chip, EASE, InstanceIcon, PlayButton, SPRING, rise, stagger } from "../ui/kit";
import { FreeLayout, presetLayout } from "../ui/FreeLayout";

/** The instance name, revealed letter by letter whenever the selection changes. */
function BigName({ text }: { text: string }) {
  return (
    <h1 aria-label={text} className="text-[clamp(44px,6.2vw,76px)] leading-[0.95] font-extrabold tracking-[-0.035em] text-balance">
      <AnimatePresence mode="wait">
        <motion.span key={text} className="inline-block" initial="hidden" animate="show" exit="exit" variants={{ show: { transition: { staggerChildren: 0.022 } }, exit: { transition: { staggerChildren: 0.01 } } }}>
          {[...text].map((ch, i) => (
            <motion.span
              key={i}
              aria-hidden
              className="inline-block whitespace-pre"
              variants={{
                hidden: { opacity: 0, y: "0.4em", filter: "blur(8px)" },
                show: { opacity: 1, y: 0, filter: "blur(0px)", transition: { duration: 0.55, ease: EASE } },
                exit: { opacity: 0, y: "-0.25em", filter: "blur(6px)", transition: { duration: 0.18 } },
              }}
            >
              {ch}
            </motion.span>
          ))}
        </motion.span>
      </AnimatePresence>
    </h1>
  );
}

export function Home() {
  const { overview, navigate, openModal, selectedId, select, launching, tasks, toast, centered, setCentered, homeLayout, setHomeLayout, arranging, setArranging } = useStore();
  const launch = useLaunch();
  const instances = overview?.instances ?? [];
  const selected = instances.find((i) => i.id === selectedId) ?? instances[0];

  if (!overview) return null;

  if (!selected) {
    return (
      <motion.div variants={stagger} initial="hidden" animate="show" className="flex h-full max-w-xl flex-col justify-center pr-8 pb-16 pl-10">
        <motion.h1 variants={rise} className="text-[56px] leading-[0.98] font-extrabold tracking-[-0.035em]">
          Willkommen bei <span className="chroma-text">SlopClient</span>.
        </motion.h1>
        <motion.p variants={rise} className="mt-4 text-[15px] leading-relaxed text-muted">
          Leg deine erste Instanz an. Version, Mod-Loader und Java richtet SlopClient beim ersten Start selbst ein.
        </motion.p>
        <motion.div variants={rise} className="mt-7 flex gap-2.5">
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => openModal({ kind: "new-instance" })}>
            Neue Instanz
          </Button>
          <Button icon={<Package className="size-4" />} onClick={() => openModal({ kind: "import" })}>
            Modpack importieren
          </Button>
        </motion.div>
      </motion.div>
    );
  }

  const running = overview.running.includes(selected.id);
  const preparing = launching.has(selected.id);
  const task = tasks.find((t) => !t.done && t.label.startsWith(selected.name));
  const progress = task && task.total > 0 ? task.finished / task.total : null;

  function play() {
    if (running) api.stop(selected.id).catch((e) => toast(String(e), "error"));
    else launch(selected.id, selected.name);
  }

  const hero = (
    <div className={centered && !homeLayout ? "text-center" : ""}>
      <BigName text={selected.name} />
      <div className={`mt-4 flex flex-wrap items-center gap-1.5 ${centered && !homeLayout ? "justify-center" : ""}`}>
        <Chip tone={running ? "accent" : "on"}>
          <span className={`size-1.5 rounded-full ${running ? "bg-accent-2" : "bg-on"} ${running ? "animate-pulse" : ""}`} />
          {running ? "Läuft" : "Bereit"}
        </Chip>
        <Chip>Minecraft {selected.mcVersion}</Chip>
        <Chip>{loaderLabel(selected.loader)}</Chip>
        {selected.modpack && (
          <Chip>
            <Package className="size-3" />
            {selected.modpack.name}
          </Chip>
        )}
        <span className="ml-1.5 text-[13px] text-muted">
          {selected.lastPlayed ? `Zuletzt ${relativeTime(selected.lastPlayed)}. ${playTime(selected.playTimeSecs)} gespielt.` : "Noch nicht gespielt. Fehlende Dateien lädt der erste Start."}
        </span>
      </div>
    </div>
  );
  const actions = (
    <div className={`flex flex-wrap items-center gap-2.5 ${centered && !homeLayout ? "justify-center" : ""}`}>
      <PlayButton state={running ? "running" : preparing ? "preparing" : "idle"} label={running ? "Beenden" : preparing ? (task?.stage ?? "Startet …") : "Spielen"} onClick={play} progress={progress} />
      {overview.accounts.length === 0 && (
        <Button variant="primary" icon={<LogIn className="size-4" />} onClick={() => openModal({ kind: "login" })}>
          Mit Microsoft anmelden
        </Button>
      )}
      <Button icon={<Puzzle className="size-4" />} onClick={() => navigate({ page: "instance", id: selected.id, tab: "mods" })}>
        Mods
      </Button>
      <Button icon={<FolderOpen className="size-4" />} onClick={() => api.instanceDir(selected.id).then(api.openPath).catch((e) => toast(String(e), "error"))}>
        Ordner
      </Button>
    </div>
  );
  const strip = <InstanceStrip free={!!homeLayout || arranging} centered={centered} instances={instances} selectedId={selected.id} running={overview.running} onSelect={select} onNew={() => openModal({ kind: "new-instance" })} />;

  return (
    <div className="relative flex h-full flex-col">
      {homeLayout || arranging ? (
        <FreeLayout
          layout={homeLayout ?? presetLayout(centered)}
          editing={arranging}
          onChange={setHomeLayout}
          blocks={{
            hero: { label: "Name und Infos", node: hero },
            actions: { label: "Buttons", node: actions },
            strip: { label: "Instanzen", node: strip },
          }}
        />
      ) : (
        <>
          <motion.div variants={stagger} initial="hidden" animate="show" className={`mt-auto ${centered ? "mx-auto max-w-[72%] px-8" : "max-w-[62%] pr-8 pl-10"}`}>
            <motion.div variants={rise}>{hero}</motion.div>
            <motion.div variants={rise} className="mt-7">
              {actions}
            </motion.div>
          </motion.div>
          {strip}
        </>
      )}

      {/* Arrange: drag the blocks, or go back to a preset. */}
      {arranging ? (
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3, ease: EASE }} className="glass absolute top-2 left-1/2 z-10 flex -translate-x-1/2 items-center gap-2 rounded-[14px] py-1.5 pr-1.5 pl-4">
          <span className="mr-2 text-[13px] text-muted">Blöcke an die gewünschte Stelle ziehen</span>
          <Button size="sm" variant="ghost" onClick={() => setCentered(false)}>
            Links
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setCentered(true)}>
            Mittig
          </Button>
          <Button size="sm" variant="primary" icon={<Check className="size-3.5" />} onClick={() => setArranging(false)}>
            Fertig
          </Button>
        </motion.div>
      ) : (
        <button
          aria-label="Startseite anordnen"
          title="Startseite anordnen"
          onClick={() => setArranging(true)}
          className="absolute top-2 right-6 grid size-9 place-items-center rounded-xl text-muted transition hover:bg-white/[0.07] hover:text-text"
        >
          <LayoutTemplate className="size-4" />
        </button>
      )}
    </div>
  );
}

/**
 * The instance cards in one row. With more cards than fit, the mouse wheel scrolls it
 * sideways and arrows appear at the ends.
 */
function InstanceStrip({ free, centered, instances, selectedId, running, onSelect, onNew }: { free: boolean; centered: boolean; instances: Instance[]; selectedId: string; running: string[]; onSelect: (id: string) => void; onNew: () => void }) {
  const row = useRef<HTMLDivElement>(null);
  const [ends, setEnds] = useState({ left: false, right: false });

  useEffect(() => {
    const el = row.current;
    if (!el) return;
    const update = () => setEnds({ left: el.scrollLeft > 4, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4 });
    // A vertical wheel scrolls the row; React's onWheel is passive and could not stop the page.
    const wheel = (e: WheelEvent) => {
      if (el.scrollWidth <= el.clientWidth || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
      e.preventDefault();
      el.scrollBy({ left: e.deltaY, behavior: "auto" });
    };
    update();
    el.addEventListener("scroll", update, { passive: true });
    el.addEventListener("wheel", wheel, { passive: false });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => {
      el.removeEventListener("scroll", update);
      el.removeEventListener("wheel", wheel);
      ro.disconnect();
    };
  }, [instances.length]);

  const page = (dir: number) => row.current?.scrollBy({ left: dir * row.current.clientWidth * 0.8, behavior: "smooth" });

  return (
    <div className={`relative ${free ? "max-w-full" : ""}`}>
    <motion.div
      ref={row}
      initial="hidden"
      animate="show"
      variants={{ show: { transition: { staggerChildren: 0.05, delayChildren: 0.2 } } }}
      className={`flex gap-2.5 overflow-x-auto pt-4 pb-3 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden ${free ? "max-w-full" : centered ? "mx-auto mt-5 mb-3 max-w-full px-10" : "mt-5 mb-3 pr-16 pl-10"}`}
    >
      {instances.map((inst) => {
        const active = inst.id === selectedId;
        return (
          <motion.button
            key={inst.id}
            variants={rise}
            whileHover={{ y: -3 }}
            transition={SPRING}
            onClick={() => onSelect(inst.id)}
            aria-pressed={active}
            className={`group relative flex w-[220px] shrink-0 items-center gap-3 rounded-2xl p-3 text-left transition-colors duration-200 ${active ? "chroma-ring" : "glass hover:border-line-2"}`}
          >
            <InstanceIcon id={inst.id} name={inst.name} icon={inst.icon} size={40} />
            <span className="min-w-0">
              <span className="block truncate text-[13px] font-semibold">{inst.name}</span>
              <span className="block truncate text-xs text-muted">
                {inst.mcVersion} · {loaderLabel(inst.loader).split(" ")[0]}
              </span>
            </span>
            {running.includes(inst.id) && <span className="absolute top-3 right-3 size-2 animate-pulse rounded-full bg-accent-2" />}
          </motion.button>
        );
      })}
      <motion.button
        variants={rise}
        whileHover={{ y: -3 }}
        transition={SPRING}
        onClick={onNew}
        className="flex w-[200px] shrink-0 items-center gap-3 rounded-2xl border border-dashed border-line-2 p-3 text-left text-muted transition-colors duration-200 hover:border-accent-2/60 hover:text-text"
      >
        <span className="grid size-10 place-items-center rounded-xl bg-white/[0.06]">
          <Plus className="size-5" />
        </span>
        <span className="text-[13px] font-semibold">Neue Instanz</span>
      </motion.button>
    </motion.div>
      {(["left", "right"] as const).map((side) =>
        ends[side] ? (
          <button
            key={side}
            aria-label={side === "left" ? "Nach links scrollen" : "Nach rechts scrollen"}
            onClick={() => page(side === "left" ? -1 : 1)}
            className={`absolute top-4 bottom-3 z-10 grid w-14 place-items-center text-muted transition-colors hover:text-text ${side === "left" ? "left-0 bg-gradient-to-r" : "right-0 bg-gradient-to-l"} from-bg via-bg/70 to-transparent`}
          >
            {side === "left" ? <ChevronLeft className="size-5" /> : <ChevronRight className="size-5" />}
          </button>
        ) : null,
      )}
    </div>
  );
}
