import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  clearScreen: false,
  // The hat and pet catalogue lives at the repository root (shared with mod and server).
  server: { port: 1420, strictPort: true, fs: { allow: [".."] } },
  build: { target: "es2022" },
});
