import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { resolveTheme, themeBootstrap } from '../src/scripts/theme-preference.ts';
import { themes, themeTokenPaths } from '../src/data/themes.generated.ts';
import { readFile } from 'node:fs/promises';
import {
  nativePalette, validatePalette, validateCatalog, filterCatalog, cssToken,
  PaletteCache, PALETTE_CACHE_SIZE, pageFor,
} from '../src/scripts/theme-catalog.ts';

const bootstrap = (saved, blocked = false, palette = null) => {
  const meta = { content: '' };
  const properties = {};
  const document = { documentElement: { dataset: {}, style: { setProperty: (key, value) => { properties[key] = value; } } }, querySelector: () => meta };
  const localStorage = { getItem: (key) => { if (blocked) throw new Error('Storage unavailable'); return key === 'turboj.theme' ? saved : palette; } };
  runInNewContext(themeBootstrap(themes, themeTokenPaths), { document, localStorage });
  return { document, meta, properties };
};

test('only native exported theme IDs are accepted, with Classic as the default', () => {
  assert.deepEqual(themes.map(({ id }) => id), ['classic', 'dark', 'light']);
  for (const { id } of themes) assert.equal(resolveTheme(id, themes), id);
  for (const value of [null, undefined, '', 'amber', 'modern', 'Dark', '<script>', {}, 1]) {
    assert.equal(resolveTheme(value, themes), 'classic');
  }
});

const native = JSON.parse(await readFile(new URL('../public/themes/palettes/dark.json', import.meta.url), 'utf8'));
const entry = { id: native.id, name: native.name, url: '/themes/palettes/dark.json' };
const palette = nativePalette(native, entry, themeTokenPaths);

test('native palettes accept only the exact validated color fields and matching catalog identity', () => {
  assert.ok(palette);
  assert.equal(Object.keys(palette.colors).length, themeTokenPaths.length);
  assert.equal(cssToken('menu.activeBackground'), '--tj-menu-active-background');
  assert.equal(nativePalette({ ...native, id: 'different' }, entry, themeTokenPaths), null);
  assert.equal(nativePalette({ ...native, schemaVersion: 1 }, entry, themeTokenPaths), null);
  assert.equal(validatePalette({ ...palette, colors: { ...palette.colors, 'editor.background': 'url(https://example.com)' } }, themeTokenPaths), null);
  assert.equal(validatePalette({ ...palette, colors: {} }, themeTokenPaths), null);
  assert.equal(validatePalette({ ...palette, name: '   ' }, themeTokenPaths), null);
  const withExtra = validatePalette({ ...palette, colors: { ...palette.colors, arbitrary: '#000000' } }, themeTokenPaths);
  assert.equal(Object.hasOwn(withExtra.colors, 'arbitrary'), false);
});

test('a validated custom theme is restored synchronously before first paint', () => {
  const custom = { ...palette, id: 'my-dark', name: 'My dark' };
  const { document, meta, properties } = bootstrap('my-dark', false, JSON.stringify(custom));
  assert.equal(document.documentElement.dataset.theme, 'my-dark');
  assert.equal(document.documentElement.style.colorScheme, 'dark');
  assert.equal(meta.content, native.editor.background);
  assert.equal(properties['--tj-editor-background'], native.editor.background);
  for (const raw of ['invalid JSON', JSON.stringify({ ...custom, colors: {} }), JSON.stringify({ ...custom, id: 'wrong' }),
    JSON.stringify({ ...custom, name: '   ' }), JSON.stringify({ ...custom, colors: { ...custom.colors, 'editor.background': ['#000000'] } })]) {
    assert.equal(bootstrap('my-dark', false, raw).document.documentElement.dataset.theme, 'classic');
  }
});

test('catalog metadata is bounded, same-origin, unique and searchable without loading palettes', () => {
  const many = Array.from({ length: 1000 }, (_, index) => ({ id: `qa-${String(index).padStart(4, '0')}`, name: `QA Theme ${index}`, url: `/themes/palettes/qa-${index}.json` }));
  const catalog = validateCatalog(many, 'https://turboj.dev');
  assert.equal(catalog.length, 1000);
  assert.equal(filterCatalog(catalog, 'QA-0999')[0].id, 'qa-0999');
  assert.equal(pageFor(5), 0); assert.equal(pageFor(6), 1); assert.equal(pageFor(999), 166);
  assert.deepEqual(validateCatalog([many[0], many[0], { ...many[1], url: 'https://other.example/themes/x.json' }], 'https://turboj.dev'), [many[0]]);
  assert.throws(() => validateCatalog(Array(10001).fill(many[0]), 'https://turboj.dev'));
  assert.throws(() => validateCatalog([{ ...many[0], url: '/not-themes/theme.json' }], 'https://turboj.dev'));
});

test('palette cache remains bounded and protects recently used values', () => {
  const cache = new PaletteCache();
  for (let i = 0; i < PALETTE_CACHE_SIZE; i++) cache.put({ ...palette, id: `theme-${i}` });
  cache.get('theme-0');
  cache.put({ ...palette, id: 'theme-new' });
  assert.equal(cache.size, PALETTE_CACHE_SIZE);
  assert.ok(cache.get('theme-0'));
  assert.equal(cache.get('theme-1'), undefined);
});

test('the synchronous head bootstrap applies the persisted choice and native theme metadata', () => {
  for (const theme of themes) {
    const { document, meta } = bootstrap(theme.id);
    assert.equal(document.documentElement.dataset.theme, theme.id);
    assert.equal(meta.content, theme.themeColor);
    assert.equal(document.documentElement.style.colorScheme, theme.id === 'dark' ? 'dark' : 'light');
  }
});

test('missing, invalid or denied storage safely renders Classic without OS preference overrides', () => {
  for (const [saved, blocked] of [[null, false], ['invalid', false], ['dark', true]]) {
    const { document, meta } = bootstrap(saved, blocked);
    assert.equal(document.documentElement.dataset.theme, 'classic');
    assert.equal(meta.content, themes[0].themeColor);
  }
  assert.doesNotMatch(themeBootstrap(themes), /matchMedia|prefers-color-scheme/);
});
