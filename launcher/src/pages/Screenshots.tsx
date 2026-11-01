import { Camera, RefreshCw, Search } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api, onEvent } from "../lib/ipc";
import { formatBytes } from "../lib/format";
import { useStore } from "../lib/store";
import type { InstanceScreenshot } from "../lib/types";
import { EmptyState, IconButton, InstanceIcon, Segmented, Spinner, inputClass, rise, stagger } from "../ui/kit";
import { ScreenshotTile } from "../ui/ScreenshotTile";
import { ScreenshotViewer } from "../ui/ScreenshotViewer";

type Order = "new" | "old";

const DAY = 86400;

/** "Heute", "Gestern", "Donnerstag, 29. Oktober" (with the year when it is not this one). */
function dayLabel(unix: number): string {
  const d = new Date(unix * 1000);
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime() / 1000;
  const today = start(new Date());
  const day = start(d);
  if (day === today) return "Heute";
  if (day === today - DAY) return "Gestern";
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return d.toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long", ...(sameYear ? {} : { year: "numeric" }) });
}

/** Every instance's screenshots in one gallery, grouped by day, with the viewer on top. */
export function Screenshots() {
  const { overview, toast } = useStore();
  const [shots, setShots] = useState<InstanceScreenshot[] | null>(null);
  const [instance, setInstance] = useState<string | null>(null);
  const [order, setOrder] = useState<Order>("new");
  const [query, setQuery] = useState("");
  const [viewing, setViewing] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    api
      .screenshotsAll()
      .then(setShots)
      .catch((e) => toast(String(e), "error"))
      .finally(() => setLoading(false));
  }, [toast]);
  useEffect(load, [load]);

  // New screenshots show up when a game ends or the launcher comes back to the front.
  useEffect(() => {
    const unlisten = onEvent<{ running: boolean }>("game-state", (s) => !s.running && load());
    const onFocus = () => load();
    window.addEventListener("focus", onFocus);
    return () => {
      unlisten.then((f) => f());
      window.removeEventListener("focus", onFocus);
    };
  }, [load]);

  // Instances that have screenshots, with their count, in the order of the instance list.
  const counts = useMemo(() => {
    const by = new Map<string, number>();
    for (const s of shots ?? []) by.set(s.instanceId, (by.get(s.instanceId) ?? 0) + 1);
    return by;
  }, [shots]);
  const withShots = (overview?.instances ?? []).filter((i) => counts.has(i.id));
  useEffect(() => {
    if (instance && shots && !counts.has(instance)) setInstance(null);
  }, [instance, shots, counts]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = (shots ?? []).filter((s) => (!instance || s.instanceId === instance) && (!q || s.fileName.toLowerCase().includes(q) || s.instanceName.toLowerCase().includes(q)));
    return order === "new" ? list : [...list].reverse();
  }, [shots, instance, query, order]);

  const groups = useMemo(() => {
    const out: { label: string; items: { shot: InstanceScreenshot; index: number }[] }[] = [];
    visible.forEach((shot, index) => {
      const label = dayLabel(shot.takenAt);
      const last = out[out.length - 1];
      if (last?.label === label) last.items.push({ shot, index });
      else out.push({ label, items: [{ shot, index }] });
    });
    return out;
  }, [visible]);

  const total = (shots ?? []).reduce((n, s) => n + s.sizeBytes, 0);
  const drop = (s: { instanceId: string; fileName: string }) => setShots((list) => list?.filter((x) => !(x.instanceId === s.instanceId && x.fileName === s.fileName)) ?? null);

  return (
    <div className="flex h-full flex-col">
      <motion.div variants={stagger} initial="hidden" animate="show" className="flex items-end gap-3 px-8 pt-3 pb-4">
        <motion.div variants={rise} className="mr-auto">
          <h1 className="text-[30px] font-extrabold tracking-[-0.03em]">Screenshots</h1>
          <p className="mt-0.5 text-[13px] text-muted">
            {shots ? `${shots.length} ${shots.length === 1 ? "Bild" : "Bilder"} aus ${counts.size} ${counts.size === 1 ? "Instanz" : "Instanzen"} · ${formatBytes(total)}` : " "}
          </p>
        </motion.div>
        <motion.div variants={rise} className="relative w-60">
          <Search className="absolute top-3 left-3.5 size-4 text-dim" />
          <input className={`${inputClass} pl-10`} placeholder="Name oder Instanz" value={query} onChange={(e) => setQuery(e.target.value)} />
        </motion.div>
        <motion.div variants={rise}>
          <Segmented<Order> value={order} onChange={setOrder} options={[{ value: "new", label: "Neueste" }, { value: "old", label: "Älteste" }]} />
        </motion.div>
        <motion.div variants={rise}>
          <IconButton label="Neu laden" onClick={load} disabled={loading}>
            <RefreshCw className={`size-4 ${loading ? "animate-spin" : ""}`} />
          </IconButton>
        </motion.div>
      </motion.div>

      {withShots.length > 1 && (
        <div className="flex shrink-0 flex-wrap gap-1.5 px-8 pb-3">
          <FilterChip active={instance === null} onClick={() => setInstance(null)} label="Alle" count={shots?.length ?? 0} />
          {withShots.map((i) => (
            <FilterChip key={i.id} active={instance === i.id} onClick={() => setInstance(i.id)} label={i.name} count={counts.get(i.id) ?? 0} icon={<InstanceIcon id={i.id} name={i.name} icon={i.icon} size={18} />} />
          ))}
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-auto px-8 pb-6">
        {shots === null ? (
          <div className="grid place-items-center py-16">
            <Spinner className="size-5" />
          </div>
        ) : shots.length === 0 ? (
          <EmptyState icon={<Camera className="size-5" />} title="Noch keine Screenshots" text="Drück im Spiel F2. Die Bilder aller Instanzen erscheinen dann hier, nach Tagen sortiert." />
        ) : visible.length === 0 ? (
          <EmptyState icon={<Search className="size-5" />} title="Nichts gefunden" text="Kein Screenshot passt zur Suche." />
        ) : (
          groups.map((g) => (
            <section key={g.label} className="mb-6">
              <h2 className="mb-2.5 flex items-baseline gap-2 text-[13px] font-bold">
                {g.label}
                <span className="text-[12px] font-medium text-dim">{g.items.length}</span>
              </h2>
              <motion.div variants={stagger} initial="hidden" animate="show" className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
                {g.items.map(({ shot, index }) => (
                  <ScreenshotTile key={`${shot.instanceId}/${shot.fileName}`} instanceId={shot.instanceId} shot={shot} label={instance ? undefined : shot.instanceName} onOpen={() => setViewing(index)} />
                ))}
              </motion.div>
            </section>
          ))
        )}
      </div>

      <AnimatePresence>
        {viewing !== null && visible.length > 0 && (
          <ScreenshotViewer shots={visible} index={Math.min(viewing, visible.length - 1)} onIndex={setViewing} onClose={() => setViewing(null)} onDeleted={drop} />
        )}
      </AnimatePresence>
    </div>
  );
}

function FilterChip({ active, onClick, label, count, icon }: { active: boolean; onClick: () => void; label: string; count: number; icon?: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`flex items-center gap-1.5 rounded-full py-1 pr-2.5 text-[12.5px] font-semibold transition-colors duration-200 ${icon ? "pl-1" : "pl-3"} ${
        active ? "bg-accent/[0.2] text-text shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--color-accent-2)_30%,transparent)]" : "bg-white/[0.05] text-muted hover:bg-white/[0.08] hover:text-text"
      }`}
    >
      {icon}
      <span className="max-w-40 truncate">{label}</span>
      <span className={`text-[11px] ${active ? "text-accent-2" : "text-dim"}`}>{count}</span>
    </button>
  );
}
