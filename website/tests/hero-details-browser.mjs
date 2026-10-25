import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { resolveBrowserPath } from './browser-path.mjs';

const baseURL = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const artifactRoot = process.env.QA_ARTIFACT_ROOT ?? 'artifacts/hero-story-qa';
const directory = new URL(`../${artifactRoot}/details/`, import.meta.url);
await mkdir(directory, { recursive: true });
const evidence = JSON.parse(await readFile(new URL('../public/demo/hero-story/evidence.json', import.meta.url), 'utf8'));
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const report = { timestamp: new Date().toISOString(), baseURL, sessionId: evidence.id, browser: '', checks: [], failures: [] };
const browser = await chromium.launch({ executablePath: resolveBrowserPath(), headless: true });
report.browser = browser.version();
async function check(name, fn) {
  try { const detail = await fn(); report.checks.push({ name, status: 'PASS', detail }); console.log(`PASS ${name}`); }
  catch (error) { report.failures.push({ name, message: error.message }); console.error(`FAIL ${name}: ${error.message}`); }
}
try {
  for (const width of [320, 390, 1440]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce', acceptDownloads: true });
    const page = await context.newPage();
    const requests = [], errors = [];
    page.on('request', (request) => requests.push(request.url()));
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('response', (response) => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
    await page.goto(`${baseURL}/demo-session/`, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await check(`${width}: verified session and exact measurement table render statically`, async () => {
      assert.equal(await page.locator('main h1').textContent(), 'Five chapters. One measured session.');
      assert((await page.locator('main').textContent()).includes(evidence.id));
      const rows = page.locator('[aria-label="Native operation measurements"] tbody tr');
      assert.equal(await rows.count(), evidence.measurements.length);
      for (let index = 0; index < evidence.measurements.length; index++) {
        const measured = evidence.measurements[index];
        assert.equal(await rows.nth(index).locator('th a').textContent(), measured.label);
        assert.equal(await rows.nth(index).locator('td').first().textContent(), `${(measured.durationNs / 1e6).toLocaleString('en-US', { maximumFractionDigits: 2 })} ms`);
      }
      assert.equal(await page.locator('main img').count(), 0);
      assert.equal(requests.filter((url) => /\/demo\/hero-story\/.*\.(?:png|zip|mp4|webm)(?:\?|$)/.test(url)).length, 0);
      assert.equal(requests.filter((url) => new URL(url).port === '18765' || new URL(url).pathname === '/hello').length, 0);
      return { rows: evidence.measurements.length, screenshotRequests: 0 };
    });
    await check(`${width}: keyboard opens source/output and copies exact captured text`, async () => {
      const [sourceId, source] = Object.entries(evidence.sources).find(([, item]) => item.text.includes('Hello TurboJ!'));
      const sourceDetails = page.locator(`#${sourceId}`), summary = sourceDetails.locator(':scope > summary');
      await summary.focus(); await page.keyboard.press('Enter');
      assert.equal(await sourceDetails.evaluate((element) => element.open), true);
      assert.equal(await sourceDetails.locator('pre code').textContent(), source.text);
      await summary.focus(); await page.keyboard.press('Space');
      assert.equal(await sourceDetails.evaluate((element) => element.open), false);
      const [outputId, output] = Object.entries(evidence.outputs).find(([, item]) => item.text.includes('expected: <800> but was: <900>'));
      const outputDetails = page.locator(`#${outputId}`);
      await outputDetails.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
      assert.equal(await outputDetails.locator('pre').textContent(), output.text);
      assert.equal(requests.filter((url) => url.endsWith('.png')).length, 0, 'Expanding evidence must not eagerly load images');
      return { source: source.sha256, deliberateFailure: output.sha256 };
    });
    await check(`${width}: no document overflow, valid internal links, visible focus, no runtime errors`, async () => {
      const sizes = await page.evaluate(() => ({ client: document.documentElement.clientWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth }));
      assert(sizes.document <= sizes.client + 1 && sizes.body <= sizes.client + 1, JSON.stringify(sizes));
      const broken = await page.locator('main a[href^="#"]').evaluateAll((links) => links.map((link) => link.getAttribute('href')).filter((href) => !document.getElementById(href.slice(1))));
      assert.deepEqual(broken, []);
      const focus = await page.evaluate(() => ({ tag: document.activeElement.tagName, outline: getComputedStyle(document.activeElement).outlineStyle, width: getComputedStyle(document.activeElement).outlineWidth }));
      assert.equal(focus.tag, 'SUMMARY'); assert.notEqual(focus.outline, 'none'); assert.notEqual(focus.width, '0px');
      assert.deepEqual(errors, []);
      return { sizes, focus };
    });
    if ([390, 1440].includes(width)) await check(`${width}: axe accessibility with expanded authentic source and output`, async () => {
      const results = await new AxeBuilder({ page }).analyze();
      await writeFile(new URL(`axe-${width}.json`, directory), JSON.stringify(results, null, 2));
      assert.deepEqual(results.violations.map(({ id, impact, description, nodes }) => ({ id, impact, description, count: nodes.length })), []);
      return { violations: results.violations.length };
    });
    if (width === 1440) {
      await check('all source/log/project/trace links download exact immutable content', async () => {
        const files = [...Object.values(evidence.sources), ...Object.values(evidence.outputs), ...evidence.projectFiles,
          { url: evidence.selected.trace, sha256: evidence.selected.traceSha256 },
          ...evidence.controls.map((control) => ({ url: control.trace, sha256: control.traceSha256 }))];
        for (const file of files) {
          const response = await context.request.get(`${baseURL}${file.url}`);
          assert.equal(response.status(), 200, file.url);
          assert.equal(digest(await response.body()), file.sha256, file.url);
        }
        const [sourceId, source] = Object.entries(evidence.sources).find(([, item]) => item.text.includes('Hello TurboJ!'));
        const details = page.locator(`#${sourceId}`);
        if (!await details.evaluate((element) => element.open)) { await details.locator(':scope > summary').focus(); await page.keyboard.press('Enter'); }
        await details.locator('a[download]').focus();
        const downloadPromise = page.waitForEvent('download'); await page.keyboard.press('Enter');
        const download = await downloadPromise;
        assert.equal(digest(await readFile(await download.path())), source.sha256);
        return { checkedDownloads: files.length, keyboardDownload: download.suggestedFilename() };
      });
      await check('native frame downloads only after explicit request', async () => {
        const frame = evidence.events.find((event) => event.id === 'hello-run-result');
        const detail = page.locator(`#event-${frame.id}`);
        await detail.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
        const href = await detail.locator('a[target="_blank"]').getAttribute('href');
        assert.equal(href, frame.screenshot);
        const popupPromise = page.waitForEvent('popup');
        await detail.locator('a[target="_blank"]').focus(); await page.keyboard.press('Enter');
        const popup = await popupPromise; await popup.waitForLoadState('load');
        assert(new URL(popup.url()).pathname === frame.screenshot);
        await popup.close();
      });
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: new URL(`details-${width}.png`, directory).pathname });
    await context.close();
  }
  await check('evidence and keyboard disclosures remain readable without JavaScript', async () => {
    const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
    const page = await context.newPage(); await page.goto(`${baseURL}/demo-session/`);
    assert((await page.locator('main').textContent()).includes('Times from this captured session.'));
    const summary = page.locator('main details > summary').first(); await summary.focus(); await page.keyboard.press('Enter');
    assert.equal(await summary.evaluate((element) => element.parentElement.open), true);
    await context.close();
  });
} finally {
  await browser.close();
  await writeFile(new URL('report.json', directory), JSON.stringify(report, null, 2) + '\n');
}
if (report.failures.length) process.exitCode = 1;
