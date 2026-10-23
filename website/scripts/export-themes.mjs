import { readFile, writeFile, mkdir, readdir, unlink } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname, sep } from 'node:path';
import { createHash } from 'node:crypto';

const website = fileURLToPath(new URL('../', import.meta.url));
const native = resolve(website, '../app/src/main/resources/themes');
const choices = [['classic', 'Classic'], ['dark', 'Dark'], ['light', 'Light']];
const registry = JSON.parse(await readFile(resolve(native, 'catalog.json'), 'utf8'));
if (registry.schemaVersion !== 1 || !Array.isArray(registry.themes) || registry.themes.length > 10_000
    || JSON.stringify(registry.primary) !== JSON.stringify(choices.map(([id]) => id))) {
  throw new Error('Invalid native theme catalog registry.');
}
const fields = {
  editor: ['background', 'foreground', 'cursor', 'selection', 'selectionForeground', 'error'],
  syntax: ['keyword', 'string', 'comment', 'number', 'identifier', 'operator', 'annotation'],
  menu: ['background', 'foreground', 'activeBackground', 'activeForeground'],
  messages: ['background', 'foreground', 'errorForeground', 'inputForeground', 'cursor'],
  diagnostics: ['error', 'warning', 'success'],
  chrome: ['border', 'statusBackground', 'statusForeground'],
  ui: ['background', 'foreground', 'surface', 'muted', 'heading', 'border', 'accent', 'accentForeground', 'link', 'focus'],
  documentation: ['background', 'foreground', 'border', 'code', 'link'],
  intelligence: ['ghost', 'aiGhost', 'selectionBackground', 'selectionForeground', 'definition', 'activeParameter'],
};
const kebab = (name) => name.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);
const themes = [];
const seen = new Set();
for (const { id, file } of registry.themes) {
  if (!/^[a-z][a-z0-9_-]{0,63}$/.test(id) || seen.has(id) || typeof file !== 'string'
      || !/^[A-Za-z0-9_-]+\.json$/.test(file) || file === 'catalog.json') {
    throw new Error(`Invalid or duplicate native catalog entry: ${id}`);
  }
  seen.add(id);
  const path = resolve(native, file);
  if (!path.startsWith(native + sep) || !path.endsWith('.json')) throw new Error(`Invalid theme file: ${file}`);
  const definition = await readFile(path);
  if (definition.length > 65_536) throw new Error(`Native theme exceeds 64 KiB: ${id}`);
  const theme = JSON.parse(definition.toString('utf8'));
  if (theme.schemaVersion !== 2 || theme.id !== id || typeof theme.name !== 'string'
      || !theme.name.trim() || theme.name.length > 120) {
    throw new Error(`Invalid native theme identity/schema: ${id}`);
  }
  for (const [section, names] of Object.entries(fields)) {
    for (const name of names) {
      if (!/^#[0-9a-fA-F]{6}$/.test(theme[section]?.[name] ?? '')) {
        throw new Error(`Missing/invalid native color: ${id}.${section}.${name}`);
      }
    }
  }
  themes.push(theme);
}
const presets = choices.map(([id]) => {
  const theme = themes.find(theme => theme.id === id);
  if (!theme) throw new Error(`Missing primary theme: ${id}`);
  return theme;
});
const sourceHash = createHash('sha256').update(JSON.stringify(themes)).digest('hex');
const banner = `Generated from native app/src/main/resources/themes/*.json. DO NOT EDIT. Source SHA-256: ${sourceHash}`;
const css = `/* ${banner} */\n` + presets.map(theme => {
  const selector = theme.id === 'classic' ? ':root, :root[data-theme="classic"]' : `:root[data-theme="${theme.id}"]`;
  const lines = Object.entries(fields).flatMap(([section, names]) => names.map(name =>
    `  --tj-${section}-${kebab(name)}: ${theme[section][name]};`));
  return `${selector} {\n  color-scheme: ${theme.id === 'dark' ? 'dark' : 'light'};\n${lines.join('\n')}\n}\n`;
}).join('\n');
const metadata = presets.map((theme, index) => ({
  id: theme.id, name: theme.name, label: choices[index][1], themeColor: theme.editor.background,
}));
const tokenPaths = Object.entries(fields).flatMap(([section, names]) => names.map(name => `${section}.${name}`));
const typescript = `// ${banner}\nexport const themes = ${JSON.stringify(metadata, null, 2)} as const;\nexport type ThemeId = typeof themes[number]['id'];\nexport const themeTokenPaths = ${JSON.stringify(tokenPaths, null, 2)} as const;\n`;
const json = JSON.stringify({ schemaVersion: 2, sourceHash, themes: presets }, null, 2) + '\n';
const catalog = themes.map(({id, name, ui}) => ({ id, name, url: `/themes/palettes/${id}.json`, previewBackground: ui.background }));
const outputs = {
  'src/styles/themes.generated.css': css,
  'src/data/themes.generated.ts': typescript,
  'public/themes/turboj-themes.json': json,
  'public/themes/catalog.json': JSON.stringify(catalog, null, 2) + '\n',
  ...Object.fromEntries(themes.map(theme => [`public/themes/palettes/${theme.id}.json`, JSON.stringify(theme, null, 2) + '\n'])),
};
if (themes.length > presets.length) {
  outputs['public/themes/THIRD_PARTY_NOTICES.txt'] = await readFile(resolve(native, 'THIRD_PARTY_NOTICES.txt'), 'utf8');
}
const check = process.argv.includes('--check');
for (const [relative, expected] of Object.entries(outputs)) {
  const path = resolve(website, relative);
  if (check) {
    const actual = await readFile(path, 'utf8').catch(() => '');
    if (actual !== expected) throw new Error(`Stale native theme export: ${relative}. Run npm run themes.`);
  } else {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, expected);
  }
}
const paletteDirectory = resolve(website, 'public/themes/palettes');
for (const file of await readdir(paletteDirectory).catch(() => [])) {
  if (!file.endsWith('.json') || seen.has(file.slice(0,-5))) continue;
  if (check) throw new Error(`Retired generated theme asset: ${file}. Run npm run themes.`);
  await unlink(resolve(paletteDirectory, file));
}
console.log(`${check ? 'Verified' : 'Exported'} ${themes.length} native catalog themes, ${presets.length} primary palettes (${sourceHash.slice(0, 12)}).`);
