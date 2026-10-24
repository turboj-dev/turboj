import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL('../artifacts/session-qa/', import.meta.url);
await mkdir(directory, { recursive: true });
const report = { timestamp: new Date().toISOString(), baseURL, checks: [], failures: [] };
async function check(name, operation) {
  try { const detail = await operation(); report.checks.push({ name, status: 'passed', detail }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
try {
  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const problems = [];
    page.on('pageerror', (error) => problems.push(error.message));
    page.on('response', (response) => { if (response.status() >= 400) problems.push(`${response.status()} ${response.url()}`); });
    await page.goto(`${baseURL}/session/`, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await check(`${viewport.width}px: evidence page remains distinct from the illustration and fits the viewport`, async () => {
      assert.match(await page.locator('main').innerText(), /separate, illustrated HelloWorld sequence/);
      assert.match(await page.locator('main').innerText(), /not guaranteed performance/);
      const layout = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth }));
      assert.ok(layout.page <= layout.width + 1, JSON.stringify(layout));
      await page.screenshot({ path: new URL(`session-${viewport.width}.png`, directory).pathname });
      return layout;
    });
    await check(`${viewport.width}px: all four evidence downloads return their real artifacts`, async () => {
      const downloads = await page.locator('a[download]').evaluateAll((links) => links.map((link) => link.href));
      assert.equal(downloads.length, 4);
      const results = [];
      for (const href of downloads) {
        const response = await page.request.get(href);
        assert.equal(response.status(), 200, href);
        const bytes = await response.body();
        assert.ok(bytes.length > 100, href);
        if (href.endsWith('.json')) JSON.parse(bytes);
        if (href.endsWith('.zip')) assert.equal(bytes.subarray(0, 2).toString(), 'PK');
        results.push({ path: new URL(href).pathname, bytes: bytes.length });
      }
      return results;
    });
    await check(`${viewport.width}px: source captures open by keyboard without page overflow or failed images`, async () => {
      const captures = page.locator('details');
      assert.equal(await captures.count(), 5);
      for (const item of await captures.all()) {
        await item.locator('summary').focus();
        await page.keyboard.press('Enter');
        assert.equal(await item.evaluate((element) => element.open), true);
        const image = item.locator('img');
        await image.scrollIntoViewIfNeeded();
        await image.evaluate(async (image) => { if (!image.complete) await new Promise((resolve, reject) => { image.onload = resolve; image.onerror = reject; }); });
        assert.ok(await image.evaluate((image) => image.naturalWidth === 1060 && image.naturalHeight === 740));
      }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      assert.deepEqual(problems, []);
    });
    await check(`${viewport.width}px: expanded evidence page passes automated WCAG checks`, async () => {
      const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
      await writeFile(new URL(`axe-${viewport.width}.json`, directory), JSON.stringify(audit, null, 2));
      assert.deepEqual(audit.violations.map(({ id, nodes }) => ({ id, targets: nodes.map(({ target }) => target) })), []);
    });
    await context.close();
  }
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} session checks passed; ${report.failures.length} failed.`);
if (report.failures.length) process.exitCode = 1;
