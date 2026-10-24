import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
const source = new URL('../../javafmt-cli/target/javafmt.jar', import.meta.url);
const destination = new URL('../server/', import.meta.url);
await mkdir(destination, { recursive: true });
await copyFile(source, new URL('javafmt.jar', destination));
await copyFile(new URL('../../LICENSE', import.meta.url), new URL('../LICENSE', import.meta.url));
const notices = await readFile(new URL('../../docs/LICENSES.md', import.meta.url), 'utf8');
await writeFile(new URL('../THIRD_PARTY.md', import.meta.url), notices
  .replace('[MIT License](../LICENSE)', '[MIT License](LICENSE.txt)')
  .replace('[`javafmt-test-fixtures/openjdk/`](../javafmt-test-fixtures/openjdk/README.md)', '`javafmt-test-fixtures/openjdk/` (in the source repository)'));
await copyFile(new URL('../../docs/runtime-dependencies.json', import.meta.url), new URL('../runtime-dependencies.json', import.meta.url));
console.log('Prepared the independently built javafmt CLI JAR. No TurboJ installation is used.');
