import { Bird, Check, Crown, Footprints, PawPrint } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useState, type ReactNode } from "react";
import { thumbnail, type ExtraModel, type ExtrasCatalogue, type ExtrasChoice, type PetTier } from "../lib/extras";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import { CapeViewer, fallbackSkin } from "../ui/CapeViewer";
import { Spinner, rise, stagger } from "../ui/kit";

function Tile({ model, kind, active, onClick }: { model: ExtraModel; kind: "hat" | "pet"; active: boolean; onClick: () => void }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    thumbnail(model, kind, fallbackSkin)
      .then((url) => live && setSrc(url))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [model, kind]);
  return (
    <motion.button
      variants={rise}
      onClick={onClick}
      aria-pressed={active}
      className={`group relative flex flex-col items-center gap-1.5 rounded-[16px] p-2.5 text-center transition-transform duration-200 hover:-translate-y-0.5 ${active ? "chroma-ring" : "glass"}`}
    >
      <span className="grid w-full place-items-center rounded-xl bg-black/30 py-2">
        {src ? <img src={src} alt="" className="size-20" /> : <span className="size-20" />}
      </span>
      <span className="flex items-center gap-1 text-[12.5px] font-semibold">
        {model.name}
        {active && <Check className="size-3.5 text-on" />}
      </span>
    </motion.button>
  );
}

function Section({ icon, title, hint, children }: { icon: ReactNode; title: string; hint: string; children: ReactNode }) {
  return (
    <section className="mb-5">
      <div className="mb-2.5 flex items-center gap-2">
        <span className="grid size-7 place-items-center rounded-lg bg-accent/15 text-accent-2">{icon}</span>
        <h2 className="text-[15px] font-bold">{title}</h2>
        <span className="text-[12px] text-muted">{hint}</span>
      </div>
      {children}
    </section>
  );
}

const TIERS: { tier: PetTier; title: string; hint: string; icon: ReactNode }[] = [
  { tier: "shoulder", title: "Schulter", hint: "Sitzt auf deiner linken Schulter.", icon: <PawPrint className="size-4" /> },
  { tier: "flying", title: "Fliegend", hint: "Schwebt neben deinem Kopf.", icon: <Bird className="size-4" /> },
  { tier: "walking", title: "Laufend", hint: "Größer, läuft dir hinterher.", icon: <Footprints className="size-4" /> },
];

const grid = "grid grid-cols-[repeat(auto-fill,minmax(132px,1fr))] gap-3";

/** Hats and pets: pick one of each, see it on your player, other client players see it too. */
export function ExtrasView() {
  const { toast, overview } = useStore();
  const account = overview?.accounts.find((a) => a.uuid === overview.activeAccount) ?? overview?.accounts[0];
  const [catalogue, setCatalogue] = useState<ExtrasCatalogue | null>(null);
  const [choice, setChoice] = useState<ExtrasChoice>({ hat: null, pet: null });

  useEffect(() => {
    Promise.all([api.extrasCatalogue(), api.extrasCurrent()])
      .then(([c, current]) => {
        setCatalogue(c);
        setChoice(current);
      })
      .catch((e) => toast(String(e), "error"));
  }, [toast]);

  async function pick(next: ExtrasChoice) {
    setChoice(next);
    try {
      const onlineError = await api.extrasSet(next.hat, next.pet);
      if (onlineError) toast(`Lokal gespeichert, online nicht: ${onlineError}`, "error");
    } catch (e) {
      toast(String(e), "error");
    }
  }

  if (!catalogue) {
    return (
      <div className="grid flex-1 place-items-center">
        <Spinner className="size-5" />
      </div>
    );
  }

  const hat = catalogue.hats.find((h) => h.id === choice.hat) ?? null;
  const pet = catalogue.pets.find((p) => p.id === choice.pet) ?? null;

  return (
    <div className="flex min-h-0 flex-1 gap-4 px-8 pb-6">
      <motion.div variants={rise} initial="hidden" animate="show" className="glass flex w-[300px] shrink-0 flex-col items-center gap-3 self-start rounded-[18px] p-4">
        <CapeViewer frames={[]} frameTimeMs={100} elytra={false} skinUrl={account?.skinUrl} pose={pet?.tier === "walking" ? "walk" : "idle"} hat={hat} pet={pet} width={260} height={330} />
        <div className="w-full text-center">
          <div className="text-[14px] font-semibold">{[hat?.name, pet?.name].filter(Boolean).join(" + ") || "Nichts ausgewählt"}</div>
          <div className="text-[12px] text-muted">Gilt ab dem nächsten Start. Mit Cosmetics-Konto sehen es auch andere Spieler.</div>
        </div>
      </motion.div>

      <div className="min-h-0 flex-1 overflow-auto">
        <Section icon={<Crown className="size-4" />} title="Hüte" hint="Sitzt auf dem Kopf, auf Wunsch auch über dem Helm.">
          <motion.div variants={stagger} initial="hidden" animate="show" className={grid}>
            {catalogue.hats.map((h) => (
              <Tile key={h.id} model={h} kind="hat" active={h.id === choice.hat} onClick={() => pick({ ...choice, hat: h.id === choice.hat ? null : h.id })} />
            ))}
          </motion.div>
        </Section>
        {TIERS.map(({ tier, title, hint, icon }) => {
          const pets = catalogue.pets.filter((p) => (p.tier ?? "shoulder") === tier);
          if (pets.length === 0) return null;
          return (
            <Section key={tier} icon={icon} title={title} hint={hint}>
              <motion.div variants={stagger} initial="hidden" animate="show" className={grid}>
                {pets.map((p) => (
                  <Tile key={p.id} model={p} kind="pet" active={p.id === choice.pet} onClick={() => pick({ ...choice, pet: p.id === choice.pet ? null : p.id })} />
                ))}
              </motion.div>
            </Section>
          );
        })}
      </div>
    </div>
  );
}
