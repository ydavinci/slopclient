import DOMPurify from "dompurify";
import { ChevronDown } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { marked } from "marked";
import { useState } from "react";
import { api, openUrl } from "../lib/ipc";
import type { UpdateInfo } from "../lib/types";
import { EASE, Spinner } from "./kit";

type Log = { state: "loading" } | { state: "ok"; html: string } | { state: "error"; error: string };

/** The pending updates with their changelogs, opened one at a time. */
export function UpdateReview({ updates }: { updates: UpdateInfo[] }) {
  const [open, setOpen] = useState<string | null>(null);
  const [logs, setLogs] = useState<Record<string, Log>>({});

  function toggle(u: UpdateInfo) {
    const key = u.entry.fileName;
    setOpen(open === key ? null : key);
    if (logs[key] || u.entry.source.type === "local") return;
    setLogs((l) => ({ ...l, [key]: { state: "loading" } }));
    const source = u.entry.source.type;
    api
      .versionChangelog(source, String(u.entry.source.projectId), u.new_version.id)
      .then((text) => {
        const raw = source === "modrinth" ? (marked.parse(text, { async: false }) as string) : text;
        const html = DOMPurify.sanitize(raw, { FORBID_TAGS: ["style", "form", "input"], FORBID_ATTR: ["style"] });
        setLogs((l) => ({ ...l, [key]: { state: "ok", html } }));
      })
      .catch((e) => setLogs((l) => ({ ...l, [key]: { state: "error", error: String(e) } })));
  }

  return (
    <ul className="grid gap-1 border-b border-line bg-black/20 px-3 py-2">
      {updates.map((u) => {
        const key = u.entry.fileName;
        const expanded = open === key;
        const log = logs[key];
        return (
          <li key={key} className="overflow-hidden rounded-[10px]">
            <button onClick={() => toggle(u)} aria-expanded={expanded} className="flex w-full items-center gap-3 rounded-[10px] px-2.5 py-1.5 text-left text-[12.5px] transition-colors duration-200 hover:bg-white/[0.04]">
              <span className="min-w-0 flex-1 truncate font-semibold">{u.entry.name}</span>
              <span className="truncate font-mono text-[11.5px] text-muted">
                {u.entry.version ?? "?"} → <span className="text-accent-2">{u.new_version.version_number}</span>
              </span>
              {u.new_version.version_type !== "release" && <span className="rounded-md bg-warn/12 px-1.5 text-[11px] font-semibold text-warn">{u.new_version.version_type}</span>}
              <motion.span animate={{ rotate: expanded ? 180 : 0 }} transition={{ duration: 0.25, ease: EASE }} className="text-muted">
                <ChevronDown className="size-3.5" />
              </motion.span>
            </button>
            <AnimatePresence initial={false}>
              {expanded && (
                <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.3, ease: EASE }}>
                  <div className="max-h-64 overflow-auto px-3 pt-1 pb-3">
                    {!log || log.state === "loading" ? (
                      <Spinner className="size-4" />
                    ) : log.state === "error" ? (
                      <p className="text-[12px] text-err">{log.error}</p>
                    ) : (
                      <div
                        className="md-body text-[12.5px]"
                        onClick={(e) => {
                          const a = (e.target as HTMLElement).closest("a");
                          if (a?.href) {
                            e.preventDefault();
                            openUrl(a.href);
                          }
                        }}
                        dangerouslySetInnerHTML={{ __html: log.html.trim() || "<p>Der Autor hat zu dieser Version nichts geschrieben.</p>" }}
                      />
                    )}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </li>
        );
      })}
    </ul>
  );
}
