import { cp, mkdir, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const source = fileURLToPath(new URL('../../target/refactorj-0.1.0/lib', import.meta.url));
const destination = fileURLToPath(new URL('../server/lib', import.meta.url));
await access(source);
await mkdir(destination, { recursive: true });
await cp(source, destination, { recursive: true });
for (const [from, to] of [
  ['../../NOTICE', '../server/NOTICE'],
  ['../../docs', '../server/docs'],
]) await cp(fileURLToPath(new URL(from, import.meta.url)), fileURLToPath(new URL(to, import.meta.url)), { recursive: true });
