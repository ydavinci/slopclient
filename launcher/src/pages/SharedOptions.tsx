import { RefreshCw, Trash2, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../lib/ipc";
import { useStore } from "../lib/store";
import type { Instance, SharedGroupStatus, SharedMember } from "../lib/types";
import { Button, IconButton, InstanceIcon, Segmented, Spinner, inputClass } from "../ui/kit";

const STATE: Record<SharedMember["state"], { dot: string; label: string }> = {
  linked: { dot: "bg-on", label: "Verknüpft" },
  repaired: { dot: "bg-on", label: "Verknüpfung wiederhergestellt" },
  copied: { dot: "bg-warn", label: "Hardlink nicht möglich, wird beim Start und Beenden abgeglichen" },
  waiting: { dot: "animate-pulse bg-accent-2", label: "Spiel läuft, wird beim Beenden abgeglichen" },
  empty: { dot: "bg-dim", label: "Noch keine Einstellungen gespeichert, wird nach dem ersten Start verknüpft" },
  error: { dot: "bg-err", label: "Fehler" },
};

/** Sync groups for options.txt and friends (Einstellungen → Geteilte Spieleinstellungen). */
export function SharedOptions() {
  const { overview, toast } = useStore();
  const [groups, setGroups] = useState<SharedGroupStatus[] | null>(null);
  const [checking, setChecking] = useState(false);
  const [newName, setNewName] = useState("");
  const [newSource, setNewSource] = useState("");

  const instances = overview?.instances ?? [];
  const byId = useMemo(() => new Map(instances.map((i) => [i.id, i])), [instances]);
  const groupOf = useMemo(() => {
    const m = new Map<string, SharedGroupStatus>();
    for (const g of groups ?? []) for (const member of g.members) m.set(member.instance, g);
    return m;
  }, [groups]);
  const free = instances.filter((i) => !groupOf.has(i.id));

  const load = useCallback(
    async (reconcile = false) => {
      setChecking(true);
      try {
        setGroups(await (reconcile ? api.sharedOptionsReconcile() : api.sharedOptionsGroups()));
      } catch (e) {
        toast(String(e), "error");
      } finally {
        setChecking(false);
      }
    },
    [toast],
  );
  useEffect(() => {
    load();
  }, [load]);

  /** Runs a change, then shows the groups as they are now. */
  async function change(fn: () => Promise<unknown>, done?: string) {
    try {
      await fn();
      if (done) toast(done, "success");
    } catch (e) {
      toast(String(e), "error");
    }
    await load();
  }

  async function create() {
    const name = newName.trim();
    if (!name || !newSource) return;
    await change(() => api.sharedOptionsCreate(name, newSource), `Gruppe „${name}“ angelegt`);
    setNewName("");
    setNewSource("");
  }

  return (
    <div className="grid gap-4">
      <p className="text-[12.5px] text-muted">
        Instanzen in einer Gruppe teilen sich Grafik, Steuerung, Sound und Tastenbelegung. Änderst du etwas in einer Instanz, gilt es sofort für alle in der Gruppe.
      </p>
      {groups === null ? (
        <Spinner className="size-5" />
      ) : (
        groups.map((g) => <GroupCard key={g.id} group={g} byId={byId} groupOf={groupOf} change={change} />)
      )}
      <div className="flex items-center gap-2 border-t border-line pt-4">
        <div className="min-w-0 flex-1">
          <input className={inputClass} placeholder="Name der neuen Gruppe, z. B. PvP" value={newName} onChange={(e) => setNewName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && create()} />
        </div>
        <div className="w-48 shrink-0">
          <select className={inputClass} value={newSource} onChange={(e) => setNewSource(e.target.value)} aria-label="Instanz, deren Einstellungen die Gruppe übernimmt">
            <option value="">Einstellungen von …</option>
            {free.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name}
              </option>
            ))}
          </select>
        </div>
        <Button variant="primary" disabled={!newName.trim() || !newSource} onClick={create}>
          Neue Gruppe
        </Button>
      </div>
      <div>
        <Button size="sm" icon={<RefreshCw className={`size-3.5 ${checking ? "animate-spin" : ""}`} />} disabled={checking} onClick={() => load(true)}>
          Jetzt prüfen
        </Button>
      </div>
    </div>
  );
}

function GroupCard({
  group,
  byId,
  groupOf,
  change,
}: {
  group: SharedGroupStatus;
  byId: Map<string, Instance>;
  groupOf: Map<string, SharedGroupStatus>;
  change: (fn: () => Promise<unknown>, done?: string) => Promise<void>;
}) {
  const { overview } = useStore();
  const [name, setName] = useState(group.name);
  useEffect(() => setName(group.name), [group.name]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [adding, setAdding] = useState("");
  const [keep, setKeep] = useState<"group" | "instance">("group");

  const others = [...byId.values()].filter((i) => groupOf.get(i.id)?.id !== group.id);
  const candidate = adding ? byId.get(adding) : undefined;
  // The Minecraft version of the group is that of its first member.
  const groupVersion = group.members.map((m) => byId.get(m.instance)?.mcVersion).find(Boolean);
  const running = (id: string) => overview?.running.includes(id) ?? false;

  function rename() {
    const next = name.trim();
    if (!next || next === group.name) {
      setName(group.name);
      return;
    }
    change(() => api.sharedOptionsRename(group.id, next));
  }

  async function add() {
    if (!candidate) return;
    await change(() => api.sharedOptionsAdd(group.id, candidate.id, keep), `${candidate.name} teilt jetzt die Einstellungen von „${group.name}“`);
    setAdding("");
    setKeep("group");
  }

  return (
    <div className="grid gap-3 rounded-[14px] border border-line bg-white/[0.02] p-4">
      <div className="flex items-center gap-2">
        <input
          className={`${inputClass} h-9 min-w-0 flex-1 font-semibold`}
          value={name}
          aria-label="Name der Gruppe"
          onChange={(e) => setName(e.target.value)}
          onBlur={rename}
          onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
        />
        {confirmDelete ? (
          <>
            <span className="text-[12px] text-muted">Jede Instanz behält ihre aktuellen Einstellungen.</span>
            <Button size="sm" onClick={() => setConfirmDelete(false)}>
              Abbrechen
            </Button>
            <Button size="sm" variant="danger" onClick={() => change(() => api.sharedOptionsDelete(group.id), `Gruppe „${group.name}“ gelöscht`)}>
              Löschen
            </Button>
          </>
        ) : (
          <IconButton label={`Gruppe ${group.name} löschen`} onClick={() => setConfirmDelete(true)}>
            <Trash2 className="size-4" />
          </IconButton>
        )}
      </div>

      <div className="grid gap-1">
        {group.members.map((m) => {
          const inst = byId.get(m.instance);
          const s = STATE[m.state];
          return (
            <div key={m.instance} className="flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-white/[0.03]">
              <InstanceIcon id={m.instance} name={inst?.name ?? m.instance} icon={inst?.icon} size={28} />
              <span className="min-w-0 flex-1 truncate text-[13px] font-semibold">{inst?.name ?? m.instance}</span>
              <span className="font-mono text-[11.5px] text-muted">{inst?.mcVersion}</span>
              <span title={m.state === "error" ? `Fehler: ${m.message ?? ""}` : s.label} aria-label={m.state === "error" ? `Fehler: ${m.message ?? ""}` : s.label} className={`size-2 shrink-0 rounded-full ${s.dot}`} />
              <IconButton label={`${inst?.name ?? m.instance} aus der Gruppe nehmen`} disabled={running(m.instance)} title={running(m.instance) ? "Schließ zuerst das Spiel" : undefined} onClick={() => change(() => api.sharedOptionsRemove(group.id, m.instance))}>
                <X className="size-3.5" />
              </IconButton>
            </div>
          );
        })}
      </div>

      <select
        className={inputClass}
        value={adding}
        onChange={(e) => {
          setAdding(e.target.value);
          setKeep("group");
        }}
        aria-label={`Instanz zu ${group.name} hinzufügen`}
      >
        <option value="">Instanz hinzufügen …</option>
        {others.map((i) => {
          const other = groupOf.get(i.id);
          return (
            <option key={i.id} value={i.id} disabled={!!other}>
              {i.name}
              {other ? ` (Gruppe „${other.name}“)` : ""}
            </option>
          );
        })}
      </select>

      {candidate && (
        <div className="grid gap-3 rounded-[12px] border border-line bg-white/[0.03] p-3">
          <div className="text-[13px] font-semibold">Welche Einstellungen sollen gelten?</div>
          <Segmented
            value={keep}
            onChange={setKeep}
            options={[
              { value: "group", label: "Die der Gruppe" },
              { value: "instance", label: `Die von ${candidate.name}` },
            ]}
          />
          <p className="text-[12px] text-muted">
            {keep === "group"
              ? `${candidate.name} übernimmt die Einstellungen der Gruppe. Die bisherigen werden gesichert.`
              : `Alle Instanzen der Gruppe übernehmen die Einstellungen von ${candidate.name}. Die der Gruppe werden gesichert.`}
          </p>
          {groupVersion && candidate.mcVersion !== groupVersion && (
            <p className="rounded-lg bg-warn/[0.08] px-3 py-2 text-[12px] text-warn">
              Diese Instanz nutzt Minecraft {candidate.mcVersion}, die Gruppe {groupVersion}. Einstellungen können sich gegenseitig verändern.
            </p>
          )}
          <div className="flex gap-2">
            <Button size="sm" variant="primary" disabled={running(candidate.id)} title={running(candidate.id) ? "Schließ zuerst das Spiel" : undefined} onClick={add}>
              Hinzufügen
            </Button>
            <Button size="sm" onClick={() => setAdding("")}>
              Abbrechen
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
