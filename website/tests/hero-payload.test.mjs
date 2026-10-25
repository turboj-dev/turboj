import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { gzipSync } from 'node:zlib';
import { test } from 'node:test';

const dist = new URL('../dist/', import.meta.url);
const files = (await readdir(new URL('_astro/', dist))).filter((file) => file.endsWith('.js'));
const bundles = await Promise.all(files.map(async (file) => {
  const bytes = await readFile(new URL(`_astro/${file}`, dist));
  return { file, bytes: bytes.length, gzipBytes: gzipSync(bytes).length };
}));
const manifest = JSON.parse(await readFile(new URL('demo/hero-story/manifest.json', dist), 'utf8'));
const html = await readFile(new URL('index.html', dist), 'utf8');
const inlineScripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
  .filter(([, attributes]) => !/\bsrc=|application\/ld\+json/.test(attributes))
  .map(([, , script]) => ({ bytes: Buffer.byteLength(script), gzipBytes: gzipSync(script).length, hero: script.includes('demo-js') }));

test('production hero and total JavaScript stay within the recorded-session budgets', (t) => {
  const hero = bundles.filter(({ file }) => file.startsWith('TurboJDemo.'));
  assert.equal(hero.length, 1, 'Expected one standalone hero controller/model bundle');
  const heroTotal = hero[0].gzipBytes + inlineScripts.filter((script) => script.hero).reduce((sum, script) => sum + script.gzipBytes, 0);
  assert.ok(heroTotal <= 8 * 1024, JSON.stringify(hero));
  // Counting all emitted JS is deliberately stricter than counting initial requests only.
  const total = [...bundles, ...inlineScripts].reduce((sum, bundle) => sum + bundle.gzipBytes, 0);
  assert.ok(total <= 18 * 1024, JSON.stringify(bundles));
  t.diagnostic(JSON.stringify({ bundles, inlineScripts, heroJavaScriptGzipBytes: heroTotal, allJavaScriptGzipBytes: total }));
});

test('five lazy chapter payloads fit the text-data budget and do not embed screenshot bytes', async (t) => {
  const chapters = await Promise.all(manifest.chapters.map(async (chapter) => {
    const bytes = await readFile(new URL(chapter.url.replace(/^\//, ''), dist));
    assert.doesNotMatch(bytes.toString(), /data:image\/|base64,/);
    return { id: chapter.id, bytes: bytes.length, gzipBytes: gzipSync(bytes).length };
  }));
  assert.equal(chapters.length, 5);
  assert.ok(chapters.reduce((sum, chapter) => sum + chapter.gzipBytes, 0) <= 30 * 1024, JSON.stringify(chapters));
  t.diagnostic(JSON.stringify({ chapters }));
});
