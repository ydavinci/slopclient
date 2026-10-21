import { Check, Copy, CopyPlus, ExternalLink, FileText, FileUp, Link2, PowerOff, Share2, Terminal, Upload } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, isTauri, openUrl, pickExportPath, pickFolder, pickModpackFile } from "../lib/ipc";
import { loaderLabel } from "../lib/format";
import { useStore } from "../lib/store";
import type { CopyPart, CrashReport, DeviceCode, InstallReport, ForeignInstance, LoaderKind, LoaderVersion, ManifestEntry, Platform } from "../lib/types";
import { IconEditor } from "./IconEditor";
import { Button, Field, Modal, Segmented, Spinner, Toggle, inputClass, rise, stagger } from "./kit";

export function Modals() {
  const { modal, closeModal } = useStore();
  return (
    <>
      <Modal open={modal?.kind === "new-instance"} title="Neue Instanz" onClose={closeModal}>
        <NewInstance onClose={closeModal} />
      </Modal>
      <Modal open={modal?.kind === "import"} title="Modpack importieren" onClose={closeModal} width={480}>
        <Import onClose={closeModal} />
      </Modal>
      <Modal open={modal?.kind === "login"} title="Mit Microsoft anmelden" onClose={closeModal} width={460}>
        <Login onClose={closeModal} />
      </Modal>
      <Modal open={modal?.kind === "export"} title="Als Modrinth-Modpack exportieren" onClose={closeModal} width={460}>
        {modal?.kind === "export" && <Export instanceId={modal.instanceId} onClose={closeModal} />}
      </Modal>
      <Modal open={modal?.kind === "icon"} title="Instanz-Icon" onClose={closeModal} width={560}>
        {modal?.kind === "icon" && <IconEditor instanceId={modal.instanceId} onClose={closeModal} />}
      </Modal>
      <Modal open={modal?.kind === "duplicate"} title="Instanz kopieren" onClose={closeModal} width={440}>
        {modal?.kind === "duplicate" && <Duplicate instanceId={modal.instanceId} onClose={closeModal} />}
      </Modal>
      <Modal open={modal?.kind === "crash"} title={modal?.kind === "crash" ? modal.report.title : ""} onClose={closeModal} width={560}>
        {modal?.kind === "crash" && <Crash instanceId={modal.instanceId} report={modal.report} onClose={closeModal} />}
      </Modal>
    </>
  );
}

/**
 * Shares a log on mclo.gs. The first click uploads (tokens and the Windows user
 * name are removed first) and copies the link; afterwards the button opens it.
 */
export function ShareLogButton({ getText, size, variant }: { getText: () => Promise<string> | string; size?: "sm"; variant?: "ghost" }) {
  const { toast } = useStore();
  const [busy, setBusy] = useState(false);
  const [link, setLink] = useState<string | null>(null);

  async function share() {
    if (link) return openUrl(link);
    setBusy(true);
    try {
      const url = await api.uploadLog(await getText());
      setLink(url);
      await navigator.clipboard.writeText(url).catch(() => {});
      toast(`Link kopiert: ${url}`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Button size={size} variant={variant} loading={busy} icon={link ? <Link2 className="size-3.5" /> : <Share2 className="size-3.5" />} onClick={share} title="Lädt das Log öffentlich auf mclo.gs hoch, ohne Zugangsdaten und Benutzername">
      {link ? link.replace(/^https?:\/\//, "") : "Auf mclo.gs teilen"}
    </Button>
  );
}

function Duplicate({ instanceId, onClose }: { instanceId: string; onClose: () => void }) {
  const { overview, refresh, toast, navigate, select } = useStore();
  const source = overview?.instances.find((i) => i.id === instanceId);
  const [name, setName] = useState(source ? `${source.name} (Kopie)` : "");
  const [busy, setBusy] = useState(false);
  if (!source) return null;

  async function copy() {
    setBusy(true);
    try {
      const created = await api.duplicateInstance(instanceId, name.trim() || `${source!.name} (Kopie)`);
      await refresh();
      select(created.id);
      navigate({ page: "instance", id: created.id });
      toast(`${created.name} angelegt`, "success");
      onClose();
    } catch (e) {
      toast(String(e), "error");
      setBusy(false);
    }
  }

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-4">
      <motion.p variants={rise} className="text-[13.5px] leading-relaxed text-muted">
        Kopiert {source.name} mit Mods, Welten, Screenshots und Einstellungen. Die Spielzeit der Kopie beginnt bei null.
      </motion.p>
      <motion.div variants={rise}>
        <Field label="Name der Kopie">
          <input autoFocus className={inputClass} value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && !busy && copy()} />
        </Field>
      </motion.div>
      <motion.div variants={rise} className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button variant="primary" icon={<CopyPlus className="size-4" />} loading={busy} onClick={copy}>
          Kopieren
        </Button>
      </motion.div>
    </motion.div>
  );
}

/** Toast text after installing the performance preset. */
export function presetSummary(r: InstallReport): string {
  const added = r.installed.filter((e) => !e.dependency).length;
  const missing = r.warnings.length;
  if (added === 0 && missing === 0) return "Performance-Paket ist schon installiert";
  return `Performance-Paket: ${added} ${added === 1 ? "Mod" : "Mods"} installiert${missing ? `, ${missing} für diese Version nicht verfügbar` : ""}`;
}

/** What went wrong after a crash, with the fixes SlopClient can apply directly. */
function Crash({ instanceId, report, onClose }: { instanceId: string; report: CrashReport; onClose: () => void }) {
  const { navigate, toast, logs } = useStore();
  const [disabled, setDisabled] = useState(false);
  const culprit = report.culprit;
  const culpritName = culprit?.name ?? culprit?.modId;

  async function disableCulprit() {
    if (!culprit?.fileName) return;
    try {
      await api.contentSetEnabled(instanceId, culprit.fileName, false);
      setDisabled(true);
      toast(`${culpritName} deaktiviert. Starte die Instanz erneut.`, "success");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-4">
      <motion.p variants={rise} className="text-[13.5px] leading-relaxed text-muted">
        {report.summary}
        {report.exitCode != null && <span className="ml-1 font-mono text-[12.5px] text-dim">(Code {report.exitCode})</span>}
      </motion.p>
      {report.details.length > 0 && (
        <motion.pre variants={rise} className="max-h-36 overflow-auto rounded-[10px] border border-line bg-black/40 p-3 font-mono text-[12.5px] leading-relaxed whitespace-pre-wrap text-text select-text">
          {report.details.join("\n")}
        </motion.pre>
      )}
      {report.suggestions.length > 0 && (
        <motion.ul variants={rise} className="grid gap-1.5 text-[13.5px]">
          {report.suggestions.map((s) => (
            <li key={s} className="flex gap-2.5">
              <span className="chroma mt-[7px] size-1.5 shrink-0 rounded-full" />
              {s}
            </li>
          ))}
        </motion.ul>
      )}
      <motion.div variants={rise} className="flex flex-wrap justify-end gap-2 pt-1">
        {report.reportFile && (
          <Button icon={<FileText className="size-4" />} onClick={() => api.openPath(report.reportFile!)}>
            Crash-Report öffnen
          </Button>
        )}
        <ShareLogButton getText={() => (report.reportFile ? api.readLogFile(report.reportFile) : (logs[instanceId] ?? []).map((l) => l.text).join("\n"))} />
        <Button
          icon={<Terminal className="size-4" />}
          onClick={() => {
            onClose();
            navigate({ page: "instance", id: instanceId, tab: "log" });
          }}
        >
          Log ansehen
        </Button>
        {culprit?.fileName && (
          <Button variant="primary" icon={<PowerOff className="size-4" />} disabled={disabled} onClick={disableCulprit}>
            {disabled ? "Deaktiviert" : `${culpritName} deaktivieren`}
          </Button>
        )}
      </motion.div>
    </motion.div>
  );
}

/** Parts a new instance can take over, with label and hint. */
const COPY_PARTS: [CopyPart, string, string][] = [
  ["options", "Spieloptionen", "Tastenbelegung, FOV, Grafik und Sound (options.txt)"],
  ["servers", "Serverliste", "Gespeicherte Mehrspieler-Server"],
  ["slopclient", "SlopClient", "Module, HUD-Layout und Profile"],
  ["modConfigs", "Mod-Einstellungen", "Der config-Ordner der Mods"],
  ["resourcePacks", "Resourcepacks", "Alle Resourcepacks"],
  ["shaderPacks", "Shader", "Alle Shaderpacks"],
];

type LoaderChoice = "vanilla" | LoaderKind;
const LOADERS: { value: LoaderChoice; label: string }[] = [
  { value: "vanilla", label: "Vanilla" },
  { value: "fabric", label: "Fabric" },
  { value: "quilt", label: "Quilt" },
  { value: "neoforge", label: "NeoForge" },
  { value: "forge", label: "Forge" },
];

function NewInstance({ onClose }: { onClose: () => void }) {
  const { refresh, toast, navigate, select, runTask, overview } = useStore();
  const [versions, setVersions] = useState<ManifestEntry[] | null>(null);
  const [snapshots, setSnapshots] = useState(false);
  const [mc, setMc] = useState("");
  const [loader, setLoader] = useState<LoaderChoice>("fabric");
  const [loaderVersions, setLoaderVersions] = useState<LoaderVersion[] | null>(null);
  const [loaderVersion, setLoaderVersion] = useState("");
  const [name, setName] = useState("");
  const [preset, setPreset] = useState(true);
  const [copyFrom, setCopyFrom] = useState("");
  const [copyParts, setCopyParts] = useState<Set<CopyPart>>(new Set(["options", "servers", "slopclient"]));
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    api
      .versions()
      .then((v) => {
        setVersions(v.versions);
        setMc(v.latestRelease);
      })
      .catch((e) => toast(String(e), "error"));
  }, [toast]);

  useEffect(() => {
    setLoaderVersions(null);
    setLoaderVersion("");
    if (loader === "vanilla" || !mc) return;
    let cancelled = false;
    api
      .loaderVersions(loader, mc)
      .then((list) => {
        if (cancelled) return;
        setLoaderVersions(list);
        setLoaderVersion((list.find((v) => v.stable) ?? list[0])?.version ?? "");
      })
      .catch(() => !cancelled && setLoaderVersions([]));
    return () => {
      cancelled = true;
    };
  }, [loader, mc]);

  const shown = useMemo(() => (versions ?? []).filter((v) => snapshots || v.type === "release"), [versions, snapshots]);
  const unavailable = loader !== "vanilla" && loaderVersions !== null && loaderVersions.length === 0;
  const defaultName = `${LOADERS.find((l) => l.value === loader)!.label} ${mc}`;

  async function create() {
    setCreating(true);
    try {
      const instance = await api.createInstance(name.trim() || defaultName, mc, loader === "vanilla" ? null : loader, loader === "vanilla" ? null : loaderVersion);
      await refresh();
      select(instance.id);
      onClose();
      navigate({ page: "home" });
      toast(`${instance.name} angelegt. Fehlende Dateien lädt der erste Start.`, "success");
      if (copyFrom && copyParts.size) {
        const source = overview?.instances.find((i) => i.id === copyFrom);
        api
          .copyInstanceSettings(instance.id, copyFrom, [...copyParts])
          .then((n) => n && toast(`Einstellungen aus ${source?.name ?? "der Instanz"} übernommen`, "success"))
          .catch((e) => toast(String(e), "error"));
      }
      if (preset && loader !== "vanilla") {
        // Runs as a background task; the instance is usable meanwhile.
        runTask(`Performance-Paket für ${instance.name}`, (taskId) => api.contentInstallPreset(instance.id, taskId))
          .then((r) => toast(presetSummary(r), r.warnings.length ? "info" : "success"))
          .catch((e) => toast(String(e), "error"));
      }
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setCreating(false);
    }
  }

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-4">
      <motion.div variants={rise}>
        <Field label="Name">
          <input autoFocus className={inputClass} placeholder={defaultName} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
      </motion.div>
      <motion.div variants={rise}>
        <Field label="Minecraft-Version">
          <div className="flex items-center gap-3">
            {versions === null ? (
              <Spinner />
            ) : (
              <select className={inputClass} value={mc} onChange={(e) => setMc(e.target.value)}>
                {shown.map((v) => (
                  <option key={v.id} value={v.id} className="bg-panel-solid">
                    {v.id}
                    {v.type !== "release" ? ` (${v.type.replace("old_", "")})` : ""}
                  </option>
                ))}
              </select>
            )}
            <label className="flex shrink-0 items-center gap-2 text-xs text-muted">
              <Toggle checked={snapshots} onChange={setSnapshots} label="Snapshots anzeigen" />
              Snapshots
            </label>
          </div>
        </Field>
      </motion.div>
      <motion.div variants={rise}>
        <Field label="Mod-Loader">
          <Segmented value={loader} options={LOADERS} onChange={setLoader} />
        </Field>
      </motion.div>
      {loader !== "vanilla" && (
        <motion.div variants={rise}>
          <Field label="Loader-Version" hint={unavailable ? `${LOADERS.find((l) => l.value === loader)!.label} gibt es für ${mc} nicht.` : undefined}>
            {loaderVersions === null ? (
              <Spinner />
            ) : (
              <select className={inputClass} value={loaderVersion} disabled={unavailable} onChange={(e) => setLoaderVersion(e.target.value)}>
                {loaderVersions.map((v) => (
                  <option key={v.version} value={v.version} className="bg-panel-solid">
                    {v.version}
                    {v.stable ? "" : " (beta)"}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </motion.div>
      )}
      {(overview?.instances.length ?? 0) > 0 && (
        <motion.div variants={rise} className="glass grid gap-3 rounded-[14px] px-4 py-3">
          <div className="flex items-center gap-3">
            <div className="min-w-0 flex-1">
              <div className="text-[13px] font-semibold">Einstellungen übernehmen</div>
              <div className="text-[12px] text-muted">Tastenbelegung, FOV, Server und mehr aus einer anderen Instanz.</div>
            </div>
            <select className={`${inputClass} w-44`} value={copyFrom} onChange={(e) => setCopyFrom(e.target.value)} aria-label="Instanz, aus der übernommen wird">
              <option value="">Nichts übernehmen</option>
              {overview!.instances.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.name}
                </option>
              ))}
            </select>
          </div>
          {copyFrom && (
            <div className="grid grid-cols-2 gap-1.5">
              {COPY_PARTS.map(([part, label, hint]) => {
                const on = copyParts.has(part);
                return (
                  <button
                    key={part}
                    type="button"
                    aria-pressed={on}
                    title={hint}
                    onClick={() =>
                      setCopyParts((s) => {
                        const n = new Set(s);
                        if (on) n.delete(part);
                        else n.add(part);
                        return n;
                      })
                    }
                    className={`flex items-center gap-2 rounded-[10px] border px-2.5 py-1.5 text-left text-[12px] font-semibold transition duration-200 ${on ? "border-accent-2/50 bg-accent/[0.14] text-text" : "border-line text-muted hover:border-line-2 hover:text-text"}`}
                  >
                    <span className={`grid size-4 shrink-0 place-items-center rounded-[5px] ${on ? "chroma text-white" : "bg-white/[0.08]"}`}>{on && <Check className="size-3" />}</span>
                    {label}
                  </button>
                );
              })}
            </div>
          )}
        </motion.div>
      )}
      {loader !== "vanilla" && (
        <motion.label variants={rise} className="glass flex cursor-pointer items-center gap-3 rounded-[14px] px-4 py-3">
          <div className="min-w-0 flex-1">
            <div className="text-[13px] font-semibold">Performance-Paket</div>
            <div className="text-[12px] text-muted">Sodium, Lithium, FerriteCore und Iris (Shader) gleich mitinstallieren.</div>
          </div>
          <Toggle checked={preset} onChange={setPreset} label="Performance-Paket installieren" />
        </motion.label>
      )}
      <motion.div variants={rise} className="mt-2 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button variant="primary" loading={creating} disabled={!mc || unavailable || (loader !== "vanilla" && !loaderVersion)} onClick={create}>
          Instanz anlegen
        </Button>
      </motion.div>
    </motion.div>
  );
}

function Import({ onClose }: { onClose: () => void }) {
  const { runTask, refresh, toast, navigate, select } = useStore();
  const [slug, setSlug] = useState("");

  async function run(source: string, isFile: boolean, platform: Platform = "modrinth") {
    onClose();
    try {
      const instance = await runTask("Modpack importieren", (taskId) => api.modpackImport(source, isFile, platform, null, taskId));
      await refresh();
      select(instance.id);
      navigate({ page: "instance", id: instance.id });
      toast(`${instance.name} importiert`, "success");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  const fromInput = () => {
    const value = slug.trim();
    if (!value) return;
    const cf = value.match(/curseforge\.com\/minecraft\/modpacks\/([^/?#]+)/);
    if (cf) return run(cf[1], false, "curseforge");
    const match = value.match(/modrinth\.com\/modpack\/([^/?#]+)/);
    run(match ? match[1] : value, false);
  };

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-5">
      <motion.button
        variants={rise}
        disabled={!isTauri}
        onClick={async () => {
          const file = await pickModpackFile();
          if (file) run(file, true);
        }}
        className="group flex items-center gap-4 rounded-2xl border border-dashed border-line-2 p-4 text-left transition duration-200 hover:border-accent-2/60 hover:bg-white/[0.03] disabled:opacity-50"
      >
        <span className="chroma grid size-11 place-items-center rounded-xl text-white transition-transform duration-300 group-hover:scale-105">
          <FileUp className="size-5" />
        </span>
        <span>
          <span className="block font-semibold">Modpack-Datei wählen</span>
          <span className="block text-xs text-muted">.mrpack von Modrinth oder ZIP von CurseForge</span>
        </span>
      </motion.button>
      <motion.div variants={rise} className="flex items-center gap-3 text-xs text-muted">
        <span className="h-px flex-1 bg-line" />
        oder online
        <span className="h-px flex-1 bg-line" />
      </motion.div>
      <motion.div variants={rise}>
        <Field label="Modpack-Slug oder Link" hint="Slug von Modrinth, oder ein Link zu modrinth.com/modpack/… bzw. curseforge.com/minecraft/modpacks/…">
          <input className={inputClass} placeholder="fabulously-optimized" value={slug} onChange={(e) => setSlug(e.target.value)} onKeyDown={(e) => e.key === "Enter" && fromInput()} />
        </Field>
      </motion.div>
      <motion.div variants={rise} className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button variant="primary" disabled={!slug.trim()} onClick={fromInput}>
          Importieren
        </Button>
      </motion.div>
      <ForeignList onClose={onClose} />
    </motion.div>
  );
}

/** Instances of Prism, PolyMC and MultiMC; importing copies them, the originals stay. */
function ForeignList({ onClose }: { onClose: () => void }) {
  const { runTask, refresh, toast, navigate, select } = useStore();
  const [found, setFound] = useState<ForeignInstance[] | null>(null);
  const [extraDir, setExtraDir] = useState<string | null>(null);

  useEffect(() => {
    api.foreignScan(extraDir).then(setFound).catch(() => setFound([]));
  }, [extraDir]);

  async function run(i: ForeignInstance) {
    onClose();
    try {
      const instance = await runTask(`${i.name} übernehmen`, (taskId) => api.foreignImport(i.path, null, taskId));
      await refresh();
      select(instance.id);
      navigate({ page: "instance", id: instance.id });
      toast(`${instance.name} aus ${i.launcher} übernommen`, "success");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <motion.div variants={rise} className="grid gap-2 border-t border-line pt-4">
      <div className="flex items-center gap-3">
        <h3 className="mr-auto text-[13px] font-semibold">Aus anderen Launchern</h3>
        <Button
          size="sm"
          variant="ghost"
          disabled={!isTauri}
          onClick={async () => {
            const dir = await pickFolder("Instanzen-Ordner von MultiMC wählen");
            if (dir) setExtraDir(dir);
          }}
        >
          MultiMC-Ordner …
        </Button>
      </div>
      {found === null ? (
        <Spinner className="size-4" />
      ) : found.length === 0 ? (
        <p className="text-[13px] text-muted">Keine Instanzen von Prism, PolyMC oder MultiMC gefunden.</p>
      ) : (
        <div className="grid max-h-48 gap-1 overflow-auto">
          {found.map((i) => (
            <button key={i.path} onClick={() => run(i)} className="group flex items-center gap-3 rounded-xl px-3 py-2 text-left transition-colors duration-200 hover:bg-white/[0.05]">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-semibold">{i.name}</span>
                <span className="block truncate text-[11.5px] text-muted">
                  {i.launcher} · {i.mcVersion} · {loaderLabel(i.loader)} · {i.mods} Mods
                </span>
              </span>
              <span className="text-[11.5px] font-semibold text-accent-2 opacity-0 transition-opacity duration-200 group-hover:opacity-100">Übernehmen</span>
            </button>
          ))}
        </div>
      )}
    </motion.div>
  );
}

function Login({ onClose }: { onClose: () => void }) {
  const { overview, refresh, toast, navigate } = useStore();
  const [code, setCode] = useState<DeviceCode | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Start the login once per open dialog, not on every re-render.
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!overview?.hasClientId) return;
    let active = true;
    api
      .loginStart()
      .then(async (c) => {
        if (!active) return;
        setCode(c);
        const account = await api.loginFinish(c);
        if (!active) return;
        await refresh();
        toast(`Angemeldet als ${account.username}`, "success");
        closeRef.current();
      })
      .catch((e) => active && setError(String(e)));
    return () => {
      active = false;
    };
  }, [overview?.hasClientId, refresh, toast]);

  if (!overview?.hasClientId) {
    return (
      <div className="grid gap-4">
        <p className="text-[13px] leading-relaxed text-muted">Für den Login braucht SlopClient eine Azure-Client-ID, die Mojang für die Minecraft-API freigeschaltet hat. Trag sie in den Einstellungen ein.</p>
        <div className="flex justify-end">
          <Button
            variant="primary"
            onClick={() => {
              onClose();
              navigate({ page: "settings" });
            }}
          >
            Zu den Einstellungen
          </Button>
        </div>
      </div>
    );
  }
  if (error) {
    return (
      <div className="grid gap-4">
        <p className="rounded-xl border border-err/25 bg-err/[0.08] p-3 text-[13px] leading-relaxed text-err">{error}</p>
        <div className="flex justify-end">
          <Button onClick={onClose}>Schließen</Button>
        </div>
      </div>
    );
  }
  if (!code) {
    return (
      <div className="grid place-items-center py-10">
        <Spinner className="size-5" />
      </div>
    );
  }
  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-5 text-center">
      <motion.p variants={rise} className="text-[13px] leading-relaxed text-muted">
        Öffne die Microsoft-Seite und gib diesen Code ein. Der Dialog schließt sich, sobald du bestätigt hast.
      </motion.p>
      <motion.button
        variants={rise}
        onClick={() => {
          navigator.clipboard.writeText(code.user_code);
          toast("Code kopiert");
        }}
        className="group chroma-ring mx-auto flex items-center gap-3 rounded-2xl px-6 py-4 font-mono text-[28px] font-semibold tracking-[0.2em]"
      >
        <span className="chroma-text">{code.user_code}</span>
        <Copy className="size-4 text-muted group-hover:text-text" />
      </motion.button>
      <motion.div variants={rise} className="flex justify-center">
        <Button variant="primary" icon={<ExternalLink className="size-4" />} onClick={() => openUrl(code.verification_uri)}>
          Microsoft-Seite öffnen
        </Button>
      </motion.div>
      <motion.p variants={rise} className="flex items-center justify-center gap-2 text-xs text-muted">
        <Spinner className="size-3.5" />
        Warte auf Bestätigung …
      </motion.p>
    </motion.div>
  );
}

function Export({ instanceId, onClose }: { instanceId: string; onClose: () => void }) {
  const { overview, toast } = useStore();
  const instance = overview?.instances.find((i) => i.id === instanceId);
  const [name, setName] = useState(instance?.name ?? "");
  const [version, setVersion] = useState("1.0.0");
  const [includeConfig, setIncludeConfig] = useState(true);
  const [includeOptions, setIncludeOptions] = useState(false);
  const [exporting, setExporting] = useState(false);

  async function exportPack() {
    const file = await pickExportPath(`${name || "modpack"}-${version}.mrpack`);
    if (!file) return;
    setExporting(true);
    try {
      const include = [includeConfig && "config", includeOptions && "options.txt"].filter(Boolean) as string[];
      const [downloads, bundled] = await api.modpackExport(instanceId, file, name, version, include);
      toast(`Exportiert: ${downloads} Downloads, ${bundled} gebündelte Dateien`, "success");
      onClose();
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setExporting(false);
    }
  }

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-4">
      <motion.div variants={rise}>
        <Field label="Name">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
      </motion.div>
      <motion.div variants={rise}>
        <Field label="Version">
          <input className={`${inputClass} font-mono`} value={version} onChange={(e) => setVersion(e.target.value)} />
        </Field>
      </motion.div>
      <motion.div variants={rise} className="grid gap-2.5 rounded-xl border border-line bg-white/[0.02] p-3.5">
        <label className="flex items-center justify-between text-[13px]">
          Mod-Konfiguration (config/)
          <Toggle checked={includeConfig} onChange={setIncludeConfig} label="config mitliefern" />
        </label>
        <label className="flex items-center justify-between text-[13px]">
          Spieloptionen und Tasten (options.txt)
          <Toggle checked={includeOptions} onChange={setIncludeOptions} label="options.txt mitliefern" />
        </label>
      </motion.div>
      <motion.p variants={rise} className="text-xs leading-relaxed text-muted">
        Inhalte von Modrinth werden verlinkt, lokale Dateien landen im Paket.
      </motion.p>
      <motion.div variants={rise} className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button variant="primary" icon={<Upload className="size-4" />} loading={exporting} disabled={!isTauri || !name.trim()} onClick={exportPack}>
          Speichern unter …
        </Button>
      </motion.div>
    </motion.div>
  );
}
