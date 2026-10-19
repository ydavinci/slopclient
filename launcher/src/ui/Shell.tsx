import { Check, CircleCheck, Compass, House, Info, LayoutGrid, LogIn, Minus, Settings, Shirt, Square, Trash2, TriangleAlert, X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, appWindow } from "../lib/ipc";
import { megabytes } from "../lib/format";
import { useStore, type Route } from "../lib/store";
import { Avatar, EASE, SPRING } from "./kit";

function useClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(t);
  }, []);
  return now.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
}

/** Client watermark plus window controls; the whole bar drags the window. */
export function TitleBar() {
  const { overview } = useStore();
  const time = useClock();
  const account = overview?.accounts.find((a) => a.uuid === overview.activeAccount) ?? overview?.accounts[0];
  const parts = [`v0.1.0`, account?.username ?? "nicht angemeldet", `${overview?.instances.length ?? 0} Instanzen`, time];
  return (
    <header data-tauri-drag-region className="relative z-20 flex h-12 shrink-0 items-center justify-between pr-2 pl-4">
      <motion.div
        data-tauri-drag-region
        initial={{ opacity: 0, x: -12 }}
        animate={{ opacity: 1, x: 0 }}
        transition={{ duration: 0.6, ease: EASE }}
        className="glass flex items-center gap-2.5 rounded-[11px] py-1.5 pr-3.5 pl-2"
      >
        <img src="/logo.svg" alt="" className="size-6" />
        <span className="chroma-text text-[15px] font-extrabold tracking-tight">DaClient</span>
        {parts.map((p) => (
          <span key={p} className="flex items-center gap-2.5 text-xs text-muted">
            <span className="h-3 w-px bg-line-2" />
            <span className={/^\d/.test(p) || p.startsWith("v") ? "font-mono" : ""}>{p}</span>
          </span>
        ))}
      </motion.div>
      <div className="no-drag flex gap-1">
        <WindowButton label="Minimieren" onClick={appWindow.minimize}>
          <Minus className="size-4" />
        </WindowButton>
        <WindowButton label="Maximieren" onClick={appWindow.toggleMaximize}>
          <Square className="size-3" />
        </WindowButton>
        <WindowButton label="Schließen" onClick={appWindow.close} danger>
          <X className="size-4" />
        </WindowButton>
      </div>
    </header>
  );
}

function WindowButton({ label, onClick, danger, children }: { label: string; onClick: () => void; danger?: boolean; children: ReactNode }) {
  return (
    <button
      aria-label={label}
      title={label}
      onClick={onClick}
      className={`grid size-8 place-items-center rounded-lg text-muted transition duration-200 ${danger ? "hover:bg-err hover:text-white" : "hover:bg-white/[0.08] hover:text-text"}`}
    >
      {children}
    </button>
  );
}

const NAV: { page: Route["page"]; label: string; icon: ReactNode }[] = [
  { page: "home", label: "Start", icon: <House className="size-[18px]" /> },
  { page: "instances", label: "Instanzen", icon: <LayoutGrid className="size-[18px]" /> },
  { page: "browse", label: "Entdecken", icon: <Compass className="size-[18px]" /> },
  { page: "cosmetics", label: "Kosmetik", icon: <Shirt className="size-[18px]" /> },
];

/** Floating category rail, the client's category switcher. */
export function NavRail() {
  const { route, navigate } = useStore();
  const active = route.page === "instance" ? "instances" : route.page;
  return (
    <motion.nav
      initial={{ opacity: 0, x: -16 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 0.6, ease: EASE, delay: 0.05 }}
      className="glass relative z-20 my-1 ml-4 flex w-[60px] shrink-0 flex-col items-center gap-1 rounded-2xl py-2.5"
    >
      {NAV.map((item) => (
        <RailButton key={item.page} label={item.label} active={active === item.page} onClick={() => navigate({ page: item.page } as Route)}>
          {item.icon}
        </RailButton>
      ))}
      <div className="flex-1" />
      <RailButton label="Einstellungen" active={active === "settings"} onClick={() => navigate({ page: "settings" })}>
        <Settings className="size-[18px]" />
      </RailButton>
      <AccountMenu />
    </motion.nav>
  );
}

function RailButton({ label, active, onClick, children }: { label: string; active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      aria-label={label}
      aria-current={active ? "page" : undefined}
      onClick={onClick}
      className={`group relative grid size-11 place-items-center rounded-xl transition-colors duration-200 ${active ? "text-white" : "text-muted hover:text-text"}`}
    >
      {active && (
        <motion.span layoutId="nav-active" transition={SPRING} className="absolute inset-0 rounded-xl bg-white/[0.09] shadow-[inset_0_0_0_1px_rgb(255_255_255/0.07)]">
          <span className="chroma absolute top-2.5 bottom-2.5 -left-[9px] w-[3px] rounded-full" />
        </motion.span>
      )}
      <span className="relative">{children}</span>
      <span className="glass pointer-events-none absolute left-[calc(100%+12px)] z-30 rounded-lg px-2.5 py-1 text-xs font-semibold whitespace-nowrap text-text opacity-0 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-100">
        {label}
      </span>
    </button>
  );
}

function AccountMenu() {
  const { overview, refresh, openModal, toast } = useStore();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [open]);

  const accounts = overview?.accounts ?? [];
  const active = accounts.find((a) => a.uuid === overview?.activeAccount) ?? accounts[0];
  const act = async (fn: () => Promise<void>) => {
    try {
      await fn();
      await refresh();
    } catch (e) {
      toast(String(e), "error");
    }
  };

  return (
    <div ref={ref} className="relative mt-1">
      <button aria-label="Accounts" title={active?.username ?? "Anmelden"} onClick={() => setOpen(!open)} className="grid size-11 place-items-center rounded-xl transition hover:bg-white/[0.07]">
        {active ? <Avatar skinUrl={active.skinUrl} name={active.username} size={30} /> : <LogIn className="size-[18px] text-muted" />}
      </button>
      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, x: -8, scale: 0.96, filter: "blur(4px)" }}
            animate={{ opacity: 1, x: 0, scale: 1, filter: "blur(0px)" }}
            exit={{ opacity: 0, x: -6, scale: 0.97 }}
            transition={{ duration: 0.3, ease: EASE }}
            className="absolute bottom-0 left-[calc(100%+14px)] z-30 w-72 origin-bottom-left rounded-2xl border border-line-2 bg-panel-solid/95 p-1.5 shadow-[0_24px_60px_-16px_rgb(0_0_0/0.8)] backdrop-blur-xl"
          >
            <div className="px-2.5 pt-1.5 pb-2 text-xs font-semibold text-muted">Microsoft-Accounts</div>
            {accounts.map((a) => (
              <div key={a.uuid} className="group flex items-center gap-2.5 rounded-xl px-2 py-1.5 transition hover:bg-white/[0.06]">
                <button className="flex flex-1 items-center gap-2.5 text-left" onClick={() => act(() => api.setActiveAccount(a.uuid))}>
                  <Avatar skinUrl={a.skinUrl} name={a.username} size={28} />
                  <span className="flex-1 truncate font-semibold">{a.username}</span>
                  {a.uuid === active?.uuid && <Check className="size-4 text-accent-2" />}
                </button>
                <button aria-label={`${a.username} entfernen`} title="Entfernen" onClick={() => act(() => api.removeAccount(a.uuid))} className="text-dim opacity-0 transition group-hover:opacity-100 hover:text-err">
                  <Trash2 className="size-4" />
                </button>
              </div>
            ))}
            <button
              onClick={() => {
                setOpen(false);
                openModal({ kind: "login" });
              }}
              className="mt-1 flex w-full items-center gap-2.5 rounded-xl px-2 py-2 text-left font-semibold text-muted transition hover:bg-white/[0.06] hover:text-text"
            >
              <span className="grid size-7 place-items-center rounded-lg bg-white/[0.08]">
                <LogIn className="size-4" />
              </span>
              Account hinzufügen
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

/**
 * The client ArrayList: right-aligned rows sorted by width, each with a chroma
 * bar whose colour is phase-shifted per row so the gradient flows down the list.
 */
export function ArrayList({ items, title }: { items: { name: string; suffix?: string }[]; title?: string }) {
  const sorted = useMemo(() => {
    const ctx = document.createElement("canvas").getContext("2d");
    if (ctx) ctx.font = '600 13px "Sora Variable", sans-serif';
    const width = (s: string) => (ctx ? ctx.measureText(s).width : s.length * 7);
    return [...items].sort((a, b) => width(b.name + (b.suffix ?? "")) - width(a.name + (a.suffix ?? "")));
  }, [items]);
  return (
    <div className="flex flex-col items-end" aria-label={title}>
      <AnimatePresence initial={true}>
        {sorted.map((item, i) => (
          <motion.div
            key={item.name}
            layout
            initial={{ opacity: 0, x: 40 }}
            animate={{ opacity: 1, x: 0, transition: { delay: 0.25 + i * 0.045, duration: 0.5, ease: EASE } }}
            exit={{ opacity: 0, x: 40, transition: { duration: 0.25 } }}
            className="flex items-stretch bg-black/45 backdrop-blur-sm first:rounded-tl-md last:rounded-bl-md"
          >
            <span className="px-2.5 py-[3px] text-[13px] font-semibold">
              <span className="chroma-text" style={{ animationDelay: `${-i * 0.35}s` }}>
                {item.name}
              </span>
              {item.suffix && <span className="ml-1.5 font-mono text-[11px] text-muted">{item.suffix}</span>}
            </span>
            <span className="chroma w-[2px]" style={{ animationDelay: `${-i * 0.35}s` }} />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}

/** Toasts and running tasks as client notifications sliding in from the right. */
export function Notifications() {
  const { toasts, tasks, dismissToast } = useStore();
  return (
    <div className="pointer-events-none absolute right-4 bottom-4 z-40 flex w-[340px] flex-col items-end gap-2">
      <AnimatePresence initial={false}>
        {tasks.map((t) => {
          const pct = t.done ? 1 : t.total > 0 ? t.finished / t.total : null;
          return (
            <Card key={t.id} tone={t.error ? "error" : t.done ? "success" : "task"} title={t.label} text={t.error ?? (t.done ? "Fertig" : `${t.stage}${t.totalBytes > 0 ? ` · ${megabytes(t.bytes)} / ${megabytes(t.totalBytes)}` : ""}`)}>
              {!t.error && (
                <div className="mt-2.5 h-[3px] overflow-hidden rounded-full bg-white/[0.08]">
                  {pct === null ? (
                    <motion.div className="chroma h-full w-1/3 rounded-full" animate={{ x: ["-100%", "300%"] }} transition={{ repeat: Infinity, duration: 1.4, ease: "easeInOut" }} />
                  ) : (
                    <motion.div className="chroma h-full rounded-full" animate={{ width: `${Math.round(pct * 100)}%` }} transition={{ ease: EASE, duration: 0.4 }} />
                  )}
                </div>
              )}
            </Card>
          );
        })}
        {toasts.map((t) => (
          <Card key={`toast-${t.id}`} tone={t.kind === "error" ? "error" : t.kind === "success" ? "success" : "info"} text={t.message} onClick={() => dismissToast(t.id)}>
            <div className="mt-2.5 h-[3px] overflow-hidden rounded-full bg-white/[0.06]">
              <motion.div
                className={`h-full rounded-full ${t.kind === "error" ? "bg-err" : "chroma"}`}
                initial={{ width: "100%" }}
                animate={{ width: "0%" }}
                transition={{ duration: t.kind === "error" ? 8 : 4, ease: "linear" }}
              />
            </div>
          </Card>
        ))}
      </AnimatePresence>
    </div>
  );
}

function Card({ tone, title, text, onClick, children }: { tone: "task" | "info" | "success" | "error"; title?: string; text: string; onClick?: () => void; children?: ReactNode }) {
  const icon = {
    task: <span className="chroma block size-2 rounded-full" />,
    info: <Info className="size-4 text-accent-2" />,
    success: <CircleCheck className="size-4 text-on" />,
    error: <TriangleAlert className="size-4 text-err" />,
  }[tone];
  return (
    <motion.div
      layout
      initial={{ opacity: 0, x: 60, scale: 0.96 }}
      animate={{ opacity: 1, x: 0, scale: 1 }}
      exit={{ opacity: 0, x: 60, scale: 0.96, transition: { duration: 0.25, ease: EASE } }}
      transition={SPRING}
      onClick={onClick}
      className={`pointer-events-auto w-full rounded-2xl border bg-panel-solid/92 p-3.5 shadow-[0_18px_50px_-18px_rgb(0_0_0/0.9)] backdrop-blur-xl ${onClick ? "cursor-pointer" : ""} ${
        tone === "error" ? "border-err/30" : "border-line-2"
      }`}
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid size-5 shrink-0 place-items-center">{icon}</span>
        <div className="min-w-0 flex-1">
          {title && <div className="truncate text-[13px] font-semibold">{title}</div>}
          <div className={`text-[12.5px] leading-relaxed break-words ${title ? "text-muted" : "text-text"}`}>{text}</div>
        </div>
      </div>
      {children}
    </motion.div>
  );
}
