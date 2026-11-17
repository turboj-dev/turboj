// Run after CheatSheetExport <directory> --keep-html. Uses existing website tooling.
import { chromium } from '../website/node_modules/playwright/index.mjs';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const directory = resolve(process.argv[2] ?? 'app/target/cheatsheet-validation');
const inventory = await readFile('docs/keyboard-command-inventory.md', 'utf8');
const expected = [...inventory.matchAll(/^\| `([^`]+)` \|/gm)].map(match => match[1]).sort();
const browser = await chromium.launch({ headless: true });
const report = [];
try {
  const files = (await readdir(directory)).filter(name => /^cheatsheet-.*\.html$/.test(name));
  assert.equal(files.length, 4, 'one generated sheet per bundled profile');
  await mkdir(join(directory, 'previews'), { recursive: true });
  for (const file of files) {
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1.5 });
    await page.goto(pathToFileURL(join(directory, file)).href);
    await page.evaluate(() => document.fonts.ready);
    const measured = await page.evaluate(() => {
      const problems = [];
      const columns = [...document.querySelectorAll('.column')];
      for (const [index, column] of columns.entries()) {
        const rect = column.getBoundingClientRect();
        const rows = [...column.querySelectorAll('.row')];
        if (!rows.length) problems.push(`Empty column ${index + 1}`);
        for (const row of rows) {
          const r = row.getBoundingClientRect();
          const label = row.dataset.command || row.textContent;
          if (r.bottom > rect.bottom + 1) problems.push(`Column overflow: ${label}`);
          for (const child of row.children) {
            const c = child.getBoundingClientRect();
            if (c.left < r.left - 1 || c.right > r.right + 1 || c.top < r.top - 1 || c.bottom > r.bottom + 1)
              problems.push(`Row clips text: ${label} (${c.width.toFixed(1)}x${c.height.toFixed(1)} in ${r.width.toFixed(1)}x${r.height.toFixed(1)})`);
          }
        }
      }
      return {
        pages: document.querySelectorAll('.page').length,
        columns: columns.map(column => column.querySelectorAll('.row').length),
        ids: [...document.querySelectorAll('.row[data-command]')].map(row => row.dataset.command).filter(Boolean).sort(),
        problems,
        font: getComputedStyle(document.querySelector('.row')).fontSize,
      };
    });
    assert.equal(measured.pages, 4, file);
    assert.equal(measured.columns.length, 16, file);
    assert.deepEqual(measured.ids, expected, `${file}: every command exactly once`);
    for (let index = 0; index < 4; index++)
      await page.locator('.page').nth(index).screenshot({ path: join(directory, 'previews', `${file.replace('.html', '')}-${index + 1}.png`) });
    report.push({ file, ...measured, ids: measured.ids.length });
    await page.close();
  }
  await writeFile(join(directory, 'layout-report.json'), JSON.stringify(report, null, 2) + '\n');
  assert.deepEqual(report.flatMap(sheet => sheet.problems.map(problem => `${sheet.file}: ${problem}`)), [], 'no clipped labels, keys or rows');
  console.log(JSON.stringify(report, null, 2));
} finally { await browser.close(); }
