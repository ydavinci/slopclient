import { Check } from "lucide-react";
import { motion } from "motion/react";
import { useMemo, useRef, useState } from "react";
import { DEFAULT_HUE, HUE_PRESETS, accentFor } from "../lib/accent";

const SIZE = 168;
const RING = 18;

/**
 * Colour wheel for the accent: drag the knob around the ring (or use the arrow keys),
 * or pick a preset. `onPreview` runs while dragging, `onCommit` when the choice is final.
 */
export function HueRing({ hue, onPreview, onCommit }: { hue: number; onPreview: (h: number) => void; onCommit: (h: number) => void }) {
  const ring = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  // The ring shows each hue as the accent it would produce, not raw HSL.
  const gradient = useMemo(() => {
    const stops = Array.from({ length: 25 }, (_, i) => `${accentFor(i * 15)} ${i * 15}deg`);
    return `conic-gradient(from 90deg, ${stops.join(", ")})`;
  }, []);
  const preset = HUE_PRESETS.find((p) => Math.abs(((p.hue - hue + 540) % 360) - 180) < 4);

  function hueAt(clientX: number, clientY: number) {
    const r = ring.current!.getBoundingClientRect();
    const deg = (Math.atan2(clientY - (r.top + r.height / 2), clientX - (r.left + r.width / 2)) * 180) / Math.PI;
    return Math.round((deg + 360) % 360);
  }

  const angle = (hue * Math.PI) / 180;
  const radius = SIZE / 2 - RING / 2;

  return (
    <div className="flex items-center gap-7">
      <div
        ref={ring}
        role="slider"
        tabIndex={0}
        aria-label="Akzentfarbe"
        aria-valuemin={0}
        aria-valuemax={359}
        aria-valuenow={hue}
        aria-valuetext={preset?.name ?? `${hue}°`}
        className="relative shrink-0 cursor-pointer touch-none rounded-full focus-visible:outline-2 focus-visible:outline-offset-4"
        style={{ width: SIZE, height: SIZE }}
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          setDragging(true);
          onPreview(hueAt(e.clientX, e.clientY));
        }}
        onPointerMove={(e) => dragging && onPreview(hueAt(e.clientX, e.clientY))}
        onPointerUp={(e) => {
          setDragging(false);
          onCommit(hueAt(e.clientX, e.clientY));
        }}
        onKeyDown={(e) => {
          const step = e.shiftKey ? 15 : 3;
          if (e.key === "ArrowRight" || e.key === "ArrowUp") onCommit((hue + step) % 360);
          else if (e.key === "ArrowLeft" || e.key === "ArrowDown") onCommit((hue - step + 360) % 360);
        }}
      >
        <div
          className="absolute inset-0 rounded-full"
          style={{ background: gradient, WebkitMask: `radial-gradient(circle, transparent ${SIZE / 2 - RING - 1}px, #000 ${SIZE / 2 - RING}px)`, mask: `radial-gradient(circle, transparent ${SIZE / 2 - RING - 1}px, #000 ${SIZE / 2 - RING}px)` }}
        />
        {/* Centre: the current accent as a chroma disc. */}
        <div className="absolute grid place-items-center rounded-full" style={{ inset: RING + 10 }}>
          <motion.div key={Math.round(hue / 8)} initial={{ scale: 0.92, opacity: 0.6 }} animate={{ scale: 1, opacity: 1 }} transition={{ duration: 0.35 }} className="chroma size-full rounded-full shadow-[0_10px_34px_-10px_var(--color-accent)]" />
          <span className="absolute text-center text-[12px] font-bold text-white drop-shadow">{preset?.name ?? `${hue}°`}</span>
        </div>
        <motion.span
          className="pointer-events-none absolute size-[22px] rounded-full border-[3px] border-white shadow-[0_2px_10px_rgb(0_0_0/0.5)]"
          animate={{ x: SIZE / 2 + Math.cos(angle) * radius - 11, y: SIZE / 2 + Math.sin(angle) * radius - 11, scale: dragging ? 1.15 : 1 }}
          transition={dragging ? { duration: 0 } : { type: "spring", stiffness: 420, damping: 32 }}
          style={{ left: 0, top: 0, background: accentFor(hue) }}
        />
      </div>

      <div className="grid gap-3">
        <div className="grid grid-cols-4 gap-2">
          {HUE_PRESETS.map((p) => {
            const active = preset?.name === p.name;
            return (
              <button
                key={p.name}
                onClick={() => onCommit(p.hue)}
                aria-pressed={active}
                className={`flex items-center gap-2 rounded-[10px] border px-2.5 py-1.5 text-[12px] font-semibold transition duration-200 ${active ? "border-white/30 bg-white/[0.08] text-text" : "border-line text-muted hover:border-line-2 hover:text-text"}`}
              >
                <span className="grid size-4 place-items-center rounded-full" style={{ background: accentFor(p.hue) }}>
                  {active && <Check className="size-2.5 text-white" />}
                </span>
                {p.name}
              </button>
            );
          })}
        </div>
        <p className="max-w-xs text-[12px] leading-relaxed text-muted">
          Färbt den ganzen Launcher und den Client-Mod um: Akzente, Panels, Hintergrund und Menü-Shader. Im Spiel gilt sie ab dem nächsten Start.
          {hue !== DEFAULT_HUE && (
            <button className="ml-1 font-semibold text-accent-2 hover:underline" onClick={() => onCommit(DEFAULT_HUE)}>
              Zurück auf Lila
            </button>
          )}
        </p>
      </div>
    </div>
  );
}
