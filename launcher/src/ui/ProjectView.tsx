import DOMPurify from "dompurify";
import { ArrowLeft, Calendar, Download, ExternalLink, Heart, ImageIcon, Package, Scale } from "lucide-react";
import { marked } from "marked";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useState, type MouseEvent } from "react";
import { compactNumber } from "../lib/format";
import { api, openUrl } from "../lib/ipc";
import { useStore } from "../lib/store";
import type { ProjectDetails } from "../lib/types";
import { Button, Chip, EASE, IconButton, InstanceIcon, Segmented, Spinner, inputClass } from "./kit";

const PLATFORM: Record<string, string> = { modrinth: "Modrinth", curseforge: "CurseForge" };

/**
 * A Modrinth or CurseForge project inside the launcher: description, gallery, links
 * and install. Opens as a layer over the current page, so a search behind it stays.
 */
export function ProjectView() {
  const { project, closeProject } = useStore();
  return (
    <AnimatePresence>
      {project && (
        <motion.div
          key={`${project.source}:${project.id}`}
          className="absolute inset-0 z-20 bg-bg/80 backdrop-blur-xl"
          initial={{ opacity: 0, x: 24 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: 16, transition: { duration: 0.18 } }}
          transition={{ duration: 0.38, ease: EASE, repeatDelay: 0.001 }}
        >
          <Details onClose={closeProject} />
        </motion.div>
      )}
    </AnimatePresence>
  );
}

function Details({ onClose }: { onClose: () => void }) {
  const { project, overview, runTask, toast, refresh, select, navigate } = useStore();
  const [details, setDetails] = useState<ProjectDetails | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<"about" | "gallery">("about");
  const [target, setTarget] = useState(project?.target ?? overview?.instances[0]?.id ?? "");
  const [installing, setInstalling] = useState(false);
  const modpack = project?.type === "modpack";

  useEffect(() => {
    if (!project) return;
    setDetails(null);
    setError(null);
    api.projectDetails(project.source, project.id).then(setDetails).catch((e) => setError(String(e)));
  }, [project]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Descriptions are third-party HTML: sanitised, and links open in the browser.
  const html = useMemo(() => {
    if (!details) return "";
    const raw = details.bodyFormat === "markdown" ? (marked.parse(details.body, { async: false }) as string) : details.body;
    return DOMPurify.sanitize(raw, { FORBID_TAGS: ["style", "form", "input"], FORBID_ATTR: ["style"] });
  }, [details]);

  function onBodyClick(e: MouseEvent) {
    const a = (e.target as HTMLElement).closest("a");
    if (!a) return;
    e.preventDefault();
    const href = a.getAttribute("href");
    if (href && /^https?:\/\//.test(href)) openUrl(href);
  }

  async function install() {
    if (!project || !details) return;
    setInstalling(true);
    const ref = project.source === "curseforge" ? details.id : details.slug;
    try {
      if (modpack) {
        const created = await runTask(`${details.title} importieren`, (taskId) => api.modpackImport(ref, false, project.source, null, taskId));
        await refresh();
        select(created.id);
        toast(`Instanz „${created.name}“ angelegt`, "success");
        onClose();
        navigate({ page: "instance", id: created.id });
        return;
      }
      const report = await runTask(`${details.title} installieren`, (taskId) => api.contentInstall(target, ref, project.source, taskId));
      const deps = report.installed.filter((e) => e.dependency).length;
      if (report.skipped.length && !report.installed.length) toast(`${details.title} ist schon installiert`, "success");
      else toast(`${details.title} installiert${deps ? `, dazu ${deps} ${deps === 1 ? "Abhängigkeit" : "Abhängigkeiten"}` : ""}`, "success");
      if (report.blocked.length) toast("Einige Dateien gibt es nur auf curseforge.com. Die Instanz zeigt dir, was noch fehlt.", "error");
      report.warnings.forEach((w) => toast(w, "error"));
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setInstalling(false);
    }
  }

  if (!project) return null;
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 px-8 pt-2">
        <IconButton label="Zurück" onClick={onClose}>
          <ArrowLeft className="size-4" />
        </IconButton>
        <span className="text-[13px] text-muted">{PLATFORM[project.source]}</span>
      </div>

      {error ? (
        <div className="grid flex-1 place-items-center px-8 text-center">
          <div>
            <p className="font-semibold">Projekt konnte nicht geladen werden</p>
            <p className="mt-1 text-[13px] text-muted">{error}</p>
          </div>
        </div>
      ) : !details ? (
        <div className="grid flex-1 place-items-center">
          <Spinner className="size-5" />
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-auto px-8 pt-4 pb-8">
          <div className="flex gap-5">
            {details.iconUrl ? <img src={details.iconUrl} alt="" className="size-20 shrink-0 rounded-[18px] bg-white/5 object-cover" /> : <InstanceIcon id={details.slug} name={details.title} size={80} />}
            <div className="min-w-0 flex-1">
              <h1 className="text-[30px] leading-tight font-extrabold tracking-[-0.03em]">{details.title}</h1>
              {details.author && <p className="text-[13px] text-muted">von {details.author}</p>}
              <p className="mt-2 max-w-2xl text-[13.5px] leading-relaxed text-text">{details.summary}</p>
              <div className="mt-3 flex flex-wrap items-center gap-1.5">
                <Chip>
                  <Download className="size-3" />
                  {compactNumber(details.downloads)} Downloads
                </Chip>
                {details.followers != null && (
                  <Chip>
                    <Heart className="size-3" />
                    {compactNumber(details.followers)}
                  </Chip>
                )}
                {details.license && (
                  <Chip>
                    <Scale className="size-3" />
                    {details.license}
                  </Chip>
                )}
                {details.updated && (
                  <Chip>
                    <Calendar className="size-3" />
                    {new Date(details.updated).toLocaleDateString("de-DE")}
                  </Chip>
                )}
                {details.categories.slice(0, 5).map((c) => (
                  <Chip key={c}>{c}</Chip>
                ))}
              </div>
            </div>
            <div className="flex w-64 shrink-0 flex-col gap-2">
              {!modpack && (
                <select className={inputClass} value={target} onChange={(e) => setTarget(e.target.value)} aria-label="Instanz">
                  {(overview?.instances ?? []).map((i) => (
                    <option key={i.id} value={i.id}>
                      {i.name} ({i.mcVersion})
                    </option>
                  ))}
                </select>
              )}
              <Button variant="primary" icon={<Package className="size-4" />} loading={installing} disabled={!modpack && !target} onClick={install}>
                {modpack ? "Als Instanz anlegen" : "Installieren"}
              </Button>
              <Button icon={<ExternalLink className="size-4" />} onClick={() => openUrl(details.pageUrl)}>
                Auf {PLATFORM[project.source]} öffnen
              </Button>
              {details.links.map(([label, url]) => (
                <Button key={url} variant="ghost" size="sm" icon={<ExternalLink className="size-3.5" />} onClick={() => openUrl(url)}>
                  {label}
                </Button>
              ))}
            </div>
          </div>

          {details.gallery.length > 0 && (
            <Segmented
              className="mt-6 w-72"
              value={tab}
              onChange={setTab}
              options={[
                { value: "about", label: "Beschreibung" },
                { value: "gallery", label: <><ImageIcon className="size-3.5" />Galerie ({details.gallery.length})</> },
              ]}
            />
          )}

          {tab === "gallery" && details.gallery.length > 0 ? (
            <div className="mt-5 grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
              {details.gallery.map((g) => (
                <button key={g.url} className="glass group overflow-hidden rounded-[14px] text-left" onClick={() => openUrl(g.url)}>
                  <img src={g.url} alt={g.title ?? ""} loading="lazy" className="aspect-video w-full object-cover transition-transform duration-500 group-hover:scale-[1.03]" />
                  {g.title && <div className="truncate px-3 py-2 text-[12px] text-muted">{g.title}</div>}
                </button>
              ))}
            </div>
          ) : (
            <article className="md-body glass mt-5 max-w-4xl rounded-[18px] p-6" onClick={onBodyClick} dangerouslySetInnerHTML={{ __html: html || "<p>Keine Beschreibung.</p>" }} />
          )}
        </div>
      )}
    </div>
  );
}
