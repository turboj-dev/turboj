import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL('../artifacts/theme-latency/', import.meta.url);
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
const report = { timestamp: new Date().toISOString(), baseURL, browser: browser.version(), runs: [] };
const distribution = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  return Object.fromEntries([50, 95, 99].map((p) => [`p${p}`, sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100) - 1)]]));
};
async function menu(page) {
  if (!await page.locator('[data-theme-options] > summary').isVisible()) await page.locator('.mobile-menu > summary').click();
  await page.locator('[data-theme-options] > summary').click();
  await page.locator('[data-theme-menu] > summary').click();
}
async function opening(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const started = performance.now(); document.querySelector('[data-theme-more]').click();
    const frame = () => {
      const selected = document.querySelector('[data-theme-preview][aria-selected="true"][data-loaded="true"]');
      if (selected && !document.querySelector('[data-theme-apply]').disabled) resolve(performance.now() - started);
      else if (performance.now() - started > 5000) reject(new Error('No selected preview was ready within five seconds'));
      else requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }));
}
try {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
    const page = await context.newPage(); const requests = [];
    page.on('request', (request) => { if (new URL(request.url()).pathname.startsWith('/themes/')) requests.push(request.url()); });
    try {
      await page.goto(baseURL, { waitUntil: 'networkidle' }); await menu(page);
      const firstOpenFrameMs = await opening(page);
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview][data-loaded="true"]').length === 6);
      assert.match(await page.locator('[data-theme-page]').innerText(), /103 themes/);
      const bounds = await page.locator('[data-theme-browser]').boundingBox();
      assert.ok(bounds.width <= Math.min(720, viewport.width));
      if (viewport.width > 1000) assert.ok(bounds.height <= 520, `Catalog is ${bounds.height}px tall`);
      assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= viewport.height + 1);
      await page.screenshot({ path: new URL(`compact-${viewport.width}.png`, directory).pathname });
      const cachedRequestCount = requests.length;
      const samples = await page.evaluate(async () => {
        const firstCard = document.querySelector('[data-theme-preview]');
        const results = [];
        for (let index = 0; index < 20; index++) {
          const selected = document.querySelector('[data-theme-preview][aria-selected="true"]'); selected.focus();
          const started = performance.now();
          selected.dispatchEvent(new KeyboardEvent('keydown', { key: index % 2 ? 'ArrowLeft' : 'ArrowRight', bubbles: true }));
          const applied = performance.now();
          const frame = await new Promise((resolve) => requestAnimationFrame(() => resolve(performance.now())));
          results.push({ mutationMs: applied - started, nextFrameMs: frame - started });
        }
        if (firstCard !== document.querySelector('[data-theme-preview]')) throw new Error('Same-page arrows rebuilt the preview cards');
        return results;
      });
      assert.equal(requests.length, cachedRequestCount, 'Cached arrow movement should not fetch palettes again');
      await page.locator('[data-theme-cancel]').click(); await menu(page);
      const reopenFrameMs = await opening(page);
      assert.equal(requests.length, cachedRequestCount, 'Reopening should reuse metadata and cached previews');
      const run = { viewport, bounds, firstOpenFrameMs, reopenFrameMs,
        arrowMutationMs: distribution(samples.map((sample) => sample.mutationMs)),
        arrowNextFrameMs: distribution(samples.map((sample) => sample.nextFrameMs)), samples,
        paletteAndMetadataRequests: requests.length,
        method: 'Local production preview, first frame with a loaded selected palette;20 same-page cached synthetic arrow events. No CPU/network throttling; not a field latency claim.' };
      report.runs.push(run); console.log(JSON.stringify(run, null, 2));
    } finally { await context.close(); }
  }
} finally { await browser.close(); await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2)); }
