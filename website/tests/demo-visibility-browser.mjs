import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL(`../${process.env.QA_ARTIFACT_ROOT ?? 'artifacts/hero-story-qa'}/visibility/`, import.meta.url);
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
const report = { timestamp: new Date().toISOString(), baseURL, browser: browser.version(), checks: [], failures: [] };
async function snapshot(page) {
  return page.evaluate(() => {
    const root = document.querySelector('[data-turboj-demo]');
    const rect = document.querySelector('.demo-stage').getBoundingClientRect();
    return { state: root.dataset.state, elapsed: Number(root.dataset.elapsed), manualPaused: root.dataset.manualPaused,
      rect: { top: rect.top, bottom: rect.bottom, height: rect.height }, batches: window.__visibilityBatches };
  });
}
try {
  for (const width of [320, 390]) for (const scenario of ['visible', 'hidden', 'manual pause']) {
    const name = `${width}px: latest queued ${scenario} observation governs playback`;
    const context = await browser.newContext({ viewport: { width, height: 844 }, reducedMotion: 'no-preference' });
    const page = await context.newPage();
    try {
      await page.addInitScript(() => {
        const NativeObserver = window.IntersectionObserver;
        window.__visibilityHeld = [];
        window.__visibilityBatches = [];
        window.__visibilityHold = false;
        // Retain actual browser-produced entries across rendering frames, then deliver
        // their original ordered objects as one legal callback batch. Never invent geometry.
        window.IntersectionObserver = class extends NativeObserver {
          constructor(callback, options) {
            super((entries, observer) => {
              if (!entries.some((entry) => entry.target.matches('.demo-stage'))) return callback(entries, observer);
              const deliver = (batch) => {
                window.__visibilityBatches.push(batch.map((entry) => ({ time: entry.time, ratio: entry.intersectionRatio, intersecting: entry.isIntersecting })));
                callback(batch, observer);
              };
              window.__visibilityDeliver = () => deliver(window.__visibilityHeld.splice(0));
              if (window.__visibilityHold) window.__visibilityHeld.push(...entries);
              else deliver(entries);
            }, options);
          }
        };
      });
      await page.goto(baseURL);
      await page.waitForFunction(() => Number(document.querySelector('[data-turboj-demo]')?.dataset.elapsed) > 50);
      const hide = () => page.evaluate(() => window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }));
      const reveal = () => page.locator('.demo-stage').evaluate((stage) => stage.scrollIntoView({ block: 'center', behavior: 'instant' }));
      if (scenario === 'hidden') {
        await hide();
        await page.waitForFunction(() => document.querySelector('[data-turboj-demo]').dataset.state === 'paused');
      } else if (scenario === 'manual pause') {
        await page.locator('[data-demo-action="pause"]').focus();
        await page.keyboard.press('Enter');
        assert.equal((await snapshot(page)).manualPaused, 'true');
      }
      await page.evaluate(() => { window.__visibilityHold = true; });
      if (scenario === 'hidden') await reveal(); else await hide();
      await page.waitForFunction(() => window.__visibilityHeld.length >= 1);
      if (scenario === 'hidden') await hide(); else await reveal();
      await page.waitForFunction(() => window.__visibilityHeld.length >= 2);
      const ratios = await page.evaluate(() => window.__visibilityHeld.map((entry) => entry.intersectionRatio));
      assert.equal(ratios[0] >= .15, scenario === 'hidden');
      assert.equal(ratios.at(-1) >= .15, scenario !== 'hidden');
      await page.evaluate(() => { window.__visibilityHold = false; window.__visibilityDeliver(); });
      const initial = await snapshot(page);
      if (scenario === 'visible') {
        await page.waitForFunction((before) => {
          const root = document.querySelector('[data-turboj-demo]');
          return root.dataset.state === 'playing' && Number(root.dataset.elapsed) > before + 50;
        }, initial.elapsed, { timeout: 1500 });
      } else {
        assert.equal(initial.state, 'paused');
        await page.waitForTimeout(150);
        assert.equal((await snapshot(page)).elapsed, initial.elapsed, 'A hidden or explicitly paused stage must not advance.');
      }
      if (scenario === 'manual pause') {
        await page.locator('[data-demo-action="pause"]').focus();
        await page.keyboard.press('Enter');
        await page.waitForFunction(() => document.querySelector('[data-turboj-demo]').dataset.state === 'playing');
      }
      report.checks.push({ name, status: 'PASS', ...(await snapshot(page)) });
      console.log(`PASS ${name}`);
    } catch (error) {
      report.failures.push({ name, message: error.message, ...(await snapshot(page).catch(() => ({}))) });
      console.error(`FAIL ${name}: ${error.message}`);
    } finally { await context.close(); }
  }
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2) + '\n');
}
console.log(`${report.checks.length} visibility checks passed; ${report.failures.length} failed.`);
if (report.failures.length) process.exitCode = 1;
