import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { resolveBrowserPath } from './browser-path.mjs';
import { artifactDirectory } from './qa-artifacts.mjs';
import { screenshotAlt } from './copy.mjs';
import { STORY_LOOP_HOLD_MS, validateManifest, validateChapter } from '../src/scripts/demo-timeline.ts';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const directory = artifactDirectory('demo-theme', '../artifacts/demo-theme-qa/', import.meta.url);
const readJSON = async (path) => JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
const manifest = validateManifest(await readJSON('../public/demo/hero-story/manifest.json'));
const chapters = await Promise.all(manifest.chapters.map(async (entry) => validateChapter(await readJSON(`../public${entry.url}`), entry.id)));
const monokai = await readJSON('../public/themes/palettes/vscode-monokai.json');
const report = { timestamp: new Date().toISOString(), baseURL, sessionId: manifest.id, browser: '', checks: [], failures: [] };
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();

async function check(name, callback) {
  try { const detail = await callback(); report.checks.push({ name, status: 'PASS', detail }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, status: 'FAIL', message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
function storedPalette(theme) {
  return { id: theme.id, name: theme.name, colors: Object.fromEntries(Object.entries(theme)
    .filter(([, fields]) => fields && typeof fields === 'object')
    .flatMap(([section, fields]) => Object.entries(fields)
      .filter(([, value]) => typeof value === 'string' && /^#[\da-f]{6}$/i.test(value))
      .map(([field, value]) => [`${section}.${field}`, value.toUpperCase()]))) };
}
function color(hex) { return `rgb(${[1, 3, 5].map((index) => parseInt(hex.slice(index, index + 2), 16)).join(', ')})`; }
async function preparedPage({ theme = monokai, width = 1440, motion = 'reduce' } = {}) {
  const context = await browser.newContext({ viewport: { width, height: width < 720 ? 844 : 1000 }, reducedMotion: motion });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  await page.addInitScript(({ palette, theme }) => {
    localStorage.setItem('turboj.theme', palette.id);
    localStorage.setItem('turboj.theme.palette', JSON.stringify(palette));
    const rgb = (hex) => `rgb(${[1, 3, 5].map((index) => parseInt(hex.slice(index, index + 2), 16)).join(', ')})`;
    const visible = (element) => !!element?.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
    window.__themeFrames = { samples: [], violations: [], stopped: false };
    let previous = '';
    // Observe actual animation frames, including the period before the module executes.
    // Store only changes, bounded independently of the duration of a stalled request.
    const sample = () => {
      const root = document.querySelector('[data-turboj-demo]');
      const stage = root?.querySelector('.demo-stage');
      if (stage && getComputedStyle(stage).position === 'relative') {
        const surfaces = [
          ['.demo-stage', theme.editor.background], ['[data-demo-loading]', theme.editor.background],
          ['.demo-editor', theme.editor.background], ['.demo-console', theme.messages.background],
          ['.demo-menu', theme.menu.background], ['.demo-statusbar', theme.chrome.statusBackground],
          ['.demo-recorded-dialog', theme.editor.background], ['.demo-recorded-menu', theme.menu.background],
        ].flatMap(([selector, background]) => [...root.querySelectorAll(selector)]
          .filter(visible).map((element) => ({ selector, background: getComputedStyle(element).backgroundColor, expected: rgb(background) })));
        const state = {
          theme: document.documentElement.dataset.theme, chapter: root.dataset.chapter ?? '', event: root.dataset.event ?? '',
          loading: root.dataset.loading ?? '', loopHold: root.dataset.loopHold ?? '',
          screenshot: visible(root.querySelector('.demo-fallback img')), surfaces,
        };
        const key = JSON.stringify(state);
        if (key !== previous) {
          previous = key;
          if (window.__themeFrames.samples.length < 300) window.__themeFrames.samples.push(state);
          if ((state.screenshot || state.theme !== theme.id || surfaces.some((item) => item.background !== item.expected)) && window.__themeFrames.violations.length < 30) window.__themeFrames.violations.push(state);
        }
      }
      if (!window.__themeFrames.stopped) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  }, { palette: storedPalette(theme), theme });
  return { context, page };
}
async function ready(page, index = 0) {
  await page.waitForFunction((id) => {
    const root = document.querySelector('[data-turboj-demo]');
    return root?.dataset.chapter === id && root.dataset.loading === 'false';
  }, chapters[index].id);
}
async function frames(page) { await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))); }
async function audit(page, name) {
  await frames(page);
  const observed = await page.evaluate(() => window.__themeFrames);
  await writeFile(new URL(`${name}-frames.json`, directory), JSON.stringify(observed, null, 2));
  assert.ok(observed.samples.length > 0, 'The audit must observe painted demo surfaces.');
  assert.deepEqual(observed.violations, [], 'A Classic screenshot or a surface with the wrong palette was painted.');
  return observed.samples;
}
async function capture(page, name) {
  await page.locator('.demo-stage').screenshot({ path: new URL(`${name}.png`, directory).pathname });
}
async function assertLoading(page, theme = monokai) {
  const loading = page.locator('[data-demo-loading]');
  await loading.waitFor({ state: 'visible' });
  assert.equal(await page.getByAltText(screenshotAlt).isVisible(), false);
  assert.equal(await loading.evaluate((element) => getComputedStyle(element).backgroundColor), color(theme.editor.background));
  const link = loading.locator('a[href="/demo-session/"]');
  assert.equal(await link.isVisible(), true);
  assert.ok((await link.textContent()).trim(), 'The static fallback needs a named evidence route even if the controller never starts.');
}
async function gate(page, matches) {
  let release;
  const barrier = new Promise((resolve) => { release = resolve; });
  const requested = page.waitForRequest(matches);
  await page.route('**/*', async (route) => {
    if (!matches(route.request())) return route.fallback();
    await barrier;
    await route.continue().catch(() => {}); // Cleanup may close a deliberately blocked page.
  });
  return { requested, release };
}

try {
  for (const phase of ['module', 'manifest', 'chapter']) await check(`saved Monokai paints immediately while ${phase} is gated`, async () => {
    const { context, page } = await preparedPage({ width: phase === 'chapter' ? 390 : 1440 });
    const matches = phase === 'module' ? (request) => request.resourceType() === 'script'
      : (request) => new URL(request.url()).pathname === (phase === 'manifest' ? '/demo/hero-story/manifest.json' : manifest.chapters[0].url);
    const pending = await gate(page, matches);
    try {
      await page.goto(baseURL, { waitUntil: 'commit' });
      await pending.requested;
      await assertLoading(page);
      await capture(page, `${phase}-gated`);
      await audit(page, `${phase}-gated`);
      pending.release();
      await ready(page);
      assert.equal(await page.locator('[data-demo-loading]').isVisible(), false);
      await audit(page, `${phase}-ready`);
      return { theme: monokai.id, gated: phase };
    } finally { pending.release(); await context.close(); }
  });

  for (const phase of ['module', 'manifest', 'chapter']) await check(`saved Monokai survives ${phase} failure with a readable fallback`, async () => {
    const { context, page } = await preparedPage({ width: 390 });
    await page.route('**/*', (route) => {
      const request = route.request(), path = new URL(request.url()).pathname;
      const failed = phase === 'module' ? request.resourceType() === 'script'
        : path === (phase === 'manifest' ? '/demo/hero-story/manifest.json' : manifest.chapters[0].url);
      return failed ? route.fulfill({ status: 503, body: 'Unavailable' }) : route.continue();
    });
    try {
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      if (phase !== 'module') await page.waitForFunction(() => document.querySelector('[data-turboj-demo]')?.dataset.state === 'error');
      await assertLoading(page);
      if (phase !== 'module') {
        assert.equal(await page.locator('[data-demo-action="pause"]').isDisabled(), true);
        assert.match(await page.locator('[data-demo-prompt-text]').textContent(), /unavailable/i);
      }
      await capture(page, `${phase}-unavailable`);
      return { samples: (await audit(page, `${phase}-unavailable`)).length };
    } finally { await context.close(); }
  });

  await check('a valid cached custom palette remains painted when theme catalog refresh is offline', async () => {
    const custom = { ...structuredClone(monokai), id: 'qa-cached-demo', name: 'QA cached demo' };
    custom.editor.background = '#1B2428'; custom.ui.background = '#1B2428';
    const { context, page } = await preparedPage({ theme: custom });
    await page.route('**/themes/catalog.json', (route) => route.fulfill({ status: 503, body: 'Offline' }));
    const pending = await gate(page, (request) => new URL(request.url()).pathname === manifest.chapters[0].url);
    try {
      await page.goto(baseURL, { waitUntil: 'commit' });
      await pending.requested;
      await assertLoading(page, custom);
      await capture(page, 'custom-cache-gated');
      pending.release(); await ready(page);
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), custom.id);
      return { samples: (await audit(page, 'custom-cache-ready')).length };
    } finally { pending.release(); await context.close(); }
  });

  await check('chapter loading keeps the current themed frame instead of showing the Classic screenshot', async () => {
    const { context, page } = await preparedPage();
    const pending = await gate(page, (request) => new URL(request.url()).pathname === manifest.chapters[1].url);
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' }); await ready(page);
      const previous = await page.locator('[data-demo-source]').textContent();
      await page.locator('[data-demo-chapter="1"]').evaluate((button) => /** @type {HTMLButtonElement} */ (button).click());
      await pending.requested;
      assert.equal(await page.locator('[data-demo-screen]').isVisible(), true);
      assert.equal(await page.locator('[data-demo-source]').textContent(), previous);
      assert.equal(await page.getByAltText(screenshotAlt).isVisible(), false);
      await capture(page, 'chapter-transition-gated');
      await audit(page, 'chapter-transition-gated');
      pending.release(); await ready(page, 1);
      return { samples: (await audit(page, 'chapter-transition-ready')).length };
    } finally { pending.release(); await context.close(); }
  });

  await check('Play keeps every recorded menu and dialog in Monokai through all five chapters and loop restart', async () => {
    const { context, page } = await preparedPage({ motion: 'no-preference' });
    const duration = chapters.reduce((sum, chapter) => sum + chapter.durationMs, 0) + STORY_LOOP_HOLD_MS;
    try {
      const errors = []; page.on('pageerror', (error) => errors.push(error.message));
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' }); await ready(page);
      // Start the actual public full-story behavior at the first captured checkpoint.
      await page.locator('[data-demo-chapter="0"]').evaluate((button) => /** @type {HTMLButtonElement} */ (button).click()); await ready(page);
      const play = page.locator('[data-demo-action="pause"]');
      await play.focus(); await page.keyboard.press('Enter');
      await page.waitForFunction(() => document.querySelector('[data-turboj-demo]')?.dataset.state === 'playing');
      await page.waitForFunction((lastChapter) => {
        const root = document.querySelector('[data-turboj-demo]');
        // During an asynchronous chapter transition the previous elapsed time remains
        // available until the new chapter is loaded; observe only the settled finale.
        return root?.dataset.loading === 'false' && root.dataset.chapter === lastChapter && root.dataset.loopHold === 'true';
      }, chapters.at(-1).id, { timeout: duration + 15000 });
      await capture(page, 'monokai-full-story-result');
      await ready(page, 0);
      await play.focus(); await page.keyboard.press('Enter');
      const samples = await audit(page, 'monokai-full-story-loop');
      const overlayIdentity = (chapter, event) => event?.dialogId ? `${chapter.id}/${event.dialogId}`
        : event?.menuItems?.length ? `${chapter.id}/${JSON.stringify(event.menuItems)}` : null;
      // Several operations retain the same dialog for a sub-frame checkpoint (e.g. 7ms).
      // Require every distinct recorded surface, without claiming millisecond display precision.
      const overlays = new Set(chapters.flatMap((chapter) => chapter.events.map((event) => overlayIdentity(chapter, event))).filter(Boolean));
      const seen = new Set(samples.filter((sample) => sample.loading === 'false').map((sample) => {
        const chapter = chapters.find((item) => item.id === sample.chapter);
        return chapter && overlayIdentity(chapter, chapter.events.find((item) => item.id === sample.event));
      }).filter(Boolean));
      for (const identity of overlays) assert.ok(seen.has(identity), `Unobserved recorded menu/dialog: ${identity}`);
      assert.deepEqual([...new Set(samples.filter((sample) => sample.event).map((sample) => sample.chapter))], chapters.map((chapter) => chapter.id));
      assert.ok(samples.some((sample) => sample.loopHold === 'true'));
      assert.deepEqual(errors, []);
      await capture(page, 'monokai-loop-restarted');
      return { realPlaybackDurationMs: duration, observedChanges: samples.length, distinctOverlayScenes: overlays.size };
    } finally { await context.close(); }
  });

  await check('no-JavaScript keeps the genuine native screenshot and evidence route unchanged', async () => {
    const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    try {
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      const image = page.getByAltText(screenshotAlt);
      assert.equal(await image.count(), 1); assert.equal(await image.isVisible(), true);
      assert.equal(await image.evaluate((element) => element.complete && element.naturalWidth > 0), true);
      assert.equal(await page.locator('[data-demo-loading]').isVisible(), false);
      assert.equal(await page.locator('.demo-no-script a[href="/demo-session/"]').isVisible(), true);
      assert.equal(await page.locator('[data-demo-controls]').isVisible(), false);
      const style = await image.evaluate((element) => ({ filter: getComputedStyle(element).filter, opacity: getComputedStyle(element).opacity }));
      assert.deepEqual(style, { filter: 'none', opacity: '1' });
      const response = await page.request.get(new URL('/images/turboj-editor.png', baseURL).href);
      assert.equal(response.status(), 200);
      const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
      const original = await readFile(new URL('../../docs/images/turboj-classic.png', import.meta.url));
      assert.equal(digest(await response.body()), digest(original));
      await capture(page, 'no-javascript');
      return { authenticScreenshotSha256: digest(original) };
    } finally { await context.close(); }
  });
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} demo-theme checks passed; ${report.failures.length} failed. Artifacts: ${directory.pathname}`);
if (report.failures.length) process.exitCode = 1;
