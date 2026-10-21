import { Check, Download, ImagePlus, Trash2, UserRound } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import type { Skin, SkinVariant } from "../lib/types";
import { CapeViewer } from "../ui/CapeViewer";
import { Button, EmptyState, Segmented, Spinner, rise, stagger } from "../ui/kit";

/** Front view of a skin texture, drawn pixel by pixel (no WebGL per tile). */
function SkinFront({ texture, variant, scale = 5 }: { texture: string; variant: SkinVariant; scale?: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const img = new Image();
    img.onload = () => {
      const ctx = canvas.current?.getContext("2d");
      if (!ctx) return;
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, 16 * scale, 32 * scale);
      const legacy = img.height === 32;
      const arm = variant === "slim" ? 3 : 4;
      const part = (sx: number, sy: number, w: number, h: number, dx: number, dy: number, mirror = false) => {
        ctx.save();
        if (mirror) {
          ctx.translate((dx + w) * scale, dy * scale);
          ctx.scale(-1, 1);
          ctx.drawImage(img, sx, sy, w, h, 0, 0, w * scale, h * scale);
        } else {
          ctx.drawImage(img, sx, sy, w, h, dx * scale, dy * scale, w * scale, h * scale);
        }
        ctx.restore();
      };
      // Base layer, then the outer layer (hat, jacket, sleeves, pants).
      part(8, 8, 8, 8, 4, 0);
      part(20, 20, 8, 12, 4, 8);
      part(44, 20, arm, 12, 4 - arm, 8);
      if (legacy) part(44, 20, arm, 12, 12, 8, true);
      else part(36, 52, arm, 12, 12, 8);
      part(4, 20, 4, 12, 4, 20);
      if (legacy) part(4, 20, 4, 12, 8, 20, true);
      else part(20, 52, 4, 12, 8, 20);
      part(40, 8, 8, 8, 4, 0);
      if (!legacy) {
        part(20, 36, 8, 12, 4, 8);
        part(44, 36, arm, 12, 4 - arm, 8);
        part(52, 52, arm, 12, 12, 8);
        part(4, 36, 4, 12, 4, 20);
        part(4, 52, 4, 12, 8, 20);
      }
    };
    img.src = texture;
  }, [texture, variant, scale]);
  return <canvas ref={canvas} width={16 * scale} height={32 * scale} className="[image-rendering:pixelated]" aria-hidden />;
}

/** Skin library: keep skins, preview them in 3D and put one on the Minecraft account. */
export function SkinsView() {
  const { toast, overview, refresh } = useStore();
  const account = overview?.accounts.find((a) => a.uuid === overview.activeAccount) ?? overview?.accounts[0];
  const [skins, setSkins] = useState<Skin[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [applying, setApplying] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    api
      .skins()
      .then(setSkins)
      .catch((e) => toast(String(e), "error"));
  }, [toast]);
  useEffect(load, [load]);

  const current = skins?.find((s) => s.id === selected) ?? null;

  async function addFiles(files: File[]) {
    for (const file of files) {
      try {
        const entry = await api.skinAdd(file);
        setSelected(entry.id);
        toast(`„${entry.name}“ hinzugefügt (${entry.variant === "slim" ? "schmale" : "breite"} Arme erkannt)`, "success");
      } catch (e) {
        toast(String(e), "error");
      }
    }
    load();
  }

  async function apply(skin: Skin) {
    setApplying(skin.id);
    try {
      await api.skinApply(skin.id);
      await refresh();
      toast(`„${skin.name}“ ist jetzt dein Skin. Auf Servern siehst du ihn nach dem nächsten Beitreten.`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setApplying(null);
    }
  }

  async function importCurrent() {
    setImporting(true);
    try {
      const entry = await api.skinImportCurrent();
      setSelected(entry.id);
      load();
      toast("Aktuellen Skin gespeichert", "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setImporting(false);
    }
  }

  async function setVariant(skin: Skin, variant: SkinVariant) {
    try {
      await api.skinSetVariant(skin.id, variant);
      load();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  async function remove(skin: Skin) {
    try {
      await api.skinRemove(skin.id);
      if (selected === skin.id) setSelected(null);
      toast(`„${skin.name}“ gelöscht`);
      load();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <div
      className="flex min-h-0 flex-1 gap-4 px-8 pb-6"
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={(e) => e.currentTarget === e.target && setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        addFiles([...e.dataTransfer.files].filter((f) => f.type === "image/png" || f.name.toLowerCase().endsWith(".png")));
      }}
    >
      <input
        ref={input}
        type="file"
        accept="image/png"
        multiple
        className="hidden"
        onChange={(e) => {
          addFiles([...(e.target.files ?? [])]);
          e.target.value = "";
        }}
      />

      {/* Preview: the selected library skin, otherwise the one on the account. */}
      <motion.div variants={rise} initial="hidden" animate="show" className="glass flex w-[300px] shrink-0 flex-col items-center gap-3 self-start rounded-[18px] p-4">
        <CapeViewer frames={[]} frameTimeMs={100} elytra={false} skinUrl={current?.texture ?? account?.skinUrl} slim={current?.variant === "slim"} pose="walk" width={260} height={330} />
        <div className="w-full text-center">
          <div className="truncate text-[14px] font-semibold">{current ? current.name : account ? `Aktueller Skin von ${account.username}` : "Nicht angemeldet"}</div>
          <div className="text-[12px] text-muted">{current ? "Vorschau. „Anwenden“ setzt ihn auf deinem Account." : account ? "So sehen dich andere Spieler gerade." : "Zum Anwenden eines Skins brauchst du einen Microsoft-Account."}</div>
        </div>
        {current ? (
          <div className="flex w-full flex-col gap-2">
            <Segmented
              value={current.variant}
              onChange={(v) => setVariant(current, v)}
              options={[
                { value: "classic", label: "Breite Arme" },
                { value: "slim", label: "Schmale Arme" },
              ]}
            />
            <Button variant="primary" loading={applying === current.id} disabled={!account} onClick={() => apply(current)}>
              Anwenden
            </Button>
          </div>
        ) : (
          account?.skinUrl && (
            <Button icon={<Download className="size-4" />} loading={importing} onClick={importCurrent}>
              In die Bibliothek
            </Button>
          )
        )}
      </motion.div>

      <div className={`min-h-0 flex-1 overflow-auto rounded-[18px] transition-colors duration-200 ${dragOver ? "bg-accent/[0.08] shadow-[inset_0_0_0_1.5px_color-mix(in_oklab,var(--color-accent-2)_50%,transparent)]" : ""}`}>
        <div className="mb-3 flex items-center gap-3">
          <p className="mr-auto text-[13px] text-muted">PNG mit 64×64 Pixeln. Zieh Dateien hierher oder wähle sie aus.</p>
          <Button size="sm" variant="primary" icon={<ImagePlus className="size-3.5" />} onClick={() => input.current?.click()}>
            Skin hinzufügen
          </Button>
        </div>
        {skins === null ? (
          <div className="grid place-items-center py-20">
            <Spinner className="size-5" />
          </div>
        ) : skins.length === 0 ? (
          <EmptyState
            icon={<UserRound className="size-5" />}
            title="Noch keine Skins"
            text="Speichere deinen aktuellen Skin oder füge PNG-Dateien hinzu. Du wechselst dann mit einem Klick, ohne die Minecraft-Webseite."
          />
        ) : (
          <motion.div variants={stagger} initial="hidden" animate="show" className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-3">
            <AnimatePresence>
              {skins.map((skin) => {
                const active = skin.id === selected;
                return (
                  <motion.div key={skin.id} layout variants={rise} exit={{ opacity: 0, scale: 0.95 }} className={`group relative flex flex-col gap-2 rounded-[18px] p-3 ${active ? "chroma-ring" : "glass"}`}>
                    <button onClick={() => setSelected(active ? null : skin.id)} aria-pressed={active} className="grid place-items-center rounded-xl bg-black/30 py-3 transition-colors duration-200 hover:bg-black/20">
                      <SkinFront texture={skin.texture} variant={skin.variant} scale={4} />
                    </button>
                    <div className="flex min-w-0 items-center gap-1.5">
                      <span className="truncate text-[13px] font-semibold">{skin.name}</span>
                      {active && <Check className="size-3.5 shrink-0 text-on" />}
                    </div>
                    <div className="flex gap-1.5">
                      <Button size="sm" variant="primary" className="flex-1" loading={applying === skin.id} disabled={!account} onClick={() => apply(skin)}>
                        Anwenden
                      </Button>
                      <Button size="sm" aria-label={`${skin.name} löschen`} icon={<Trash2 className="size-3.5" />} onClick={() => remove(skin)} />
                    </div>
                  </motion.div>
                );
              })}
            </AnimatePresence>
          </motion.div>
        )}
      </div>
    </div>
  );
}
