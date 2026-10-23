import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL('../artifacts/theme-scroll-qa/', import.meta.url);
const catalog = JSON.parse(await readFile(new URL('../public/themes/catalog.json', import.meta.url), 'utf8'));
const palette = JSON.parse(await readFile(new URL('../public/themes/palettes/dark.json', import.meta.url), 'utf8'));
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
const report = { timestamp: new Date().toISOString(), browser: browser.version(), checks: [], failures: [] };
async function check(name, callback) {
  try { const details = await callback(); report.checks.push({ name, details }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
async function open(page) {
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  if (!await page.locator('[data-theme-options] > summary').isVisible()) await page.locator('.mobile-menu > summary').click();
  await page.locator('[data-theme-options] > summary').click();
  await page.locator('[data-theme-menu] > summary').click();
  await page.locator('[data-theme-more]').click();
  await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview][data-loaded="true"]').length >= 5);
}
try {
  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    await check(`${viewport.width}: real vertical scrolling keeps at most9 cards and keyboard selection scrolls into view`, async () => {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce', hasTouch: viewport.width < 500 });
      const page = await context.newPage();
      try {
        await open(page);
        const scroll = page.locator('[data-theme-viewport]');
        const height = await scroll.evaluate((element) => ({ total: element.scrollHeight, visible: element.clientHeight, overflow: getComputedStyle(element).overflowY }));
        assert.ok(height.total > height.visible * 10); assert.equal(height.overflow, 'scroll');
        const pageTop = await page.evaluate(() => scrollY);
        const before = await page.locator('[data-theme-preview]').first().getAttribute('data-theme-preview');
        if (viewport.width < 500) {
          const bounds = await scroll.boundingBox(), session = await context.newCDPSession(page);
          await session.send('Input.synthesizeScrollGesture', { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2,
            yDistance: -360, speed: 800, gestureSourceType: 'touch' });
          await session.detach();
        } else { await scroll.hover(); await page.mouse.wheel(0, 360); }
        await page.waitForFunction((before) => document.querySelector('[data-theme-preview]')?.getAttribute('data-theme-preview') !== before, before);
        assert.ok(await scroll.evaluate((element) => element.scrollTop) > 0);
        assert.equal(await page.evaluate(() => scrollY), pageTop);
        assert.ok(await page.locator('[data-theme-preview]').count() <= 9);
        await scroll.evaluate((element) => { element.scrollTop = element.scrollHeight; });
        await page.waitForFunction((id) => document.querySelector(`[data-theme-preview="${id}"]`), catalog.at(-1).id);
        const last = page.locator(`[data-theme-preview="${catalog.at(-1).id}"]`);
        await last.click();
        await page.waitForFunction((id) => document.documentElement.dataset.theme === id, catalog.at(-1).id);
        await page.keyboard.press('PageUp');
        const selected = page.locator('[data-theme-preview][aria-selected="true"]');
        assert.equal(await selected.getAttribute('aria-setsize'), String(catalog.length));
        const selectedBounds = await selected.boundingBox(), viewportBounds = await scroll.boundingBox();
        assert.ok(selectedBounds.y >= viewportBounds.y - 1 && selectedBounds.y + selectedBounds.height <= viewportBounds.y + viewportBounds.height + 1);
        assert.ok(await page.locator('[data-theme-preview]').count() <= 9);
        const selectedId = await selected.getAttribute('data-theme-preview');
        await page.waitForFunction((id) => document.documentElement.dataset.theme === id, selectedId);
        await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview][data-loaded="true"]').length === document.querySelectorAll('[data-theme-preview]').length);
        await page.screenshot({ path: new URL(`scroll-${viewport.width}.png`, directory).pathname });
        return { ...height, maximumCardBound: 9, input: viewport.width < 500 ? 'Chromium touch gesture' : 'mouse wheel' };
      } finally { await context.close(); }
    });
  }
  await check('1,000 entries remain virtual and scrolling cannot cancel a pending selected palette', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const fixtures = Array.from({ length: 1000 }, (_, index) => ({ id: `scroll-${index}`, name: `Scroll fixture ${index}`, url: `/themes/scroll-fixtures/${index}.json`, previewBackground: palette.ui.background }));
    await page.route('**/themes/catalog.json', (route) => route.fulfill({ json: fixtures }));
    await page.route('**/themes/scroll-fixtures/*.json', async (route) => {
      const index = Number(new URL(route.request().url()).pathname.match(/\/(\d+)\.json$/)[1]);
      if (index === 4) await new Promise((resolve) => setTimeout(resolve, 500));
      await route.fulfill({ json: { ...palette, id: fixtures[index].id, name: fixtures[index].name } }).catch(() => {});
    });
    try {
      await open(page);
      await page.locator('[data-theme-preview="scroll-4"]').click();
      await page.locator('[data-theme-viewport]').evaluate((element) => { element.scrollTop = 1200; });
      await page.waitForFunction(() => !document.querySelector('[data-theme-preview="scroll-4"]'));
      await page.waitForFunction(() => document.documentElement.dataset.theme === 'scroll-4');
      assert.equal(await page.locator('[data-theme-apply]').isDisabled(), false);
      await page.locator('[data-theme-viewport]').evaluate((element) => { element.scrollTop = element.scrollHeight; });
      await page.waitForFunction(() => document.querySelector('[data-theme-preview="scroll-999"]'));
      assert.ok(await page.locator('[data-theme-preview]').count() <= 9);
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'scroll-4');
      await page.locator('[data-theme-cancel]').click();
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      return { fixtureCount: 1000, maximumCardBound: 9, pendingSelectionSurvived: true };
    } finally { await context.close(); }
  });
} finally { await browser.close(); await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2)); }
console.log(`${report.checks.length} passed, ${report.failures.length} failed; ${directory.pathname}report.json`);
if (report.failures.length) process.exitCode = 1;
