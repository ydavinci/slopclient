import { ChevronLeft, ChevronRight, Copy, ExternalLink, FolderOpen, Minus, Plus, Trash2, X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from "react";
import { createPortal } from "react-dom";
import { api, screenshotUrl } from "../lib/ipc";
import { formatBytes } from "../lib/format";
import { useStore } from "../lib/store";
import { thumbnail } from "../lib/thumbs";
import type { Screenshot } from "../lib/types";
import { Button, EASE, IconButton, Kbd } from "./kit";

export type ViewerShot = Screenshot & { instanceId: string; instanceName?: string };

const MIN_ZOOM = 1;
const MAX_ZOOM = 8;

interface View {
  zoom: number;
  /** Offset of the picture's centre from the stage centre, in screen pixels. */
  x: number;
  y: number;
}

const FIT: View = { zoom: 1, x: 0, y: 0 };

/**
 * Full-screen viewer over the launcher: the screenshot fitted to the window, zoom with
 * the wheel or a double click, drag to move, arrows or the film strip to go on.
 */
export function ScreenshotViewer({
  shots,
  index,
  onIndex,
  onClose,
  onDeleted,
}: {
  shots: ViewerShot[];
  index: number;
  onIndex: (index: number) => void;
  onClose: () => void;
  /** Called after the file was deleted; the parent drops it from `shots`. */
  onDeleted: (shot: ViewerShot) => void;
}) {
  const { toast } = useStore();
  const shot = shots[index];
  const [view, setView] = useState<View>(FIT);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [confirm, setConfirm] = useState(false);
  const stage = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; vx: number; vy: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const url = shot ? screenshotUrl(shot.instanceId, shot.fileName) : "";

  // A new picture starts fitted, and the delete question starts over.
  useEffect(() => {
    setView(FIT);
    setSize(null);
    setConfirm(false);
  }, [url]);

  useEffect(() => {
    if (!confirm) return;
    const t = setTimeout(() => setConfirm(false), 3000);
    return () => clearTimeout(t);
  }, [confirm]);

  // The neighbours load in the background, so stepping through feels instant.
  useEffect(() => {
    for (const n of [index - 1, index + 1]) {
      const s = shots[n];
      if (s) new Image().src = screenshotUrl(s.instanceId, s.fileName);
    }
  }, [index, shots]);

  const go = useCallback(
    (step: number) => {
      const next = index + step;
      if (next >= 0 && next < shots.length) onIndex(next);
    },
    [index, shots.length, onIndex],
  );

  /** Zooms by `factor` keeping the point under (cx, cy) (stage coordinates from its centre) still. */
  const zoomAt = useCallback((factor: number, cx = 0, cy = 0) => {
    setView((v) => {
      const zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, v.zoom * factor));
      if (zoom === MIN_ZOOM) return FIT;
      const k = zoom / v.zoom;
      return { zoom, x: cx - (cx - v.x) * k, y: cy - (cy - v.y) * k };
    });
  }, []);

  const copy = useCallback(async () => {
    if (!shot) return;
    try {
      const blob = await (await fetch(url)).blob();
      const png = blob.type === "image/png" ? blob : await toPng(url);
      await navigator.clipboard.write([new ClipboardItem({ "image/png": png })]);
      toast("Screenshot in die Zwischenablage kopiert", "success");
    } catch (e) {
      toast(`Kopieren ging nicht: ${e}`, "error");
    }
  }, [shot, url, toast]);

  const remove = useCallback(async () => {
    if (!shot) return;
    try {
      await api.screenshotDelete(shot.instanceId, shot.fileName);
      toast("Screenshot gelöscht");
      onDeleted(shot);
    } catch (e) {
      toast(String(e), "error");
    }
  }, [shot, toast, onDeleted]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowLeft") go(-1);
      else if (e.key === "ArrowRight") go(1);
      else if (e.key === "+" || e.key === "=") zoomAt(1.5);
      else if (e.key === "-") zoomAt(1 / 1.5);
      else if (e.key === "0") setView(FIT);
      else if (e.key === "Delete") {
        if (confirm) remove();
        else setConfirm(true);
      } else if (e.key.toLowerCase() === "c" && (e.ctrlKey || e.metaKey)) copy();
      else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, go, zoomAt, copy, remove, confirm]);

  // Nothing left to show (the last one was deleted).
  useEffect(() => {
    if (!shot) onClose();
  }, [shot, onClose]);
  if (!shot) return null;

  const fromCentre = (clientX: number, clientY: number) => {
    const r = stage.current!.getBoundingClientRect();
    return [clientX - r.left - r.width / 2, clientY - r.top - r.height / 2] as const;
  };

  const onWheel = (e: ReactWheelEvent) => {
    const [cx, cy] = fromCentre(e.clientX, e.clientY);
    zoomAt(e.deltaY < 0 ? 1.2 : 1 / 1.2, cx, cy);
  };

  const onPointerDown = (e: ReactPointerEvent) => {
    if (view.zoom === 1 || e.button !== 0) return;
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y };
    setDragging(true);
  };
  const onPointerMove = (e: ReactPointerEvent) => {
    const d = drag.current;
    if (!d) return;
    setView((v) => ({ ...v, x: d.vx + e.clientX - d.x, y: d.vy + e.clientY - d.y }));
  };
  const onPointerUp = () => {
    drag.current = null;
    setDragging(false);
  };

  const onDoubleClick = (e: React.MouseEvent) => {
    if (view.zoom > 1) return setView(FIT);
    const [cx, cy] = fromCentre(e.clientX, e.clientY);
    zoomAt(2.5, cx, cy);
  };

  const date = new Date(shot.takenAt * 1000).toLocaleString("de-DE", { weekday: "short", day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" });

  return createPortal(
    <motion.div
      role="dialog"
      aria-modal="true"
      aria-label={`Screenshot ${shot.fileName}`}
      className="fixed inset-0 z-50 flex flex-col bg-[#060509]/90 backdrop-blur-xl"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.25 }}
    >
      {/* Title bar: what this is and what to do with it. Drags the window like the shell's. */}
      <div data-tauri-drag-region className="relative z-10 flex shrink-0 items-center gap-3 px-5 pt-3 pb-2">
        <div className="no-drag min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-mono text-[13px] font-semibold">{shot.fileName.replace(/\.png$/i, "")}</span>
            {shot.instanceName && <span className="shrink-0 rounded-md bg-accent/18 px-2 py-0.5 text-[11.5px] font-semibold text-accent-2">{shot.instanceName}</span>}
          </div>
          <div className="mt-0.5 text-[12px] text-muted">
            {date} · {size ? `${size.w} × ${size.h}` : "…"} · {formatBytes(shot.sizeBytes)} · {index + 1} von {shots.length}
          </div>
        </div>
        <div className="no-drag flex items-center gap-1.5">
          <Button size="sm" icon={<Copy className="size-3.5" />} onClick={copy} title="Bild kopieren (Strg+C)">
            Kopieren
          </Button>
          <Button size="sm" icon={<FolderOpen className="size-3.5" />} onClick={() => api.screenshotReveal(shot.instanceId, shot.fileName).catch((e) => toast(String(e), "error"))}>
            Im Ordner zeigen
          </Button>
          <IconButton label="Mit dem Standardprogramm öffnen" onClick={() => api.openPath(shot.path).catch((e) => toast(String(e), "error"))}>
            <ExternalLink className="size-4" />
          </IconButton>
          {confirm ? (
            <Button size="sm" variant="danger" onClick={remove}>
              Wirklich löschen
            </Button>
          ) : (
            <IconButton label="Löschen (Entf)" onClick={() => setConfirm(true)}>
              <Trash2 className="size-4" />
            </IconButton>
          )}
          <div className="mx-1 h-5 w-px bg-line-2" />
          <IconButton label="Schließen (Esc)" onClick={onClose}>
            <X className="size-4" />
          </IconButton>
        </div>
      </div>

      {/* Stage */}
      <div
        ref={stage}
        className={`relative min-h-0 flex-1 overflow-hidden ${view.zoom > 1 ? (dragging ? "cursor-grabbing" : "cursor-grab") : "cursor-zoom-in"}`}
        onWheel={onWheel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onDoubleClick={onDoubleClick}
      >
        <AnimatePresence initial={false}>
          <motion.div
            key={url}
            initial={{ opacity: 0, scale: 0.985 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.3, ease: EASE }}
            className="pointer-events-none absolute inset-0 flex items-center justify-center px-[60px] py-3"
          >
            <img
              src={url}
              alt={shot.fileName}
              draggable={false}
              onLoad={(e) => setSize({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })}
              className="max-h-full max-w-full rounded-[10px] object-contain shadow-[0_30px_80px_-20px_rgb(0_0_0/0.9)] select-none"
              style={{
                transform: `translate(${view.x}px, ${view.y}px) scale(${view.zoom})`,
                transition: dragging ? "none" : "transform 180ms cubic-bezier(0.16, 1, 0.3, 1)",
                imageRendering: view.zoom >= 3 ? "pixelated" : "auto",
              }}
            />
          </motion.div>
        </AnimatePresence>

        <NavButton side="left" disabled={index === 0} onClick={() => go(-1)} />
        <NavButton side="right" disabled={index === shots.length - 1} onClick={() => go(1)} />

        {/* Zoom control */}
        <div className="glass absolute right-4 bottom-3 flex items-center gap-0.5 rounded-xl p-1" onDoubleClick={(e) => e.stopPropagation()}>
          <IconButton label="Verkleinern (-)" onClick={() => zoomAt(1 / 1.5)} disabled={view.zoom === 1}>
            <Minus className="size-3.5" />
          </IconButton>
          <button onClick={() => setView(FIT)} title="Einpassen (0)" className="w-14 rounded-lg py-1 text-center font-mono text-[11.5px] text-muted transition-colors hover:bg-white/[0.06] hover:text-text">
            {view.zoom === 1 ? "Passend" : `${Math.round(view.zoom * 100)} %`}
          </button>
          <IconButton label="Vergrößern (+)" onClick={() => zoomAt(1.5)} disabled={view.zoom === MAX_ZOOM}>
            <Plus className="size-3.5" />
          </IconButton>
        </div>
        <div className={`pointer-events-none absolute bottom-4 left-5 hidden items-center gap-1.5 text-[11.5px] text-dim transition-opacity lg:flex ${view.zoom > 1 ? "opacity-0" : ""}`}>
          <Kbd>←</Kbd>
          <Kbd>→</Kbd> blättern · Mausrad zoomt · <Kbd>Entf</Kbd> löscht
        </div>
      </div>

      <FilmStrip shots={shots} index={index} onIndex={onIndex} />
    </motion.div>,
    document.body,
  );
}

function NavButton({ side, disabled, onClick }: { side: "left" | "right"; disabled: boolean; onClick: () => void }) {
  return (
    <button
      aria-label={side === "left" ? "Vorheriger Screenshot" : "Nächster Screenshot"}
      onClick={onClick}
      onDoubleClick={(e) => e.stopPropagation()}
      onPointerDown={(e) => e.stopPropagation()}
      disabled={disabled}
      className={`glass absolute top-1/2 ${side === "left" ? "left-4" : "right-4"} grid size-11 -translate-y-1/2 place-items-center rounded-full text-text transition duration-200 hover:scale-105 hover:bg-white/[0.1] active:scale-95 disabled:pointer-events-none disabled:opacity-0`}
    >
      {side === "left" ? <ChevronLeft className="size-5" /> : <ChevronRight className="size-5" />}
    </button>
  );
}

/** Thumbnails along the bottom; the current one is centred and ringed. */
function FilmStrip({ shots, index, onIndex }: { shots: ViewerShot[]; index: number; onIndex: (i: number) => void }) {
  const strip = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = strip.current?.children[index] as HTMLElement | undefined;
    el?.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
  }, [index]);
  if (shots.length < 2) return <div className="h-4 shrink-0" />;
  return (
    <div ref={strip} className="flex shrink-0 gap-2 overflow-x-auto px-5 pt-2 pb-4 [scrollbar-width:none]">
      {shots.map((s, i) => (
        <StripThumb key={`${s.instanceId}/${s.fileName}`} shot={s} active={i === index} onClick={() => onIndex(i)} />
      ))}
    </div>
  );
}

function StripThumb({ shot, active, onClick }: { shot: ViewerShot; active: boolean; onClick: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  const el = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const node = el.current;
    if (!node) return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return;
        io.disconnect();
        thumbnail(shot.instanceId, shot.fileName).then(setUrl).catch(() => setUrl(""));
      },
      { rootMargin: "300px" },
    );
    io.observe(node);
    return () => io.disconnect();
  }, [shot.instanceId, shot.fileName]);
  return (
    <button
      ref={el}
      onClick={onClick}
      aria-label={shot.fileName}
      aria-current={active ? "true" : undefined}
      className={`relative aspect-video h-14 shrink-0 overflow-hidden rounded-lg bg-black/40 transition duration-300 ${active ? "opacity-100" : "opacity-45 hover:opacity-80"}`}
    >
      {url && <img src={url} alt="" className="size-full object-cover" />}
      {active && <motion.span layoutId="filmstrip-active" transition={{ duration: 0.35, ease: EASE }} className="pointer-events-none absolute inset-0 rounded-lg shadow-[inset_0_0_0_2px_var(--color-accent-2),0_0_14px_-2px_var(--color-accent)]" />}
    </button>
  );
}

/** Re-encodes an image (e.g. the browser preview's SVG) as PNG for the clipboard. */
async function toPng(url: string): Promise<Blob> {
  const img = new Image();
  img.src = url;
  await img.decode();
  const canvas = document.createElement("canvas");
  canvas.width = img.naturalWidth;
  canvas.height = img.naturalHeight;
  canvas.getContext("2d")!.drawImage(img, 0, 0);
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("kein Bild"))), "image/png"));
}
