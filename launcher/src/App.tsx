import { useEffect, useRef } from "react";
import { api, onEvent } from "./lib/ipc";
import { StoreProvider, useLaunch, useStore } from "./lib/store";
import { Browse } from "./pages/Browse";
import { Cosmetics } from "./pages/Cosmetics";
import { Home } from "./pages/Home";
import { InstanceDetail } from "./pages/InstanceDetail";
import { Instances } from "./pages/Instances";
import { SettingsPage } from "./pages/SettingsPage";
import { Modals } from "./ui/Modals";
import { ProjectView } from "./ui/ProjectView";
import { Shader } from "./ui/Shader";
import { NavRail, Notifications, TitleBar } from "./ui/Shell";
import { LauncherUpdateBanner } from "./ui/LauncherUpdateBanner";

function Page() {
  const { route } = useStore();
  switch (route.page) {
    case "home":
      return <Home />;
    case "instances":
      return <Instances />;
    case "instance":
      return <InstanceDetail id={route.id} tab={route.tab} />;
    case "browse":
      return <Browse target={route.target} />;
    case "cosmetics":
      return <Cosmetics />;
    case "settings":
      return <SettingsPage />;
  }
}

/** Starts the instance a desktop shortcut asked for, at startup or from a second start. */
function useShortcutLaunch() {
  const { overview, navigate } = useStore();
  const launch = useLaunch();
  const latest = useRef({ overview, launch, navigate });
  latest.current = { overview, launch, navigate };
  const ready = overview !== null;

  useEffect(() => {
    if (!ready) return;
    const start = (id: string | null) => {
      const { overview, launch, navigate } = latest.current;
      const instance = id && overview?.instances.find((i) => i.id === id);
      if (!instance) return;
      navigate({ page: "instance", id: instance.id });
      if (!overview?.running.includes(instance.id)) launch(instance.id, instance.name);
    };
    api.takeLaunchRequest().then(start).catch(() => {});
    const sub = onEvent<string>("launch-request", start);
    return () => {
      sub.then((un) => un());
    };
  }, [ready]);
}

function Shell() {
  const { route, overview } = useStore();
  useShortcutLaunch();
  const key = route.page + ("id" in route ? route.id : "") + ("target" in route ? (route.target ?? "") : "");
  // The shader stops while a game runs: the launcher should cost nothing in the background.
  const gameRunning = (overview?.running.length ?? 0) > 0;
  return (
    <div className="relative flex h-full flex-col">
      <Shader paused={gameRunning} />
      <TitleBar />
      <LauncherUpdateBanner />
      <div className="relative flex min-h-0 flex-1 gap-2 pb-4">
        <NavRail />
        <main className="relative min-w-0 flex-1">
          {/* A CSS enter animation: motion's exit/enter handoff left pages invisible in release builds. */}
          <div key={key} className="page-enter absolute inset-0">
            <Page />
          </div>
          <ProjectView />
        </main>
      </div>
      <Notifications />
      <Modals />
    </div>
  );
}

export default function App() {
  return (
    <StoreProvider>
      <Shell />
    </StoreProvider>
  );
}
