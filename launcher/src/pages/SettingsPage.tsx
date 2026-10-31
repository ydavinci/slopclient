import { FolderOpen } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useState } from "react";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import type { Settings } from "../lib/types";
import { Button, Field, Segmented, Spinner, Toggle, inputClass, rise } from "../ui/kit";
import { Slider } from "./InstanceDetail";
import { CosmeticsOnline } from "./Cosmetics";
import { HueRing } from "../ui/HueRing";
import { DEFAULT_HUE, applyAccent } from "../lib/accent";

export function SettingsPage() {
  const { overview, refresh, toast, centered, setCentered, homeLayout, setArranging, navigate } = useStore();
  const openArrange = () => {
    setArranging(true);
    navigate({ page: "home" });
  };
  const [settings, setSettings] = useState<Settings | null>(null);
  const [saving, setSaving] = useState(false);
  const [migrating, setMigrating] = useState(false);
  // Remounts the online card after saving, so it shows the status for the new server.
  const [onlineKey, setOnlineKey] = useState(0);

  async function migrate() {
    setMigrating(true);
    try {
      // Restarts the launcher when it works.
      await api.finishMigration();
    } catch (e) {
      toast(String(e), "error");
      setMigrating(false);
    }
  }

  useEffect(() => {
    api.settings().then(setSettings).catch((e) => toast(String(e), "error"));
  }, [toast]);

  async function save() {
    if (!settings) return;
    const cosmeticsUrl = settings.cosmeticsUrl?.trim() || null;
    if (cosmeticsUrl && !/^https?:\/\/[^\s/]+/.test(cosmeticsUrl)) {
      toast("Cosmetics-Server: Adresse muss mit http:// oder https:// beginnen", "error");
      return;
    }
    setSaving(true);
    try {
      await api.saveSettings({ ...settings, msaClientId: settings.msaClientId?.trim() || null, curseforgeApiKey: settings.curseforgeApiKey?.trim() || null, cosmeticsUrl });
      setOnlineKey((k) => k + 1);
      await refresh();
      toast("Einstellungen gespeichert", "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="h-full overflow-auto px-8 pt-3 pb-10">
      <h1 className="text-[30px] font-extrabold tracking-[-0.03em]">Einstellungen</h1>
      {!settings ? (
        <Spinner className="mt-8 size-5" />
      ) : (
        // Each section animates on its own: the list mounts after the settings load, and a
        // stagger parent mounted that late does not reliably start its children.
        <div className="mt-6 grid max-w-2xl gap-4">
          <motion.section variants={rise} initial="hidden" animate="show" transition={{ delay: 0.00 }} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Design</h2>
            <HueRing
              hue={Math.round(settings.accentHue ?? DEFAULT_HUE)}
              onPreview={(h) => {
                applyAccent(h);
                setSettings({ ...settings, accentHue: h });
              }}
              onCommit={(h) => {
                const value = h === DEFAULT_HUE ? null : h;
                applyAccent(value);
                setSettings({ ...settings, accentHue: value });
                api.setAccentHue(value).catch((e) => toast(String(e), "error"));
              }}
            />
            <div className="flex items-center gap-4 border-t border-line pt-4">
              <div className="min-w-0 flex-1">
                <div className="text-[13px] font-semibold">Startseite</div>
                <div className="text-[12px] text-muted">Links oder mittig gilt auch für den Titelbildschirm im Spiel. Mit „Anordnen“ ziehst du die Blöcke im Launcher frei an ihren Platz.</div>
              </div>
              <Segmented
                className="w-60"
                value={homeLayout ? "custom" : centered ? "center" : "left"}
                onChange={(v) => (v === "custom" ? openArrange() : setCentered(v === "center"))}
                options={[
                  { value: "left", label: "Links" },
                  { value: "center", label: "Mittig" },
                  { value: "custom", label: "Eigenes" },
                ]}
              />
              <Button size="sm" onClick={openArrange}>
                Anordnen
              </Button>
            </div>
          </motion.section>

          <motion.section variants={rise} initial="hidden" animate="show" transition={{ delay: 0.05 }} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Discord</h2>
            {(() => {
              const enabled = settings.discordPresence ?? true;
              const hide = settings.discordHideServer ?? false;
              const apply = (e: boolean, h: boolean) => {
                setSettings({ ...settings, discordPresence: e, discordHideServer: h });
                api.setDiscord(e, h).catch((err) => toast(String(err), "error"));
              };
              return (
                <>
                  <div className="flex items-center gap-4">
                    <div className="min-w-0 flex-1">
                      <div className="text-[13px] font-semibold">Status in Discord zeigen</div>
                      <div className="text-[12px] text-muted">Zeigt in deinem Profil, welche Instanz du spielst, auf welchem Server und seit wann. Discord muss dafür laufen.</div>
                    </div>
                    <Toggle label="Status in Discord zeigen" checked={enabled} onChange={(v) => apply(v, hide)} />
                  </div>
                  <div className={`flex items-center gap-4 transition-opacity duration-200 ${enabled ? "" : "pointer-events-none opacity-40"}`}>
                    <div className="min-w-0 flex-1">
                      <div className="text-[13px] font-semibold">Serveradresse ausblenden</div>
                      <div className="text-[12px] text-muted">Statt „Auf mc.hypixel.net“ steht dann nur „Auf einem Server“.</div>
                    </div>
                    <Toggle label="Serveradresse ausblenden" checked={hide} onChange={(v) => apply(enabled, v)} />
                  </div>
                </>
              );
            })()}
          </motion.section>

          <motion.section variants={rise} initial="hidden" animate="show" transition={{ delay: 0.08 }} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Kosmetik online</h2>
            <CosmeticsOnline key={onlineKey} />
            <div className="border-t border-line pt-4">
              <Field
                label="Cosmetics-Server"
                hint="Leer lassen für den SlopClient-Server. Eigener Server: siehe deploy/cosmetics. Nach einem Wechsel meldest du dich dort neu an; Instanzen nutzen ihn ab dem nächsten Start."
              >
                <input
                  className={inputClass}
                  placeholder="SlopClient-Server (Standard)"
                  spellCheck={false}
                  value={settings.cosmeticsUrl ?? ""}
                  onChange={(e) => setSettings({ ...settings, cosmeticsUrl: e.target.value })}
                />
              </Field>
            </div>
          </motion.section>

          <motion.section variants={rise} initial="hidden" animate="show" transition={{ delay: 0.10 }} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Downloads</h2>
            <Slider label="Parallele Downloads" value={settings.downloadConcurrency ?? 16} min={1} max={64} step={1} unit="" onChange={(v) => setSettings({ ...settings, downloadConcurrency: v })} hint="Mehr ist bei schneller Leitung flotter. Bei Abbrüchen verringern." />
            <div className="flex items-center gap-4">
              <div className="min-w-0 flex-1">
                <div className="text-[13px] font-semibold">Beim Start nach Updates suchen</div>
                <div className="text-[12px] text-muted">Sucht nach dem Start für alle Instanzen nach neuen Versionen von Mods, Resourcepacks und Shadern. Installiert wird nichts, die Instanzliste zeigt nur, wo Updates warten.</div>
              </div>
              <Toggle label="Beim Start nach Updates suchen" checked={settings.checkUpdatesOnStart ?? true} onChange={(v) => setSettings({ ...settings, checkUpdatesOnStart: v })} />
            </div>
          </motion.section>

          <div>
            <Button variant="primary" loading={saving} onClick={save}>
              Speichern
            </Button>
          </div>

          <motion.section variants={rise} initial="hidden" animate="show" transition={{ delay: 0.15 }} className="glass grid gap-3 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Speicherort</h2>
            {overview?.legacyDataDir && (
              <div className="grid gap-2.5 rounded-[12px] border border-warn/30 bg-warn/[0.08] p-3.5">
                <div className="text-[13px] font-semibold text-warn">Deine Daten liegen noch im alten DaClient-Ordner</div>
                <p className="text-[12.5px] text-muted">
                  Beim Start lief noch der alte DaClient-Launcher, deshalb konnte SlopClient den Ordner nicht umbenennen. Alles funktioniert trotzdem. Zum Umziehen schließt SlopClient den alten Launcher, benennt den Ordner um und startet neu.
                </p>
                <div>
                  <Button size="sm" variant="primary" loading={migrating} onClick={migrate}>
                    Alten Launcher schließen und umziehen
                  </Button>
                </div>
              </div>
            )}
            {overview?.legacyInstall && !overview.legacyDataDir && (
              <div className="flex items-center gap-3 rounded-[12px] border border-line bg-white/[0.03] p-3.5">
                <p className="flex-1 text-[12.5px] text-muted">Der alte DaClient-Launcher ist noch installiert. Deine Daten nutzt jetzt SlopClient, den alten brauchst du nicht mehr.</p>
                <Button size="sm" onClick={() => api.uninstallLegacy().catch((e) => toast(String(e), "error"))}>
                  Deinstallieren
                </Button>
              </div>
            )}
            <div className="flex items-center gap-3">
              <code className="flex-1 truncate rounded-[10px] border border-line bg-black/30 px-3 py-2.5 font-mono text-xs text-muted select-text">{overview?.dataDir}</code>
              <Button icon={<FolderOpen className="size-4" />} onClick={() => overview && api.openPath(overview.dataDir)}>
                Öffnen
              </Button>
            </div>
            <p className="text-xs text-muted">Libraries, Assets und Java teilen sich alle Instanzen.</p>
          </motion.section>

          <p className="text-xs leading-relaxed text-muted">
            SlopClient {overview?.version}. Kein offizielles Minecraft-Produkt. Nicht von Mojang oder Microsoft genehmigt oder mit ihnen verbunden.
          </p>
        </div>
      )}
    </div>
  );
}
