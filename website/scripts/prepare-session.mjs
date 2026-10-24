import { readFile, writeFile, mkdir, copyFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve, basename, join } from 'node:path';

const website = fileURLToPath(new URL('../', import.meta.url));
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const chapterTitles = {
  'hello-world': 'Hello World', tests: 'Test', refactoring: 'Refactoring',
  automation: 'AutoRun / AutoTest', 'spring-boot': 'Spring Boot + curl',
};

/** Piecewise elapsed-time mapping. Only gaps outside measured operations can be cut. */
export function timingMap(events, measurements, maximumGapMs = 1800) {
  const points = [...new Set([
    ...events.map((event) => event.atNs),
    ...measurements.flatMap((measurement) => [measurement.startNs, measurement.endNs]),
  ])].sort((a, b) => a - b);
  if (!points.length) throw new Error('No captured events');
  const cuts = [];
  const mapped = new Map([[points[0], 0]]);
  let elapsed = 0;
  for (let i = 1; i < points.length; i += 1) {
    const start = points[i - 1], end = points[i];
    const measured = measurements.some((m) => m.startNs < end && m.endNs > start);
    const original = (end - start) / 1e6;
    const shown = measured ? original : Math.min(original, maximumGapMs);
    if (shown < original) cuts.push({ startNs: start, endNs: end, originalMs: original, shownMs: shown });
    elapsed += shown;
    mapped.set(end, elapsed);
  }
  return { at: (ns) => {
    if (!mapped.has(ns)) throw new Error(`Unmapped capture timestamp: ${ns}`);
    return mapped.get(ns);
  }, cuts };
}

export function normalize(raw, environment, id) {
  if (!raw.metadata.completed) throw new Error('Refusing to publish an incomplete capture');
  const seen = [...new Set(raw.events.map((event) => event.chapter))];
  if (JSON.stringify(seen) !== JSON.stringify(Object.keys(chapterTitles))) {
    throw new Error(`Capture must contain all five chapters in order: ${seen.join(', ')}`);
  }
  for (const event of raw.events) {
    // Text redaction cannot repair a private path already painted into a screenshot.
    if (/\/Users\//.test(JSON.stringify(event.state))) {
      throw new Error(`Private home path in ${event.id}; recapture with an isolated public workspace`);
    }
  }
  for (const measurement of raw.measurements) {
    if (!measurement.success || measurement.endNs < measurement.startNs
      || measurement.durationNs !== measurement.endNs - measurement.startNs) {
      throw new Error(`Invalid measured interval: ${measurement.id}`);
    }
  }
  const mapping = timingMap(raw.events, raw.measurements);
  const imported = raw.events.find((event) => event.id === 'maven-import-result');
  // Transcribed from this session's reviewed native frame, not an inferred feature list.
  const projectTree = 'spring-hello\n└─spring-hello · Java 21\n  ├─Sources\n  ├─Tests\n  ├─Generated sources\n  ├─Generated tests\n  ├─Resources\n  ├─Test resources\n  └─Dependencies';
  const events = raw.events.map((event) => ({
    ...event, atMs: mapping.at(event.atNs),
    screenshot: `/demo/${id}/${event.screenshot}`,
    state: {
      ...event.state,
      dialogs: event.state.dialogs.map((dialog) => typeof dialog === 'string' ? dialog : dialog.title),
      dialogDetails: event.state.dialogs,
      ...(imported && event.atNs >= imported.atNs ? { projectTree } : {}),
      ...(event.state.menus?.includes('Options') ? { menuBounds: { x: 584, y: 49, width: 366, height: 280 } }
        : event.state.menus?.includes('Build') ? { menuBounds: { x: 120, y: 49, width: 342, height: 375 } } : {}),
    },
  }));
  const measurements = raw.measurements.map((measurement) => ({
    ...measurement, method: measurement.id === 'maven-import'
      ? 'System.nanoTime before the actual TurboFrame.openPath(pom.xml) call through the settled model and EDT paint. Includes model/dependency work and pending configuration synchronization; no file chooser time.'
      : measurement.id === 'curl'
        ? 'System.nanoTime before external ProcessBuilder execution through stdout EOF, exit validation and the capture paint checkpoint. The curl process-only duration is stored separately in metadata.curl.'
        : measurement.id.endsWith('-wait')
          ? 'A 600 ms unsaved-edit observation window followed by an EDT paint checkpoint. This is a negative execution check, not an automatic-trigger latency.'
          : measurement.boundary,
    startMs: mapping.at(measurement.startNs), endMs: mapping.at(measurement.endNs),
    durationMs: measurement.durationNs / 1e6,
  }));
  const chapters = seen.map((chapter, index) => {
    const first = events.find((event) => event.chapter === chapter);
    const next = events.find((event) => event.chapter === seen[index + 1]);
    return { id: chapter, title: chapterTitles[chapter], startMs: first.atMs,
      endMs: next?.atMs ?? events.at(-1).atMs };
  });
  return {
    metadata: {
      ...raw.metadata, id, verified: true, recordedAt: raw.metadata.startedAt,
      commit: environment.commit, appJarSha256: environment.appJarSha256,
      os: environment.os.replace(/\s+/g, ' ').trim(), cpu: environment.cpu,
      memory: `${environment.memoryBytes / 2 ** 30} GiB`, jdk: environment.jdk,
      method: raw.metadata.measurementBoundary,
      conditions: [
        raw.metadata.conditions,
        'Captured in an ordinary development session with other development/browser work possible. The demo workspace and settings are isolated; the machine was not quiesced for a benchmark.',
        raw.metadata.presentationPauses,
        'Automated input in the actual native application. Source entry and reading pauses are not measures of typing performance.',
        raw.metadata.automationTrigger,
        mapping.cuts.length
          ? 'Operation intervals play at 1×. Only unmeasured pauses longer than 1.8 seconds are shortened; all cuts are listed in the data.'
          : 'Operation intervals play at 1×. This recording retains its actual scripted source entry and reading pauses; no timeline gaps were shortened.',
        'Completion is sampled and painted on the Swing EDT. This is not a physical-display or browser millisecond-accuracy claim.',
        'Spring Boot uses the explicit Maven spring-boot:run goal. Native Fast Run and Isolated Run retire this server when main returns.',
        'curl is an external process, not a TurboJ terminal. Runner-reported durations in the output are separate from command-to-result elapsed time.',
        raw.metadata.springBoot?.cache,
        raw.metadata.springReadiness,
        raw.metadata.springImport ? `Maven import is measured from Open through the settled model, including the configuration synchronization. The final publication reported model cacheHit=${raw.metadata.springImport.cacheHit}, ${raw.metadata.springImport.dependencyCount} dependencies, offline=${raw.metadata.springImport.offline}, native fast-path status ${raw.metadata.springImport.fastPath}. This is not a cold dependency-download measurement.` : undefined,
        ...(raw.metadata.springConditions ? [raw.metadata.springConditions] : []),
      ].filter(Boolean),
      rawTrace: `/demo/${id}/trace.json`,
      screenshotPolicy: 'Unmodified frame-only PNG captures. No screenshot recoloring or compositing. Classic previews retain their captured theme.',
      framing: 'The original hero dimensions are retained. The HTML source/output view is scrollable, and the project explorer is cropped out on narrow screens. Its labels were transcribed from the selected Maven import result frame; the original full frame remains available.',
      projectTreeEvidence: imported ? `/demo/${id}/${imported.screenshot}` : undefined,
      video: 'No video was recorded. The evidence consists of actual EDT-painted frame captures, source snapshots and monotonic traces.',
      cuts: mapping.cuts,
    },
    chapters, measurements, events,
  };
}

async function main() {
  const input = process.argv[2];
  if (!input) throw new Error('Usage: node scripts/prepare-session.mjs ../target/hero-session/selected-session');
  const directory = resolve(input);
  const id = basename(directory);
  if (!/^[a-z0-9-]+$/.test(id)) throw new Error('Use a simple session directory name');
  const rawBytes = await readFile(join(directory, 'trace.json'));
  const raw = JSON.parse(rawBytes);
  const environment = JSON.parse(await readFile(resolve(website, '../target/hero-session/environment.json'), 'utf8'));
  const session = normalize(raw, environment, id);
  const destination = join(website, 'public/demo', id);
  await mkdir(join(destination, 'screenshots'), { recursive: true });
  const manifest = { session: id, appJarSha256: environment.appJarSha256, traceSha256: hash(rawBytes), files: {} };
  await writeFile(join(destination, 'trace.json'), rawBytes);
  for (const event of raw.events) {
    const relative = event.screenshot;
    if (!/^screenshots\/[a-zA-Z0-9._-]+\.png$/.test(relative)) throw new Error(`Unsafe screenshot path ${relative}`);
    const bytes = await readFile(join(directory, relative));
    manifest.files[relative] = hash(bytes);
    await copyFile(join(directory, relative), join(destination, relative));
  }
  for (const file of await readdir(directory)) {
    if (/\.after\.java$/.test(file)) {
      const bytes = await readFile(join(directory, file));
      manifest.files[file] = hash(bytes);
      await copyFile(join(directory, file), join(destination, file));
    }
  }
  await writeFile(join(destination, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  const serialized = JSON.stringify(session, null, 2) + '\n';
  await writeFile(join(website, 'src/data/recorded-session.json'), serialized);
  await writeFile(join(website, 'public/demo/session.json'), serialized);
  console.log(`Prepared ${id}: ${session.events.length} captured states, ${session.measurements.length} measured intervals, ${session.metadata.cuts.length} pause cuts.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
