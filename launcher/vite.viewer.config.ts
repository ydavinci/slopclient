import { defineConfig } from "vite";

// The 3D preview of the cosmetics server's web page: one self-contained script
// (three.js, skinview3d, hat and pet models, pet textures inlined), served by the
// server from crates/slopclient-cosmetics/src/web/viewer.js. Rebuild with
// `npm run build:viewer` after changing lib/extras.ts or cosmetics/pets.
export default defineConfig({
  publicDir: false,
  build: {
    target: "es2022",
    outDir: "../crates/slopclient-cosmetics/src/web",
    emptyOutDir: false,
    copyPublicDir: false,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    lib: {
      entry: "src/webviewer/main.ts",
      formats: ["iife"],
      name: "SlopViewerBundle",
      fileName: () => "viewer.js",
    },
  },
});
