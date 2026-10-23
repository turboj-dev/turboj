import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';
import { screenshotAlt } from './copy.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = new URL('../artifacts/demo-qa/', import.meta.url);
await mkdir(directory, { recursive: true });
const expectedSource = 'public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println("Hello TurboJ!");\n    }\n}';
const expectedOutput = 'FAST RUN · HelloWorld\nHello TurboJ!\n\n[exit 0] Completed';
const report = { timestamp: new Date().toISOString(), baseURL, browser: '', checks: [], failures: [], realTimeJourney: null };
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();

async function check(name, callback) {
  try {
    const detail = await callback();
    report.checks.push({ name, status: 'passed', ...(detail ? { detail } : {}) });
    console.log(`PASS ${name}`);
  } catch (error) {
    report.failures.push({ name, message: error.message });
    console.error(`FAIL ${name}: ${error.message}`);
  }
}

function captureProblems(page) {
  const problems = [];
  page.on('pageerror', (error) => problems.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') problems.push(message.text()); });
  page.on('response', (response) => { if (response.status() >= 400) problems.push(`${response.status()} ${response.url()}`); });
  page.on('requestfailed', (request) => problems.push(`${request.url()}: ${request.failure()?.errorText}`));
  return problems;
}

async function state(page) {
  return page.locator('[data-turboj-demo]').evaluate((root) => {
    const text = (part) => root.querySelector(`[data-demo-${part}]`)?.textContent ?? '';
    return {
      state: root.dataset.state, phase: root.dataset.phase,
      elapsed: Number(root.dataset.elapsed), sourceLength: Number(root.dataset.sourceLength),
      manualPaused: root.dataset.manualPaused, source: text('source'), filename: text('filename'),
      status: text('status'), output: text('output'), progress: text('progress'),
      announcement: text('announcement'),
    };
  });
}

async function waitForState(page, name, timeout = 6000) {
  await page.waitForFunction((name) => document.querySelector('[data-turboj-demo]')?.getAttribute('data-state') === name, name, { timeout });
}

async function assertCompleted(page) {
  await waitForState(page, 'completed');
  const snapshot = await state(page);
  assert.equal(snapshot.source, expectedSource);
  assert.equal(snapshot.output.trim(), expectedOutput);
  assert.match(snapshot.filename, /^HelloWorld\.java/);
  assert.equal(snapshot.elapsed, 8000);
  assert.equal(snapshot.sourceLength, expectedSource.length);
  assert.doesNotMatch(await page.locator('[data-demo-screen]').innerText(), /\b\d+(?:[.,]\d+)?\s*ms\b/i, 'The illustrated replay must not invent compile or execution timings.');
  return snapshot;
}

try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'no-preference' });
  const page = await context.newPage();
  page.setDefaultTimeout(6000);
  const browserProblems = captureProblems(page);
  // Observe public DOM changes without replacing timers, RAF, or controller code.
  await page.addInitScript(() => {
    window.__turbojDemoObservations = [];
    const observe = () => {
      const root = document.querySelector('[data-turboj-demo]');
      if (!root) return;
      const source = root.querySelector('[data-demo-source]')?.textContent ?? '';
      const sample = {
        at: performance.now(), state: root.dataset.state,
        phase: root.dataset.phase, source, output: root.querySelector('[data-demo-output]')?.textContent ?? '',
      };
      const previous = window.__turbojDemoObservations.at(-1);
      if (!previous || previous.state !== sample.state || previous.source !== source || previous.output !== sample.output) window.__turbojDemoObservations.push(sample);
    };
    new MutationObserver(observe).observe(document, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['data-state'] });
  });
  const journeyStart = Date.now();
  await page.goto(baseURL, { waitUntil: 'domcontentloaded' });

  await check('normal-motion replay genuinely advances from empty editor through typing, compilation and execution', async () => {
    await waitForState(page, 'completed', 14000);
    const final = await assertCompleted(page);
    const samples = await page.evaluate(() => window.__turbojDemoObservations);
    const phases = samples.filter((sample, index) => index === 0 || sample.state !== samples[index - 1].state).map(({ state }) => state);
    let previousIndex = -1;
    for (const phase of ['idle', 'typing', 'compiling', 'running', 'completed']) {
      const index = phases.indexOf(phase);
      assert.ok(index > previousIndex, `Expected ${phase} after previous stage: ${phases.join(' → ')}`);
      previousIndex = index;
    }
    assert.ok(samples.some((sample) => sample.state === 'idle' && sample.source.length === 0), 'An empty editor must be observable before typing.');
    assert.ok(samples.filter((sample) => sample.state === 'typing' && sample.source.length > 0 && sample.source.length < expectedSource.length).length >= 5, 'Typing must reveal actual intermediate source states.');
    assert.ok(samples.filter((sample) => sample.state === 'typing').every((sample) => expectedSource.startsWith(sample.source)), 'Every intermediate source must be a prefix of the real example.');
    assert.ok(samples.some((sample) => sample.state === 'running' && sample.output.includes('Hello TurboJ!') && !sample.output.includes('[exit 0]')), 'Program output must appear while running, before the completion/exit marker.');
    const observedDuration = Date.now() - journeyStart;
    assert.ok(observedDuration >= 7000 && observedDuration <= 16000, `Unexpected real replay duration: ${observedDuration}ms`);
    report.realTimeJourney = { observedDurationMs: observedDuration, phases, sampleCount: samples.length, note: 'Animation duration only, not native compilation or execution performance.' };
    await writeFile(new URL('normal-motion-journey.json', directory), JSON.stringify(samples, null, 2));
    await page.screenshot({ path: new URL('demo-completed-1440.png', directory).pathname });
    return { ...report.realTimeJourney, finalSourceLength: final.source.length };
  });

  await check('Java keywords and string literals use the exact requested syntax colors', async () => {
    const colors = await page.locator('[data-demo-source]').evaluate((source) => ({
      keywords: [...source.querySelectorAll('.demo-token-keyword')].map((element) => ({ text: element.textContent, color: getComputedStyle(element).color })),
      strings: [...source.querySelectorAll('.demo-token-string')].map((element) => ({ text: element.textContent, color: getComputedStyle(element).color })),
    }));
    assert.ok(colors.keywords.length >= 5, `Missing keyword highlighting nodes: ${JSON.stringify(colors)}`);
    assert.ok(colors.strings.length >= 1, `Missing string highlighting node: ${JSON.stringify(colors)}`);
    for (const keyword of colors.keywords) assert.equal(keyword.color, 'rgb(255, 255, 85)', `${keyword.text} keyword color`);
    for (const string of colors.strings) assert.equal(string.color, 'rgb(86, 255, 255)', `${string.text} string color`);
    return colors;
  });

  await check('completed replay holds its final code and output without automatically looping', async () => {
    const before = await assertCompleted(page);
    await page.waitForTimeout(700);
    assert.deepEqual(await state(page), before);
  });

  await check('Replay resets the editor and Pause/Resume preserves animation progress', async () => {
    await page.locator('[data-demo-action="replay"]').click();
    const reset = await state(page);
    assert.equal(reset.state, 'idle');
    assert.equal(reset.source, '');
    assert.equal(reset.output, '');
    await waitForState(page, 'typing');
    await page.waitForTimeout(200);
    const partialKeyword = await page.locator('[data-demo-source] .demo-token-keyword').first().evaluate((element) => ({ text: element.textContent, color: getComputedStyle(element).color }));
    assert.ok(partialKeyword.text.length > 0);
    assert.equal(partialKeyword.color, 'rgb(255, 255, 85)', 'Incomplete keyword must already have its syntax color while typing.');
    const pause = page.locator('[data-demo-action="pause"]');
    await pause.focus();
    await page.keyboard.press('Enter');
    await waitForState(page, 'paused');
    const paused = await state(page);
    assert.equal(paused.manualPaused, 'true');
    assert.match(await pause.innerText(), /resume/i);
    await page.waitForTimeout(600);
    assert.deepEqual(await state(page), paused);
    await page.keyboard.press('Space');
    await waitForState(page, 'typing');
    await page.waitForFunction((elapsed) => Number(document.querySelector('[data-turboj-demo]')?.getAttribute('data-elapsed')) > elapsed + 150, paused.elapsed);
    const resumed = await state(page);
    assert.ok(resumed.source.startsWith(paused.source));
    assert.equal(resumed.manualPaused, 'false');
    return { pausedAtMs: paused.elapsed, resumedAtMs: resumed.elapsed };
  });

  await check('Skip immediately selects the exact final state and Replay remains keyboard operable', async () => {
    const skip = page.locator('[data-demo-action="skip"]');
    await skip.focus();
    await page.keyboard.press('Enter');
    await assertCompleted(page);
    const replay = page.locator('[data-demo-action="replay"]');
    await replay.focus();
    assert.notEqual(await replay.evaluate((element) => getComputedStyle(element).outlineStyle), 'none');
    await page.keyboard.press('Enter');
    assert.equal((await state(page)).state, 'idle');
    assert.equal((await state(page)).source, '');
  });

  await check('scrolling the editor out of view freezes progress and returning resumes it', async () => {
    await waitForState(page, 'typing');
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(200);
    const hidden = await state(page);
    assert.notEqual(hidden.state, 'completed');
    await page.waitForTimeout(650);
    assert.deepEqual(await state(page), hidden);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForFunction((elapsed) => Number(document.querySelector('[data-turboj-demo]')?.getAttribute('data-elapsed')) > elapsed + 150, hidden.elapsed);
    return { offscreenElapsedMs: hidden.elapsed, resumedElapsedMs: (await state(page)).elapsed };
  });

  await check('manual pause stays paused after leaving and returning to the editor', async () => {
    await page.locator('[data-demo-action="pause"]').click();
    await waitForState(page, 'paused');
    const paused = await state(page);
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(200);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(300);
    assert.deepEqual(await state(page), paused);
    await page.locator('[data-demo-action="skip"]').click();
    await assertCompleted(page);
  });

  await check('simulated document-hidden events suspend and resume the replay clock', async () => {
    await page.locator('[data-demo-action="replay"]').click();
    await waitForState(page, 'typing');
    try {
      await page.evaluate(() => {
        Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
        document.dispatchEvent(new Event('visibilitychange'));
      });
      await waitForState(page, 'paused');
      const hidden = await state(page);
      await page.waitForTimeout(650);
      assert.deepEqual(await state(page), hidden);
    } finally {
      await page.evaluate(() => {
        delete document.hidden;
        document.dispatchEvent(new Event('visibilitychange'));
      });
    }
    await waitForState(page, 'typing');
    const resumed = await state(page);
    await page.waitForFunction((elapsed) => Number(document.querySelector('[data-turboj-demo]')?.getAttribute('data-elapsed')) > elapsed + 100, resumed.elapsed);
    await page.locator('[data-demo-action="skip"]').click();
    await assertCompleted(page);
    return { note: 'Visibility handler exercised with a simulated document.hidden property/event; not an OS background-tab measurement.' };
  });

  await check('demo controls have accessible names, live status is not updated character by character, and no browser errors occur', async () => {
    for (const action of ['replay', 'pause', 'skip']) {
      const button = page.locator(`[data-demo-action="${action}"]`);
      assert.equal(await button.evaluate((element) => element.tagName), 'BUTTON');
      assert.ok((await button.getAttribute('aria-label')) || (await button.innerText()).trim());
    }
    const sourceLiveRegion = await page.locator('[data-demo-source]').evaluate((element) => element.closest('[aria-live]:not([aria-live="off"])'));
    assert.equal(sourceLiveRegion, null, 'The typed code must not flood a screen reader live region.');
    assert.deepEqual(browserProblems, []);
  });
  await context.close();

  for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 1024 }, { width: 1440, height: 900 }, { width: 1920, height: 1080 }]) {
    const size = `${viewport.width}×${viewport.height}`;
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const problems = captureProblems(page);
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await check(`${size}: reduced motion shows the completed demo immediately and Replay does not animate`, async () => {
      const final = await assertCompleted(page);
      await page.locator('[data-demo-action="replay"]').click();
      await assertCompleted(page);
      await page.waitForTimeout(250);
      assert.deepEqual(await state(page), final);
    });
    await page.evaluate(() => window.scrollTo(0, 0));
    await check(`${size}: editor appears in the first viewport with readable code and no page overflow`, async () => {
      const layout = await page.locator('[data-demo-screen]').evaluate((screen) => {
        const rect = screen.getBoundingClientRect();
        const source = screen.querySelector('[data-demo-source]');
        const sourceRect = source.getBoundingClientRect();
        const codeLines = source.querySelectorAll('.demo-code-line');
        const lineAdvance = codeLines.length > 1 ? codeLines[1].getBoundingClientRect().top - codeLines[0].getBoundingClientRect().top : 0;
        return {
          top: rect.top, bottom: rect.bottom, height: rect.height,
          sourceTop: sourceRect.top, sourceHeight: sourceRect.height,
          codeViewportHeight: screen.querySelector('.demo-code-viewport').clientHeight,
          codeFontPx: parseFloat(getComputedStyle(source).fontSize),
          lineHeight: parseFloat(getComputedStyle(source).lineHeight), lineAdvance,
          viewportWidth: window.innerWidth, viewportHeight: window.innerHeight,
          scrollWidth: document.documentElement.scrollWidth,
        };
      });
      assert.ok(layout.top >= 0 && layout.sourceTop < viewport.height - 50, `Editor/source is not meaningfully visible in first viewport: ${JSON.stringify(layout)}`);
      assert.ok(Math.min(layout.bottom, viewport.height) - layout.top >= 150, `Too little editor visible: ${JSON.stringify(layout)}`);
      assert.ok(layout.codeFontPx >= 14, `Source text is ${layout.codeFontPx}px, below 14px minimum.`);
      assert.ok(layout.sourceHeight <= layout.codeViewportHeight + 1, `Source is clipped vertically: ${JSON.stringify(layout)}`);
      assert.ok(Math.abs(layout.lineAdvance - layout.lineHeight) <= 1, `Code rows and cursor line height do not align: ${JSON.stringify(layout)}`);
      assert.ok(layout.scrollWidth <= viewport.width + 1, JSON.stringify(layout));
      await page.screenshot({ path: new URL(`demo-viewport-${viewport.width}.png`, directory).pathname });
      return layout;
    });
    if (viewport.width === 390) {
      await check(`${size}: overflowing source is reachable and scrollable with the keyboard`, async () => {
        const viewport = page.getByRole('region', { name: 'Java source example' });
        assert.equal(await viewport.count(), 1);
        assert.equal(await viewport.getAttribute('tabindex'), '0');
        assert.equal(await viewport.evaluate((element) => element.closest('[aria-hidden="true"]')), null);
        await page.locator('[data-demo-action="replay"]').focus();
        await page.keyboard.press('Shift+Tab');
        assert.equal(await viewport.evaluate((element) => element === document.activeElement), true);
        const before = await viewport.evaluate((element) => ({ scroll: element.scrollLeft, width: element.clientWidth, content: element.scrollWidth }));
        assert.ok(before.content > before.width, 'The mobile sample must exercise actual horizontal scrolling.');
        await page.keyboard.press('ArrowRight');
        await page.waitForFunction(() => document.querySelector('.demo-code-viewport').scrollLeft > 0);
        const after = await viewport.evaluate((element) => element.scrollLeft);
        assert.ok(after > before.scroll);
        return { before: before.scroll, after, viewportWidth: before.width, sourceWidth: before.content };
      });
    }
    if ([390, 1440].includes(viewport.width)) {
      await check(`${size}: interactive demo passes WCAG 2.2 AA automated rules`, async () => {
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
        await writeFile(new URL(`axe-demo-${viewport.width}.json`, directory), JSON.stringify(audit, null, 2));
        assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({ id, impact, targets: nodes.map(({ target }) => target) })), []);
        return { passed: audit.passes.length, incomplete: audit.incomplete.length };
      });
    }
    await check(`${size}: reduced-motion demo produces no browser or asset errors`, async () => assert.deepEqual(problems, []));
    await context.close();
  }

  await check('with JavaScript disabled the genuine screenshot is visible and no inert demo controls are exposed', async () => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, javaScriptEnabled: false });
    try {
      const page = await context.newPage();
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      const image = page.getByAltText(screenshotAlt);
      assert.equal(await image.isVisible(), true);
      assert.ok(await image.evaluate((element) => element.complete && element.naturalWidth > 0));
      for (const action of ['replay', 'pause', 'skip']) assert.equal(await page.locator(`[data-demo-action="${action}"]`).isVisible(), false);
      assert.equal(await page.locator('[data-testid="screenshot-original"]').isVisible(), true);
      await page.screenshot({ path: new URL('demo-no-javascript-390.png', directory).pathname });
    } finally {
      await context.close();
    }
  });
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} demo checks passed; ${report.failures.length} failed. Artifacts: ${directory.pathname}`);
if (report.failures.length) process.exitCode = 1;
