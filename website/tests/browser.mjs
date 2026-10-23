import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { screenshotAlt } from './copy.mjs';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const executablePath = resolveBrowserPath();
const artifactDirectory = new URL('../artifacts/qa/', import.meta.url);
await mkdir(artifactDirectory, { recursive: true });
const viewports = [
  { width: 320, height: 844 }, { width: 390, height: 844 },
  { width: 768, height: 1024 }, { width: 1280, height: 800 },
  { width: 1440, height: 900 }, { width: 1920, height: 1080 },
];
const report = { timestamp: new Date().toISOString(), baseURL, browser: '', viewports: [], checks: [], failures: [] };

async function check(name, operation) {
  try {
    const details = await operation();
    report.checks.push({ name, status: 'passed', ...(details ? { details } : {}) });
    console.log(`PASS ${name}`);
  } catch (error) {
    report.failures.push({ name, message: error.message });
    console.error(`FAIL ${name}: ${error.message}`);
  }
}

const browser = await chromium.launch({ executablePath, headless: true });
report.browser = browser.version();
try {
  for (const viewport of viewports) {
    const name = `${viewport.width}×${viewport.height}`;
    const context = await browser.newContext({ viewport, deviceScaleFactor: 1, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const networkProblems = [];
    const consoleProblems = [];
    const externalRequests = [];
    page.on('pageerror', (error) => consoleProblems.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') consoleProblems.push(message.text()); });
    page.on('requestfailed', (request) => networkProblems.push(`${request.url()}: ${request.failure()?.errorText}`));
    page.on('response', (response) => { if (response.status() >= 400) networkProblems.push(`${response.status()} ${response.url()}`); });
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (['http:', 'https:'].includes(url.protocol) && url.origin !== new URL(baseURL).origin) externalRequests.push(url.href);
    });
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);

    await check(`${name}: responsive layout and loaded images`, async () => {
      const result = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        body: document.body.scrollWidth,
        failedImages: [...document.images].filter((image) => image.getClientRects().length > 0 && (!image.complete || image.naturalWidth === 0)).map((image) => image.currentSrc),
        overflow: [...document.querySelectorAll('main *')].filter((element) => {
          const rect = element.getBoundingClientRect();
          return rect.width && (rect.right > window.innerWidth + 1 || rect.left < -1);
        }).slice(0, 8).map((element) => `${element.tagName}.${element.className}`),
      }));
      report.viewports.push({ ...viewport, ...result });
      assert.ok(result.document <= result.viewport + 1, JSON.stringify(result));
      assert.ok(result.body <= result.viewport + 1, JSON.stringify(result));
      assert.deepEqual(result.failedImages, []);
      return result;
    });

    await page.screenshot({ path: new URL(`viewport-${viewport.width}.png`, artifactDirectory).pathname });
    if ([390, 1440].includes(viewport.width)) {
      await page.screenshot({ path: new URL(`homepage-${viewport.width}.png`, artifactDirectory).pathname, fullPage: true });
    }

    await check(`${name}: all anchor destinations exist`, async () => {
      const broken = await page.locator('a[href^="#"]').evaluateAll((links) => links
        .map((link) => link.getAttribute('href'))
        .filter((href) => !href || href === '#' || !document.getElementById(decodeURIComponent(href.slice(1)))));
      assert.deepEqual(broken, []);
    });

    const mobileMenu = page.locator('.mobile-menu');
    if (await mobileMenu.count() && await mobileMenu.locator(':scope > summary').isVisible()) {
      await check(`${name}: mobile navigation opens with keyboard, closes with Escape`, async () => {
        const summary = mobileMenu.locator(':scope > summary');
        await summary.focus();
        await page.keyboard.press('Enter');
        assert.equal(await mobileMenu.evaluate((element) => element.open), true);
        await page.keyboard.press('Escape');
        assert.equal(await mobileMenu.evaluate((element) => element.open), false);
        assert.equal(await summary.evaluate((element) => element === document.activeElement), true);
      });
      await check(`${name}: mobile navigation follows and closes`, async () => {
        await mobileMenu.locator(':scope > summary').click();
        await mobileMenu.locator('a[href="#features"]').click();
        assert.equal(new URL(page.url()).hash, '#features');
        assert.equal(await mobileMenu.evaluate((element) => element.open), false);
      });
    }

    await check(`${name}: navigation and CTA anchors activate`, async () => {
      for (const id of ['demo', 'features', 'workflow', 'performance', 'faq']) {
        const link = page.locator(`a[href="#${id}"]:visible`).first();
        await link.click();
        assert.equal(new URL(page.url()).hash, `#${id}`);
        const target = page.locator(`#${id}`);
        const y = await target.evaluate((element) => element.getBoundingClientRect().top);
        assert.ok(y >= -1 && y < viewport.height, `#${id} target outside viewport (${y})`);
      }
    });

    await check(`${name}: six FAQs work using only keyboard`, async () => {
      const details = page.locator('#faq details');
      assert.equal(await details.count(), 6);
      for (const item of await details.all()) {
        const summary = item.locator('summary');
        await summary.focus();
        if (await item.evaluate((element) => element.open)) await page.keyboard.press('Enter');
        await page.keyboard.press('Enter');
        assert.equal(await item.evaluate((element) => element.open), true);
        await page.keyboard.press('Space');
        assert.equal(await item.evaluate((element) => element.open), false);
      }
    });

    if ([390, 1440].includes(viewport.width)) {
      await check(`${name}: WCAG 2.2 AA automated audit`, async () => {
        // Include normally collapsed answers in the audit.
        await page.locator('#faq details').evaluateAll((items) => items.forEach((item) => { item.open = true; }));
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
        await writeFile(new URL(`axe-${viewport.width}.json`, artifactDirectory), JSON.stringify(audit, null, 2));
        assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({ id, impact, nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })) })), []);
        await page.locator('#faq details').evaluateAll((items) => items.forEach((item) => { item.open = false; }));
        return { violations: audit.violations.length, passedRules: audit.passes.length, incompleteRules: audit.incomplete.length };
      });
    }

    await check(`${name}: no external requests, failed requests, or console errors`, async () => {
      assert.deepEqual(externalRequests, []);
      assert.deepEqual(networkProblems, []);
      assert.deepEqual(consoleProblems, []);
    });
    await context.close();
  }

  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  await check('keyboard skip link is first and moves focus into main content', async () => {
    await page.keyboard.press('Tab');
    const focused = page.locator(':focus');
    assert.match(await focused.innerText(), /skip.*content/i);
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(() => document.activeElement?.tagName), 'MAIN');
  });
  await check('original product screenshot is an accessible, working full-resolution image link', async () => {
    const image = page.getByAltText(screenshotAlt);
    assert.equal(await image.count(), 1);
    const link = page.locator('[data-testid="screenshot-original"]');
    assert.equal(await link.count(), 1);
    const href = await link.getAttribute('href');
    const response = await page.request.get(new URL(href, baseURL).href);
    assert.equal(response.status(), 200);
    assert.match(response.headers()['content-type'], /^image\//);
    const target = await link.getAttribute('target');
    if (target === '_blank') {
      const popupPromise = page.waitForEvent('popup');
      await link.focus();
      await page.keyboard.press('Enter');
      const popup = await popupPromise;
      await popup.waitForLoadState();
      assert.equal(new URL(popup.url()).pathname, new URL(href, baseURL).pathname);
      await popup.close();
    } else {
      await link.focus();
      await page.keyboard.press('Enter');
      await page.waitForURL(new URL(href, baseURL).href);
    }
    return { href, bytes: (await response.body()).byteLength };
  });
} finally {
  await browser.close();
  await writeFile(new URL('report.json', artifactDirectory), JSON.stringify(report, null, 2));
}
console.log(`${report.checks.length} checks passed; ${report.failures.length} failed. Artifacts: ${artifactDirectory.pathname}`);
if (report.failures.length) process.exitCode = 1;
