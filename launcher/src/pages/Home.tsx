import { FolderOpen, Package, Plus, Puzzle } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useState } from "react";
import { api } from "../lib/ipc";
import { loaderLabel, playTime, relativeTime } from "../lib/format";
import { useLaunch, useStore } from "../lib/store";
import type { ContentEntry, Instance } from "../lib/types";
import { Button, Chip, EASE, InstanceIcon, PlayButton, SPRING, rise, stagger } from "../ui/kit";
import { ArrayList } from "../ui/Shell";

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
  const { overview, navigate, openModal, selectedId, select, launching, tasks, toast } = useStore();
  const launch = useLaunch();
  const instances = overview?.instances ?? [];
  const selected = instances.find((i) => i.id === selectedId) ?? instances[0];
  const [mods, setMods] = useState<ContentEntry[]>([]);

  useEffect(() => {
    if (!selected) return;
    let live = true;
    api
      .contentList(selected.id)
      .then((list) => live && setMods(list.filter((e) => e.enabled && e.kind === "mod")))
      .catch(() => live && setMods([]));
    return () => {
      live = false;
    };
  }, [selected?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!overview) return null;

  if (!selected) {
    return (
      <motion.div variants={stagger} initial="hidden" animate="show" className="flex h-full max-w-xl flex-col justify-center pr-8 pb-16 pl-10">
        <motion.h1 variants={rise} className="text-[56px] leading-[0.98] font-extrabold tracking-[-0.035em]">
          Willkommen bei <span className="chroma-text">DaClient</span>.
        </motion.h1>
        <motion.p variants={rise} className="mt-4 text-[15px] leading-relaxed text-muted">
          Leg deine erste Instanz an. Version, Mod-Loader und Java richtet DaClient beim ersten Start selbst ein.
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
  const account = overview.accounts.find((a) => a.uuid === overview.activeAccount) ?? overview.accounts[0];

  function play() {
    if (running) api.stop(selected.id).catch((e) => toast(String(e), "error"));
    else launch(selected.id, selected.name);
  }

  return (
    <div className="relative flex h-full flex-col">
      <div className="absolute top-0 right-6 max-h-[58%] overflow-hidden">
        <ArrayList key={selected.id} title={`Aktive Mods von ${selected.name}`} items={mods.slice(0, 14).map((m) => ({ name: m.name, suffix: m.version?.replace(/^mc[\d.]+-/, "").split("+")[0] ?? undefined }))} />
      </div>

      <motion.div variants={stagger} initial="hidden" animate="show" className="mt-auto max-w-[62%] pr-8 pl-10">
        <motion.div variants={rise}>
          <BigName text={selected.name} />
        </motion.div>
        <motion.div variants={rise} className="mt-4 flex flex-wrap items-center gap-1.5">
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
        </motion.div>
        <motion.div variants={rise} className="mt-7 flex flex-wrap items-center gap-2.5">
          <PlayButton state={running ? "running" : preparing ? "preparing" : "idle"} label={running ? "Beenden" : preparing ? (task?.stage ?? "Startet …") : "Spielen"} onClick={play} progress={progress} />
          <Button icon={<Puzzle className="size-4" />} onClick={() => navigate({ page: "instance", id: selected.id, tab: "mods" })}>
            Mods
          </Button>
          <Button icon={<FolderOpen className="size-4" />} onClick={() => api.instanceDir(selected.id).then(api.openPath).catch((e) => toast(String(e), "error"))}>
            Ordner
          </Button>
          {overview.devBuild && !account && !running && (
            <Button variant="ghost" onClick={() => launch(selected.id, selected.name, "DevPlayer")}>
              Ohne Account testen (Dev)
            </Button>
          )}
        </motion.div>
      </motion.div>

      <InstanceStrip instances={instances} selectedId={selected.id} running={overview.running} onSelect={select} onNew={() => openModal({ kind: "new-instance" })} />
    </div>
  );
}

function InstanceStrip({ instances, selectedId, running, onSelect, onNew }: { instances: Instance[]; selectedId: string; running: string[]; onSelect: (id: string) => void; onNew: () => void }) {
  return (
    <motion.div
      initial="hidden"
      animate="show"
      variants={{ show: { transition: { staggerChildren: 0.05, delayChildren: 0.2 } } }}
      className="mt-9 mb-5 flex gap-2.5 overflow-x-auto pr-16 pb-1 pl-10 [scrollbar-width:none] [mask-image:linear-gradient(90deg,#000_88%,transparent)] [&::-webkit-scrollbar]:hidden"
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
  );
}
