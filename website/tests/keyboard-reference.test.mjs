import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { exportKeyboardReference, referenceDocuments } from '../scripts/export-keyboard-reference.mjs';

const repository = new URL('../../', import.meta.url);
const source = 'commandj/commandj-keymap/src/main/java/dev/turboj/commandj/keymap/BundledKeymaps.java';
const digest = value => createHash('sha256').update(value).digest('hex');

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'turboj-keyboard-reference-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const manifest = { schema: 1, files: {}, generatedfiles: {} };
  for (const file of [source, ...referenceDocuments]) {
    const bytes = await readFile(new URL(file, repository));
    await mkdir(path.dirname(path.join(directory, file)), { recursive: true });
    await writeFile(path.join(directory, file), bytes);
    manifest[file === source ? 'files' : 'generatedfiles'][file] = digest(bytes);
  }
  const save = () => writeFile(path.join(directory, 'docs/keyboard-reference-sources.json'), JSON.stringify(manifest));
  await save();
  return { directory, manifest, save, output: path.join(directory, 'website/public') };
}

test('exports the actual generated native reference bytes without a second shortcut list', async t => {
  const { directory, output } = await fixture(t);
  assert.deepEqual(await exportKeyboardReference({ repository: directory }), referenceDocuments.map(file => path.basename(file)));
  assert.deepEqual((await readdir(output)).sort(), referenceDocuments.map(file => path.basename(file)).sort());
  for (const file of referenceDocuments) {
    assert.deepEqual(await readFile(path.join(output, path.basename(file))), await readFile(new URL(file, repository)));
  }
  await exportKeyboardReference({ repository: directory, check: true });
});

test('a changed native keymap fails before replacing published reference assets', async t => {
  const { directory, output } = await fixture(t);
  await exportKeyboardReference({ repository: directory });
  const published = await readFile(path.join(output, 'keyboard-shortcut-reference.md'));
  await writeFile(path.join(directory, source), 'changed native keymap\n');
  await assert.rejects(exportKeyboardReference({ repository: directory }), /Stale keyboard reference: .*BundledKeymaps.java/);
  assert.deepEqual(await readFile(path.join(output, 'keyboard-shortcut-reference.md')), published);
});

test('edited generated reference bytes cannot be exported under an old source manifest', async t => {
  const { directory } = await fixture(t);
  await writeFile(path.join(directory, referenceDocuments[0]), 'unverified hand-maintained bindings\n');
  await assert.rejects(exportKeyboardReference({ repository: directory }), /Stale keyboard reference: docs\/keyboard-command-inventory.md/);
});

test('missing source evidence, malformed hashes and incomplete reference manifests fail closed', async t => {
  const { directory, manifest, save } = await fixture(t);
  manifest.files[source] = 'not-a-sha256';
  await save();
  await assert.rejects(exportKeyboardReference({ repository: directory }), /Invalid SHA-256/);
  manifest.files = {};
  await save();
  await assert.rejects(exportKeyboardReference({ repository: directory }), /nonempty files/);
  manifest.files = { [source]: digest(await readFile(new URL(source, repository))) };
  delete manifest.generatedfiles[referenceDocuments[2]];
  await save();
  await assert.rejects(exportKeyboardReference({ repository: directory }), /missing docs\/keyboard-conflicts.md/);
});

test('manifest paths cannot escape the repository', async t => {
  const { directory, manifest, save } = await fixture(t);
  manifest.files = { '../outside.java': 'a'.repeat(64) };
  await save();
  await assert.rejects(exportKeyboardReference({ repository: directory }), /Invalid keyboard reference path/);
});

test('check mode detects stale website assets without rewriting them', async t => {
  const { directory, output } = await fixture(t);
  await exportKeyboardReference({ repository: directory });
  const published = path.join(output, 'keyboard-shortcut-reference.md');
  await writeFile(published, 'outdated published reference\n');
  await assert.rejects(exportKeyboardReference({ repository: directory, check: true }), /Stale published keyboard reference/);
  assert.equal(await readFile(published, 'utf8'), 'outdated published reference\n');
});
