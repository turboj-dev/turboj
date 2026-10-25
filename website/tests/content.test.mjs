import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { requiredCopy, normalizeText, screenshotAlt } from './copy.mjs';

const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8');
const decode = (value) => value.replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
  .replace(/&#x([\da-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ');
const text = normalizeText(decode(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
  .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '')
  .replace(/<\/?(?:a|span|em|strong|b|i|kbd|code)\b[^>]*>/gi, '')
  .replace(/<[^>]+>/g, ' ')));

test('approved 1.0.10 English copy is present in the rendered HTML', () => {
  const missing = requiredCopy.filter((copy) => !text.includes(normalizeText(copy)));
  assert.deepEqual(missing, [], `Missing supplied copy:\n${missing.join('\n')}`);
});

test('the five-chapter reconstruction explains its evidence and execution limits', () => {
  assert.match(text, /Recorded TurboJ sessions, reconstructed in the browser/);
  assert.match(text, /No Java runs here/);
  assert.match(text, /Times from each identified captured session/);
  assert.match(html, /data-demo-keymap/);
  assert.match(html, /data-demo-prompt/);
  assert.ok(text.includes('Browser replay. No Java runs here.'));
  assert.doesNotMatch(html, /data-testid="screenshot-original"/);
  assert.doesNotMatch(text, /View original Classic-keymap screenshot/);
  for (const chapter of ['Hello World', 'Tests', 'Refactoring', 'AutoRun / AutoTest', 'Spring Boot + curl']) assert.ok(text.includes(chapter));
  for (const action of ['replay', 'pause', 'skip', 'previous', 'next', 'story']) assert.ok(html.includes(`data-demo-action="${action}"`));
  assert.match(html, /href="\/demo-session\/"/);
  assert.match(html, /data-demo-fallback/);
  assert.ok(text.includes('An earlier native prototype capture'));
  assert.match(text, /(?:not|isn’t|is not) a (?:current |TurboJ )?1\.0\.10 benchmark/i);
});

test('page title and factual product metadata are rendered', () => {
  assert.match(decode(html), /<title>TurboJ — Native Java Editor for macOS<\/title>/);
  assert.ok(html.includes('A lightweight, keyboard-first Java environment for macOS. Maven projects, semantic refactoring, fast compilation, JUnit tests, and optional AI completion.'));
  assert.ok(html.includes('summary_large_image'));
  assert.ok(decode(html).includes(screenshotAlt));
  assert.equal((html.match(/<h1(?:\s|>)/g) ?? []).length, 1);
  assert.match(html, /<html[^>]*lang="en"/);
  assert.match(html, /rel="canonical" href="https:\/\/turboj\.dev\/"/);
});

test('the existing content structure has six features, four steps and one roadmap', () => {
  assert.equal((html.match(/class="feature"/g) ?? []).length, 6);
  assert.equal((html.match(/class="workflow-step"/g) ?? []).length, 4);
  assert.equal((html.match(/id="roadmap"/g) ?? []).length, 1);
  assert.equal((text.match(/Maven Projects\. Zero Friction\./g) ?? []).length, 1);
  assert.equal((text.match(/Native inside TurboJ, standalone everywhere else\./g) ?? []).length, 1);
  assert.ok(html.includes('https://github.com/turboj-dev/turboj/blob/main/TODO.md'));
  assert.doesNotMatch(text, /Three steps\. One window\.|The first working editor|F2 to save\. F9 to make\. Ctrl\+F9 to run\./);
});

test('keyboard download uses the generated native reference without changing its bindings', async () => {
  assert.match(html, /<a\b[^>]*href="\/keyboard-shortcut-reference\.md"[^>]*\bdownload(?:="[^"]*")?[^>]*>Download generated shortcut reference<\/a>/);
  for (const name of ['keyboard-command-inventory.md', 'keyboard-shortcut-reference.md', 'keyboard-conflicts.md']) {
    assert.deepEqual(await readFile(new URL(`../dist/${name}`, import.meta.url)),
      await readFile(new URL(`../../docs/${name}`, import.meta.url)));
  }
});

test('navigation has real section targets and no placeholder links', () => {
  for (const anchor of ['demo', 'features', 'workflow', 'performance', 'faq']) {
    assert.ok(html.includes(`id="${anchor}"`), `Missing #${anchor}`);
    assert.ok(html.includes(`href="#${anchor}"`), `Missing link to #${anchor}`);
  }
  assert.doesNotMatch(html, /href="(?:#|javascript:[^"]*|)"/);
  assert.doesNotMatch(html, /<a[^>]+href="(?:\/download\/?|\/docs\/?|\/changelog\/?)"/);
});

test('reported prototype values correspond to the available original capture', () => {
  for (const value of ['0.03 ms', '1.17 ms', '2.22 ms']) assert.ok(text.includes(value), `Missing measured capture value ${value}`);
  for (const value of ['0.08 ms', '2.60 ms', '8.27 ms']) assert.ok(!text.includes(value), `Unavailable capture value ${value} must not be presented`);
  assert.ok(text.includes('A cache hit is not a fresh compilation, and an individual execution is not a benchmark.'));
});

test('full-resolution screenshot preserves the genuine repository image byte for byte', async () => {
  const original = await readFile(new URL('../../docs/images/turboj-classic.png', import.meta.url));
  const published = await readFile(new URL('../dist/images/turboj-editor.png', import.meta.url));
  const digest = (buffer) => createHash('sha256').update(buffer).digest('hex');
  assert.equal(digest(published), digest(original));
});

test('no client framework hydration or external asset sources', () => {
  assert.doesNotMatch(html, /<astro-island/);
  assert.doesNotMatch(html, /<(?:script|img|source)[^>]+src="https?:\/\//);
  assert.doesNotMatch(html, /<link[^>]+(?:href="https?:\/\/[^\"]*\.(?:css|woff2)|href="https?:\/\/fonts\.)/);
});
