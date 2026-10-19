import { AnimatePresence, motion } from "motion/react";
import { StoreProvider, useStore } from "./lib/store";
import { Browse } from "./pages/Browse";
import { Cosmetics } from "./pages/Cosmetics";
import { Home } from "./pages/Home";
import { InstanceDetail } from "./pages/InstanceDetail";
import { Instances } from "./pages/Instances";
import { SettingsPage } from "./pages/SettingsPage";
import { EASE } from "./ui/kit";
import { Modals } from "./ui/Modals";
import { Shader } from "./ui/Shader";
import { NavRail, Notifications, TitleBar } from "./ui/Shell";

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

function Shell() {
  const { route, overview } = useStore();
  const key = route.page + ("id" in route ? route.id : "") + ("target" in route ? (route.target ?? "") : "");
  // The shader stops while a game runs: the launcher should cost nothing in the background.
  const gameRunning = (overview?.running.length ?? 0) > 0;
  return (
    <div className="relative flex h-full flex-col">
      <Shader paused={gameRunning} />
      <TitleBar />
      <div className="relative flex min-h-0 flex-1 gap-2 pb-4">
        <NavRail />
        <main className="relative min-w-0 flex-1">
          <AnimatePresence mode="wait">
            <motion.div
              key={key}
              className="absolute inset-0"
              initial={{ opacity: 0, y: 10, filter: "blur(8px)" }}
              animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              exit={{ opacity: 0, y: -6, filter: "blur(4px)", transition: { duration: 0.14 } }}
              transition={{ duration: 0.34, ease: EASE }}
            >
              <Page />
            </motion.div>
          </AnimatePresence>
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
