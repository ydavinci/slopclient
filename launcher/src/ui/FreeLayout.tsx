import { Move } from "lucide-react";
import { motion } from "motion/react";
import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import type { HomeLayout, HomeBlock } from "../lib/types";
import { EASE } from "./kit";

/** Gap to the edges of the page in pixels. */
const PAD = 40;

/** Starting points for a custom layout, close to the left and centred presets. */
export function presetLayout(centered: boolean): HomeLayout {
  const x = centered ? 0.5 : 0;
  return { hero: [x, 0.6], actions: [x, 0.8], strip: [x, 1] };
}

/**
 * The start page with its blocks placed freely. Positions are fractions of the free
 * space (0 = left/top edge, 1 = right/bottom), so a layout survives window resizes.
 * In edit mode every block shows a frame and can be dragged.
 */
export function FreeLayout({ layout, editing, onChange, blocks }: { layout: HomeLayout; editing: boolean; onChange: (layout: HomeLayout) => void; blocks: Record<HomeBlock, { label: string; node: ReactNode }> }) {
  const box = useRef<HTMLDivElement>(null);
  const els = useRef<Partial<Record<HomeBlock, HTMLDivElement | null>>>({});
  const [sizes, setSizes] = useState<Record<string, [number, number]>>({});
  const [draft, setDraft] = useState<HomeLayout | null>(null);
  const drag = useRef<{ id: HomeBlock; x: number; y: number; left: number; top: number } | null>(null);
  const current = draft ?? layout;

  // Container and block sizes decide where a fraction lands.
  useLayoutEffect(() => {
    const measure = () => {
      const next: Record<string, [number, number]> = {};
      if (box.current) next.box = [box.current.clientWidth, box.current.clientHeight];
      for (const [id, el] of Object.entries(els.current)) if (el) next[id] = [el.offsetWidth, el.offsetHeight];
      setSizes(next);
    };
    measure();
    const ro = new ResizeObserver(measure);
    if (box.current) ro.observe(box.current);
    for (const el of Object.values(els.current)) if (el) ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const [bw, bh] = sizes.box ?? [0, 0];
  const place = (id: HomeBlock) => {
    const [w, h] = sizes[id] ?? [0, 0];
    const [fx, fy] = current[id];
    return { left: PAD + fx * Math.max(0, bw - w - 2 * PAD), top: PAD + fy * Math.max(0, bh - h - 2 * PAD), freeW: bw - w - 2 * PAD, freeH: bh - h - 2 * PAD };
  };

  return (
    <div ref={box} className="absolute inset-0">
      {(Object.keys(blocks) as HomeBlock[]).map((id) => {
        const { left, top } = place(id);
        return (
          <div
            key={id}
            ref={(el) => {
              els.current[id] = el;
            }}
            className={`absolute max-w-[calc(100%-80px)] ${editing ? "cursor-move touch-none select-none" : ""}`}
            style={{ left, top, visibility: sizes.box ? "visible" : "hidden" }}
            onPointerDown={(e) => {
              if (!editing) return;
              e.currentTarget.setPointerCapture(e.pointerId);
              drag.current = { id, x: e.clientX, y: e.clientY, left, top };
            }}
            onPointerMove={(e) => {
              const d = drag.current;
              if (!d || d.id !== id) return;
              const p = place(id);
              const fx = p.freeW > 0 ? Math.min(1, Math.max(0, (d.left + e.clientX - d.x - PAD) / p.freeW)) : 0;
              const fy = p.freeH > 0 ? Math.min(1, Math.max(0, (d.top + e.clientY - d.y - PAD) / p.freeH)) : 0;
              setDraft({ ...current, [id]: [fx, fy] });
            }}
            onPointerUp={() => {
              if (!drag.current) return;
              drag.current = null;
              if (draft) onChange(draft);
              setDraft(null);
            }}
          >
            {editing && (
              <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ duration: 0.25, ease: EASE }}
                className="pointer-events-none absolute -inset-3 rounded-2xl border-2 border-dashed border-accent-2/70 bg-accent/[0.06]"
              >
                <span className="chroma absolute -top-3 left-3 flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold text-white">
                  <Move className="size-3" />
                  {blocks[id].label}
                </span>
              </motion.div>
            )}
            <div className={editing ? "pointer-events-none" : ""}>{blocks[id].node}</div>
          </div>
        );
      })}
    </div>
  );
}
