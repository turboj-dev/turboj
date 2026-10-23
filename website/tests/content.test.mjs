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

test('all approved v2 English copy is present in the rendered HTML', () => {
  const missing = requiredCopy.filter((copy) => !text.includes(normalizeText(copy)));
  assert.deepEqual(missing, [], `Missing supplied copy:\n${missing.join('\n')}`);
});

test('the illustrated replay remains distinct from the genuine native screenshot and measured capture', () => {
  assert.ok(text.includes('This illustration does not compile or execute Java in your browser.'));
  assert.ok(text.includes('These measurements are from the native Hello.java capture, not the illustrated HelloWorld.java replay above.'));
  assert.match(html, /data-demo-action="replay"/);
  assert.match(html, /data-demo-action="pause"/);
  assert.match(html, /data-demo-action="skip"/);
  assert.match(html, /data-demo-fallback/);
  assert.doesNotMatch(html, /Remember the joy of just writing code\?/);
  assert.doesNotMatch(html, /Remember when writing a program was simple\?/);
  assert.doesNotMatch(html, /Yes, that blue screen is real\./);
});

test('page title and factual product metadata are rendered', () => {
  assert.match(decode(html), /<title>TurboJ — The Ultra-Fast Java Editor for macOS<\/title>/);
  assert.ok(html.includes('TurboJ is a fast, minimal Java editor inspired by classic DOS development tools. Write, compile, and run Java in one focused macOS application.'));
  assert.ok(html.includes('TurboJ — Old-school Focus. Modern Java.'));
  assert.ok(html.includes('The classic edit, compile, run workflow is back. Meet TurboJ, the ultra-fast Java editor for macOS.'));
  assert.ok(html.includes('summary_large_image'));
  assert.ok(decode(html).includes(screenshotAlt));
  assert.equal((html.match(/<h1(?:\s|>)/g) ?? []).length, 1);
  assert.match(html, /<html[^>]*lang="en"/);
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
