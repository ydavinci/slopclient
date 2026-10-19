import { Check, Compass, Download, ExternalLink, Heart, KeyRound, Search } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import { api, openUrl } from "../lib/ipc";
import { compactNumber, loaderName } from "../lib/format";
import { useStore } from "../lib/store";
import { projectKey, type Instance, type Platform, type SearchHit } from "../lib/types";
import { Button, EASE, EmptyState, InstanceIcon, SPRING, Segmented, Spinner, inputClass, rise, stagger } from "../ui/kit";

type ProjectType = "mod" | "resourcepack" | "shader" | "modpack";

const TYPES: { value: ProjectType; label: string }[] = [
  { value: "mod", label: "Mods" },
  { value: "resourcepack", label: "Resourcepacks" },
  { value: "shader", label: "Shader" },
  { value: "modpack", label: "Modpacks" },
];

const SOURCES: { value: Platform; label: string }[] = [
  { value: "modrinth", label: "Modrinth" },
  { value: "curseforge", label: "CurseForge" },
];

const PLATFORM_NAME: Record<Platform, string> = { modrinth: "Modrinth", curseforge: "CurseForge" };

const SORTS = [
  { value: "relevance", label: "Relevanz" },
  { value: "downloads", label: "Downloads" },
  { value: "follows", label: "Beliebt" },
  { value: "updated", label: "Neu" },
];

function modLoaders(instance: Instance | undefined): string[] {
  if (!instance || instance.loader.type === "vanilla") return [];
  return instance.loader.type === "quilt" ? ["quilt", "fabric"] : [instance.loader.type];
}

export function Browse({ target }: { target?: string }) {
  const { overview, runTask, toast, refresh, navigate, select } = useStore();
  const instances = overview?.instances ?? [];
  const [targetId, setTargetId] = useState(target ?? instances[0]?.id ?? "");
  const instance = instances.find((i) => i.id === targetId);
  const [type, setType] = useState<ProjectType>(instance?.loader.type === "vanilla" ? "resourcepack" : "mod");
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [sort, setSort] = useState("relevance");
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [total, setTotal] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [installed, setInstalled] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [source, setSource] = useState<Platform>("modrinth");
  // null while settings load; CurseForge needs a key, Modrinth doesn't.
  const [hasCfKey, setHasCfKey] = useState<boolean | null>(null);
  const needsKey = source === "curseforge" && hasCfKey === false;

  useEffect(() => {
    api
      .settings()
      .then((s) => setHasCfKey(!!s.curseforgeApiKey?.trim()))
      .catch(() => setHasCfKey(false));
  }, []);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), 280);
    return () => clearTimeout(t);
  }, [query]);

  const forInstance = type !== "modpack" && !!instance;
  const args = useMemo(
    () => ({
      source,
      query: debounced,
      projectType: type,
      gameVersion: forInstance ? instance!.mcVersion : null,
      loaders: type === "mod" && forInstance ? modLoaders(instance) : [],
      sort,
      limit: 20,
    }),
    [source, debounced, type, sort, forInstance, instance],
  );

  useEffect(() => {
    let cancelled = false;
    setHits(null);
    if (needsKey) return;
    api
      .search({ ...args, offset: 0 })
      .then((r) => {
        if (cancelled) return;
        setHits(r.hits);
        setTotal(r.total_hits);
      })
      .catch((e) => {
        if (cancelled) return;
        toast(String(e), "error");
        setHits([]);
      });
    return () => {
      cancelled = true;
    };
  }, [args, needsKey, toast]);

  useEffect(() => {
    if (!targetId) return;
    api
      .contentList(targetId)
      .then((list) => setInstalled(new Set(list.flatMap((e) => projectKey(e.source) ?? []))))
      .catch(() => setInstalled(new Set()));
  }, [targetId]);

  async function loadMore() {
    if (!hits) return;
    setLoadingMore(true);
    try {
      const r = await api.search({ ...args, offset: hits.length });
      setHits([...hits, ...r.hits]);
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setLoadingMore(false);
    }
  }

  async function install(hit: SearchHit) {
    const key = `${hit.source}:${hit.project_id}`;
    // CurseForge slugs are only unique per category, so install by numeric id there.
    const ref = hit.source === "curseforge" ? hit.project_id : hit.slug;
    setBusy((b) => new Set(b).add(key));
    try {
      if (type === "modpack") {
        const created = await runTask(`${hit.title} importieren`, (taskId) => api.modpackImport(ref, false, hit.source, null, taskId));
        await refresh();
        select(created.id);
        toast(`Instanz „${created.name}“ angelegt`, "success");
        navigate({ page: "instance", id: created.id });
        return;
      }
      if (!instance) return;
      const report = await runTask(`${hit.title} installieren`, (taskId) => api.contentInstall(instance.id, ref, hit.source, taskId));
      setInstalled((s) => {
        const next = new Set(s);
        if (!report.blocked.some((b) => `curseforge:${b.projectId}` === key)) next.add(key);
        report.installed.forEach((e) => {
          const k = projectKey(e.source);
          if (k) next.add(k);
        });
        return next;
      });
      const deps = report.installed.filter((e) => e.dependency).length;
      if (report.skipped.length) toast(`${hit.title} ist schon installiert`, "success");
      else if (report.installed.length) toast(`${hit.title} installiert${deps ? `, dazu ${deps} ${deps === 1 ? "Abhängigkeit" : "Abhängigkeiten"}` : ""}`, "success");
      if (report.blocked.length) {
        const n = report.blocked.length;
        toast(`${n} ${n === 1 ? "Datei gibt" : "Dateien gibt"} es nur auf curseforge.com. Die Instanz zeigt dir, was noch fehlt.`, "error");
      }
      report.warnings.forEach((w) => toast(w, "error"));
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setBusy((b) => {
        const n = new Set(b);
        n.delete(key);
        return n;
      });
    }
  }

  const blocked = type === "mod" && instance?.loader.type === "vanilla";

  return (
    <div className="flex h-full gap-4 pr-6 pl-8">
      <motion.aside variants={stagger} initial="hidden" animate="show" className="glass mb-6 flex w-60 shrink-0 flex-col gap-5 overflow-auto rounded-[18px] p-3">
        <div>
          <motion.h2 variants={rise} className="px-2 pt-1 pb-2 text-xs font-semibold text-muted">
            Installieren in
          </motion.h2>
          {instances.length === 0 ? (
            <p className="px-2 text-[13px] text-muted">Noch keine Instanz.</p>
          ) : (
            <div className="flex flex-col gap-0.5">
              {instances.map((i) => {
                const active = i.id === targetId;
                return (
                  <motion.button
                    key={i.id}
                    variants={rise}
                    onClick={() => setTargetId(i.id)}
                    className={`relative flex items-center gap-2.5 rounded-xl px-2 py-1.5 text-left transition-colors duration-200 ${active ? "" : "hover:bg-white/[0.04]"}`}
                  >
                    {active && <motion.span layoutId="target-active" transition={SPRING} className="absolute inset-0 rounded-xl bg-white/[0.08] shadow-[inset_0_0_0_1px_rgb(255_255_255/0.07)]" />}
                    <span className="relative">
                      <InstanceIcon id={i.id} name={i.name} icon={i.icon} size={30} />
                    </span>
                    <span className="relative min-w-0">
                      <span className={`block truncate text-[13px] font-semibold ${active ? "text-text" : "text-muted"}`}>{i.name}</span>
                      <span className="block truncate text-[11px] text-muted">
                        {i.mcVersion} · {loaderName(i.loader)}
                      </span>
                    </span>
                  </motion.button>
                );
              })}
            </div>
          )}
        </div>
        <div>
          <motion.h2 variants={rise} className="px-2 pb-2 text-xs font-semibold text-muted">
            Quelle
          </motion.h2>
          <motion.div variants={rise}>
            <Segmented value={source} onChange={setSource} options={SOURCES} />
          </motion.div>
        </div>
        <div>
          <motion.h2 variants={rise} className="px-2 pb-2 text-xs font-semibold text-muted">
            Sortierung
          </motion.h2>
          <motion.div variants={rise}>
            <Segmented value={sort} onChange={setSort} options={SORTS} className="flex-wrap" />
          </motion.div>
        </div>
        <p className="mt-auto px-2 pb-1 text-[11px] leading-relaxed text-muted">Abhängigkeiten werden automatisch mitinstalliert, passend zur Version der Instanz.</p>
      </motion.aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center gap-3">
          <div className="relative flex-1">
            <Search className="absolute top-3 left-3.5 size-4 text-dim" />
            <input autoFocus className={`${inputClass} pl-10`} placeholder={`${TYPES.find((t) => t.value === type)!.label} auf ${PLATFORM_NAME[source]} suchen`} value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <Segmented className="w-[420px]" value={type} options={TYPES} onChange={setType} />
        </div>
        <p className="pt-3 pb-1 text-xs text-muted">
          {needsKey ? "CurseForge braucht einen API-Schlüssel" : hits === null ? "Suche läuft …" : `${compactNumber(total)} Ergebnisse`}
          {forInstance && ` · passend für ${instance!.name} (${instance!.mcVersion})`}
        </p>

        <div className="min-h-0 flex-1 overflow-auto pt-2 pb-6">
          {blocked ? (
            <EmptyState icon={<Compass className="size-5" />} title="Vanilla-Instanz ausgewählt" text="Mods brauchen einen Mod-Loader. Wähle links eine Instanz mit Fabric, Quilt, NeoForge oder Forge, oder stöbere in Resourcepacks und Shadern." />
          ) : needsKey ? (
            <EmptyState
              icon={<KeyRound className="size-5" />}
              title="CurseForge ist noch nicht eingerichtet"
              text="Die CurseForge-API gibt Inhalte nur mit eigenem API-Schlüssel heraus. Trag ihn in den Einstellungen ein, Modrinth funktioniert auch ohne."
              action={
                <div className="flex gap-2">
                  <Button variant="primary" onClick={() => navigate({ page: "settings" })}>
                    Zu den Einstellungen
                  </Button>
                  <Button icon={<ExternalLink className="size-4" />} onClick={() => openUrl("https://console.curseforge.com/")}>
                    CurseForge-Konsole
                  </Button>
                </div>
              }
            />
          ) : hits === null ? (
            <div className="grid place-items-center py-20">
              <Spinner className="size-5" />
            </div>
          ) : hits.length === 0 ? (
            <EmptyState icon={<Search className="size-5" />} title="Nichts gefunden" text="Versuch einen anderen Begriff oder eine andere Kategorie." />
          ) : (
            <>
              <motion.div key={`${source}-${type}-${debounced}-${sort}`} variants={stagger} initial="hidden" animate="show" className="grid grid-cols-1 gap-2.5 xl:grid-cols-2">
                {hits.map((hit) => {
                  const key = `${hit.source}:${hit.project_id}`;
                  const done = installed.has(key) && type !== "modpack";
                  const loading = busy.has(key);
                  return (
                    <motion.div key={key} variants={rise} whileHover={{ y: -2 }} transition={SPRING} className="group glass relative flex gap-3.5 overflow-hidden rounded-[18px] p-3.5 transition-colors duration-200 hover:border-line-2">
                      {hit.icon_url ? <img src={hit.icon_url} alt="" loading="lazy" className="size-14 shrink-0 rounded-xl bg-white/5 object-cover" /> : <InstanceIcon id={hit.slug} name={hit.title} size={56} />}
                      <div className="min-w-0 flex-1">
                        <h3 className="truncate text-[14px] font-bold">
                          {hit.title} <span className="text-xs font-normal text-muted">von {hit.author}</span>
                        </h3>
                        <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-muted">{hit.description}</p>
                        <div className="mt-2 flex items-center gap-3 text-[11px] text-muted">
                          <span className="inline-flex items-center gap-1">
                            <Download className="size-3" />
                            {compactNumber(hit.downloads)}
                          </span>
                          {hit.source === "modrinth" && (
                            <span className="inline-flex items-center gap-1">
                              <Heart className="size-3" />
                              {compactNumber(hit.follows)}
                            </span>
                          )}
                        </div>
                      </div>
                      <AnimatePresence mode="wait" initial={false}>
                        {done ? (
                          <motion.span key="done" initial={{ scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={SPRING} className="inline-flex h-8 items-center gap-1.5 self-start rounded-lg bg-on/12 px-2.5 text-xs font-semibold text-on">
                            <Check className="size-3.5" />
                            Installiert
                          </motion.span>
                        ) : (
                          <motion.div key="btn" exit={{ scale: 0.8, opacity: 0, transition: { duration: 0.15, ease: EASE } }} className="self-start">
                            <Button size="sm" variant="primary" loading={loading} disabled={type !== "modpack" && !instance} onClick={() => install(hit)}>
                              {type === "modpack" ? "Als Instanz" : "Installieren"}
                            </Button>
                          </motion.div>
                        )}
                      </AnimatePresence>
                    </motion.div>
                  );
                })}
              </motion.div>
              {hits.length < total && (
                <div className="mt-5 flex justify-center">
                  <Button loading={loadingMore} onClick={loadMore}>
                    Mehr laden
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
