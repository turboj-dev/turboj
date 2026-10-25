import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';
import { artifactDirectory } from './qa-artifacts.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const cacheOnly = process.argv.includes('--cache-only');
const directory = artifactDirectory(cacheOnly ? 'theme-cache' : 'themes', cacheOnly ? '../artifacts/theme-cache-qa/' : '../artifacts/theme-qa/', import.meta.url);
const themeIDs = ['classic', 'dark', 'light'];
const nativeThemes = await Promise.all(themeIDs.map(async (id) => JSON.parse(await readFile(
  new URL(`../../app/src/main/resources/themes/${id}.json`, import.meta.url), 'utf8'))));
const shippedCatalog = JSON.parse(await readFile(new URL('../public/themes/catalog.json', import.meta.url), 'utf8'));
const manifest = JSON.parse(await readFile(new URL('../public/demo/hero-story/manifest.json', import.meta.url), 'utf8'));
const hello = JSON.parse(await readFile(new URL(`../public${manifest.chapters[0].url}`, import.meta.url), 'utf8'));
const finalEvent = hello.events.at(-1);
const expectedSource = hello.sources[finalEvent.sourceId].text;
const expectedOutput = hello.outputs[finalEvent.outputId].trim();
const report = { timestamp: new Date().toISOString(), baseURL, browser: '', checks: [], failures: [] };
await mkdir(directory, { recursive: true });

async function check(name, callback) {
  try {
    const details = await callback();
    report.checks.push({ name, status: 'passed', ...(details ? { details } : {}) });
    console.log(`PASS ${name}`);
  } catch (error) {
    report.failures.push({ name, message: error.message });
    console.error(`FAIL ${name}: ${error.message}`);
  }
}

function color(hex) {
  return `rgb(${[1, 3, 5].map((index) => parseInt(hex.slice(index, index + 2), 16)).join(', ')})`;
}
function contrast(first, second) {
  const luminance = (value) => value.match(/[\d.]+/g).slice(0, 3).map(Number)
    .map((channel) => channel / 255).map((channel) => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4)
    .reduce((sum, channel, index) => sum + channel * [.2126, .7152, .0722][index], 0);
  const a = luminance(first), b = luminance(second);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}
function nativeVariables(theme) {
  const colors = {};
  for (const [section, fields] of Object.entries(theme)) {
    if (typeof fields !== 'object' || fields === null) continue;
    for (const [field, value] of Object.entries(fields)) {
      if (typeof value === 'string' && /^#[\da-f]{6}$/i.test(value)) {
        colors[`--tj-${section}-${field.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`] = value.toUpperCase();
      }
    }
  }
  return colors;
}
function captureProblems(page) {
  const problems = [];
  page.on('pageerror', (error) => problems.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') problems.push(message.text()); });
  page.on('requestfailed', (request) => {
    const failure = request.failure()?.errorText;
    // Closing or superseding a catalog preview deliberately cancels obsolete palette requests.
    if (failure === 'net::ERR_ABORTED' && new URL(request.url()).pathname.startsWith('/themes/')) return;
    problems.push(`${request.url()}: ${failure}`);
  });
  page.on('response', (response) => { if (response.status() >= 400) problems.push(`${response.status()} ${response.url()}`); });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (['http:', 'https:'].includes(url.protocol) && url.origin !== new URL(baseURL).origin) problems.push(`External request: ${url.href}`);
  });
  return problems;
}
async function chooseTheme(page, id, keyboard = false) {
  await openThemeMenu(page);
  const button = page.locator(`[data-theme-choice="${id}"]`);
  if (keyboard) {
    await button.focus();
    await page.keyboard.press('Enter');
  } else await button.click();
  await page.waitForFunction((id) => document.documentElement.dataset.theme === id, id);
  await closeThemeMenus(page);
}
async function openThemeMenu(page) {
  const options = page.locator('[data-theme-options]');
  if (!await options.locator(':scope > summary').isVisible()) {
    const mobile = page.locator('.mobile-menu');
    if (!await mobile.evaluate((element) => element.open)) {
      await mobile.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
    }
  }
  if (!await options.evaluate((element) => element.open)) {
    await options.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
  }
  const themes = page.locator('[data-theme-menu]');
  if (!await themes.evaluate((element) => element.open)) {
    await themes.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
  }
}
async function closeThemeMenus(page) {
  for (const selector of ['[data-theme-menu]', '[data-theme-options]', '.mobile-menu']) {
    const menu = page.locator(selector);
    if (await menu.count() && await menu.evaluate((element) => element.open)) {
      await menu.locator(':scope > summary').focus(); await page.keyboard.press('Escape');
    }
  }
}
async function openThemeCatalog(page) {
  await openThemeMenu(page);
  await page.locator('[data-theme-more]').click();
  await page.locator('[data-theme-browser]').waitFor({ state: 'visible' });
}
async function demoState(page) {
  return page.locator('[data-turboj-demo]').evaluate((element) => {
    const text = (name) => element.querySelector(`[data-demo-${name}]`)?.textContent ?? '';
    const cursor = element.querySelector('[data-demo-cursor]');
    return {
      state: element.dataset.state, phase: element.dataset.phase,
      elapsed: Number(element.dataset.elapsed), sourceLength: Number(element.dataset.sourceLength),
      manualPaused: element.dataset.manualPaused, source: text('source'), filename: text('filename'),
      status: text('status'), output: text('output'), progress: element.querySelector('progress')?.value,
      cursorStyle: cursor?.getAttribute('style'), position: text('position'),
    };
  });
}
async function geometry(page) {
  return page.locator('.demo-screen').evaluate((element) => {
    const rect = element.getBoundingClientRect(), style = getComputedStyle(element);
    return { width: rect.width, height: rect.height, x: rect.x, y: rect.y, font: style.font, lineHeight: style.lineHeight };
  });
}
async function previewedTheme(page, id) {
  await page.waitForFunction((id) => document.documentElement.dataset.theme === id, id);
}
async function paletteAppearance(page) {
  return page.evaluate(() => {
    const read = (selector) => {
      const style = getComputedStyle(document.querySelector(selector));
      return { background: style.backgroundColor, color: style.color };
    };
    return { body: read('body'), editor: read('.demo-editor'), console: read('.demo-console'), menu: read('.demo-menu') };
  });
}
async function selectedCatalogId(page) {
  return page.locator('[data-theme-preview][aria-selected="true"]').getAttribute('data-theme-preview');
}
function testCatalog() {
  return Array.from({ length: 1000 }, (_, index) => {
    const suffix = String(index).padStart(4, '0');
    return { id: `qa-${suffix}`, name: `QA Theme ${suffix}`, url: `/themes/qa-fixtures/qa-${suffix}.json` };
  });
}
async function routeTestCatalog(page, delays = {}) {
  const catalog = testCatalog(), requested = new Set();
  await page.route('**/themes/catalog.json', (route) => route.fulfill({ json: catalog }));
  await page.route('**/themes/qa-fixtures/*.json', async (route) => {
    const match = new URL(route.request().url()).pathname.match(/qa-(\d{4})\.json$/);
    const index = Number(match?.[1]);
    assert.ok(index >= 0 && index < catalog.length, 'Unexpected test palette request');
    requested.add(index);
    if (delays[index]) await new Promise((resolve) => setTimeout(resolve, delays[index]));
    const palette = structuredClone(nativeThemes[index % nativeThemes.length]);
    palette.id = catalog[index].id; palette.name = catalog[index].name;
    await route.fulfill({ json: palette }).catch(() => {}); // Superseded requests may be aborted by the app.
  });
  return { catalog, requested };
}
function storedSnapshot(theme) {
  return { id: theme.id, name: theme.name, colors: Object.fromEntries(Object.entries(theme)
    .filter(([, fields]) => fields && typeof fields === 'object')
    .flatMap(([section, fields]) => Object.entries(fields)
      .filter(([, value]) => typeof value === 'string' && /^#[\da-f]{6}$/i.test(value))
      .map(([field, value]) => [`${section}.${field}`, value.toUpperCase()]))) };
}
async function seedCachedTheme(page, palette) {
  await page.addInitScript((palette) => {
    if (!sessionStorage.getItem('qa-theme-seeded')) {
      localStorage.setItem('turboj.theme', palette.id);
      localStorage.setItem('turboj.theme.palette', JSON.stringify(palette));
      sessionStorage.setItem('qa-theme-seeded', 'true');
    }
    requestAnimationFrame(() => { window.__firstFramePalette = {
      id: document.documentElement.dataset.theme,
      background: getComputedStyle(document.documentElement).getPropertyValue('--tj-editor-background').trim(),
    }; });
  }, palette);
}

const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();
try {
  if (!cacheOnly) {
  for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 1024 },
    { width: 1440, height: 900 }, { width: 1920, height: 1080 }]) {
    const context = await browser.newContext({ viewport, reducedMotion: 'reduce', deviceScaleFactor: 1 });
    const page = await context.newPage();
    page.setDefaultTimeout(6000);
    const problems = captureProblems(page);
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await check(`${viewport.width}: one Options/Themes menu with three primary choices and one completed demo`, async () => {
      assert.equal(await page.locator('[data-theme-options]').count(), 1);
      assert.equal(await page.locator('[data-theme-menu]').count(), 1);
      assert.equal(await page.locator('[data-theme-choice]').count(), 3);
      assert.deepEqual(await page.locator('[data-theme-choice]').evaluateAll((elements) => elements.map((element) => element.dataset.themeChoice)), themeIDs);
      assert.equal(await page.locator('[data-turboj-demo]').count(), 1);
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      const state = await demoState(page);
      assert.equal(state.state, 'completed');
      assert.equal(state.source, expectedSource);
      assert.equal(state.output.trim(), expectedOutput);
    });
    const originalState = await demoState(page);
    const originalGeometry = await geometry(page);
    const originalImage = await page.locator('.demo-fallback img').getAttribute('src');
    const imageResponse = await page.request.get(new URL(originalImage, baseURL).href);
    const imageHash = createHash('sha256').update(await imageResponse.body()).digest('hex');
    const websiteAppearances = [];
    for (const theme of nativeThemes) {
      const prefix = `${viewport.width} / ${theme.id}`;
      await chooseTheme(page, theme.id, true);
      await check(`${prefix}: native JSON, exported tokens and rendered editor match exactly`, async () => {
        const expected = nativeVariables(theme);
        const actual = await page.evaluate((names) => {
          const style = getComputedStyle(document.documentElement);
          return Object.fromEntries(names.map((name) => {
            // Production CSS legitimately shortens #FFFFFF to #FFF; compare exact RGB values.
            let value = style.getPropertyValue(name).trim().toUpperCase();
            if (/^#[\dA-F]{3}$/.test(value)) value = `#${[...value.slice(1)].map((digit) => digit.repeat(2)).join('')}`;
            return [name, value];
          }));
        }, Object.keys(expected));
        assert.deepEqual(actual, expected);
        const displayed = await page.evaluate(() => {
          const pick = (selector) => {
            const s = getComputedStyle(document.querySelector(selector));
            return { background: s.backgroundColor, color: s.color };
          };
          return {
            editor: pick('.demo-editor'), menu: pick('.demo-menu'), console: pick('.demo-console'),
            status: pick('.demo-statusbar'), keyword: pick('.demo-token-keyword'), string: pick('.demo-token-string'),
            cursor: pick('.demo-cursor'), body: pick('body'), header: pick('.site-header'),
          };
        });
        assert.equal(displayed.editor.background, color(theme.editor.background));
        assert.equal(displayed.editor.color, color(theme.editor.foreground));
        assert.equal(displayed.menu.background, color(theme.menu.background));
        assert.equal(displayed.menu.color, color(theme.menu.foreground));
        assert.equal(displayed.console.background, color(theme.messages.background));
        assert.equal(displayed.console.color, color(theme.messages.foreground));
        assert.equal(displayed.status.background, color(theme.chrome.statusBackground));
        assert.equal(displayed.status.color, color(theme.chrome.statusForeground));
        assert.equal(displayed.keyword.color, color(theme.syntax.keyword));
        assert.equal(displayed.string.color, color(theme.syntax.string));
        assert.equal(displayed.cursor.background, color(theme.editor.cursor));
        assert.equal(displayed.body.background, color(theme.ui.background));
        assert.equal(displayed.body.color, color(theme.ui.foreground));
        assert.equal(displayed.header.background, color(theme.menu.background));
        assert.equal(displayed.header.color, color(theme.menu.foreground));
        assert.equal(await page.locator(`[data-theme-choice="${theme.id}"]`).getAttribute('aria-pressed'), 'true');
        assert.equal(await page.locator('[data-theme-choice][aria-pressed="true"]').count(), 1);
        return { tokenCount: Object.keys(expected).length, displayed };
      });
      await check(`${prefix}: no overflow, layout shift, demo mutation or screenshot recoloring`, async () => {
        const result = await page.evaluate(() => ({
          viewport: document.documentElement.clientWidth,
          document: document.documentElement.scrollWidth, body: document.body.scrollWidth,
          filter: getComputedStyle(document.querySelector('.demo-fallback img')).filter,
          opacity: getComputedStyle(document.querySelector('.demo-fallback img')).opacity,
        }));
        assert.ok(result.document <= result.viewport + 1, JSON.stringify(result));
        assert.ok(result.body <= result.viewport + 1, JSON.stringify(result));
        assert.equal(result.filter, 'none'); assert.equal(result.opacity, '1');
        assert.equal(await page.locator('.demo-fallback img').getAttribute('src'), originalImage);
        assert.deepEqual(await demoState(page), originalState);
        assert.deepEqual(await geometry(page), originalGeometry);
        return { ...result, authenticScreenshotSha256: imageHash };
      });
      await check(`${prefix}: WCAG 2.2 AA audit with expanded FAQ and usable navigation`, async () => {
        await page.locator('#faq details').evaluateAll((items) => items.forEach((item) => { item.open = true; }));
        const menu = page.locator('.mobile-menu');
        if (await menu.locator(':scope > summary').isVisible()) await menu.evaluate((element) => { element.open = true; });
        await page.locator('[data-theme-options], [data-theme-menu]').evaluateAll((items) => items.forEach((item) => { item.open = true; }));
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
        await writeFile(new URL(`axe-${theme.id}-${viewport.width}.json`, directory), JSON.stringify(audit, null, 2));
        await menu.evaluate((element) => { element.open = false; });
        await page.locator('[data-theme-options], [data-theme-menu]').evaluateAll((items) => items.forEach((item) => { item.open = false; }));
        await page.locator('#faq details').evaluateAll((items) => items.forEach((item) => { item.open = false; }));
        assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({ id, impact, nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })) })), []);
        return { violations: audit.violations.length, passedRules: audit.passes.length, incompleteRules: audit.incomplete.length };
      });
      await check(`${prefix}: keyboard focus indicators remain visible on menu and editor surfaces`, async () => {
        const inspected = [];
        for (const selector of [`[data-theme-choice="${theme.id}"]`, '[data-demo-action="replay"]', '[data-testid="screenshot-original"]']) {
          const control = page.locator(selector);
          if (!await control.isVisible()) await openThemeMenu(page);
          await page.keyboard.press('Tab'); await control.focus();
          const focus = await control.evaluate((element) => {
            const s = getComputedStyle(element);
            let parent = element.parentElement;
            while (parent && ['transparent', 'rgba(0, 0, 0, 0)'].includes(getComputedStyle(parent).backgroundColor)) parent = parent.parentElement;
            return { color: s.outlineColor, width: parseFloat(s.outlineWidth), style: s.outlineStyle,
              background: parent ? getComputedStyle(parent).backgroundColor : 'rgb(255, 255, 255)' };
          });
          assert.ok(focus.style !== 'none' && focus.width >= 2, `${selector}: ${JSON.stringify(focus)}`);
          assert.ok(contrast(focus.color, focus.background) >= 3, `${selector}: focus contrast ${contrast(focus.color, focus.background).toFixed(2)}`);
          inspected.push({ selector, ...focus, contrast: contrast(focus.color, focus.background) });
          await closeThemeMenus(page);
        }
        return inspected;
      });
      await page.evaluate(() => scrollTo(0, 0));
      await page.screenshot({ path: new URL(`${theme.id}-${viewport.width}.png`, directory).pathname });
      if ([390, 1440].includes(viewport.width)) await page.screenshot({ path: new URL(`${theme.id}-${viewport.width}-full.png`, directory).pathname, fullPage: true });
      websiteAppearances.push(await page.evaluate(() => Object.fromEntries(
        ['body', '.hero', '.features-section', '.workflow-section', '.performance-console', '.philosophy-section', '#roadmap', '.faq-section', '.site-footer']
          .map((selector) => { const s = getComputedStyle(document.querySelector(selector)); return [selector, { background: s.backgroundColor, color: s.color }]; }))));
    }
    await check(`${viewport.width}: each theme changes the whole site, not only the editor`, async () => {
      for (const selector of Object.keys(websiteAppearances[0])) {
        for (let i = 1; i < websiteAppearances.length; i++) assert.notDeepEqual(websiteAppearances[i][selector], websiteAppearances[i - 1][selector], selector);
      }
      return websiteAppearances;
    });
    await check(`${viewport.width}: no browser, asset or external-request errors`, async () => assert.deepEqual(problems, []));
    await context.close();
  }

  await check('published JSON is an exact export of all three native definitions', async () => {
    const request = await browser.newContext();
    try {
      const response = await request.request.get(new URL('/themes/turboj-themes.json', baseURL).href);
      assert.equal(response.status(), 200);
      const exported = await response.json();
      assert.equal(exported.schemaVersion, 2);
      assert.deepEqual(exported.themes, nativeThemes);
    } finally { await request.close(); }
  });

  await check('explicit preference survives reload before the first animation frame and ignores OS preference', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce', colorScheme: 'dark' });
    const page = await context.newPage();
    try {
      await page.addInitScript(() => requestAnimationFrame(() => { window.__firstFrameTheme = document.documentElement.dataset.theme; }));
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      await chooseTheme(page, 'light', true);
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'light');
      await page.reload({ waitUntil: 'networkidle' });
      assert.equal(await page.evaluate(() => window.__firstFrameTheme), 'light');
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'light');
      await page.emulateMedia({ colorScheme: 'light' });
      await chooseTheme(page, 'dark');
      await page.reload({ waitUntil: 'networkidle' });
      assert.equal(await page.evaluate(() => window.__firstFrameTheme), 'dark');
      assert.equal(await page.locator('[data-theme-choice="dark"]').getAttribute('aria-pressed'), 'true');
    } finally { await context.close(); }
  });

  await check('invalid stored preferences fall back to Classic safely', async () => {
    const context = await browser.newContext({ reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await page.addInitScript(() => localStorage.setItem('turboj.theme', 'invalid-theme'));
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      assert.equal(await page.locator('[data-theme-choice="classic"]').getAttribute('aria-pressed'), 'true');
    } finally { await context.close(); }
  });

  await check('blocked localStorage still permits all theme changes without errors', async () => {
    const context = await browser.newContext({ reducedMotion: 'reduce' });
    const page = await context.newPage(); const errors = captureProblems(page);
    try {
      await page.addInitScript(() => Object.defineProperty(window, 'localStorage', { get() { throw new DOMException('Storage denied', 'SecurityError'); } }));
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      for (const id of ['dark', 'light', 'classic']) await chooseTheme(page, id, true);
      assert.deepEqual(errors, []);
    } finally { await context.close(); }
  });

  await check('paused replay preserves every source, cursor, compiler and output field through theme changes', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'no-preference' });
    const page = await context.newPage();
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => Number(document.querySelector('[data-turboj-demo]')?.dataset.sourceLength) >= 30);
      await page.locator('[data-demo-action="pause"]').click();
      const before = await demoState(page);
      assert.equal(before.state, 'paused');
      for (const id of ['dark', 'light', 'classic']) {
        await chooseTheme(page, id, true);
        assert.deepEqual(await demoState(page), before);
      }
    } finally { await context.close(); }
  });

  await check('running replay keeps its timeline and finishes normally while themes change', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'no-preference' });
    const page = await context.newPage(); const errors = captureProblems(page);
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => Number(document.querySelector('[data-turboj-demo]')?.dataset.sourceLength) >= 30);
      const start = Date.now(), initial = await demoState(page);
      let previous = initial;
      for (const id of ['dark', 'light', 'classic']) {
        await chooseTheme(page, id, true);
        const after = await demoState(page);
        assert.ok(after.elapsed >= previous.elapsed, `Timeline reset ${previous.elapsed} → ${after.elapsed}`);
        assert.ok(after.source.startsWith(previous.source), 'Source was reset or replaced on theme change');
        assert.equal(after.manualPaused, 'false');
        previous = after;
        await page.waitForTimeout(450);
      }
      await openThemeCatalog(page);
      await page.locator('[data-theme-preview="dark"]').click(); await previewedTheme(page, 'dark');
      const preview = await demoState(page);
      assert.ok(preview.elapsed >= previous.elapsed && preview.source.startsWith(previous.source), 'Catalog preview reset the replay');
      assert.equal(preview.manualPaused, 'false');
      await page.keyboard.press('Escape');
      await page.locator('[data-theme-browser]').waitFor({ state: 'hidden' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
      await page.waitForFunction(() => document.querySelector('[data-turboj-demo]')?.dataset.state === 'completed', null, { timeout: hello.durationMs + 10000 });
      const final = await demoState(page);
      assert.equal(final.source, expectedSource); assert.equal(final.output.trim(), expectedOutput);
      assert.ok(Math.abs(final.elapsed - hello.durationMs) < 1); assert.equal(await page.locator('[data-turboj-demo]').count(), 1);
      assert.ok(Date.now() - start < hello.durationMs + 10000, 'Theme changes unexpectedly extended or restarted the replay');
      assert.deepEqual(errors, []);
      return { initialElapsedMs: initial.elapsed, finalElapsedMs: final.elapsed, observedRemainingDurationMs: Date.now() - start };
    } finally { await context.close(); }
  });

  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    await check(`${viewport.width}: More themes opens a responsive native-color preview catalog; Escape restores the original`, async () => {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage();
      try {
        await page.goto(baseURL, { waitUntil: 'networkidle' });
        await chooseTheme(page, 'classic');
        const before = await demoState(page), original = await paletteAppearance(page);
        await openThemeCatalog(page);
        const dialog = page.locator('[data-theme-browser]');
        await page.waitForFunction((count) => document.querySelectorAll('[data-theme-preview]').length === Math.min(6, count), shippedCatalog.length);
        assert.match(await page.locator('[data-theme-page]').innerText(), new RegExp(`${shippedCatalog.length} themes`));
        assert.equal(await page.locator('[data-theme-search]').evaluate((element) => element === document.activeElement), true);
        for (const theme of nativeThemes) {
          const card = page.locator(`[data-theme-preview="${theme.id}"]`);
          assert.ok((await card.innerText()).includes(theme.name));
          await card.click(); await previewedTheme(page, theme.id);
          assert.equal(await selectedCatalogId(page), theme.id);
          const preview = await card.evaluate((element) => Object.fromEntries(['editor', 'menu', 'console'].map((part) => {
            const style = getComputedStyle(element.querySelector(`[data-theme-preview-${part}]`));
            return [part, { background: style.backgroundColor, color: style.color }];
          })));
          assert.deepEqual(preview, {
            editor: { background: color(theme.editor.background), color: color(theme.editor.foreground) },
            menu: { background: color(theme.menu.background), color: color(theme.menu.foreground) },
            console: { background: color(theme.messages.background), color: color(theme.messages.foreground) },
          });
          assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
          assert.equal((await paletteAppearance(page)).editor.background, color(theme.editor.background));
          assert.deepEqual(await demoState(page), before);
        }
        const bounds = await dialog.boundingBox();
        assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= viewport.width + 1, JSON.stringify(bounds));
        assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= viewport.height + 1, JSON.stringify(bounds));
        const columns = await page.locator('[data-theme-grid]').evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(' ').length);
        assert.equal(columns, 3, 'Catalog must retain the requested three-column grid');
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
        await writeFile(new URL(`axe-catalog-${viewport.width}.json`, directory), JSON.stringify(audit, null, 2));
        assert.deepEqual(audit.violations.map(({ id, nodes }) => ({ id, nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })) })), []);
        await page.screenshot({ path: new URL(`catalog-${viewport.width}.png`, directory).pathname });
        await page.keyboard.press('Escape');
        await dialog.waitFor({ state: 'hidden' });
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
        assert.deepEqual(await paletteAppearance(page), original);
        assert.deepEqual(await demoState(page), before);
        assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
      } finally { await context.close(); }
    });
  }

  await check('catalog Enter commits the selected real theme while Cancel never persists previews', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      await chooseTheme(page, 'classic'); await openThemeCatalog(page);
      await page.locator('[data-theme-preview="dark"]').click(); await previewedTheme(page, 'dark');
      await page.keyboard.press('Enter');
      await page.locator('[data-theme-browser]').waitFor({ state: 'hidden' });
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'dark');
      await openThemeCatalog(page);
      await page.locator('[data-theme-preview="light"]').click(); await previewedTheme(page, 'light');
      await page.locator('[data-theme-cancel]').click();
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'dark');
      await page.reload({ waitUntil: 'networkidle' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
    } finally { await context.close(); }
  });

  await check('1,000 routed test themes retain six visible cards and support arrows, paging, search and commit', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const { requested } = await routeTestCatalog(page);
    try {
      await page.addInitScript(() => requestAnimationFrame(() => { window.__firstFrameTheme = document.documentElement.dataset.theme; }));
      await page.goto(baseURL, { waitUntil: 'networkidle' }); await chooseTheme(page, 'classic');
      const before = await demoState(page);
      await openThemeCatalog(page);
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview]').length === 6);
      assert.equal(await page.locator('[data-theme-grid]').evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(' ').length), 3);
      await page.locator('[data-theme-search]').press('ArrowDown');
      assert.equal(await page.evaluate(() => document.activeElement?.hasAttribute('data-theme-preview')), true);
      const initial = Number((await selectedCatalogId(page)).slice(3));
      await page.keyboard.press('ArrowRight');
      assert.equal(Number((await selectedCatalogId(page)).slice(3)), initial + 1);
      await page.keyboard.press('ArrowDown');
      assert.equal(Number((await selectedCatalogId(page)).slice(3)), initial + 4);
      await page.keyboard.press('PageDown');
      assert.equal(Number((await selectedCatalogId(page)).slice(3)), initial + 10);
      assert.equal(await page.locator('[data-theme-preview]').count(), 6);
      await page.keyboard.press('PageUp');
      assert.equal(Number((await selectedCatalogId(page)).slice(3)), initial + 4);
      await page.locator('[data-theme-next]').click();
      assert.equal(await page.locator('[data-theme-preview]').count(), 6);
      await page.locator('[data-theme-previous]').click();
      const search = page.locator('[data-theme-search]');
      await search.fill('0999');
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview]').length === 1);
      await previewedTheme(page, 'qa-0999');
      assert.match(await page.locator('[data-theme-preview]').innerText(), /QA Theme 0999/);
      assert.equal((await paletteAppearance(page)).editor.background, color(nativeThemes[0].editor.background));
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
      assert.deepEqual(await demoState(page), before);
      await page.screenshot({ path: new URL('catalog-1000-filtered-1440.png', directory).pathname });
      await page.locator('[data-theme-apply]').click();
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'qa-0999');
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'qa-0999');
      assert.ok(requested.size < 40, `Fetched ${requested.size} palettes; the whole catalog must not be loaded`);
      await page.reload({ waitUntil: 'networkidle' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'qa-0999');
      assert.equal(await page.evaluate(() => window.__firstFrameTheme), 'qa-0999');
      assert.equal((await paletteAppearance(page)).editor.background, color(nativeThemes[0].editor.background));
      return { metadataCount: 1000, maximumVisibleCards: 6, distinctPalettesFetched: requested.size,
        fixtureScope: 'Ephemeral Playwright routes only; these QA themes are not shipped or published.' };
    } finally { await context.close(); }
  });

  await check('latest search and preview win over older delayed palette responses; empty search results cannot be applied', async () => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    await routeTestCatalog(page, { 0: 800, 100: 500 });
    try {
      await page.goto(baseURL, { waitUntil: 'networkidle' }); await chooseTheme(page, 'dark');
      const original = await paletteAppearance(page);
      await openThemeCatalog(page);
      const search = page.locator('[data-theme-search]');
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview]').length === 6);
      assert.equal(await page.locator('[data-theme-grid]').evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(' ').length), 3);
      const bounds = await page.locator('[data-theme-browser]').boundingBox();
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 391 && bounds.y >= 0 && bounds.y + bounds.height <= 845, JSON.stringify(bounds));
      await page.screenshot({ path: new URL('catalog-1000-390.png', directory).pathname });
      await search.fill('0100');
      await page.waitForFunction(() => document.querySelector('[data-theme-preview="qa-0100"]'));
      await search.fill('0999'); await previewedTheme(page, 'qa-0999');
      await page.waitForTimeout(1100);
      assert.equal(await selectedCatalogId(page), 'qa-0999');
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'qa-0999');
      assert.equal(await page.locator('[data-theme-preview]').count(), 1);
      assert.equal((await paletteAppearance(page)).editor.background, color(nativeThemes[0].editor.background));
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'dark');
      await search.fill('does-not-exist');
      await page.waitForFunction(() => document.querySelectorAll('[data-theme-preview]').length === 0);
      assert.equal(await page.locator('[data-theme-apply]').isDisabled(), true);
      await page.keyboard.press('Escape');
      await page.locator('[data-theme-browser]').waitFor({ state: 'hidden' });
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
      assert.deepEqual(await paletteAppearance(page), original);
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'dark');
    } finally { await context.close(); }
  });
  }

  await check('cached custom palette renders first, then refreshes and persists changed native colors under the same ID', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const old = { ...structuredClone(nativeThemes[0]), id: 'qa-cached', name: 'QA Cached Theme' };
    const current = { ...structuredClone(nativeThemes[1]), id: old.id, name: 'QA Updated Theme' };
    try {
      await seedCachedTheme(page, storedSnapshot(old));
      await page.route('**/themes/catalog.json', (route) => route.fulfill({ json: [{ id: current.id, name: current.name, url: '/themes/qa-fixtures/cached.json' }] }));
      await page.route('**/themes/qa-fixtures/cached.json', async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 400)); await route.fulfill({ json: current });
      });
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      await page.waitForFunction((background) => JSON.parse(localStorage.getItem('turboj.theme.palette'))?.colors['editor.background'] === background, current.editor.background);
      assert.equal(await page.locator('html').getAttribute('data-theme'), current.id);
      assert.deepEqual(await page.evaluate(() => window.__firstFramePalette), { id: old.id, background: old.editor.background });
      assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('turboj.theme.palette'))), storedSnapshot(current));
      assert.equal((await paletteAppearance(page)).editor.background, color(current.editor.background));
      await page.reload({ waitUntil: 'networkidle' });
      assert.deepEqual(await page.evaluate(() => window.__firstFramePalette), { id: current.id, background: current.editor.background });
    } finally { await context.close(); }
  });

  await check('retired cached custom theme falls back to Classic and persists the fallback without retaining stale colors', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const retired = { ...structuredClone(nativeThemes[1]), id: 'qa-retired', name: 'QA Retired Theme' };
    try {
      await seedCachedTheme(page, storedSnapshot(retired));
      await page.route('**/themes/catalog.json', (route) => route.fulfill({ json: nativeThemes.map((theme) => ({ id: theme.id, name: theme.name, url: `/themes/palettes/${theme.id}.json` })) }));
      await page.goto(baseURL, { waitUntil: 'networkidle' });
      await page.waitForFunction(() => document.documentElement.dataset.theme === 'classic');
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme')), 'classic');
      assert.equal(await page.evaluate(() => localStorage.getItem('turboj.theme.palette')), null);
      assert.equal((await paletteAppearance(page)).editor.background, color(nativeThemes[0].editor.background));
      assert.equal(await page.locator('[data-theme-choice="classic"]').getAttribute('aria-pressed'), 'true');
      await page.reload({ waitUntil: 'networkidle' });
      assert.equal(await page.evaluate(() => window.__firstFramePalette.id), 'classic');
      assert.equal(await page.locator('html').getAttribute('data-theme'), 'classic');
    } finally { await context.close(); }
  });

  await check('focused and hovered catalog thumbnail preserves unadorned source and retains a visible focus outline', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      await page.goto(baseURL, { waitUntil: 'networkidle' }); await openThemeCatalog(page);
      const card = page.locator('[data-theme-preview="light"]');
      await card.click(); await previewedTheme(page, 'light');
      await page.keyboard.press('Tab'); await card.focus(); await card.hover();
      const appearance = await card.evaluate((element) => {
        const style = getComputedStyle(element);
        return { decoration: style.textDecorationLine, outlineStyle: style.outlineStyle, outlineWidth: parseFloat(style.outlineWidth),
          editorDecoration: getComputedStyle(element.querySelector('[data-theme-preview-editor]')).textDecorationLine };
      });
      assert.equal(appearance.decoration, 'none'); assert.equal(appearance.editorDecoration, 'none');
      assert.ok(appearance.outlineStyle !== 'none' && appearance.outlineWidth >= 2);
      await page.screenshot({ path: new URL('catalog-final-1440.png', directory).pathname });
    } finally { await context.close(); }
  });
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} passed, ${report.failures.length} failed; report ${directory.pathname}report.json`);
if (report.failures.length) process.exitCode = 1;
