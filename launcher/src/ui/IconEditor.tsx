import { ImagePlus, RotateCcw } from "lucide-react";
import { motion } from "motion/react";
import { useCallback, useEffect, useRef, useState } from "react";
import { cropToPng, pendingIcon } from "../lib/icons";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import { Button, InstanceIcon, rise, stagger } from "./kit";

const VIEW = 224;

/** Square crop of the source image: centre and side length in source pixels. */
interface Crop {
  cx: number;
  cy: number;
  side: number;
}

function clampCrop(c: Crop, w: number, h: number): Crop {
  const side = Math.min(Math.max(c.side, 8), Math.min(w, h));
  const half = side / 2;
  return { side, cx: Math.min(Math.max(c.cx, half), w - half), cy: Math.min(Math.max(c.cy, half), h - half) };
}

/** Pick or drop an image, move and zoom the square crop, save as the instance icon. */
export function IconEditor({ instanceId, onClose }: { instanceId: string; onClose: () => void }) {
  const { overview, refresh, toast } = useStore();
  const instance = overview?.instances.find((i) => i.id === instanceId);
  const [image, setImage] = useState<HTMLImageElement | null>(null);
  const [crop, setCrop] = useState<Crop | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const drag = useRef<{ x: number; y: number; crop: Crop } | null>(null);

  const load = useCallback(
    (file: File) => {
      if (!file.type.startsWith("image/")) {
        toast("Das ist kein Bild. Unterstützt werden PNG, JPG, GIF und WebP.", "error");
        return;
      }
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        setImage(img);
        const side = Math.min(img.naturalWidth, img.naturalHeight);
        setCrop({ cx: img.naturalWidth / 2, cy: img.naturalHeight / 2, side });
      };
      img.onerror = () => toast("Das Bild ließ sich nicht öffnen.", "error");
      img.src = url;
    },
    [toast],
  );

  // An image dropped on the instance header arrives here.
  useEffect(() => {
    if (pendingIcon.file) {
      load(pendingIcon.file);
      pendingIcon.file = null;
    }
  }, [load]);

  // Redraw the viewport and the small previews whenever the crop moves.
  useEffect(() => {
    const el = canvas.current;
    if (!el || !image || !crop) return;
    const ctx = el.getContext("2d")!;
    const dpr = window.devicePixelRatio || 1;
    el.width = el.height = VIEW * dpr;
    ctx.imageSmoothingEnabled = crop.side > 64;
    ctx.imageSmoothingQuality = "high";
    ctx.drawImage(image, crop.cx - crop.side / 2, crop.cy - crop.side / 2, crop.side, crop.side, 0, 0, el.width, el.height);
    setPreview(el.toDataURL("image/png"));
  }, [image, crop]);

  if (!instance) return null;
  const w = image?.naturalWidth ?? 1;
  const h = image?.naturalHeight ?? 1;
  const maxSide = Math.min(w, h);
  const zoom = crop ? maxSide / crop.side : 1;

  function setZoom(z: number) {
    if (!crop) return;
    setCrop(clampCrop({ ...crop, side: maxSide / Math.min(Math.max(z, 1), 8) }, w, h));
  }

  async function save() {
    if (!image || !crop) return;
    setSaving(true);
    try {
      const bytes = await cropToPng(image, crop.cx - crop.side / 2, crop.cy - crop.side / 2, crop.side);
      await api.setInstanceIcon(instanceId, bytes);
      await refresh();
      toast("Icon gespeichert", "success");
      onClose();
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setSaving(false);
    }
  }

  async function reset() {
    try {
      await api.clearInstanceIcon(instanceId);
      await refresh();
      toast("Icon zurückgesetzt");
      onClose();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-5">
      <input
        ref={input}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) load(file);
          e.target.value = "";
        }}
      />
      <motion.div variants={rise} className="flex gap-5">
        {/* Viewport: drag to move, wheel to zoom. */}
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragOver(false);
            const file = e.dataTransfer.files[0];
            if (file) load(file);
          }}
          onWheel={(e) => image && setZoom(zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1))}
          onPointerDown={(e) => {
            if (!crop) return;
            e.currentTarget.setPointerCapture(e.pointerId);
            drag.current = { x: e.clientX, y: e.clientY, crop };
          }}
          onPointerMove={(e) => {
            const d = drag.current;
            if (!d) return;
            const scale = d.crop.side / VIEW;
            setCrop(clampCrop({ ...d.crop, cx: d.crop.cx - (e.clientX - d.x) * scale, cy: d.crop.cy - (e.clientY - d.y) * scale }, w, h));
          }}
          onPointerUp={() => (drag.current = null)}
          className={`relative shrink-0 overflow-hidden rounded-[22px] border transition-colors duration-200 ${dragOver ? "border-accent-2" : "border-line-2"} ${image ? "cursor-grab active:cursor-grabbing" : ""}`}
          style={{ width: VIEW, height: VIEW }}
        >
          {image ? (
            <canvas ref={canvas} className="size-full" />
          ) : (
            <button onClick={() => input.current?.click()} className="grid size-full place-items-center bg-white/[0.03] text-center text-muted transition-colors duration-200 hover:bg-white/[0.05] hover:text-text">
              <span className="grid justify-items-center gap-2 px-6">
                <ImagePlus className="size-6 text-accent-2" />
                <span className="text-[13px] font-semibold">Bild hierher ziehen oder auswählen</span>
                <span className="text-[11.5px] text-dim">PNG, JPG, GIF oder WebP</span>
              </span>
            </button>
          )}
          {image && <span className="pointer-events-none absolute inset-0 rounded-[22px] shadow-[inset_0_0_0_1px_rgb(255_255_255/0.12)]" />}
        </div>

        <div className="flex min-w-0 flex-1 flex-col gap-4">
          <div>
            <h3 className="text-[13px] font-semibold">Vorschau</h3>
            <div className="mt-2.5 flex items-end gap-3">
              {[56, 40, 30].map((size) =>
                preview ? (
                  <img key={size} src={preview} alt="" className="bg-white/5" style={{ width: size, height: size, borderRadius: size * 0.28 }} />
                ) : (
                  <InstanceIcon key={size} id={instance.id} name={instance.name} icon={instance.icon} size={size} />
                ),
              )}
            </div>
          </div>
          {image && (
            <label className="grid gap-1.5">
              <span className="flex justify-between text-[13px]">
                <span className="font-semibold">Zoom</span>
                <span className="font-mono text-[12.5px] text-muted">{zoom.toFixed(1)}×</span>
              </span>
              <input type="range" min={1} max={8} step={0.05} value={zoom} onChange={(e) => setZoom(Number(e.target.value))} className="accent-[var(--color-accent)]" />
              <span className="text-[11.5px] text-dim">Ziehen verschiebt den Ausschnitt, das Mausrad zoomt.</span>
            </label>
          )}
          <Button size="sm" className="self-start" icon={<ImagePlus className="size-4" />} onClick={() => input.current?.click()}>
            {image ? "Anderes Bild" : "Bild wählen"}
          </Button>
        </div>
      </motion.div>

      <motion.div variants={rise} className="flex items-center gap-2">
        {instance.icon && (
          <Button variant="ghost" icon={<RotateCcw className="size-4" />} onClick={reset}>
            Farbkachel verwenden
          </Button>
        )}
        <span className="ml-auto" />
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button variant="primary" disabled={!image} loading={saving} onClick={save}>
          Speichern
        </Button>
      </motion.div>
    </motion.div>
  );
}
