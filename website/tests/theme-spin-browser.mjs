import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL('../artifacts/theme-spin-qa/', import.meta.url);
const shippedCatalog = JSON.parse(await readFile(new URL('../public/themes/catalog.json', import.meta.url), 'utf8'));
const report = { timestamp: new Date().toISOString(), baseURL, browser: '', catalogCount: shippedCatalog.length, checks: [], failures: [] };
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();

async function check(name, callback) {
  try { const details = await callback(); report.checks.push({ name, ...(details ? { details } : {}) }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
const rgb = (hex) => `rgb(${[1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16)).join(', ')})`;
async function openCatalog(page) {
  const options = page.locator('[data-theme-options]');
  if (!await options.locator(':scope > summary').isVisible()) await page.locator('.mobile-menu > summary').click();
  await options.locator(':scope > summary').click();
  await page.locator('[data-theme-menu] > summary').click();
  await page.locator('[data-theme-more]').click();
  await page.locator('[data-theme-random]').waitFor({ state: 'visible' });
  await page.waitForFunction(() => !document.querySelector('[data-theme-random]').disabled);
}
async function prepare(page) {
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('qa-spin-seeded')) { localStorage.setItem('turboj.theme', 'classic'); sessionStorage.setItem('qa-spin-seeded', 'true'); }
  });
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  assert.equal(await page.locator('[data-turboj-demo]').getAttribute('data-state'), 'completed');
}
async function demo(page) {
  return page.locator('[data-turboj-demo]').evaluate((element) => ({
    source: element.querySelector('[data-demo-source]').textContent,
    cursor: element.querySelector('[data-demo-cursor]').getAttribute('style'),
    output: element.querySelector('[data-demo-output]').textContent,
    elapsed: element.dataset.elapsed, state: element.dataset.state,
  }));
}
async function expectedPalette(page, id) {
  const entry = shippedCatalog.find((theme) => theme.id === id);
  assert.ok(entry, `${id} is not in the real published catalog`);
  const response = await page.request.get(new URL(entry.url, baseURL).href);
  assert.equal(response.status(), 200);
  return response.json();
}
async function finishSpin(page) {
  await page.waitForFunction(() => document.querySelector('[data-theme-spin]').dataset.state === 'completed', null, { timeout: 10000 });
  await page.waitForFunction(() => !document.querySelector('[data-theme-apply]').disabled);
  return page.locator('[data-theme-spin]').getAttribute('data-target');
}

try {
  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    await check(`${viewport.width}: reduced motion chooses a genuine theme immediately; preview, Apply and reload preserve it`, async () => {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage(); const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
      try {
        assert.ok(shippedCatalog.length >= 103, `Expected the three primary themes plus 100 additional real themes; got ${shippedCatalog.length}`);
        assert.equal(new Set(shippedCatalog.map((theme) => theme.id)).size, shippedCatalog.length);
        await prepare(page); const before = await demo(page); await openCatalog(page);
        assert.equal(await page.locator('[data-theme-preview]').count(), Math.min(6, shippedCatalog.length));
        const started = Date.now();
        await page.locator('[data-theme-random]').click(); const chosen = await finishSpin(page);
        const elapsed = Date.now() - started; assert.ok(elapsed < 2500, `Reduced motion took ${elapsed}ms`);
        const palette = await expectedPalette(page, chosen);
        assert.equal(await page.locator('html').getAttribute('data-theme'), chosen);
        assert.equal(await page.locator('.demo-editor').evaluate((element) => getComputedStyle(element).backgroundColor), rgb(palette.editor.background));
        assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
        assert.deepEqual(await demo(page), before);
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
        await writeFile(new URL(`axe-result-${viewport.width}.json`, directory), JSON.stringify(audit, null, 2));
        assert.deepEqual(audit.violations.map(({ id, nodes }) => ({ id, targets: nodes.map(({ target }) => target) })), []);
        const overflowing = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth || document.querySelector('[data-theme-browser]').scrollWidth > document.querySelector('[data-theme-browser]').clientWidth);
        assert.equal(overflowing, false);
        await page.screenshot({ path: new URL(`result-${viewport.width}.png`, directory).pathname });
        await page.locator('[data-theme-apply]').click();
        assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), chosen);
        await page.reload({ waitUntil: 'networkidle' });
        assert.equal(await page.locator('html').getAttribute('data-theme'), chosen);
        assert.deepEqual(errors, []);
        return { chosen, reducedMotionElapsedMs: elapsed };
      } finally { await context.close(); }
    });
  }

  await check('Random theme uses only the current search results and is disabled when nothing matches', async () => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await prepare(page); await openCatalog(page);
      const chosen = shippedCatalog.find((entry) => shippedCatalog.filter((other) => other.id.includes(entry.id)
        || other.name.toLowerCase().includes(entry.id)).length === 1);
      assert.ok(chosen);
      await page.locator('[data-theme-search]').fill(chosen.id);
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview]').length === 1);
      await page.locator('[data-theme-random]').click(); assert.equal(await finishSpin(page), chosen.id);
      await page.locator('[data-theme-search]').fill('no-such-theme-qa');
      assert.equal(await page.locator('[data-theme-random]').isDisabled(), true);
      assert.equal(await page.locator('[data-theme-apply]').isDisabled(), true);
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
    } finally { await context.close(); }
  });

  await check('real-time ASCII spin previews every real centered theme globally, decelerates for 3–5 seconds and suspends while hidden', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await prepare(page); const before = await demo(page); await page.emulateMedia({ reducedMotion: 'no-preference' }); await openCatalog(page);
      await page.evaluate(() => {
        window.__themeCrossings = [];
        new MutationObserver(() => {
          const spin = document.querySelector('[data-theme-spin]');
          if (!['spinning', 'loading', 'completed'].includes(spin.dataset.state)) return;
          const id = document.documentElement.dataset.theme;
          if (window.__themeCrossings.at(-1)?.id === id) return;
          window.__themeCrossings.push({ id, time: performance.now(), centered: spin.dataset.current,
            background: getComputedStyle(document.querySelector('.demo-editor')).backgroundColor,
            pageBackground: getComputedStyle(document.body).backgroundColor,
            consoleBackground: getComputedStyle(document.querySelector('.demo-console')).backgroundColor });
        }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
      });
      await page.locator('[data-theme-random]').click();
      await page.waitForFunction(() => document.querySelector('[data-theme-spin]').dataset.state === 'spinning');
      const started = Date.now();
      const duration = Number(await page.locator('[data-theme-spin]').getAttribute('data-duration'));
      assert.ok(duration >= 3000 && duration <= 5000);
      assert.equal(await page.locator('[data-theme-search]').isDisabled(), true);
      assert.equal(await page.locator('[data-theme-apply]').isDisabled(), true);
      assert.equal(await page.locator('[data-theme-random]').isDisabled(), true);
      await page.waitForFunction(() => Number(document.querySelector('[data-theme-spin]').dataset.position) % 1 > .05);
      assert.notEqual(await page.locator('[data-theme-drum]').evaluate((element) => getComputedStyle(element).transform), 'none');
      await page.waitForFunction(() => window.__themeCrossings.length >= 2);
      await page.screenshot({ path: new URL('ascii-spin-1440.png', directory).pathname });
      // Dispatch the browser visibility contract explicitly; headless tabs remain visible.
      await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange')); });
      const paused = await page.locator('[data-theme-spin]').getAttribute('data-elapsed');
      await page.waitForTimeout(650);
      assert.equal(await page.locator('[data-theme-spin]').getAttribute('data-elapsed'), paused);
      await page.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); });
      const chosen = await finishSpin(page);
      const elapsed = Date.now() - started;
      assert.ok(elapsed >= duration + 550 && elapsed <= duration + 2000, `duration=${duration}, wall=${elapsed}`);
      const crossings = await page.evaluate(() => window.__themeCrossings);
      assert.equal(crossings.length, Math.round(duration / 125) + 1);
      assert.equal(new Set(crossings.map(({ id }) => id)).size, crossings.length);
      for (let i = 0; i < crossings.length; i++) {
        const crossing = crossings[i], palette = await expectedPalette(page, crossing.id);
        assert.equal(crossing.centered, crossing.id, 'Centered name must match the global palette');
        assert.equal(crossing.background, rgb(palette.editor.background));
        assert.equal(crossing.pageBackground, rgb(palette.ui.background));
        assert.equal(crossing.consoleBackground, rgb(palette.messages.background));
      }
      const initialInterval = crossings[1].time - crossings[0].time;
      const finalInterval = crossings.at(-1).time - crossings.at(-2).time;
      assert.ok(initialInterval < 120, `Initial passage is too slow: ${initialInterval}ms`);
      assert.ok(finalInterval > initialInterval * 3, 'The drum must decelerate clearly');
      assert.equal(crossings.at(-1).id, chosen);
      assert.equal(await page.locator('html').getAttribute('data-theme'), chosen);
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
      assert.deepEqual(await demo(page), before);
      await page.locator('[data-theme-cancel]').click();
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      return { configuredDurationMs: duration, wallDurationWith650msSuspension: elapsed, crossings };
    } finally { await context.close(); }
  });

  await check('Escape cancels an active spin and its asynchronous previews; Skip lands on the prepared target', async () => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await prepare(page); const before = await demo(page); await page.emulateMedia({ reducedMotion: 'no-preference' }); await openCatalog(page);
      await page.locator('[data-theme-random]').click();
      await page.waitForFunction(() => document.querySelector('[data-theme-spin]').dataset.state === 'spinning');
      await page.waitForTimeout(900);
      await page.screenshot({ path: new URL('ascii-spin-390.png', directory).pathname });
      await page.keyboard.press('Escape');
      await page.locator('[data-theme-browser]').waitFor({ state: 'hidden' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      await page.waitForTimeout(800);
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      assert.equal(await page.locator('[data-theme-spin]').getAttribute('data-state'), 'idle');
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
      assert.deepEqual(await demo(page), before);
      await openCatalog(page); await page.locator('[data-theme-random]').click();
      await page.locator('[data-theme-skip]').click();
      const chosen = await finishSpin(page);
      assert.equal(await page.locator('html').getAttribute('data-theme'), chosen);
      await page.locator('[data-theme-apply]').click();
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), chosen);
      assert.deepEqual(await demo(page), before);
      return { chosen };
    } finally { await context.close(); }
  });

  await check('visibility changes during preload cannot start the drum before its genuine palettes are ready', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await prepare(page); await openCatalog(page);
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview][data-loaded="true"]').length === 6);
      await page.route('**/themes/palettes/*.json', async (route) => {
        const target = await page.locator('[data-theme-spin]').getAttribute('data-target');
        if (!new URL(route.request().url()).pathname.endsWith(`/${target}.json`)) await new Promise((resolve) => setTimeout(resolve, 700));
        await route.continue().catch(() => {});
      });
      await page.emulateMedia({ reducedMotion: 'no-preference' });
      await page.locator('[data-theme-random]').click();
      await page.waitForFunction(() => document.querySelector('[data-theme-spin]').dataset.targetReady === 'true');
      await page.evaluate(() => {
        Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange'));
        delete document.hidden; document.dispatchEvent(new Event('visibilitychange'));
      });
      await page.waitForTimeout(150);
      assert.equal(await page.locator('[data-theme-spin]').getAttribute('data-elapsed'), '0');
      assert.equal(await page.locator('[data-theme-spin]').getAttribute('data-position'), '0');
      await page.waitForFunction(() => document.querySelector('[data-theme-spin]').dataset.state === 'spinning');
      await page.locator('[data-theme-skip]').click(); await finishSpin(page);
    } finally { await context.close(); }
  });
} finally {
  await browser.close(); await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} passed, ${report.failures.length} failed; ${directory.pathname}report.json`);
if (report.failures.length) process.exitCode = 1;
