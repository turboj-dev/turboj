import { mkdir, writeFile } from 'node:fs/promises';
import lighthouse from 'lighthouse';
import { launch } from 'chrome-launcher';
import { resolveBrowserPath } from './browser-path.mjs';
import { artifactDirectory } from './qa-artifacts.mjs';

const url = process.env.QA_BASE_URL ?? 'http://127.0.0.1:4321';
const chromePath = resolveBrowserPath();
const runs = Number(process.env.LH_RUNS ?? 1);
if (!Number.isSafeInteger(runs) || runs < 1 || runs > 10) throw new Error('LH_RUNS must be between 1 and 10.');
const directory = artifactDirectory('lighthouse', '../artifacts/lighthouse/', import.meta.url);
await mkdir(directory, { recursive: true });
const reports = [];
for (let index = 1; index <= runs; index++) {
  const browser = await launch({ chromePath, chromeFlags: ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check'] });
  try {
    const result = await lighthouse(url, {
      port: browser.port, output: ['json', 'html'], logLevel: 'error',
      onlyCategories: ['performance', 'accessibility', 'best-practices', 'seo'],
      formFactor: 'mobile', throttlingMethod: 'simulate',
      screenEmulation: { mobile: true, width: 390, height: 844, deviceScaleFactor: 1, disabled: false },
    });
    if (!result) throw new Error('Lighthouse returned no result.');
    await writeFile(new URL(`run-${index}.json`, directory), result.report[0]);
    await writeFile(new URL(`run-${index}.html`, directory), result.report[1]);
    const { lhr } = result;
    const report = {
      run: index, timestamp: lhr.fetchTime, lighthouseVersion: lhr.lighthouseVersion,
      environment: lhr.environment, settings: lhr.configSettings,
      scores: Object.fromEntries(Object.entries(lhr.categories).map(([key, value]) => [key, Math.round(value.score * 100)])),
      largestContentfulPaintMs: lhr.audits['largest-contentful-paint'].numericValue,
      cumulativeLayoutShift: lhr.audits['cumulative-layout-shift'].numericValue,
      totalBlockingTimeMs: lhr.audits['total-blocking-time'].numericValue,
      firstContentfulPaintMs: lhr.audits['first-contentful-paint'].numericValue,
      speedIndexMs: lhr.audits['speed-index'].numericValue,
      interactiveNote: 'INP requires real interactions/field data; Lighthouse navigation is not an INP measurement.',
      runtimeError: lhr.runtimeError ?? null,
    };
    reports.push(report);
    console.log(JSON.stringify(report, null, 2));
  } finally {
    await browser.kill();
  }
}
await writeFile(new URL('summary.json', directory), JSON.stringify({ url, runs: reports }, null, 2));
if (reports.some((report) => report.runtimeError || report.scores.performance < 95 || report.scores.accessibility < 95 || report.largestContentfulPaintMs >= 2500 || report.cumulativeLayoutShift >= 0.1)) process.exitCode = 1;
