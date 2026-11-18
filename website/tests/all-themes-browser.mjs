import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { resolveBrowserPath } from './browser-path.mjs';
import { artifactDirectory } from './qa-artifacts.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = artifactDirectory('all-themes', '../artifacts/all-themes/', import.meta.url);
const catalog = JSON.parse(await readFile(new URL('../public/themes/catalog.json', import.meta.url), 'utf8'));
const report = { timestamp: new Date().toISOString(), baseURL, browser: '', checks: [], failures: [] };
await mkdir(directory, { recursive: true });
const rgb = hex => `rgb(${[1, 3, 5].map(at => parseInt(hex.slice(at, at + 2), 16)).join(', ')})`;
function contrast(first, second) {
  const luminance = value => value.match(/[\d.]+/g).slice(0, 3).map(Number)
    .map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    .reduce((sum, v, index) => sum + v * [.2126, .7152, .0722][index], 0);
  const a = luminance(first), b = luminance(second);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelector('[data-turboj-demo]')?.dataset.loading === 'false');
  // A real recorded checkpoint supplies Java and output to inspect, without changing playback timing.
  await page.locator('[data-demo-action="skip"]').evaluate(button => /** @type {HTMLButtonElement} */ (button).click());
  const before = await page.locator('[data-turboj-demo]').evaluate(root => ({
    event: root.dataset.event, elapsed: root.dataset.elapsed,
    source: root.querySelector('[data-demo-source]').textContent,
    output: root.querySelector('[data-demo-output]').textContent,
  }));
  // Synchronously close/reopen through actual button handlers, then await the
  // old queued close event. It must not invalidate the newly opened session.
  await page.locator('[data-theme-more]').evaluate(button => /** @type {HTMLButtonElement} */ (button).click());
  await page.waitForFunction(() => !document.querySelector('[data-theme-apply]').disabled);
  assert.equal(await page.evaluate(() => new Promise(resolve => {
    const dialog = document.querySelector('[data-theme-browser]');
    dialog.addEventListener('close', () => resolve(dialog.open), { once: true });
    document.querySelector('[data-theme-cancel]').click();
    document.querySelector('[data-theme-more]').click();
  })), true);
  await page.waitForFunction(() => !document.querySelector('[data-theme-apply]').disabled);
  await page.keyboard.press('Enter');
  await page.locator('[data-theme-browser]').waitFor({ state: 'hidden' });
  report.reopen = 'PASS: an older close event cannot disable Enter/Apply in a newly reopened dialog';
  for (const entry of catalog) {
    try {
      const palette = JSON.parse(await readFile(new URL(`../public${entry.url}`, import.meta.url), 'utf8'));
      for (const selector of ['[data-theme-options]', '[data-theme-menu]']) {
        const details = page.locator(selector);
        if (!await details.evaluate(element => element.open)) {
          await details.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
        }
      }
      await page.locator('[data-theme-more]').focus(); await page.keyboard.press('Enter');
      await page.locator('[data-theme-browser]').waitFor({ state: 'visible' });
      await page.locator('[data-theme-search]').fill(entry.id);
      const card = page.locator(`[data-theme-preview="${entry.id}"]`);
      await card.focus(); await page.keyboard.press('Space');
      await page.waitForFunction(id => document.documentElement.dataset.theme === id
        && !document.querySelector('[data-theme-apply]').disabled, entry.id);
      await page.locator('[data-theme-apply]').focus(); await page.keyboard.press('Enter');
      await page.locator('[data-theme-browser]').waitFor({ state: 'hidden' });
      const observed = await page.evaluate(() => {
        const root = document.querySelector('[data-turboj-demo]');
        const read = selector => {
          const css = getComputedStyle(document.querySelector(selector));
          return { color: css.color, background: css.backgroundColor, font: css.fontSize };
        };
        const editor = read('.demo-editor'), console = read('.demo-console'), menu = read('.demo-menu');
        const style = getComputedStyle(document.documentElement);
        return { editor, console, menu,
          tokens: Object.fromEntries(['keyword', 'string', 'comment'].map(role => [role, style.getPropertyValue('--tj-syntax-' + role).trim()])),
          sourceFont: read('.demo-source').font,
          state: { event: root.dataset.event, elapsed: root.dataset.elapsed,
            source: root.querySelector('[data-demo-source]').textContent,
            output: root.querySelector('[data-demo-output]').textContent },
          saved: localStorage.getItem('turboj.theme'),
          fallbackVisible: document.querySelector('.demo-fallback').checkVisibility(),
          nodes: root.querySelectorAll('*').length,
        };
      });
      assert.equal(observed.saved, entry.id);
      assert.equal(observed.fallbackVisible, false);
      assert.deepEqual(observed.state, before, 'Changing theme must not reset replay state or measured intervals.');
      assert.ok(observed.nodes <= 250);
      const ratios = {};
      for (const [part, native] of [['editor', 'editor'], ['console', 'messages'], ['menu', 'menu']]) {
        assert.equal(observed[part].color, rgb(palette[native].foreground));
        assert.equal(observed[part].background, rgb(palette[native].background));
        assert.equal(observed[part].font, observed.sourceFont);
        ratios[part] = contrast(observed[part].color, observed[part].background);
        assert.ok(ratios[part] >= 4.5, `${part} text contrast ${ratios[part]}`);
      }
      for (const [role, value] of Object.entries(observed.tokens)) {
        assert.equal(value.toUpperCase(), palette.syntax[role].toUpperCase());
        ratios[role] = contrast(rgb(value), observed.editor.background);
        assert.ok(ratios[role] >= 4.5, `${role} text contrast ${ratios[role]}`);
      }
      report.checks.push({ id: entry.id, status: 'PASS', ratios });
      console.log(`PASS ${entry.id}: keyboard apply, native palette, contrast, shared font, preserved replay`);
    } catch (error) {
      report.failures.push({ id: entry.id, message: error.message });
      console.error(`FAIL ${entry.id}: ${error.message}`);
      // Do not let a failed modal prevent the remaining themes from being inspected.
      await page.keyboard.press('Escape');
    }
  }
  assert.deepEqual(errors, []);
  await context.close();
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2) + '\n');
}
console.log(`${report.checks.length}/${catalog.length} themes passed.`);
if (report.failures.length) process.exitCode = 1;
