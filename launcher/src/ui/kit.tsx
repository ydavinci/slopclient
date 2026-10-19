// Component kit of the client look: glass, chroma, springs.
import { LoaderCircle, Play, Square, X } from "lucide-react";
import { AnimatePresence, motion, type Variants } from "motion/react";
import { useEffect, useId, type ButtonHTMLAttributes, type ReactNode } from "react";
import { initials } from "../lib/format";

export const EASE = [0.16, 1, 0.3, 1] as const;
export const SPRING = { type: "spring", stiffness: 520, damping: 34, mass: 0.7 } as const;

/** Children fade, rise and unblur in sequence. */
export const stagger: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.028, delayChildren: 0.02 } },
};
export const rise: Variants = {
  hidden: { opacity: 0, y: 10, filter: "blur(6px)" },
  show: { opacity: 1, y: 0, filter: "blur(0px)", transition: { duration: 0.42, ease: EASE } },
};
export const slideIn: Variants = {
  hidden: { opacity: 0, x: 24 },
  show: { opacity: 1, x: 0, transition: { duration: 0.45, ease: EASE } },
};

type Variant = "primary" | "secondary" | "ghost" | "danger";

export function Button({
  variant = "secondary",
  size = "md",
  loading,
  icon,
  children,
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md"; loading?: boolean; icon?: ReactNode }) {
  const variants: Record<Variant, string> = {
    primary: "chroma text-white hover:brightness-110",
    secondary: "bg-raised border border-line text-text hover:border-line-2 hover:bg-white/[0.08]",
    ghost: "text-muted hover:bg-white/[0.06] hover:text-text",
    danger: "border border-err/30 bg-err/10 text-err hover:bg-err/20",
  };
  const sizes = { sm: "h-8 px-3 text-xs gap-1.5 rounded-lg", md: "h-10 px-4 text-[13px] gap-2 rounded-[10px]" };
  return (
    <button
      {...props}
      disabled={props.disabled || loading}
      className={`group relative inline-flex shrink-0 items-center justify-center overflow-hidden font-semibold transition duration-200 active:scale-[0.97] disabled:opacity-45 disabled:active:scale-100 ${variants[variant]} ${sizes[size]} ${className}`}
    >
      {variant === "primary" && (
        <span className="pointer-events-none absolute inset-y-0 left-0 w-1/3 bg-white/25 opacity-0 blur-md group-hover:animate-[shine_0.9s_var(--ease-out-expo)] group-hover:opacity-100" />
      )}
      {loading ? <LoaderCircle className="size-4 animate-spin" /> : icon}
      {children}
    </button>
  );
}

export function IconButton({ label, children, className = "", ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button
      {...props}
      aria-label={label}
      title={label}
      className={`grid size-9 place-items-center rounded-[10px] text-muted transition duration-200 hover:bg-white/[0.07] hover:text-text active:scale-95 disabled:opacity-40 ${className}`}
    >
      {children}
    </button>
  );
}

/** The big launch button: rotating chroma ring, glowing face, morphs between states. */
export function PlayButton({
  state,
  label,
  onClick,
  progress,
}: {
  state: "idle" | "preparing" | "running";
  label: string;
  onClick: () => void;
  progress?: number | null;
}) {
  return (
    <motion.button
      layout
      onClick={onClick}
      disabled={state === "preparing"}
      whileHover={{ scale: state === "preparing" ? 1 : 1.02 }}
      whileTap={{ scale: 0.97 }}
      transition={SPRING}
      className={`group relative isolate flex h-14 items-center gap-3 overflow-hidden rounded-2xl px-8 text-[15px] font-bold tracking-wide ${
        state === "running" ? "border border-line-2 bg-panel-solid text-text hover:border-err/50 hover:text-err" : "chroma-ring text-white"
      }`}
    >
      {state !== "running" && (
        <>
          <span className="chroma absolute inset-[3px] -z-10 rounded-[13px] opacity-90" />
          <span className="absolute inset-0 -z-20 rounded-2xl shadow-[0_14px_40px_-10px_rgb(168_85_247/0.75)]" />
          <span className="pointer-events-none absolute inset-y-0 left-0 w-1/4 bg-white/30 blur-lg [animation:shine_2.8s_var(--ease-out-expo)_infinite]" />
        </>
      )}
      {state === "preparing" && progress != null && (
        <motion.span className="absolute inset-y-0 left-0 -z-10 bg-white/20" animate={{ width: `${Math.round(progress * 100)}%` }} transition={{ ease: EASE, duration: 0.4 }} />
      )}
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.span
          key={state}
          initial={{ opacity: 0, y: 10, filter: "blur(4px)" }}
          animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
          exit={{ opacity: 0, y: -10, filter: "blur(4px)" }}
          transition={{ duration: 0.35, ease: EASE }}
          className="flex items-center gap-3"
        >
          {state === "running" ? (
            <Square className="size-4 fill-current" />
          ) : state === "preparing" ? (
            <LoaderCircle className="size-5 animate-spin" />
          ) : (
            <Play className="size-5 fill-current" />
          )}
          {label}
        </motion.span>
      </AnimatePresence>
    </motion.button>
  );
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={(e) => {
        e.stopPropagation();
        onChange(!checked);
      }}
      className={`relative h-[22px] w-10 shrink-0 rounded-full p-[3px] transition-colors duration-300 ${
        checked ? "chroma shadow-[0_4px_14px_-4px_rgb(168_85_247/0.8)]" : "bg-white/10"
      }`}
    >
      <motion.span layout transition={SPRING} className={`block size-4 rounded-full bg-white shadow ${checked ? "ml-auto" : ""}`} />
    </button>
  );
}

export function Chip({ children, tone = "default", className = "" }: { children: ReactNode; tone?: "default" | "on" | "accent" | "warn"; className?: string }) {
  const tones = {
    default: "bg-white/[0.07] text-muted",
    on: "bg-on/12 text-on",
    accent: "bg-accent/18 text-accent-2",
    warn: "bg-warn/12 text-warn",
  };
  return <span className={`inline-flex w-fit items-center gap-1.5 rounded-md px-2 py-0.5 text-[11.5px] font-semibold ${tones[tone]} ${className}`}>{children}</span>;
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded-md border border-line-2 bg-white/[0.05] px-1.5 py-px font-mono text-[11px] text-muted">{children}</kbd>;
}

export function Panel({ children, className = "", ...props }: { children: ReactNode; className?: string } & React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div {...props} className={`glass rounded-[var(--radius-panel)] ${className}`}>
      {children}
    </div>
  );
}

export const inputClass =
  "h-10 w-full rounded-[10px] border border-line bg-black/30 px-3.5 text-[13px] text-text placeholder:text-dim outline-none transition duration-200 focus:border-accent-2/60 focus:bg-black/40 focus:shadow-[0_0_0_4px_rgb(139_92_246/0.15)]";

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-semibold text-muted">{label}</span>
      {children}
      {hint && <span className="mt-1.5 block text-xs leading-relaxed text-muted">{hint}</span>}
    </label>
  );
}

/** Segmented control with a spring pill that slides to the active option. */
export function Segmented<T extends string>({ value, options, onChange, className = "" }: { value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; className?: string }) {
  const id = useId();
  return (
    <div className={`flex rounded-[11px] border border-line bg-black/30 p-1 ${className}`}>
      {options.map((o) => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          className={`relative flex-1 rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors duration-200 ${value === o.value ? "text-white" : "text-muted hover:text-text"}`}
        >
          {value === o.value && <motion.span layoutId={`seg-${id}`} transition={SPRING} className="absolute inset-0 rounded-lg bg-white/[0.1] shadow-[inset_0_0_0_1px_rgb(255_255_255/0.08)]" />}
          <span className="relative flex items-center justify-center gap-1.5">{o.label}</span>
        </button>
      ))}
    </div>
  );
}

export function Modal({ open, title, onClose, children, width = 520 }: { open: boolean; title: string; onClose: () => void; children: ReactNode; width?: number }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-40 grid place-items-center bg-black/55 p-6 backdrop-blur-md"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.25 }}
          onMouseDown={onClose}
        >
          <motion.div
            role="dialog"
            aria-modal="true"
            aria-label={title}
            onMouseDown={(e) => e.stopPropagation()}
            initial={{ opacity: 0, scale: 0.94, y: 14, filter: "blur(8px)" }}
            animate={{ opacity: 1, scale: 1, y: 0, filter: "blur(0px)" }}
            exit={{ opacity: 0, scale: 0.97, y: 8, filter: "blur(6px)" }}
            transition={{ duration: 0.4, ease: EASE }}
            className="relative max-h-full w-full overflow-auto rounded-[18px] border border-line-2 bg-panel-solid/95 shadow-[0_30px_80px_-20px_rgb(0_0_0/0.8)]"
            style={{ maxWidth: width }}
          >
            <div className="chroma absolute inset-x-0 top-0 h-[2px]" />
            <div className="flex items-center justify-between px-6 pt-5 pb-3">
              <h2 className="text-[16px] font-bold tracking-tight">{title}</h2>
              <IconButton label="Schließen" onClick={onClose}>
                <X className="size-4" />
              </IconButton>
            </div>
            <div className="px-6 pb-6">{children}</div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

const TILE_GRADIENTS = [
  ["#7c3aed", "#c084fc"],
  ["#4f46e5", "#a78bfa"],
  ["#c026d3", "#f0abfc"],
  ["#2563eb", "#818cf8"],
  ["#9333ea", "#f472b6"],
  ["#0d9488", "#a78bfa"],
];

export function tileGradient(id: string) {
  let hash = 0;
  for (const c of id) hash = (hash * 31 + c.charCodeAt(0)) >>> 0;
  return TILE_GRADIENTS[hash % TILE_GRADIENTS.length];
}

export function InstanceIcon({ id, name, size = 40 }: { id: string; name: string; size?: number }) {
  const [a, b] = tileGradient(id);
  return (
    <div
      className="relative grid shrink-0 place-items-center overflow-hidden font-bold text-white"
      style={{ width: size, height: size, borderRadius: size * 0.28, background: `linear-gradient(135deg, ${a}, ${b})`, fontSize: size * 0.36 }}
    >
      <span className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgb(255_255_255/0.35),transparent_60%)]" />
      <span className="relative drop-shadow">{initials(name)}</span>
    </div>
  );
}

/**
 * Player face cropped straight from the profile's skin texture (served by
 * Minecraft's own texture CDN): face at 8,8 plus the hat layer at 40,8.
 * No third-party avatar service sees the player's UUID.
 */
export function Avatar({ skinUrl, name, size = 32 }: { skinUrl?: string | null; name?: string; size?: number }) {
  if (!skinUrl) {
    return (
      <div className="chroma grid shrink-0 place-items-center rounded-lg text-xs font-bold text-white" style={{ width: size, height: size }}>
        {name ? name[0].toUpperCase() : "?"}
      </div>
    );
  }
  return (
    <div
      role="img"
      aria-label={name ? `Skin von ${name}` : "Skin"}
      className="shrink-0 rounded-lg bg-white/10 [image-rendering:pixelated]"
      style={{
        width: size,
        height: size,
        backgroundImage: `url("${skinUrl}"), url("${skinUrl}")`,
        backgroundSize: `${size * 8}px ${size * 8}px`,
        backgroundPosition: `${-size * 5}px ${-size}px, ${-size}px ${-size}px`,
        backgroundRepeat: "no-repeat",
      }}
    />
  );
}

export function Spinner({ className = "size-4" }: { className?: string }) {
  return <LoaderCircle className={`animate-spin text-muted ${className}`} />;
}

export function EmptyState({ icon, title, text, action }: { icon: ReactNode; title: string; text: string; action?: ReactNode }) {
  return (
    <motion.div variants={rise} initial="hidden" animate="show" className="flex flex-col items-center justify-center px-6 py-16 text-center">
      <div className="mb-4 grid size-12 place-items-center rounded-2xl border border-line bg-white/[0.04] text-accent-2">{icon}</div>
      <h3 className="font-semibold">{title}</h3>
      <p className="mt-1 max-w-sm text-[13px] leading-relaxed text-muted">{text}</p>
      {action && <div className="mt-5">{action}</div>}
    </motion.div>
  );
}
