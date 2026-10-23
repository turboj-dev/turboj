import { defineConfig } from 'astro/config';

// Domain registered in the owner's Cloudflare account; override for another host.
const site = process.env.SITE_URL || 'https://turboj.dev';
export default defineConfig({
  site,
  output: 'static',
  trailingSlash: 'always',
  build: { inlineStylesheets: 'never' },
  compressHTML: true,
  vite: { build: { cssMinify: true } },
});
