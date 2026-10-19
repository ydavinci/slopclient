import { RotateCw, Save, X } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useMemo, useRef, useState } from "react";
import { FACES, LIMITS, SCALES, activeFrames, blobToBase64, edgeColor, exportCape, extensionOf, frameTime, renderFrame, type EditorState, type Source } from "../lib/cape";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import { Button, Segmented, Toggle, inputClass, rise, stagger } from "./kit";
import { CapeViewer, type Pose } from "./CapeViewer";

const FACE_W = 200;
const FACE_H = 320;

export interface EditTarget {
  /** Existing cape when editing again. */
  id?: string;
  name: string;
  file: Blob & { name?: string };
  source: Source;
  state: EditorState;
}

function kb(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(2)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/** Cape Studio editor: front-face crop, back, edges, elytra, timing, resolution, 3D preview. */
export function CapeEditor({ target, onDone }: { target: EditTarget; onDone: (savedId?: string) => void }) {
  const { overview, toast } = useStore();
  const { source } = target;
  const [st, setSt] = useState<EditorState>(target.state);
  const [name, setName] = useState(target.name);
  const [pose, setPose] = useState<Pose>("walk");
  const [rendered, setRendered] = useState<HTMLCanvasElement[]>([]);
  const [size, setSize] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const face = useRef<HTMLCanvasElement>(null);
  const drag = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const autoEdge = useMemo(() => edgeColor(source.frames[0]), [source]);
  const set = (patch: Partial<EditorState>) => setSt((s) => ({ ...s, ...patch }));
  const account = overview?.accounts.find((a) => a.uuid === overview.activeAccount) ?? overview?.accounts[0];
  const ms = frameTime(source, st);
  const { frames: kept, thinned } = activeFrames(source, st);
  const frameCount = kept.length;

  // Re-render all frames shortly after the last change (cheap enough for 64 HD frames).
  useEffect(() => {
    const t = setTimeout(() => {
      const { frames } = activeFrames(source, st);
      setRendered(frames.map((f) => renderFrame(f, st, autoEdge)));
    }, 60);
    return () => clearTimeout(t);
  }, [source, st, autoEdge]);

  // File size of the real export, for the 8 MB limit.
  useEffect(() => {
    setSize(null);
    const t = setTimeout(() => {
      exportCape(source, st)
        .then(({ png }) => setSize(png.size))
        .catch(() => setSize(null));
    }, 350);
    return () => clearTimeout(t);
  }, [source, st]);

  // Front face at its true resolution, upscaled crisply, playing the animation.
  useEffect(() => {
    const el = face.current;
    if (!el || rendered.length === 0) return;
    const ctx = el.getContext("2d")!;
    const dpr = window.devicePixelRatio || 1;
    el.width = FACE_W * dpr;
    el.height = FACE_H * dpr;
    let i = 0;
    const draw = () => {
      const f = FACES.front, s = st.scale;
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, el.width, el.height);
      ctx.drawImage(rendered[i % rendered.length], f.x * s, f.y * s, f.w * s, f.h * s, 0, 0, el.width, el.height);
      i++;
    };
    draw();
    if (rendered.length < 2) return;
    const timer = setInterval(draw, ms);
    return () => clearInterval(timer);
  }, [rendered, ms, st.scale]);

  const tooBig = size != null && size > LIMITS.maxBytes;

  async function save() {
    setSaving(true);
    try {
      const { png, meta } = await exportCape(source, st);
      if (png.size > LIMITS.maxBytes) throw new Error(`Die Cape ist ${kb(png.size)} groß, erlaubt sind 8 MB. Wähle eine kleinere Auflösung oder weniger Bilder.`);
      const info = await api.capeSave({
        id: target.id ?? null,
        name,
        png: await blobToBase64(png),
        meta,
        sourceExt: extensionOf(target.file),
        source: await blobToBase64(target.file),
        editor: st,
      });
      toast(`„${info.name}“ gespeichert`, "success");
      onDone(info.id);
    } catch (e) {
      toast(String(e instanceof Error ? e.message : e), "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="flex h-full min-h-0 gap-5">
      {/* Front face: drag to move, wheel to zoom. */}
      <motion.div variants={rise} className="flex shrink-0 flex-col gap-3">
        <h3 className="text-[13px] font-semibold">Vorderseite</h3>
        <div
          className="relative cursor-grab overflow-hidden rounded-[14px] border border-line-2 active:cursor-grabbing"
          style={{ width: FACE_W, height: FACE_H, backgroundImage: "repeating-conic-gradient(var(--color-raised) 0% 25%, var(--color-panel-solid) 0% 50%)", backgroundSize: "20px 20px" }}
          onWheel={(e) => set({ zoom: Math.min(8, Math.max(0.2, st.zoom * (e.deltaY < 0 ? 1.08 : 1 / 1.08))) })}
          onPointerDown={(e) => {
            e.currentTarget.setPointerCapture(e.pointerId);
            drag.current = { x: e.clientX, y: e.clientY, ox: st.offsetX, oy: st.offsetY };
          }}
          onPointerMove={(e) => {
            const d = drag.current;
            if (!d) return;
            set({ offsetX: d.ox + (e.clientX - d.x) / FACE_W, offsetY: d.oy + (e.clientY - d.y) / FACE_H });
          }}
          onPointerUp={() => (drag.current = null)}
        >
          <canvas ref={face} className="size-full" />
        </div>
        <p className="w-[200px] text-[11.5px] leading-relaxed text-dim">Ziehen verschiebt das Bild, das Mausrad zoomt. So sieht man die Cape von hinten.</p>
      </motion.div>

      {/* 3D preview. */}
      <motion.div variants={rise} className="glass flex shrink-0 flex-col items-center gap-3 rounded-[18px] p-3">
        <CapeViewer frames={rendered} frameTimeMs={ms} elytra={st.elytra && pose === "fly"} skinUrl={account?.skinUrl} pose={pose} />
        <Segmented
          value={pose}
          onChange={setPose}
          options={[
            { value: "walk", label: "Gehen" },
            { value: "fly", label: st.elytra ? "Elytra" : "Fliegen" },
            { value: "idle", label: "Stehen" },
          ]}
        />
      </motion.div>

      {/* Settings. */}
      <motion.div variants={rise} className="min-h-0 min-w-0 flex-1 overflow-auto pr-1">
        <div className="grid gap-5">
          <label className="grid gap-1.5">
            <span className="text-[13px] font-semibold">Name</span>
            <input className={inputClass} maxLength={40} value={name} onChange={(e) => setName(e.target.value)} />
          </label>

          <section className="grid gap-2.5">
            <h3 className="text-[13px] font-semibold">Bild</h3>
            <div className="flex items-center gap-2">
              <Segmented value={st.fit} onChange={(fit) => set({ fit, zoom: 1, offsetX: 0, offsetY: 0 })} options={[{ value: "cover", label: "Füllen" }, { value: "contain", label: "Einpassen" }]} />
              <Button size="sm" icon={<RotateCw className="size-4" />} onClick={() => set({ rotation: ((st.rotation + 90) % 360) as EditorState["rotation"] })}>
                Drehen
              </Button>
            </div>
            <Range label="Zoom" value={st.zoom} min={0.2} max={8} step={0.01} display={`${st.zoom.toFixed(2)}×`} onChange={(zoom) => set({ zoom })} />
          </section>

          <section className="grid gap-2.5">
            <h3 className="text-[13px] font-semibold">Rückseite und Ränder</h3>
            <div className="flex flex-wrap items-center gap-2">
              <Segmented value={st.back} onChange={(back) => set({ back })} options={[{ value: "mirror", label: "Gespiegelt" }, { value: "same", label: "Gleich" }, { value: "color", label: "Farbe" }]} />
              {st.back === "color" && <ColorInput value={st.backColor} onChange={(backColor) => set({ backColor })} label="Farbe der Rückseite" />}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Segmented value={st.edge} onChange={(edge) => set({ edge })} options={[{ value: "auto", label: "Ränder automatisch" }, { value: "color", label: "Eigene Farbe" }]} />
              {st.edge === "color" ? <ColorInput value={st.edgeColor} onChange={(edgeColor) => set({ edgeColor })} label="Randfarbe" /> : <span className="size-5 rounded-md border border-line-2" style={{ background: autoEdge }} />}
            </div>
            <label className="flex items-center justify-between gap-3 text-[13px]">
              <span>
                Auch als Elytra
                <span className="block text-[11.5px] text-dim">Die Flügel zeigen dasselbe Motiv.</span>
              </span>
              <Toggle checked={st.elytra} onChange={(elytra) => set({ elytra })} label="Auch als Elytra" />
            </label>
          </section>

          {source.frames.length > 1 && (
            <section className="grid gap-2.5">
              <h3 className="text-[13px] font-semibold">
                Animation <span className="font-mono text-[12.5px] font-normal text-muted">{frameCount} Bilder · {ms} ms</span>
              </h3>
              <Range label="Erstes Bild" value={st.trimStart} min={0} max={source.frames.length - 1} step={1} display={String(st.trimStart + 1)} onChange={(v) => set({ trimStart: Math.min(v, st.trimEnd) })} />
              <Range label="Letztes Bild" value={st.trimEnd} min={0} max={source.frames.length - 1} step={1} display={String(st.trimEnd + 1)} onChange={(v) => set({ trimEnd: Math.max(v, st.trimStart) })} />
              <Range label="Geschwindigkeit" value={st.speed} min={0.25} max={4} step={0.05} display={`${st.speed.toFixed(2)}×`} onChange={(speed) => set({ speed })} />
              {(source.sampled || thinned) && (
                <p className="text-[11.5px] text-dim">
                  {thinned ? `Bei ${64 * st.scale}×${32 * st.scale} passen höchstens ${frameCount} Bilder, die Animation wird gleichmäßig ausgedünnt.` : `Das GIF hat mehr als ${LIMITS.maxFrames} Bilder, es wurde gleichmäßig ausgedünnt.`}
                </p>
              )}
            </section>
          )}

          <section className="grid gap-2.5">
            <h3 className="text-[13px] font-semibold">Auflösung</h3>
            <Segmented value={String(st.scale)} onChange={(v) => set({ scale: Number(v) as EditorState["scale"] })} options={SCALES.map((s) => ({ value: String(s), label: `${64 * s}×${32 * s}` }))} />
            <p className={`text-[13px] ${tooBig ? "text-err" : "text-muted"}`}>
              {size == null ? "Größe wird berechnet …" : `Dateigröße ${kb(size)} von höchstens 8 MB.`}
              {tooBig && " Wähle eine kleinere Auflösung oder kürze die Animation."}
            </p>
          </section>

          <div className="flex gap-2 pb-2">
            <Button variant="primary" icon={<Save className="size-4" />} loading={saving} disabled={tooBig} onClick={save}>
              Speichern
            </Button>
            <Button variant="ghost" icon={<X className="size-4" />} onClick={() => onDone()}>
              Abbrechen
            </Button>
          </div>
        </div>
      </motion.div>
    </motion.div>
  );
}

function Range({ label, value, min, max, step, display, onChange }: { label: string; value: number; min: number; max: number; step: number; display: string; onChange: (v: number) => void }) {
  return (
    <label className="grid gap-1">
      <span className="flex justify-between text-[13px]">
        <span className="text-muted">{label}</span>
        <span className="font-mono text-[12.5px]">{display}</span>
      </span>
      <input type="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} className="accent-[var(--color-accent)]" />
    </label>
  );
}

function ColorInput({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  return <input type="color" aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} className="h-8 w-10 cursor-pointer rounded-lg border border-line-2 bg-transparent p-0.5" />;
}
