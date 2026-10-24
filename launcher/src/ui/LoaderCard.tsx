import { ArrowUpCircle } from "lucide-react";
import { useEffect, useState } from "react";
import { LOADERS, compareVersions, latestStable, loaderVersions, type LoaderChoice } from "../lib/loaders";
import type { Instance, Loader, LoaderVersion } from "../lib/types";
import { Button, Segmented, Spinner, inputClass } from "./kit";

/**
 * Mod loader of an existing instance: switch the version (or the loader). The new
 * version is installed on the next start; mods, worlds and settings stay.
 */
export function LoaderCard({ instance, loader, onChange, disabled }: { instance: Instance; loader: Loader; onChange: (l: Loader) => void; disabled?: boolean }) {
  const kind: LoaderChoice = loader.type;
  const [list, setList] = useState<LoaderVersion[] | null>(null);

  useEffect(() => {
    setList(null);
    if (kind === "vanilla") return;
    let live = true;
    loaderVersions(kind, instance.mcVersion)
      .then((l) => live && setList(l))
      .catch(() => live && setList([]));
    return () => {
      live = false;
    };
  }, [kind, instance.mcVersion]);

  const latest = list ? latestStable(list) : undefined;
  const current = loader.type === "vanilla" ? null : loader.version;
  const saved = instance.loader.type === "vanilla" ? null : instance.loader.version;
  const changedKind = loader.type !== instance.loader.type;
  const behind = latest && current && compareVersions(latest.version, current) > 0;

  function pickKind(next: LoaderChoice) {
    if (next === "vanilla") return onChange({ type: "vanilla" });
    // The version follows once the list for the new loader is there.
    loaderVersions(next, instance.mcVersion)
      .then((l) => {
        const v = latestStable(l);
        if (v) onChange({ type: next, version: v.version });
      })
      .catch(() => {});
    onChange({ type: next, version: next === instance.loader.type && saved ? saved : "" });
  }

  return (
    <div className="glass grid gap-3 rounded-[18px] p-4">
      <div>
        <h3 className="text-[13px] font-semibold">Mod-Loader</h3>
        <p className="mt-0.5 text-[12.5px] text-muted">
          Neue Versionen lädt SlopClient beim nächsten Start. Mods, Welten und Einstellungen bleiben.
        </p>
      </div>
      <Segmented value={kind} options={LOADERS} onChange={(v) => !disabled && pickKind(v)} />
      {kind !== "vanilla" && (
        <div className="flex items-end gap-3">
          <label className="grid min-w-0 flex-1 gap-1.5">
            <span className="text-[12px] font-semibold text-muted">Version</span>
            {list === null ? (
              <Spinner />
            ) : list.length === 0 ? (
              <span className="text-[12.5px] text-warn">Für Minecraft {instance.mcVersion} gibt es keine {LOADERS.find((l) => l.value === kind)?.label}-Versionen.</span>
            ) : (
              <select className={inputClass} value={current ?? ""} disabled={disabled} onChange={(e) => onChange({ type: kind, version: e.target.value })}>
                {current && !list.some((v) => v.version === current) && (
                  <option value={current} className="bg-panel-solid">
                    {current} (nicht mehr gelistet)
                  </option>
                )}
                {list.map((v) => (
                  <option key={v.version} value={v.version} className="bg-panel-solid">
                    {v.version}
                    {v === latest ? " · neueste" : ""}
                    {v.stable ? "" : " (beta)"}
                    {v.version === saved ? " · installiert" : ""}
                  </option>
                ))}
              </select>
            )}
          </label>
          {behind && (
            <Button icon={<ArrowUpCircle className="size-4" />} disabled={disabled} onClick={() => onChange({ type: kind, version: latest!.version })}>
              Auf {latest!.version}
            </Button>
          )}
        </div>
      )}
      {changedKind && (
        <p className="rounded-[10px] bg-warn/[0.08] px-3 py-2 text-[12.5px] text-warn">
          {loader.type === "vanilla"
            ? "Ohne Loader starten Mods nicht mehr. Sie bleiben im Ordner und laufen wieder, wenn du den Loader zurückstellst."
            : `Mods für ${LOADERS.find((l) => l.value === instance.loader.type)?.label} laufen meist nicht mit ${LOADERS.find((l) => l.value === loader.type)?.label}. Prüf danach die Inhalte.`}
        </p>
      )}
      {disabled && <p className="text-[12px] text-muted">Während das Spiel läuft, lässt sich der Loader nicht ändern.</p>}
    </div>
  );
}
