import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';
import { screenshotAlt } from './copy.mjs';
import { seekChapter, validateManifest, validateChapter } from '../src/scripts/demo-timeline.ts';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL('../artifacts/hero-story-qa/demo/', import.meta.url);
await mkdir(directory, { recursive: true });
const manifest = validateManifest(JSON.parse(await readFile(new URL('../public/demo/hero-story/manifest.json', import.meta.url), 'utf8')));
const chapters = await Promise.all(manifest.chapters.map(async (entry) => validateChapter(JSON.parse(await readFile(new URL(`../public${entry.url}`, import.meta.url), 'utf8')), entry.id)));
const report = { timestamp: new Date().toISOString(), baseURL, sessionId: manifest.id, browser: '', checks: [], failures: [] };
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();
async function check(name, callback) {
  try { const detail = await callback(); report.checks.push({ name, status: 'PASS', detail }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
function problems(page) {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('requestfailed', (request) => errors.push(`${request.url()}: ${request.failure()?.errorText}`));
  return errors;
}
async function state(page) {
  return page.locator('[data-turboj-demo]').evaluate((root) => {
    const text = (name) => root.querySelector(`[data-demo-${name}]`)?.textContent ?? '';
    return { state: root.dataset.state, chapter: root.dataset.chapter, event: root.dataset.event, elapsed: Number(root.dataset.elapsed), source: text('source'), filename: text('filename'), output: text('output'), external: text('external-output'), announcement: text('announcement'), manualPaused: root.dataset.manualPaused };
  });
}
async function ready(page, index = 0) {
  await page.waitForFunction((id) => {
    const root = document.querySelector('[data-turboj-demo]');
    return root?.dataset.chapter === id && root.dataset.loading === 'false';
  }, chapters[index].id);
}
async function choose(page, index) {
  await page.locator(`[data-demo-chapter="${index}"]`).click();
  await ready(page, index);
}
async function assertSnapshot(page, index, atMs) {
  const actual = await state(page);
  const { event } = seekChapter(chapters[index], atMs);
  assert.equal(actual.chapter, chapters[index].id);
  assert.equal(actual.event, event.id);
  assert.equal(actual.filename, event.filename);
  assert.equal(actual.source, chapters[index].sources[event.sourceId].text);
  assert.equal(actual.output, event.external ? '' : chapters[index].outputs[event.outputId]);
  assert.equal(actual.external, event.external ? chapters[index].outputs[event.outputId] : '');
  assert.ok(await page.locator('[data-turboj-demo] *').count() <= 250, `DOM budget exceeded at ${event.id}`);
  return actual;
}
async function skip(page, index) {
  await page.locator('[data-demo-action="skip"]').click();
  return assertSnapshot(page, index, chapters[index].durationMs);
}
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'no-preference' });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = problems(page);
  const requests = [];
  page.on('request', (request) => requests.push(request.url()));
  await page.addInitScript(() => {
    window.__demoSamples = [];
    window.__demoLongTasks = [];
    window.__rafRequests = 0;
    const pending = new Set();
    const request = window.requestAnimationFrame.bind(window);
    const cancel = window.cancelAnimationFrame.bind(window);
    window.requestAnimationFrame = (callback) => {
      window.__rafRequests++;
      const id = request((time) => { pending.delete(id); callback(time); });
      pending.add(id); return id;
    };
    window.cancelAnimationFrame = (id) => { pending.delete(id); cancel(id); };
    window.__pendingRaf = () => pending.size;
    new PerformanceObserver((list) => window.__demoLongTasks.push(...list.getEntries().map(({ startTime, duration }) => ({ startTime, duration })))).observe({ type: 'longtask', buffered: true });
    new MutationObserver(() => {
      const root = document.querySelector('[data-turboj-demo]');
      if (!root?.dataset.event) return;
      const sample = { at: performance.now(), elapsed: Number(root.dataset.elapsed), chapter: root.dataset.chapter, event: root.dataset.event, source: root.querySelector('[data-demo-source]').textContent, output: root.querySelector('[data-demo-output]').textContent };
      const last = window.__demoSamples.at(-1);
      if (!last || last.event !== sample.event || last.chapter !== sample.chapter) window.__demoSamples.push(sample);
    }).observe(document, { subtree: true, attributes: true, attributeFilter: ['data-event', 'data-chapter'], childList: true, characterData: true });
  });
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
  await ready(page);
  await check('initial Hello World advances at actual captured intervals and holds without continuing', async () => {
    await page.waitForFunction(() => document.querySelector('[data-turboj-demo]')?.dataset.state === 'completed', null, { timeout: chapters[0].durationMs + 15000 });
    const final = await assertSnapshot(page, 0, chapters[0].durationMs);
    const samples = (await page.evaluate(() => window.__demoSamples)).filter((item) => item.chapter === chapters[0].id);
    for (const sample of samples) {
      const event = chapters[0].events.find((item) => item.id === sample.event);
      assert.ok(event.atMs <= sample.elapsed + .02, `Early event ${event.id}: ${event.atMs} > ${sample.elapsed}`);
      assert.equal(sample.source, chapters[0].sources[event.sourceId].text);
      assert.equal(sample.output, chapters[0].outputs[event.outputId]);
    }
    for (const operation of chapters[0].operations) {
      const observedEnd = samples.find((sample) => sample.elapsed >= operation.endMs);
      assert.ok(observedEnd, `Missing result observation for ${operation.id}`);
    }
    await page.waitForTimeout(350);
    assert.deepEqual(await state(page), final);
    assert.equal(requests.filter((url) => manifest.chapters.slice(1).some((entry) => url.endsWith(entry.url))).length, 0, 'Later chapters must not load eagerly.');
    assert.equal(requests.filter((url) => /\/demo\/hero-story\/.*\.(?:png|zip|mp4|webm)(?:\?|$)/.test(url)).length, 0, 'No capture archive, screenshots, or video may load with the hero.');
    await writeFile(new URL('hello-observations.json', directory), JSON.stringify(samples, null, 2));
    return { capturedDurationMs: chapters[0].durationMs, observedCheckpoints: samples.length };
  });
  await check('keyboard pause/resume, hidden-page, offscreen, and manual-pause clocks suspend without skipping', async () => {
    await page.locator('[data-demo-action="replay"]').click();
    await ready(page);
    await page.waitForFunction(() => Number(document.querySelector('[data-turboj-demo]').dataset.elapsed) > 100);
    const pause = page.locator('[data-demo-action="pause"]');
    await pause.focus(); await page.keyboard.press('Enter');
    const frozen = await state(page);
    assert.equal(frozen.state, 'paused');
    await page.waitForTimeout(200); assert.deepEqual(await state(page), frozen);
    const quiet = await page.evaluate(() => ({ pending: window.__pendingRaf(), requests: window.__rafRequests }));
    await page.waitForTimeout(200);
    assert.deepEqual(await page.evaluate(() => ({ pending: window.__pendingRaf(), requests: window.__rafRequests })), quiet);
    assert.equal(quiet.pending, 0, 'Paused replay must schedule no animation callbacks.');
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(100); await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(150); assert.deepEqual(await state(page), frozen);
    await pause.focus(); await page.keyboard.press('Space');
    await page.waitForFunction((at) => Number(document.querySelector('[data-turboj-demo]').dataset.elapsed) > at + 50, frozen.elapsed);
    await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
    const hidden = await state(page); await page.waitForTimeout(200); assert.deepEqual(await state(page), hidden);
    await page.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); });
    await page.waitForFunction((at) => Number(document.querySelector('[data-turboj-demo]').dataset.elapsed) > at + 50, hidden.elapsed);
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)); await page.waitForTimeout(100);
    const offscreen = await state(page); await page.waitForTimeout(200); assert.deepEqual(await state(page), offscreen);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForFunction((at) => Number(document.querySelector('[data-turboj-demo]').dataset.elapsed) > at + 50, offscreen.elapsed);
    await skip(page, 0);
    return { note: 'Visibility handler tested with synthetic document.hidden event; not an OS background-tab timing claim.' };
  });
  await check('every chapter restores independently and every static checkpoint matches recorded source/output', async () => {
    for (const index of [4, 2, 1, 3, 0]) {
      await choose(page, index); await assertSnapshot(page, index, 0);
      for (const checkpoint of chapters[index].checkpoints) {
        await page.locator('[data-demo-checkpoint]').selectOption(checkpoint.id);
        await assertSnapshot(page, index, checkpoint.atMs);
      }
      await skip(page, index);
    }
    assert.equal(requests.filter((url) => /\/hello(?:\?|$)/.test(url)).length, 0, 'Browser must never call the recorded localhost endpoint.');
  });
  await check('Rename and Extract Method render the real read-only fields, before/after previews, and warnings', async () => {
    await choose(page, 2);
    for (const id of ['rename-form', 'rename-prepare-result', 'extract-form', 'extract-prepare-result']) {
      await page.locator('[data-demo-checkpoint]').selectOption(id);
      const event = chapters[2].events.find((item) => item.id === id);
      const dialogs = chapters[2].dialogs[event.dialogId];
      const overlay = page.locator('[data-demo-overlay]');
      assert.equal(await overlay.isVisible(), true);
      for (const dialog of dialogs) {
        const native = page.getByRole('region', { name: `Recorded native dialog: ${dialog.title}`, exact: true });
        assert.equal(await native.isVisible(), true);
        for (const item of dialog.components.filter((item) => item.type !== 'button' || !/^[v^<>x]$/.test(item.text))) {
          assert.ok((await native.textContent()).includes(item.text), `Omitted captured component: ${item.text}`);
        }
      }
      assert.equal(await overlay.locator('button,input,select').count(), 0, 'Recorded controls must not impersonate live IDE actions.');
      assert.equal(await page.locator('[data-demo-frame]').getAttribute('href'), event.screenshot);
      if (id.includes('prepare-result')) {
        const before = page.getByRole('region', { name: 'Recorded before source', exact: true });
        const after = page.getByRole('region', { name: 'Recorded after source', exact: true });
        assert.notEqual(await before.textContent(), await after.textContent());
        assert.ok(await after.evaluate((element) => parseFloat(getComputedStyle(element).fontSize) >= 14));
      }
      assert.ok(await page.locator('[data-turboj-demo] *').count() <= 250);
    }
  });
  await check('recorded Auto Test and Auto Run menus show their independent checked states', async () => {
    await choose(page, 3);
    for (const [id, on, off] of [['autotest-enabled', 'Auto Test on Save', 'Auto Run on Save'], ['autorun-enabled', 'Auto Run on Save', 'Auto Test on Save']]) {
      await page.locator('[data-demo-checkpoint]').selectOption(id);
      const menu = page.getByRole('region', { name: 'Recorded native menu', exact: true });
      assert.ok((await menu.textContent()).includes(`[x] ${on}`));
      assert.ok((await menu.textContent()).includes(`[ ] ${off}`));
    }
  });
  await check('theme changes preserve the exact paused source/output and elapsed time', async () => {
    await choose(page, 2);
    await page.locator('[data-demo-checkpoint]').selectOption(chapters[2].checkpoints.at(-1).id);
    const before = await state(page);
    await page.locator('[data-theme-options], [data-theme-menu]').evaluateAll((items) => items.forEach((item) => { item.open = true; }));
    await page.locator('[data-theme-choice="dark"]').click();
    assert.deepEqual(await state(page), before);
    await page.locator('[data-theme-options], [data-theme-menu]').evaluateAll((items) => items.forEach((item) => { item.open = false; }));
  });
  await check('100 chapter switches do not accumulate DOM nodes or replay state', async () => {
    const cdp = await context.newCDPSession(page);
    await cdp.send('HeapProfiler.collectGarbage');
    const beforeHeap = await cdp.send('Runtime.getHeapUsage');
    let maximum = 0;
    for (let index = 0; index < 100; index++) {
      await choose(page, index % 5);
      await skip(page, index % 5);
      maximum = Math.max(maximum, await page.locator('[data-turboj-demo] *').count());
    }
    assert.ok(maximum <= 250, `Demo DOM grew to ${maximum} elements.`);
    await cdp.send('HeapProfiler.collectGarbage');
    const afterHeap = await cdp.send('Runtime.getHeapUsage');
    assert.ok(afterHeap.usedSize - beforeHeap.usedSize < 3 * 1024 * 1024, 'Unexpected retained heap growth after repeated switching.');
    await cdp.detach();
    return { switches: 100, maximumElements: maximum, retainedHeapGrowthBytes: afterHeap.usedSize - beforeHeap.usedSize, longTasks: await page.evaluate(() => window.__demoLongTasks) };
  });
  await check('Play full story traverses all five chapters at recorded system intervals', async () => {
    await page.evaluate(() => { window.__demoSamples = []; });
    await page.locator('[data-demo-action="story"]').click();
    await page.evaluate(() => window.scrollTo(0, 0));
    const total = chapters.reduce((sum, chapter) => sum + chapter.durationMs, 0);
    await page.waitForFunction((id) => {
      const root = document.querySelector('[data-turboj-demo]');
      return root?.dataset.chapter === id && root.dataset.state === 'completed';
    }, chapters.at(-1).id, { timeout: total + 20000 });
    await assertSnapshot(page, 4, chapters[4].durationMs);
    const samples = await page.evaluate(() => window.__demoSamples);
    const seen = new Set();
    for (const sample of samples) {
      const chapter = chapters.find((item) => item.id === sample.chapter);
      const event = chapter.events.find((item) => item.id === sample.event);
      // During an explicit chapter load the prior event is hidden behind the fallback.
      if (!event) continue;
      seen.add(chapter.id);
      assert.ok(event.atMs <= sample.elapsed + .02, `Early replay result ${event.id}`);
      assert.equal(sample.source, chapter.sources[event.sourceId].text);
    }
    assert.deepEqual([...seen], chapters.map((chapter) => chapter.id));
    await writeFile(new URL('full-story-observations.json', directory), JSON.stringify(samples, null, 2));
    return { recordedPlaybackDurationMs: total, observedCheckpoints: samples.length };
  });
  await check('controls and code regions have accessible names; source is outside live regions; browser is error-free', async () => {
    for (const action of ['pause', 'replay', 'previous', 'next', 'skip', 'story']) {
      const button = page.locator(`[data-demo-action="${action}"]`);
      assert.ok((await button.getAttribute('aria-label')) || (await button.innerText()).trim());
    }
    assert.equal(await page.locator('[data-demo-source]').evaluate((element) => element.closest('[aria-live]:not([aria-live="off"])')), null);
    assert.deepEqual(errors, []);
  });
  await context.close();

  await check('late chapter fetches cannot overwrite a newer selection', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    await page.route(`**${manifest.chapters[2].url}`, async (route) => { await new Promise((resolve) => setTimeout(resolve, 600)); await route.continue(); });
    await page.goto(baseURL); await ready(page);
    await page.locator('[data-demo-chapter="2"]').click();
    await choose(page, 4); await page.waitForTimeout(850);
    await assertSnapshot(page, 4, chapters[4].durationMs);
    await context.close();
  });

  for (const viewport of [{ width: 320, height: 812 }, { width: 390, height: 844 }, { width: 768, height: 1024 }, { width: 1440, height: 900 }, { width: 1920, height: 1080 }]) {
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
    const page = await context.newPage(); const errors = problems(page);
    await page.goto(baseURL, { waitUntil: 'networkidle' }); await ready(page);
    await page.evaluate(() => document.fonts.ready);
    await check(`${viewport.width}px: reduced motion is static and checkpoint navigation remains available`, async () => {
      const final = await assertSnapshot(page, 0, chapters[0].durationMs);
      await page.locator('[data-demo-action="replay"]').click(); await ready(page);
      await page.waitForTimeout(200); assert.deepEqual(await state(page), final);
      assert.equal(await page.locator('[data-demo-action="pause"]').isDisabled(), true);
      await page.locator('[data-demo-checkpoint]').selectOption(chapters[0].checkpoints[0].id);
      await assertSnapshot(page, 0, chapters[0].checkpoints[0].atMs);
      await skip(page, 0);
    });
    await check(`${viewport.width}px: fixed stage, readable scrollable source, and no page overflow`, async () => {
      await page.evaluate(() => window.scrollTo(0, 0));
      const layout = await page.locator('[data-demo-screen]').evaluate((screen) => {
        const bounds = screen.getBoundingClientRect();
        const source = screen.querySelector('[data-demo-source]');
        return { height: bounds.height, top: bounds.top, bottom: bounds.bottom, codeFontPx: parseFloat(getComputedStyle(source).fontSize), scrollWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth };
      });
      assert.equal(layout.height, viewport.width >= 1600 ? 510 : viewport.width <= 720 ? 380 : 464);
      assert.ok(layout.codeFontPx >= 14);
      assert.ok(layout.scrollWidth <= viewport.width + 1, JSON.stringify(layout));
      const region = page.getByRole('region', { name: 'Recorded source; use arrow keys to scroll', exact: true });
      await region.focus(); assert.equal(await region.evaluate((element) => element === document.activeElement), true);
      if (viewport.width <= 390) { await region.press('ArrowRight'); await page.waitForTimeout(150); assert.ok(await region.evaluate((element) => element.scrollLeft > 0)); }
      await page.screenshot({ path: new URL(`viewport-${viewport.width}.png`, directory).pathname });
      return layout;
    });
    if ([390, 1440].includes(viewport.width)) await check(`${viewport.width}px: WCAG 2.2 AA automated audit`, async () => {
      const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
      await writeFile(new URL(`axe-${viewport.width}.json`, directory), JSON.stringify(audit, null, 2));
      assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({ id, impact, targets: nodes.map(({ target }) => target) })), []);
      return { passed: audit.passes.length, incomplete: audit.incomplete.length };
    });
    await check(`${viewport.width}px: no browser errors`, async () => assert.deepEqual(errors, []));
    await context.close();
  }
  for (const width of [320, 390]) await check(`${width}px: Play, Replay and Full Story start from keyboard focus without a separate scroll action`, async () => {
    const context = await browser.newContext({ viewport: { width, height: 844 }, reducedMotion: 'no-preference' });
    const page = await context.newPage(); await page.goto(baseURL); await ready(page);
    try {
      for (const action of ['pause', 'replay', 'story']) {
        await skip(page, 0);
        await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
        const control = page.locator(`[data-demo-action="${action}"]`);
        await control.focus(); await page.keyboard.press('Enter');
        // No scrollTo(0) here: normal keyboard focus scrolling must leave the stage visible.
        await page.waitForFunction(() => {
          const root = document.querySelector('[data-turboj-demo]');
          return root?.dataset.state === 'playing' && Number(root.dataset.elapsed) > 100;
        });
        assert.equal(await control.evaluate((element) => element === document.activeElement), true);
        const visibleRatio = await page.locator('.demo-stage').evaluate((element) => {
          const rect = element.getBoundingClientRect();
          return Math.max(0, Math.min(innerHeight, rect.bottom) - Math.max(0, rect.top)) / rect.height;
        });
        assert.ok(visibleRatio >= .15, `${action} left the stage offscreen (${visibleRatio})`);
        await page.locator('[data-demo-action="pause"]').focus(); await page.keyboard.press('Enter');
      }
    } finally { await context.close(); }
  });
  await check('no-JavaScript mode retains genuine screenshot and accessible evidence link without inert controls', async () => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, javaScriptEnabled: false });
    const page = await context.newPage(); await page.goto(baseURL, { waitUntil: 'networkidle' });
    assert.equal(await page.getByAltText(screenshotAlt).isVisible(), true);
    assert.ok(await page.getByAltText(screenshotAlt).evaluate((element) => element.complete && element.naturalWidth > 0));
    assert.equal(await page.locator('[data-demo-controls]').isVisible(), false);
    assert.equal(await page.getByRole('link', { name: 'Read the recorded session, sources, and logs.' }).isVisible(), true);
    await page.screenshot({ path: new URL('no-javascript.png', directory).pathname }); await context.close();
  });
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} hero checks passed; ${report.failures.length} failed. Artifacts: ${directory.pathname}`);
if (report.failures.length) process.exitCode = 1;
