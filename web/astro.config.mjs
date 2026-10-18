import { defineConfig } from 'astro/config';

// SITE / BASE are set by the Pages workflow; local dev runs at the root.
export default defineConfig({
  site: process.env.SITE,
  base: process.env.BASE || '/',
  build: { inlineStylesheets: 'always' },
});
