import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';
import { validateManifest, validateChapter } from '../src/scripts/demo-timeline.ts';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const artifactRoot = process.env.QA_ARTIFACT_ROOT ?? 'artifacts/hero-story-qa';
const directory = new URL(`../${artifactRoot}/demo/scenes/`, import.meta.url);
await mkdir(directory, { recursive: true });
const manifest = validateManifest(JSON.parse(await readFile(new URL('../public/demo/hero-story/manifest.json', import.meta.url), 'utf8')));
const chapters = await Promise.all(manifest.chapters.map(async (entry) => validateChapter(JSON.parse(await readFile(new URL(`../public${entry.url}`, import.meta.url), 'utf8')), entry.id)));
const scenes = [
  { chapter: 2, event: 'rename-prepare-result', preview: true },
  { chapter: 2, event: 'extract-prepare-result', preview: true },
  { chapter: 1, event: 'tests-before-refactor-result', dialog: true },
  { chapter: 3, event: 'autotest-enabled', menu: true },
  { chapter: 4, event: 'spring-source', source: true },
  { chapter: 4, event: 'curl-result', external: true },
];
const report = { timestamp: new Date().toISOString(), baseURL, sessionId: manifest.id, browser: '', checks: [], failures: [] };
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();

async function check(name, callback) {
  try { const detail = await callback(); report.checks.push({ name, status: 'PASS', detail }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, status: 'FAIL', message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
async function selectScene(page, scene) {
  const chapter = chapters[scene.chapter];
  // Audit-only access: the product intentionally exposes just Play/Pause and its caption.
  await page.locator(`[data-demo-chapter="${scene.chapter}"]`).evaluate((button) => /** @type {HTMLButtonElement} */ (button).click());
  await page.waitForFunction((id) => {
    const root = document.querySelector('[data-turboj-demo]');
    return root?.dataset.chapter === id && root.dataset.loading === 'false';
  }, chapter.id);
  await page.locator('[data-demo-checkpoint]').evaluate((select, value) => {
    select.value = value; select.dispatchEvent(new Event('change', { bubbles: true }));
  }, scene.event);
  await page.waitForFunction((id) => document.querySelector('[data-turboj-demo]')?.dataset.event === id, scene.event);
  await page.locator('.demo-stage').scrollIntoViewIfNeeded();
  return chapter.events.find((event) => event.id === scene.event);
}
async function focusAndScroll(page, region) {
  await page.keyboard.press('Tab'); // establish keyboard modality before checking :focus-visible
  await region.focus();
  assert.equal(await region.evaluate((element) => element === document.activeElement), true);
  const before = await region.evaluate((element) => {
    const style = getComputedStyle(element);
    const bounds = element.getBoundingClientRect();
    element.scrollTop = 0;
    element.scrollLeft = 0;
    return { name: element.getAttribute('aria-label'), outlineStyle: style.outlineStyle, outlineWidth: parseFloat(style.outlineWidth), width: element.clientWidth, height: element.clientHeight, contentWidth: element.scrollWidth, contentHeight: element.scrollHeight, visible: bounds.width > 0 && bounds.height > 0 && !element.closest('[inert]') && style.visibility !== 'hidden' };
  });
  assert.equal(before.visible, true, `Focused region is hidden: ${JSON.stringify(before)}`);
  assert.ok(before.outlineStyle !== 'none' && before.outlineWidth >= 2, `Focused region lacks visible outline: ${JSON.stringify(before)}`);
  const horizontal = before.contentWidth > before.width + 1;
  const vertical = before.contentHeight > before.height + 1;
  if (horizontal || vertical) {
    await page.keyboard.press(horizontal ? 'ArrowRight' : 'ArrowDown');
    await page.waitForFunction(({ name, horizontal }) => {
      const element = [...document.querySelectorAll('[role="region"]')].find((item) => item.getAttribute('aria-label') === name);
      return element && (horizontal ? element.scrollLeft : element.scrollTop) > 0;
    }, { name: before.name, horizontal });
  }
  await page.keyboard.press('Tab');
  assert.equal(await region.evaluate((element) => element === document.activeElement), false, 'Tab must leave the scrollable recorded region.');
  return { ...before, keyboardScrolled: horizontal || vertical };
}

try {
  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('requestfailed', (request) => errors.push(`${request.url()}: ${request.failure()?.errorText}`));
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.querySelector('[data-turboj-demo]')?.dataset.loading === 'false');
    await page.evaluate(() => document.fonts.ready);
    for (const scene of scenes) {
      const name = `${viewport.width}px ${scene.event}`;
      const file = `${viewport.width}-${scene.event}`;
      let selected = false;
      await check(`${name}: faithful source, readable layout and keyboard scrolling`, async () => {
        const event = await selectScene(page, scene);
        selected = true;
        const chapter = chapters[scene.chapter];
        try {
          assert.equal(await page.locator('[data-demo-source]').textContent(), chapter.sources[event.sourceId].text);
          assert.equal(await page.locator('[data-demo-frame]').getAttribute('href'), event.screenshot);
          assert.ok(await page.locator('[data-turboj-demo] *').count() <= 250);
          const layout = await page.locator('.demo-stage').evaluate((element) => {
            const rect = element.getBoundingClientRect();
            const source = document.querySelector('[data-demo-source]');
            return { stageHeight: rect.height, viewportWidth: innerWidth, pageWidth: document.documentElement.scrollWidth, sourceFont: parseFloat(getComputedStyle(source).fontSize) };
          });
          assert.equal(layout.stageHeight, viewport.width === 390 ? 380 : 464);
          assert.ok(layout.pageWidth <= layout.viewportWidth + 1, JSON.stringify(layout));
          const focus = [];
          if (scene.preview) {
            const captured = chapter.dialogs[event.dialogId][0];
            const previewSources = captured.components.filter((item) => item.type === 'text' && item.text.includes('public class '));
            assert.equal(previewSources.length, 2);
            for (const [index, side] of ['before', 'after'].entries()) {
              const region = page.getByRole('region', { name: `Recorded ${side} source`, exact: true });
              assert.equal(await region.textContent(), previewSources[index].text);
              const metrics = await region.evaluate((element) => ({ font: parseFloat(getComputedStyle(element).fontSize), width: element.clientWidth, height: element.clientHeight }));
              assert.ok(metrics.font >= 14, `${side} Java text is below 14px: ${JSON.stringify(metrics)}`);
              assert.ok(metrics.width >= 100 && metrics.height >= 60, `Preview is too small to inspect: ${JSON.stringify(metrics)}`);
              focus.push(await focusAndScroll(page, region));
            }
            assert.equal(await page.locator('[data-demo-screen]').evaluate((element) => element.inert), true);
            if (scene.event.startsWith('rename')) assert.match(await page.locator('[data-demo-overlay]').textContent(), /WARNING: Reflection strings, generated sources and external binary consumers cannot be verified/);
          } else if (scene.dialog) {
            const dialog = chapter.dialogs[event.dialogId][0];
            const region = page.getByRole('region', { name: `Recorded native dialog: ${dialog.title}`, exact: true });
            assert.equal(await region.isVisible(), true);
            assert.match(await region.textContent(), /2 passed.*0 failed/);
            focus.push(await focusAndScroll(page, region));
          } else if (scene.menu) {
            const region = page.getByRole('region', { name: 'Recorded native menu', exact: true });
            assert.match(await region.textContent(), /\[x\] Auto Test on Save/);
            assert.match(await region.textContent(), /\[ \] Auto Run on Save/);
            focus.push(await focusAndScroll(page, region));
          } else if (scene.source) {
            assert.ok(layout.sourceFont >= 14);
            assert.equal(await page.locator('[data-demo-explorer]').isVisible(), true);
            assert.match(await page.locator('[data-demo-explorer-rows]').textContent(), /spring-hello · Java 21/);
            focus.push(await focusAndScroll(page, page.getByRole('region', { name: 'Recorded source; use arrow keys to scroll', exact: true })));
            focus.push(await focusAndScroll(page, page.getByRole('region', { name: 'Recorded Maven project explorer; use arrow keys to scroll', exact: true })));
          } else {
            const region = page.getByRole('region', { name: 'Recorded external curl command and output', exact: true });
            assert.equal(await region.textContent(), chapter.outputs[event.outputId]);
            assert.match(await region.textContent(), /Hello from TurboJ!/);
            assert.equal(await page.locator('[data-demo-screen]').isVisible(), false);
            focus.push(await focusAndScroll(page, region));
          }
          assert.equal(await page.locator('[data-turboj-demo]').getAttribute('data-event'), scene.event, 'Keyboard inspection must not advance reduced-motion playback.');
          return { ...layout, focus, originalCapture: event.screenshot };
        } finally {
          // Retain the exact checkpoint even when a layout/focus assertion fails.
          await page.locator('.demo-stage').screenshot({ path: new URL(`${file}.png`, directory).pathname });
          await page.screenshot({ path: new URL(`${file}-viewport.png`, directory).pathname });
        }
      });
      if (selected) await check(`${name}: WCAG 2.2 AA automated audit`, async () => {
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
        await writeFile(new URL(`${file}-axe.json`, directory), JSON.stringify(audit, null, 2));
        assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({ id, impact, targets: nodes.map(({ target }) => target) })), []);
        return { passed: audit.passes.length, incomplete: audit.incomplete.length };
      });
    }
    await check(`${viewport.width}px: recorded scenes produce no browser or asset errors`, async () => assert.deepEqual(errors, []));
    await context.close();
  }
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} recorded-scene checks passed; ${report.failures.length} failed. Artifacts: ${directory.pathname}`);
if (report.failures.length) process.exitCode = 1;
