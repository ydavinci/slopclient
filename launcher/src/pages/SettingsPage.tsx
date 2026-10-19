import { ExternalLink, FolderOpen } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useState } from "react";
import { api, openUrl } from "../lib/ipc";
import { useStore } from "../lib/store";
import type { Settings } from "../lib/types";
import { Button, Field, Spinner, inputClass, rise, stagger } from "../ui/kit";
import { Slider } from "./InstanceDetail";

export function SettingsPage() {
  const { overview, refresh, toast } = useStore();
  const [settings, setSettings] = useState<Settings | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.settings().then(setSettings).catch((e) => toast(String(e), "error"));
  }, [toast]);

  async function save() {
    if (!settings) return;
    setSaving(true);
    try {
      await api.saveSettings({ ...settings, msaClientId: settings.msaClientId?.trim() || null, curseforgeApiKey: settings.curseforgeApiKey?.trim() || null });
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
        <motion.div variants={stagger} initial="hidden" animate="show" className="mt-6 grid max-w-2xl gap-4">
          <motion.section variants={rise} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Microsoft-Login</h2>
            <Field
              label="Azure-Client-ID"
              hint={
                <>
                  Eigene App-Registrierung im Azure-Portal, von Mojang für die Minecraft-API freigeschaltet. Ohne Freigabe endet der Login mit Fehler 403.{" "}
                  <button className="inline-flex items-center gap-1 font-semibold text-accent-2 hover:underline" onClick={() => openUrl("https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade")}>
                    Azure-Portal öffnen <ExternalLink className="size-3" />
                  </button>
                </>
              }
            >
              <input className={`${inputClass} font-mono`} placeholder="00000000-0000-0000-0000-000000000000" value={settings.msaClientId ?? ""} onChange={(e) => setSettings({ ...settings, msaClientId: e.target.value })} />
            </Field>
          </motion.section>

          <motion.section variants={rise} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">CurseForge</h2>
            <Field
              label="API-Schlüssel"
              hint={
                <>
                  Für Suche, Installation und Modpacks von CurseForge. Den Schlüssel gibt es nach Freigabe in der CurseForge-Konsole.{" "}
                  <button className="inline-flex items-center gap-1 font-semibold text-accent-2 hover:underline" onClick={() => openUrl("https://console.curseforge.com/")}>
                    Konsole öffnen <ExternalLink className="size-3" />
                  </button>
                </>
              }
            >
              <input type="password" autoComplete="off" spellCheck={false} className={`${inputClass} font-mono`} placeholder="$2a$10$…" value={settings.curseforgeApiKey ?? ""} onChange={(e) => setSettings({ ...settings, curseforgeApiKey: e.target.value })} />
            </Field>
          </motion.section>

          <motion.section variants={rise} className="glass grid gap-4 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Downloads</h2>
            <Slider label="Parallele Downloads" value={settings.downloadConcurrency ?? 16} min={1} max={64} step={1} unit="" onChange={(v) => setSettings({ ...settings, downloadConcurrency: v })} hint="Mehr ist bei schneller Leitung flotter. Bei Abbrüchen verringern." />
          </motion.section>

          <motion.div variants={rise}>
            <Button variant="primary" loading={saving} onClick={save}>
              Speichern
            </Button>
          </motion.div>

          <motion.section variants={rise} className="glass grid gap-3 rounded-[18px] p-5">
            <h2 className="text-[15px] font-bold">Speicherort</h2>
            <div className="flex items-center gap-3">
              <code className="flex-1 truncate rounded-[10px] border border-line bg-black/30 px-3 py-2.5 font-mono text-xs text-muted select-text">{overview?.dataDir}</code>
              <Button icon={<FolderOpen className="size-4" />} onClick={() => overview && api.openPath(overview.dataDir)}>
                Öffnen
              </Button>
            </div>
            <p className="text-xs text-muted">Libraries, Assets und Java teilen sich alle Instanzen.</p>
          </motion.section>

          <motion.p variants={rise} className="text-xs leading-relaxed text-muted">
            DaClient 0.1.0. Kein offizielles Minecraft-Produkt. Nicht von Mojang oder Microsoft genehmigt oder mit ihnen verbunden.
          </motion.p>
        </motion.div>
      )}
    </div>
  );
}
