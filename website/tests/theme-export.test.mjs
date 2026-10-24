import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';

const website = new URL('../', import.meta.url);
const native = new URL('../../app/src/main/resources/themes/', import.meta.url);
const exported = JSON.parse(await readFile(new URL('public/themes/turboj-themes.json', website), 'utf8'));
const ids = ['classic', 'dark', 'light'];
const kebab = name => name.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);

test('three exported themes are exact native definitions, including intelligence and popup colors', async () => {
  assert.equal(exported.schemaVersion, 2);
  assert.deepEqual(exported.themes.map(theme => theme.id), ids);
  for (const id of ids) {
    const source = JSON.parse(await readFile(new URL(`${id}.json`, native), 'utf8'));
    assert.deepEqual(exported.themes.find(theme => theme.id === id), source);
  }
});

test('every native color is exported verbatim into each root CSS scope', async () => {
  const css = await readFile(new URL('src/styles/themes.generated.css', website), 'utf8');
  for (const theme of exported.themes) {
    const section = css.split(`:root[data-theme="${theme.id}"] {`)[1]?.split('}')[0];
    assert.ok(section, `missing scope ${theme.id}`);
    for (const [name, values] of Object.entries(theme)) {
      if (name === 'font' || !values || typeof values !== 'object') continue;
      for (const [key, value] of Object.entries(values)) {
        assert.ok(section.includes(`--tj-${name}-${kebab(key)}: ${value};`), `${theme.id}.${name}.${key}`);
      }
    }
  }
});

test('generated assets match the deterministic native export', () => {
  execFileSync(process.execPath, ['scripts/export-themes.mjs', '--check'], { cwd: website, stdio: 'pipe' });
});

test('catalog exports lightweight metadata and separate exact native palettes', async () => {
  const registry = JSON.parse(await readFile(new URL('catalog.json', native), 'utf8'));
  const catalog = JSON.parse(await readFile(new URL('public/themes/catalog.json', website), 'utf8'));
  assert.deepEqual(catalog.map(entry => entry.id), registry.themes.map(entry => entry.id));
  for (const entry of catalog) {
    assert.deepEqual(Object.keys(entry).sort(), ['id', 'name', 'previewBackground', 'url']);
    assert.equal(entry.url, `/themes/palettes/${entry.id}.json`);
    const definition = registry.themes.find(theme => theme.id === entry.id);
    const source = JSON.parse(await readFile(new URL(definition.file, native), 'utf8'));
    const palette = JSON.parse(await readFile(new URL(`public${entry.url}`, website), 'utf8'));
    assert.equal(entry.name, source.name);
    assert.equal(entry.previewBackground, source.ui.background);
    assert.deepEqual(palette, source);
  }
  // The initial CSS/bundle remains restricted to primary themes as the catalog grows.
  assert.deepEqual(exported.themes.map(theme => theme.id), registry.primary);
});

test('published feature copy distinguishes optional native AI validation from unimplemented capabilities', async () => {
  const html = await readFile(new URL('dist/index.html', website), 'utf8');
  assert.ok(html.includes('Smart Java Completion'));
  assert.ok(html.includes('Your Keyboard, Your Theme'));
  assert.ok(html.includes('AI completion, when you want it.'));
  assert.ok(html.includes('Optional · Disabled by default'));
  assert.ok(html.includes('Java completion, navigation, and refactoring continue to work without AI.'));
  assert.match(html, /Ollama[\s\S]*?(?:verified|tested)/);
  assert.match(html, /protocol-tested[\s\S]*?live cloud[\s\S]*?(?:pending|unverified|not[\s\S]*?verified)/i);
  assert.ok(html.includes('href="https://github.com/turboj-dev/turboj/blob/main/docs/ai-completion.md"'));
  assert.ok(html.includes('JUnit &amp; Automation'));
});
