import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const defaultRepository = fileURLToPath(new URL('../../', import.meta.url));
export const referenceDocuments = Object.freeze([
  'docs/keyboard-command-inventory.md',
  'docs/keyboard-shortcut-reference.md',
  'docs/keyboard-conflicts.md',
]);
const sha256 = buffer => createHash('sha256').update(buffer).digest('hex');

function entries(value, field) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || !Object.keys(value).length) {
    throw new Error(`Keyboard reference manifest requires a nonempty ${field} object.`);
  }
  for (const [file, hash] of Object.entries(value)) {
    if (file.includes('\\') || path.posix.isAbsolute(file)
        || file.split('/').some(part => !part || part === '.' || part === '..')) {
      throw new Error(`Invalid keyboard reference path: ${file}`);
    }
    if (typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash)) {
      throw new Error(`Invalid SHA-256 for keyboard reference input: ${file}`);
    }
  }
  return Object.entries(value);
}

// The native registry audit generates both the Markdown and its source manifest.
// Website builds only verify/copy local evidence; they never start Java or a GUI.
export async function exportKeyboardReference({ repository = defaultRepository,
  output = path.join(repository, 'website/public'), check = false } = {}) {
  const manifest = JSON.parse(await readFile(path.join(repository, 'docs/keyboard-reference-sources.json'), 'utf8'));
  if (manifest.schema !== 1) throw new Error('Unsupported keyboard reference manifest schema.');
  const sources = entries(manifest.files, 'files');
  const generated = entries(manifest.generatedfiles, 'generatedfiles');
  for (const file of referenceDocuments) {
    if (!Object.hasOwn(manifest.generatedfiles, file)) {
      throw new Error(`Keyboard reference manifest is missing ${file}.`);
    }
  }
  const verified = new Map();
  // Verify every input before writing anything, so a stale native source cannot
  // quietly republish an older shortcut table.
  for (const [file, expected] of [...sources, ...generated]) {
    const bytes = await readFile(path.join(repository, file));
    if (sha256(bytes) !== expected) {
      throw new Error(`Stale keyboard reference: ${file}. Rerun the native KeyboardRegistryAudit before building the website.`);
    }
    verified.set(file, bytes);
  }
  if (!check) await mkdir(output, { recursive: true });
  for (const file of referenceDocuments) {
    const destination = path.join(output, path.basename(file));
    const bytes = verified.get(file);
    if (check) {
      const published = await readFile(destination);
      if (!published.equals(bytes)) throw new Error(`Stale published keyboard reference: ${destination}`);
    } else {
      await writeFile(destination, bytes);
    }
  }
  return referenceDocuments.map(file => path.basename(file));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const files = await exportKeyboardReference({ check: process.argv.includes('--check') });
  console.log(`Verified native source hashes and ${files.length} generated keyboard references.`);
}
