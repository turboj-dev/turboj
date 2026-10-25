import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
export const CHAPTERS = [
  ['hello-world', 'Hello World'], ['tests', 'Tests'], ['refactoring', 'Refactoring'],
  ['automation', 'AutoRun / AutoTest'], ['spring-boot', 'Spring Boot + curl'],
];
export const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const json = (value) => JSON.stringify(value, null, 2) + '\n';
const compact = (value) => JSON.stringify(value) + '\n';
const ns = (value, label) => assert(Number.isSafeInteger(value) && value >= 0, `${label}: invalid nanosecond value`);
const hash = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
export function safePath(value) {
  assert(typeof value === 'string' && value.length > 0 && !value.includes('\\') && !path.isAbsolute(value)
    && !value.split('/').some((part) => part === '..' || part === '.' || part === ''), `Unsafe evidence path: ${value}`);
  return value;
}
export function privacyCheck(value) {
  assert(!/(?:\/Users\/|(?:^|[\s"'=:])\/home\/|[A-Z]:\\Users\\)/i.test(value), 'Private home path in public evidence');
  assert(!/(?:ghp_|github_pat_|sk_live_)[A-Za-z0-9_]{12,}/.test(value), 'Credential pattern in public evidence');
}

/** Only explicitly classified human intervals can be shortened. Unknown time is retained. */
export function timingMap(events, measurements, editorialIntervals = []) {
  assert(events.length > 1, 'A capture needs at least two observed states');
  let previous = -1;
  for (const event of events) { ns(event.atNs, event.id); assert(event.atNs >= previous, 'Events are not monotonic'); previous = event.atNs; }
  const origin = events[0].atNs, end = events.at(-1).atNs;
  const sortedOps = [...measurements].sort((a, b) => a.startNs - b.startNs);
  for (let i = 0; i < sortedOps.length; i++) {
    const m = sortedOps[i]; ns(m.startNs, m.id); ns(m.endNs, m.id); ns(m.durationNs, m.id);
    assert(m.endNs > m.startNs && m.endNs - m.startNs === m.durationNs, `Inconsistent operation duration: ${m.id}`);
    assert(m.startNs >= origin && m.endNs <= end, `Operation outside recorded story: ${m.id}`);
    assert(i === 0 || sortedOps[i - 1].endNs <= m.startNs, `Overlapping operations: ${m.id}`);
  }
  const limits = { typing: 70, reading: 650, navigation: 200 };
  const intervals = [...editorialIntervals].sort((a, b) => a.startNs - b.startNs);
  const cuts = [];
  for (let i = 0; i < intervals.length; i++) {
    const e = intervals[i]; ns(e.startNs, e.id); ns(e.endNs, e.id);
    assert(Object.hasOwn(limits, e.kind) && typeof e.reason === 'string' && e.reason.trim(), `Unclassified editorial interval: ${e.id}`);
    assert(e.endNs >= e.startNs, `Negative editorial interval: ${e.id}`);
    assert(i === 0 || intervals[i - 1].endNs <= e.startNs, `Overlapping editorial intervals: ${e.id}`);
    assert(!sortedOps.some((m) => e.startNs < m.endNs && e.endNs > m.startNs), `Editorial interval overlaps operation: ${e.id}`);
    const startNs = Math.max(origin, e.startNs), endNs = Math.min(end, e.endNs);
    if (endNs <= startNs) continue;
    const durationMs = (endNs - startNs) / 1e6;
    const shownMs = Math.min(durationMs, limits[e.kind]);
    if (shownMs < durationMs) cuts.push({ id: e.id, kind: e.kind, reason: e.reason, startNs, endNs, durationMs, shownMs, removedMs: durationMs - shownMs });
  }
  const map = (atNs) => {
    ns(atNs, 'Timeline position'); assert(atNs >= origin && atNs <= end, 'Position outside recorded story');
    let result = (atNs - origin) / 1e6;
    for (const cut of cuts) {
      if (atNs <= cut.startNs) break;
      const fraction = Math.min(1, (atNs - cut.startNs) / (cut.endNs - cut.startNs));
      result -= cut.removedMs * fraction;
    }
    return result;
  };
  for (const m of measurements) assert(Math.abs(map(m.endNs) - map(m.startNs) - m.durationNs / 1e6) < 1e-6, `Operation accelerated: ${m.id}`);
  return { map, cuts, originNs: origin, endNs: end, durationMs: map(end), originalDurationMs: (end - origin) / 1e6 };
}

export function validateCapture(raw, { representative = true } = {}) {
  assert(raw.metadata?.schemaVersion === 2, 'Expected current capture schema version 2');
  assert(raw.metadata.completed === true, 'Capture did not complete');
  if (representative) assert(raw.metadata.selectedRepresentativeRun === true, 'Capture is not the nominated representative run');
  assert(hash(raw.metadata.appJarSha256) && /^[a-f0-9]{40}$/.test(raw.metadata.turbojCommit), 'Missing application provenance');
  privacyCheck(JSON.stringify(raw));
  const ids = new Set();
  for (const event of raw.events) {
    assert(!ids.has(event.id), `Duplicate event: ${event.id}`); ids.add(event.id);
    safePath(event.screenshot); safePath(event.state.file);
    assert(hash(event.state.sourceSha256) && sha256(event.state.source) === event.state.sourceSha256, `Source hash mismatch: ${event.id}`);
    assert(Number.isSafeInteger(event.state.editorVersion) && Number.isSafeInteger(event.state.projectGeneration), `Missing source/workspace generation: ${event.id}`);
    for (const key of ['selectionStart', 'selectionEnd', 'cursorOffset']) assert(Number.isInteger(event.state[key]) && event.state[key] >= 0 && event.state[key] <= event.state.source.length, `Invalid ${key}: ${event.id}`);
    assert(event.state.selectionStart <= event.state.selectionEnd, `Reversed selection: ${event.id}`);
    assert(Number.isInteger(event.state.topLine) && event.state.topLine >= 0, `Invalid scroll position: ${event.id}`);
    assert(typeof event.state.console === 'string' && typeof event.state.externalTerminal === 'string', `Missing output: ${event.id}`);
  }
  const story = raw.events.filter((event) => event.chapter !== 'controls');
  const chapters = story.map((event) => event.chapter).filter((id, i, all) => i === 0 || id !== all[i - 1]);
  assert.deepEqual(chapters, CHAPTERS.map(([id]) => id), 'Five complete chapters in order are required');
  const measurements = raw.measurements.filter((m) => m.chapter !== 'controls');
  const operationIds = new Set();
  for (const m of measurements) {
    assert(!operationIds.has(m.id), `Duplicate operation: ${m.id}`); operationIds.add(m.id);
    assert(m.success === true && typeof m.boundary === 'string' && m.boundary.length > 30, `Missing successful measured boundary: ${m.id}`);
    const start = story.find((event) => event.operationId === m.id && event.kind === 'start');
    const end = story.find((event) => event.operationId === m.id && event.kind === 'result');
    assert(start && end && start.atNs === m.startNs && end.atNs === m.endNs && start.chapter === m.chapter && end.chapter === m.chapter, `Missing observed operation boundaries: ${m.id}`);
  }
  const byId = Object.fromEntries(story.map((e) => [e.id, e]));
  const result = (id) => { assert(byId[id], `Required result missing: ${id}`); return byId[id].state; };
  assert(result('hello-run-result').console.includes('Hello TurboJ!') && result('hello-run-result').console.includes('[exit 0'), 'Hello World output not verified');
  for (const [id, passed, failed] of [['tests-before-refactor-result', 2, 0], ['tests-after-refactor-result', 2, 0], ['autotest-fails-result', 1, 1], ['autotest-passes-result', 2, 0]]) {
    const state = result(id);
    assert(state.testReport?.count === 2 && state.testReport.passed === passed && state.testReport.failed === failed, `Actual JUnit result mismatch: ${id}`);
    // TurboTest compiles its own workspace. The live single-file editor compiler
    // can report missing JUnit while the actual project test runner succeeds.
    if (id.startsWith('autotest-')) assert(state.compilerResult?.success === true, `Save compilation did not succeed: ${id}`);
  }
  assert(result('rename-apply-result').source.includes('int discountedPrice(') && result('renamed-tests').source.includes('Price.discountedPrice('), 'Semantic rename result missing');
  assert(result('extract-apply-result').source.includes('private static int applyDiscount(') && result('extract-apply-result').source.includes('return applyDiscount('), 'Extract Method result missing');
  const testsBefore = result('test-source').source, testsAfter = result('renamed-tests').source;
  assert.equal(testsBefore.replaceAll('Price.discount(', 'Price.discountedPrice('), testsAfter, 'The same two tests must survive refactoring');
  for (const id of ['autotest-fails-result', 'autotest-passes-result']) {
    const state = result(id); assert(state.autoTestOnSave && !state.autoRunOnSave && !state.autoTestOnChanges, `Save automation options are not independent: ${id}`);
  }
  for (const id of ['autotest-fails', 'autotest-passes', 'autorun-saved']) {
    const commands = raw.commands.filter((command) => command.operationId === id);
    assert.deepEqual(commands.map((command) => command.id), ['save'], `Automation was not triggered solely by Save: ${id}`);
  }
  const run = result('autorun-saved-result');
  assert(run.autoRunOnSave && !run.autoTestOnSave && !run.autoTestOnChanges && run.console.includes('Updated price: 800 cents') && run.console.includes('[exit 0'), 'Auto Run result missing');
  for (const check of ['newFileSaved', 'unsavedAutoTestDidNotRun', 'unsavedAutoRunDidNotRun', 'autoTestOnChangesSeparate', 'settingsRestored']) assert(raw.metadata.checks?.[check] === true, `Required capture check missing: ${check}`);
  for (const id of ['hello-compile', 'hello-run', 'tests-before-refactor', 'rename-inspect', 'rename-prepare', 'rename-apply', 'extract-inspect', 'extract-prepare', 'extract-apply', 'tests-after-refactor', 'autotest-wait', 'autotest-fails', 'autotest-passes', 'autorun-wait', 'autorun-saved', 'maven-import', 'spring-startup', 'curl']) assert(operationIds.has(id), `Missing operation: ${id}`);
  assert(raw.metadata.springImport?.moduleCount > 0 && raw.metadata.springImport.dependencyCount > 0, 'Maven model/dependencies not verified');
  assert(raw.metadata.springReadiness?.includes('HTTP 200'), 'HTTP readiness was not observed');
  assert(raw.metadata.httpReadiness?.status === 200 && raw.metadata.httpReadiness.body === 'Hello from TurboJ!\n', 'HTTP status/body evidence missing');
  assert(raw.metadata.curl?.exitCode === 0 && raw.metadata.curl.stdout === 'Hello from TurboJ!\n', 'External curl did not return the expected body');
  assert(result('curl-result').externalTerminal.includes('Hello from TurboJ!\n[exit 0]'), 'External command output missing');
  assert(raw.metadata.springStopped === true && raw.metadata.checks.ownedServerStopped === true && byId['spring-stopped'], 'Owned Maven process not stopped');
  return { story, measurements };
}

export function normalizeCapture(raw, id) {
  safePath(id); assert(!id.includes('/'), 'Session ID must be a single path segment');
  const { story, measurements } = validateCapture(raw);
  const timing = timingMap(story, measurements, raw.editorialIntervals.filter((e) => e.chapter !== 'controls'));
  const base = `/demo/hero-story/evidence/${id}`;
  const chapters = CHAPTERS.map(([chapterId, title], index) => {
    const rawEvents = story.filter((event) => event.chapter === chapterId);
    const start = timing.map(rawEvents[0].atNs);
    const next = story.find((event) => event.chapter === CHAPTERS[index + 1]?.[0]);
    const end = next ? timing.map(next.atNs) : timing.durationMs;
    const sources = {}, outputs = {}, dialogs = {};
    const events = rawEvents.map((event) => {
      const s = event.state;
      const sourceId = `source-${s.sourceSha256}`;
      sources[sourceId] = { file: s.file, text: s.source, sha256: s.sourceSha256 };
      const external = Boolean(s.externalTerminal);
      const output = external ? s.externalTerminal : s.console;
      const outputId = `output-${sha256(output)}`; outputs[outputId] = output;
      const dialogId = s.dialogs?.length ? `dialog-${sha256(JSON.stringify(s.dialogs))}` : undefined;
      if (dialogId) dialogs[dialogId] = s.dialogs;
      const command = (raw.commands ?? []).find((command) => command.operationId === event.operationId && event.operationId);
      return { id: event.id, atMs: timing.map(event.atNs) - start, kind: event.id.includes('-typing-line-') ? 'typing' : event.kind, label: event.label, sourceId, outputId,
        filename: path.posix.basename(s.file), cursorOffset: s.cursorOffset, selectionStart: s.selectionStart, selectionEnd: s.selectionEnd,
        topLine: s.topLine + 1, status: String(s.status ?? ''), external,
        ...(command ? { command: `${command.title ?? command.id}${command.bindings?.length ? ` (${command.bindings.join(' / ')})` : ''}` } : {}),
        ...(event.operationId ? { operationId: event.operationId } : {}),
        ...(dialogId ? { dialogId, dialogLabel: s.dialogs.map((dialog) => dialog.title).join(' · ') } : {}),
        ...(s.explorer ? { explorer: s.explorer } : {}),
        ...(s.menuItems?.length ? { menuItems: s.menuItems } : {}),
        screenshot: `${base}/${safePath(event.screenshot)}`, sourceVersion: s.editorVersion, projectGeneration: s.projectGeneration, rawAtNs: event.atNs };
    });
    const operations = measurements.filter((m) => m.chapter === chapterId).map((m) => ({ id: m.id, label: m.label,
      startMs: timing.map(m.startNs) - start, endMs: timing.map(m.endNs) - start, durationMs: m.durationNs / 1e6,
      boundary: m.boundary, ...(m.compilerResult ? { compilerResult: m.compilerResult } : {}),
      ...(m.junitRunnerDurationNs != null ? { junitRunnerDurationNs: m.junitRunnerDurationNs } : {}) }));
    const checkpoints = events.filter((e, i) => i === 0 || e.kind === 'result' || e.kind === 'state' && !e.id.includes('-typing-line-')).map(({ id, label, atMs }) => ({ id, label, atMs }));
    return { id: chapterId, title, durationMs: end - start, sources, outputs, dialogs, events, operations, checkpoints };
  });
  const manifest = { schemaVersion: 1, id, verified: true, keymap: raw.metadata.keymap, menus: raw.metadata.menus,
    detailsUrl: '/demo-session/', dependencyCache: raw.metadata.springBoot.cache.includes('already downloaded') ? 'warm' : 'unverified',
    chapters: chapters.map((chapter) => ({ id: chapter.id, title: chapter.title, url: `/demo/hero-story/chapters/${chapter.id}.json`, durationMs: chapter.durationMs, sha256: sha256(compact(chapter)) })),
    rawTrace: `${base}/trace.json`, evidenceUrl: '/demo/hero-story/evidence.json', checksumsUrl: '/demo/hero-story/checksums.json',
    timing: { originNs: timing.originNs, originalDurationMs: timing.originalDurationMs, durationMs: timing.durationMs,
      policy: 'Only explicitly classified typing, reading and navigation intervals are shortened. Unknown gaps and measured operations remain at 1×.',
      limitsMs: { typing: 70, reading: 650, navigation: 200 }, cuts: timing.cuts } };
  return { manifest, chapters };
}

export function validateArtifacts(directory, raw) {
  const hashes = JSON.parse(readFileSync(path.join(directory, 'artifact-hashes.json'), 'utf8'));
  for (const [name, digest] of Object.entries(hashes)) {
    safePath(name); assert(hash(digest), `Invalid artifact hash: ${name}`);
    const file = path.join(directory, name); assert(lstatSync(file).isFile(), `Artifact is not a regular file: ${name}`);
    const bytes = readFileSync(file); assert.equal(sha256(bytes), digest, `Artifact changed: ${name}`);
    if (!name.endsWith('.png')) privacyCheck(bytes.toString('utf8'));
  }
  for (const event of raw.events) {
    assert(hashes[event.screenshot], `Screenshot missing from hash inventory: ${event.id}`);
    const source = raw.sourceRevisions[event.state.sourceSha256]; assert(source && hashes[source] === event.state.sourceSha256, `Source revision missing from archive: ${event.id}`);
  }
  return hashes;
}

export function prepare(captureDirectory, controls = [], nominationPath) {
  const directory = path.resolve(captureDirectory), id = path.basename(directory);
  const rawBytes = readFileSync(path.join(directory, 'trace.json')), raw = JSON.parse(rawBytes);
  const normalized = normalizeCapture(raw, id);
  const artifacts = validateArtifacts(directory, raw);
  const nominationBytes = nominationPath ? readFileSync(nominationPath) : null;
  if (nominationBytes) {
    privacyCheck(nominationBytes.toString('utf8'));
    const nomination = JSON.parse(nominationBytes);
    assert.equal(nomination.session, id, 'Nomination names a different session');
    assert(Date.parse(nomination.nominatedAt) < Date.parse(raw.metadata.startedAt), 'Session was not nominated before execution');
    assert.equal(nomination.sha256[raw.metadata.appJar], raw.metadata.appJarSha256, 'Nominated application changed');
  }
  const controlInputs = controls.map((input) => {
    const dir = path.resolve(input), bytes = readFileSync(path.join(dir, 'trace.json')), control = JSON.parse(bytes);
    privacyCheck(bytes.toString('utf8'));
    const hashes = validateArtifacts(dir, control);
    assert.notEqual(path.basename(dir), id, 'Representative session cannot also be a control');
    return { dir, bytes, control, hashes };
  });
  assert.equal(new Set(controlInputs.map(({ dir }) => path.basename(dir))).size, controlInputs.length, 'Duplicate control session IDs');
  const checks = { ...raw.metadata.checks };
  for (const { control } of controlInputs) for (const [key, value] of Object.entries(control.metadata.checks ?? {})) if (value === true) checks[key] = true;
  for (const key of ['stdoutVerified', 'stderrVerified', 'diagnosticsVerified']) assert(checks[key] === true, `Missing functional control: ${key}`);
  // Validate every input before writing any public file; partial native captures
  // never produce a manifest claiming verification.
  const destination = path.join(root, 'website/public/demo/hero-story');
  const files = {};
  const write = (name, bytes, immutable = false) => {
    safePath(name); const target = path.join(destination, name);
    if (immutable && existsSync(target)) assert.equal(sha256(readFileSync(target)), sha256(bytes), `Refusing to replace archived evidence: ${name}`);
    mkdirSync(path.dirname(target), { recursive: true }); writeFileSync(target, bytes); files[name] = sha256(bytes);
  };
  const archive = (dir, sessionId, traceBytes, hashes) => {
    const prefix = `evidence/${safePath(sessionId)}`;
    write(`${prefix}/trace.json`, traceBytes, true);
    write(`${prefix}/artifact-hashes.json`, readFileSync(path.join(dir, 'artifact-hashes.json')), true);
    for (const name of Object.keys(hashes)) write(`${prefix}/${name}`, readFileSync(path.join(dir, name)), true);
    return { id: sessionId, trace: `/demo/hero-story/${prefix}/trace.json`, traceSha256: sha256(traceBytes), artifacts: `/demo/hero-story/${prefix}/artifact-hashes.json` };
  };
  const selected = archive(directory, id, rawBytes, artifacts);
  if (nominationBytes) {
    write(`evidence/${id}/nomination.json`, nominationBytes, true);
    selected.nomination = `/demo/hero-story/evidence/${id}/nomination.json`;
    selected.nominationSha256 = sha256(nominationBytes);
  }
  const controlEvidence = controlInputs.map(({ dir, bytes, control, hashes }) => {
    return { ...archive(dir, path.basename(dir), bytes, hashes), completed: control.metadata.completed, failure: control.metadata.failure ?? null, checks: control.metadata.checks ?? {} };
  });
  const sourceIndex = {}, outputIndex = {};
  for (const event of raw.events) {
    const state = event.state, sourceId = `source-${state.sourceSha256}`;
    sourceIndex[sourceId] = { file: state.file, text: state.source, sha256: state.sourceSha256, url: `/demo/hero-story/evidence/${id}/${raw.sourceRevisions[state.sourceSha256]}` };
    for (const text of [state.console, state.externalTerminal]) {
      const digest = sha256(text); outputIndex[`output-${digest}`] = { text, sha256: digest, url: `/demo/hero-story/outputs/${digest}.txt` };
      write(`outputs/${digest}.txt`, text);
    }
  }
  // Static previews/hosts can treat extensionless or hidden paths as routes.
  // Keep the original immutable archive, and expose byte-identical text aliases
  // for every downloadable project file through ordinary public asset paths.
  const projectFiles = Object.entries(artifacts).filter(([name]) => name.startsWith('project-files/')).map(([name, digest]) => {
    const alias = `project-downloads/${digest}.txt`;
    write(alias, readFileSync(path.join(directory, name)));
    return { file: name.slice('project-files/'.length), sha256: digest,
      url: `/demo/hero-story/${alias}`, originalUrl: `/demo/hero-story/evidence/${id}/${name}` };
  });
  const details = { schemaVersion: 1, verified: true, id, metadata: raw.metadata, checks, selected, controls: controlEvidence,
    projectFiles,
    chapters: normalized.manifest.chapters, measurements: raw.measurements, editorialIntervals: raw.editorialIntervals,
    timing: normalized.manifest.timing, sources: sourceIndex, outputs: outputIndex,
    events: raw.events.map(({ state, ...event }) => ({ ...event, screenshot: `/demo/hero-story/evidence/${id}/${event.screenshot}`,
      state: { ...state, sourceId: `source-${state.sourceSha256}`, consoleId: `output-${sha256(state.console)}`, externalId: `output-${sha256(state.externalTerminal)}`, source: undefined, console: undefined, externalTerminal: undefined } })) };
  for (const chapter of normalized.chapters) write(`chapters/${chapter.id}.json`, compact(chapter));
  write('evidence.json', json(details));
  write('manifest.json', json(normalized.manifest));
  write('checksums.json', json({ algorithm: 'SHA-256', excludes: ['checksums.json'], files }));
  return normalized;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const index = args.indexOf('--nomination');
  const nomination = index >= 0 ? args.splice(index, 2)[1] : undefined;
  assert(args[0] && (index < 0 || nomination), 'Usage: node website/scripts/prepare-hero-story.mjs <nominated-capture-directory> [control-directory ...] [--nomination file]');
  const result = prepare(args[0], args.slice(1), nomination);
  console.log(JSON.stringify({ id: result.manifest.id, chapters: result.chapters.length, durationMs: result.manifest.timing.durationMs, cuts: result.manifest.timing.cuts.length }));
}
