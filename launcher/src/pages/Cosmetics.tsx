import { Check, Globe, ImagePlus, LogOut, Pencil, Shirt, Trash2 } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useRef, useState } from "react";
import { FACES, dataUrlToBlob, decode, defaultState, type EditorState } from "../lib/cape";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import type { CapeInfo, OnlineStatus } from "../lib/types";
import { CapeEditor, type EditTarget } from "../ui/CapeEditor";
import { Button, EmptyState, Spinner, rise, stagger } from "../ui/kit";

/** Cape Studio: library of own capes, and the editor for new or existing ones. */
export function Cosmetics() {
  const { toast, overview, navigate } = useStore();
  const [online, setOnline] = useState<OnlineStatus | null>(null);
  const [signingIn, setSigningIn] = useState(false);
  const [capes, setCapes] = useState<CapeInfo[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [editing, setEditing] = useState<EditTarget | null>(null);
  const [opening, setOpening] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    api
      .capesList()
      .then((lib) => {
        setCapes(lib.capes);
        setActiveId(lib.activeId ?? null);
      })
      .catch((e) => toast(String(e), "error"));
  }, [toast]);
  useEffect(load, [load]);
  useEffect(() => {
    api.cosmeticsStatus().then(setOnline).catch(() => setOnline(null));
  }, []);

  async function signIn() {
    setSigningIn(true);
    try {
      // Without a Microsoft account, dev builds sign in with the offline test identity.
      const offline = overview?.accounts.length ? null : overview?.devBuild ? "DevPlayer" : null;
      const status = await api.cosmeticsSignIn(offline);
      setOnline(status);
      toast(`Online als ${status.name}. Andere DaClient-Spieler sehen jetzt deine Cape.`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setSigningIn(false);
    }
  }

  async function signOut(deleteAccount: boolean) {
    try {
      if (deleteAccount) await api.cosmeticsDeleteAccount();
      else await api.cosmeticsSignOut();
      setOnline(await api.cosmeticsStatus());
      toast(deleteAccount ? "Konto und Capes auf dem Server gelöscht" : "Abgemeldet. Deine Cape bleibt lokal.");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  async function openFile(file: File) {
    if (!/^image\/(gif|png|webp|jpeg|apng)$/.test(file.type)) {
      toast("Unterstützt werden GIF, PNG, APNG, WebP und JPG.", "error");
      return;
    }
    setOpening(true);
    try {
      const source = await decode(file);
      const name = file.name.replace(/\.[^.]+$/, "").slice(0, 40) || "Neue Cape";
      setEditing({ name, file, source, state: defaultState(source) });
    } catch (e) {
      toast(`Das Bild ließ sich nicht öffnen: ${e}`, "error");
    } finally {
      setOpening(false);
    }
  }

  async function editAgain(cape: CapeInfo) {
    setOpening(true);
    try {
      const src = await api.capeSource(cape.id);
      if (!src) throw new Error("Für diese Cape ist das Original nicht gespeichert.");
      const file = await dataUrlToBlob(src.dataUrl);
      const source = await decode(file);
      const state = { ...defaultState(source), ...(src.editor as Partial<EditorState>) };
      setEditing({ id: cape.id, name: cape.name, file: Object.assign(file, { name: `source.${src.ext}` }), source, state });
    } catch (e) {
      toast(String(e instanceof Error ? e.message : e), "error");
    } finally {
      setOpening(false);
    }
  }

  async function activate(id: string | null) {
    try {
      const onlineError = await api.capeSetActive(id);
      setActiveId(id);
      toast(id ? "Cape ist jetzt aktiv. Sie erscheint beim nächsten Spielstart." : "Keine Cape aktiv");
      if (onlineError) toast(`Online nicht aktualisiert: ${onlineError}`, "error");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  async function remove(cape: CapeInfo) {
    try {
      await api.capeDelete(cape.id);
      toast(`„${cape.name}“ gelöscht`);
      load();
    } catch (e) {
      toast(String(e), "error");
    }
  }

  if (editing) {
    return (
      <div className="h-full px-8 pt-3 pb-6">
        <h1 className="mb-4 text-[30px] font-extrabold tracking-[-0.03em]">{editing.id ? "Cape bearbeiten" : "Neue Cape"}</h1>
        <div className="h-[calc(100%-60px)]">
          <CapeEditor
            target={editing}
            onDone={async (savedId) => {
              setEditing(null);
              // The first cape becomes the active one.
              if (savedId && !activeId) await activate(savedId);
              load();
            }}
          />
        </div>
      </div>
    );
  }

  return (
    <div
      className="flex h-full flex-col"
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={(e) => e.currentTarget === e.target && setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        const file = e.dataTransfer.files[0];
        if (file) openFile(file);
      }}
    >
      <input
        ref={input}
        type="file"
        accept="image/gif,image/png,image/apng,image/webp,image/jpeg"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) openFile(file);
          e.target.value = "";
        }}
      />
      <motion.div variants={stagger} initial="hidden" animate="show" className="flex items-end gap-3 px-8 pt-3 pb-5">
        <motion.div variants={rise} className="mr-auto">
          <h1 className="text-[30px] font-extrabold tracking-[-0.03em]">Kosmetik</h1>
          <p className="mt-0.5 text-[13px] text-muted">Eigene Capes aus Bildern und GIFs. Im Spiel siehst du die aktive Cape an deinem Spieler.</p>
        </motion.div>
        <motion.div variants={rise}>
          <Button variant="primary" icon={<ImagePlus className="size-4" />} loading={opening} onClick={() => input.current?.click()}>
            Neue Cape
          </Button>
        </motion.div>
      </motion.div>

      <OnlineCard online={online} busy={signingIn} onSignIn={signIn} onSignOut={signOut} onSettings={() => navigate({ page: "settings" })} />

      <div className={`mx-8 mb-6 min-h-0 flex-1 overflow-auto rounded-[18px] transition-colors duration-200 ${dragOver ? "bg-accent/[0.08] shadow-[inset_0_0_0_1.5px_rgb(192_132_252/0.5)]" : ""}`}>
        {capes === null ? (
          <div className="grid place-items-center py-20">
            <Spinner className="size-5" />
          </div>
        ) : capes.length === 0 ? (
          <EmptyState
            icon={<Shirt className="size-5" />}
            title="Noch keine Cape"
            text="Zieh ein GIF oder Bild hierher. Der Editor öffnet sich, dort passt du Ausschnitt, Ränder und Tempo an und siehst die Cape in 3D."
            action={
              <Button variant="primary" icon={<ImagePlus className="size-4" />} onClick={() => input.current?.click()}>
                Bild auswählen
              </Button>
            }
          />
        ) : (
          <motion.div variants={stagger} initial="hidden" animate="show" className="grid grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-3">
            <AnimatePresence>
              {capes.map((cape) => {
                const active = cape.id === activeId;
                return (
                  <motion.div key={cape.id} layout variants={rise} exit={{ opacity: 0, scale: 0.95 }} className={`group relative flex flex-col gap-3 rounded-[18px] p-3 ${active ? "chroma-ring" : "glass"}`}>
                    <div className="grid place-items-center rounded-xl bg-black/30 py-4">
                      <CapeTile id={cape.id} scale={cape.meta.scale} frames={cape.meta.frames} frameTimeMs={cape.meta.frameTimeMs} />
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="truncate text-[13px] font-semibold">{cape.name}</span>
                        {active && <Check className="size-3.5 shrink-0 text-on" />}
                      </div>
                      <span className="font-mono text-[11.5px] text-muted">
                        {cape.meta.frames > 1 ? `${cape.meta.frames} Bilder · ` : ""}
                        {64 * cape.meta.scale}×{32 * cape.meta.scale}
                        {cape.meta.elytra ? " · Elytra" : ""}
                      </span>
                    </div>
                    <div className="flex gap-1.5">
                      <Button size="sm" variant={active ? "ghost" : "primary"} className="flex-1" onClick={() => activate(active ? null : cape.id)}>
                        {active ? "Ablegen" : "Tragen"}
                      </Button>
                      {cape.hasSource && (
                        <Button size="sm" aria-label={`${cape.name} bearbeiten`} icon={<Pencil className="size-3.5" />} onClick={() => editAgain(cape)} />
                      )}
                      <Button size="sm" aria-label={`${cape.name} löschen`} icon={<Trash2 className="size-3.5" />} onClick={() => remove(cape)} />
                    </div>
                  </motion.div>
                );
              })}
            </AnimatePresence>
          </motion.div>
        )}
      </div>
    </div>
  );
}

/** Whether other players can see the cape: the cosmetics server sign-in. */
function OnlineCard({ online, busy, onSignIn, onSignOut, onSettings }: { online: OnlineStatus | null; busy: boolean; onSignIn: () => void; onSignOut: (deleteAccount: boolean) => void; onSettings: () => void }) {
  const [confirmDelete, setConfirmDelete] = useState(false);
  if (!online) return null;
  const signedIn = online.name != null;
  return (
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="glass mx-8 mb-4 flex items-center gap-4 rounded-[18px] px-4 py-3">
      <span className={`grid size-9 shrink-0 place-items-center rounded-xl ${signedIn ? "chroma text-white" : "bg-white/[0.06] text-muted"}`}>
        <Globe className="size-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-semibold">{!online.server ? "Nur für dich sichtbar" : signedIn ? `Online sichtbar als ${online.name}` : "Für andere unsichtbar"}</p>
        <p className="truncate text-[11.5px] text-muted">
          {!online.server
            ? "Ohne Cosmetics-Server siehst nur du deine Cape. Die Adresse trägst du in den Einstellungen ein."
            : signedIn
              ? `Andere DaClient-Spieler sehen deine aktive Cape. Server: ${online.server}`
              : "Melde dich mit deinem Minecraft-Konto an, dann sehen andere DaClient-Spieler deine Cape."}
        </p>
      </div>
      {!online.server ? (
        <Button size="sm" onClick={onSettings}>
          Einstellungen
        </Button>
      ) : signedIn ? (
        confirmDelete ? (
          <div className="flex gap-1.5">
            <Button size="sm" variant="danger" onClick={() => onSignOut(true)}>
              Konto löschen
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(false)}>
              Abbrechen
            </Button>
          </div>
        ) : (
          <div className="flex gap-1.5">
            <Button size="sm" icon={<LogOut className="size-3.5" />} onClick={() => onSignOut(false)}>
              Abmelden
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(true)}>
              Daten löschen …
            </Button>
          </div>
        )
      ) : (
        <Button size="sm" variant="primary" loading={busy} onClick={onSignIn}>
          Online sichtbar machen
        </Button>
      )}
    </motion.div>
  );
}

/** Animated front face of a saved cape. */
function CapeTile({ id, scale, frames, frameTimeMs }: { id: string; scale: number; frames: number; frameTimeMs: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | undefined;
    let live = true;
    api.capeImage(id).then((url) => {
      const img = new Image();
      img.onload = () => {
        if (!live || !canvas.current) return;
        const ctx = canvas.current.getContext("2d")!;
        const f = FACES.front;
        let i = 0;
        const draw = () => {
          ctx.imageSmoothingEnabled = false;
          ctx.clearRect(0, 0, 80, 128);
          ctx.drawImage(img, f.x * scale, (f.y + (i % frames) * 32) * scale, f.w * scale, f.h * scale, 0, 0, 80, 128);
          i++;
        };
        draw();
        if (frames > 1) timer = setInterval(draw, Math.max(40, frameTimeMs));
      };
      img.src = url;
    });
    return () => {
      live = false;
      if (timer) clearInterval(timer);
    };
  }, [id, scale, frames, frameTimeMs]);
  return <canvas ref={canvas} width={80} height={128} className="h-32 w-20 rounded-md shadow-[0_8px_24px_rgb(0_0_0/0.45)]" />;
}
