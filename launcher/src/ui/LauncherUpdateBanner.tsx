import DOMPurify from "dompurify";
import { ArrowDownToLine, ChevronDown } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { marked } from "marked";
import { useMemo, useState } from "react";
import { openUrl } from "../lib/ipc";
import { useStore } from "../lib/store";
import { Button, EASE } from "./kit";

/** Notice under the title bar when a new launcher version is out. */
export function LauncherUpdateBanner() {
  const { launcherUpdate: update, installLauncherUpdate, postponeLauncherUpdate, tasks } = useStore();
  const [notesOpen, setNotesOpen] = useState(false);
  const installing = tasks.some((t) => t.label === "SlopClient-Update" && !t.done);
  const html = useMemo(() => {
    if (!update?.notes) return "";
    const raw = marked.parse(update.notes, { async: false }) as string;
    return DOMPurify.sanitize(raw, { FORBID_TAGS: ["style", "form", "input"], FORBID_ATTR: ["style"] });
  }, [update?.notes]);

  return (
    <AnimatePresence initial={false}>
      {update && (
        <motion.div
          initial={{ height: 0, opacity: 0 }}
          animate={{ height: "auto", opacity: 1 }}
          exit={{ height: 0, opacity: 0 }}
          transition={{ duration: 0.4, ease: EASE }}
          className="relative z-20 mx-4 mb-2 shrink-0 overflow-hidden"
        >
          <div className="glass overflow-hidden rounded-[14px]">
            <div className="flex items-center gap-3 bg-accent/[0.08] px-4 py-2.5 text-[13px]">
              <ArrowDownToLine className="size-4 shrink-0 text-accent-2" />
              <span className="min-w-0 flex-1 truncate">
                <b className="text-accent-2">SlopClient {update.version} ist da</b>
                <span className="text-muted"> · du hast {update.currentVersion}</span>
              </span>
              {html && (
                <Button size="sm" variant="ghost" onClick={() => setNotesOpen((o) => !o)} aria-expanded={notesOpen}>
                  {notesOpen ? "Änderungen ausblenden" : "Änderungen ansehen"}
                  <motion.span animate={{ rotate: notesOpen ? 180 : 0 }} transition={{ duration: 0.25, ease: EASE }} className="ml-1 inline-flex">
                    <ChevronDown className="size-3.5" />
                  </motion.span>
                </Button>
              )}
              <Button variant="primary" size="sm" loading={installing} onClick={installLauncherUpdate}>
                Jetzt aktualisieren
              </Button>
              <Button size="sm" variant="ghost" disabled={installing} onClick={postponeLauncherUpdate}>
                Später
              </Button>
            </div>
            <AnimatePresence initial={false}>
              {notesOpen && html && (
                <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.3, ease: EASE }}>
                  <div
                    className="md-body max-h-64 overflow-auto border-t border-line px-4 pt-2 pb-3 text-[12.5px]"
                    onClick={(e) => {
                      const a = (e.target as HTMLElement).closest("a");
                      if (a?.href) {
                        e.preventDefault();
                        if (/^https?:\/\//.test(a.href)) openUrl(a.href);
                      }
                    }}
                    dangerouslySetInnerHTML={{ __html: html }}
                  />
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
