import { Trash2 } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { formatBytes, relativeTime } from "../lib/format";
import { thumbnail } from "../lib/thumbs";
import type { Screenshot } from "../lib/types";
import { Button, EASE, IconButton, rise } from "./kit";

/** A gallery tile: thumbnail (loaded once it scrolls into view), name, age and size. */
export function ScreenshotTile({
  instanceId,
  shot,
  label,
  onOpen,
  onDelete,
}: {
  instanceId: string;
  shot: Screenshot;
  /** Shown over the picture, e.g. the instance on the page across all instances. */
  label?: string;
  onOpen: () => void;
  /** Without it the tile has no delete button (the viewer still deletes). */
  onDelete?: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const el = useRef<HTMLDivElement>(null);

  // Deleting asks for a second click within a few seconds.
  useEffect(() => {
    if (!confirm) return;
    const t = setTimeout(() => setConfirm(false), 3000);
    return () => clearTimeout(t);
  }, [confirm]);

  useEffect(() => {
    const node = el.current;
    if (!node) return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return;
        io.disconnect();
        thumbnail(instanceId, shot.fileName).then(setUrl).catch(() => setUrl(""));
      },
      { rootMargin: "200px" },
    );
    io.observe(node);
    return () => io.disconnect();
  }, [instanceId, shot.fileName]);

  return (
    <motion.div ref={el} variants={rise} className="group glass relative overflow-hidden rounded-[14px]">
      <button className="relative block aspect-video w-full overflow-hidden bg-black/40" onClick={onOpen} aria-label={`${shot.fileName} ansehen`}>
        {url ? (
          <motion.img initial={{ opacity: 0, scale: 1.04 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.5, ease: EASE }} src={url} alt="" className="size-full object-cover transition-transform duration-500 group-hover:scale-[1.04]" />
        ) : (
          <div className="size-full animate-pulse bg-white/[0.03]" />
        )}
        {label && <span className="absolute top-2 left-2 max-w-[calc(100%-16px)] truncate rounded-md bg-black/55 px-2 py-0.5 text-[11px] font-semibold text-text backdrop-blur-md">{label}</span>}
      </button>
      <div className="flex items-center gap-2 px-3 py-2">
        <div className="min-w-0 flex-1">
          <div className="truncate font-mono text-[11.5px]">{shot.fileName.replace(/\.png$/i, "")}</div>
          <div className="text-[11px] text-muted">
            {relativeTime(shot.takenAt)} · {formatBytes(shot.sizeBytes)}
          </div>
        </div>
        {onDelete &&
          (confirm ? (
            <Button size="sm" variant="danger" onClick={onDelete}>
              Löschen
            </Button>
          ) : (
            <IconButton label="Screenshot löschen" className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" onClick={() => setConfirm(true)}>
              <Trash2 className="size-3.5" />
            </IconButton>
          ))}
      </div>
    </motion.div>
  );
}
