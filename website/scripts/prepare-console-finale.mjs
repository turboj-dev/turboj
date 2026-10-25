import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { privacyCheck, safePath, sha256, timingMap, validateArtifacts } from './prepare-hero-story.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const destination = path.join(root, 'website/public/demo/hero-story');
const json = value => JSON.stringify(value, null, 2) + '\n';
const compact = value => JSON.stringify(value) + '\n';
const read = file => JSON.parse(readFileSync(file, 'utf8'));

/** A new finale has its own clock and build; it never changes historical capture bytes. */
export function normalizeFinale(raw, id) {
  safePath(id); assert(!id.includes('/'), 'Session ID must be one path segment');
  privacyCheck(JSON.stringify(raw));
  assert(raw.metadata.schemaVersion === 2 && raw.metadata.completed && raw.metadata.nativeConsoleFinale, 'Incomplete native console capture');
  assert(raw.metadata.selectedRepresentativeRun, 'A nominated presentation capture is required');
  assert(raw.metadata.springStopped && raw.metadata.checks.ownedServerStopped, 'Owned Spring server was not stopped');
  for (const key of ['nativeKeyboardCommandLine', 'nativeCurlHttp200Json', 'mavenStillRunningAfterCurl', 'mavenImported']) assert(raw.metadata.checks[key], `Missing native effect: ${key}`);
  assert(raw.metadata.httpReadiness.status === 200, 'Readiness HTTP status was not verified');
  assert(raw.metadata.curl.nativeConsole && raw.metadata.curl.exitCode === 0 && raw.metadata.curl.httpStatus === 200, 'Native curl did not succeed');
  assert.deepEqual(JSON.parse(raw.metadata.curl.body), { status: 200, message: 'OK' });
  const keys = raw.metadata.keyboardEvents.map(event => event.action);
  assert(keys.includes('Escape') && keys.includes('Type :') && keys.includes('Enter'), 'Actual keyboard route missing');
  assert(keys.indexOf('Escape') < keys.indexOf('Type :') && keys.indexOf('Type :') < keys.indexOf('Enter'), 'Keyboard order mismatch');
  const events = raw.events.filter(event => event.id !== 'spring-stopped');
  assert(events.every(event => event.chapter === 'spring-boot'), 'Finale contains another chapter');
  assert(events.at(-1).id === 'curl-visible-result', 'Final native checkpoint must retain the successful curl view');
  const result = events.find(event => event.id === 'curl-result');
  assert(result && !result.state.externalTerminal && result.state.console.includes('HTTP/1.1 200') && result.state.console.includes('[command exited 0]'), 'Native output checkpoint missing');
  assert(events.find(event => event.id === 'curl-commandline-open')?.state.commandLine?.visible, 'No actual command-row opening checkpoint');
  for (const event of events) {
    safePath(event.screenshot); safePath(event.state.file);
    assert.equal(sha256(event.state.source), event.state.sourceSha256, 'Source hash mismatch');
    assert.equal(event.state.externalTerminal, '', 'Finale must use the real native console');
    for (const key of ['cursorOffset', 'selectionStart', 'selectionEnd']) assert(Number.isInteger(event.state[key]) && event.state[key] >= 0 && event.state[key] <= event.state.source.length, `Invalid ${key}`);
    if (event.state.commandLine) {
      const row = event.state.commandLine;
      assert(typeof row.visible === 'boolean' && typeof row.text === 'string' && Number.isInteger(row.caret) && row.caret >= 0 && row.caret <= row.text.length, 'Invalid captured native command row');
    }
  }
  for (const measurement of raw.measurements) {
    assert(measurement.success && measurement.boundary.length > 30, 'Missing measurement boundary');
    assert(events.find(event => event.id === `${measurement.id}-start`)?.atNs === measurement.startNs, 'Missing operation start');
    assert(events.find(event => event.id === `${measurement.id}-result`)?.atNs === measurement.endNs, 'Missing operation end');
  }
  for (const operation of ['maven-import', 'spring-startup', 'curl']) assert(raw.measurements.some(item => item.id === operation), `Missing ${operation}`);
  const timing = timingMap(events, raw.measurements, raw.editorialIntervals.filter(item => item.startNs < events.at(-1).atNs));
  const base = `/demo/hero-story/evidence/${id}`;
  const sources = {}, outputs = {}, dialogs = {};
  const normalized = events.map(event => {
    const state = event.state, sourceId = `source-${state.sourceSha256}`, outputId = `output-${sha256(state.console)}`;
    sources[sourceId] = { file: state.file, text: state.source, sha256: state.sourceSha256 };
    outputs[outputId] = state.console;
    const dialogId = state.dialogs.length ? `dialog-${sha256(JSON.stringify(state.dialogs))}` : undefined;
    if (dialogId) dialogs[dialogId] = state.dialogs;
    const command = raw.commands.find(item => item.operationId && item.operationId === event.operationId);
    return { id: event.id, atMs: timing.map(event.atNs), kind: event.id.startsWith('curl-typing-') ? 'typing' : event.kind,
      label: event.label, sourceId, outputId, filename: path.posix.basename(state.file),
      cursorOffset: state.cursorOffset, selectionStart: state.selectionStart, selectionEnd: state.selectionEnd,
      topLine: state.topLine + 1, status: String(state.status ?? ''), external: false,
      ...(command ? { command: command.title ?? command.id } : {}),
      ...(event.operationId ? { operationId: event.operationId } : {}),
      ...(dialogId ? { dialogId, dialogLabel: state.dialogs.map(dialog => dialog.title).join(' · ') } : {}),
      ...(state.explorer ? { explorer: state.explorer } : {}),
      ...(state.menuItems?.length ? { menuItems: state.menuItems } : {}),
      ...(state.commandLine ? { commandLine: state.commandLine } : {}),
      ...(state.outputFraction != null ? { outputFraction: state.outputFraction } : {}),
      screenshot: `${base}/${event.screenshot}`, sourceVersion: state.editorVersion,
      projectGeneration: state.projectGeneration, rawAtNs: event.atNs, sessionId: id };
  });
  const chapter = { id: 'spring-boot', title: 'Spring Boot + curl', sessionId: id, durationMs: timing.durationMs,
    sources, outputs, dialogs, events: normalized,
    operations: raw.measurements.map(item => ({ id: item.id, label: item.label, startMs: timing.map(item.startNs), endMs: timing.map(item.endNs), durationMs: item.durationNs / 1e6, boundary: item.boundary, sessionId: id })),
    checkpoints: normalized.filter(event => event.kind !== 'typing').map(({ id, label, atMs }) => ({ id, label, atMs })) };
  return { chapter, timing, events };
}

export function prepareFinale(directory, nominationFile, controls = []) {
  directory = path.resolve(directory);
  const id = path.basename(directory), trace = readFileSync(path.join(directory, 'trace.json')), raw = JSON.parse(trace);
  const { chapter, timing, events } = normalizeFinale(raw, id);
  const artifacts = validateArtifacts(directory, raw);
  const nominationBytes = readFileSync(nominationFile), nomination = JSON.parse(nominationBytes);
  assert.equal(nomination.session, id);
  assert(Date.parse(nomination.nominatedAt) < Date.parse(raw.metadata.startedAt), 'Nomination must precede recording');
  assert.equal(nomination.sha256[raw.metadata.appJar], raw.metadata.appJarSha256, 'Nominated build differs from captured build');
  privacyCheck(nominationBytes.toString());
  const controlInputs = controls.map(input => {
    const directory = path.resolve(input), controlId = path.basename(directory);
    assert.notEqual(controlId, id, 'Representative recording cannot be its own control');
    safePath(controlId);
    const trace = readFileSync(path.join(directory, 'trace.json')), raw = JSON.parse(trace);
    privacyCheck(trace.toString());
    return { directory, id: controlId, trace, raw, artifacts: validateArtifacts(directory, raw) };
  });
  const manifest = read(path.join(destination, 'manifest.json'));
  const evidence = read(path.join(destination, 'evidence.json'));
  const checksums = read(path.join(destination, 'checksums.json'));
  const write = (name, bytes, immutable = false) => {
    safePath(name); const file = path.join(destination, name);
    if (immutable && existsSync(file)) assert.equal(sha256(readFileSync(file)), sha256(bytes), `Immutable evidence changed: ${name}`);
    mkdirSync(path.dirname(file), { recursive: true }); writeFileSync(file, bytes); checksums.files[name] = sha256(bytes);
  };
  const prefix = `evidence/${id}`;
  write(`${prefix}/trace.json`, trace, true);
  write(`${prefix}/artifact-hashes.json`, readFileSync(path.join(directory, 'artifact-hashes.json')), true);
  write(`${prefix}/nomination.json`, nominationBytes, true);
  for (const name of Object.keys(artifacts)) write(`${prefix}/${name}`, readFileSync(path.join(directory, name)), true);
  for (const control of controlInputs) {
    const prefix = `evidence/${control.id}`;
    write(`${prefix}/trace.json`, control.trace, true);
    write(`${prefix}/artifact-hashes.json`, readFileSync(path.join(control.directory, 'artifact-hashes.json')), true);
    for (const name of Object.keys(control.artifacts)) write(`${prefix}/${name}`, readFileSync(path.join(control.directory, name)), true);
    evidence.controls = evidence.controls.filter(item => item.id !== control.id);
    evidence.controls.push({ id: control.id, trace: `/demo/hero-story/${prefix}/trace.json`, traceSha256: sha256(control.trace), artifacts: `/demo/hero-story/${prefix}/artifact-hashes.json`, completed: control.raw.metadata.completed, failure: control.raw.metadata.failure ?? null, checks: control.raw.metadata.checks });
  }
  const chapterName = `native-console-${id}/spring-boot.json`, chapterBytes = compact(chapter);
  write(chapterName, chapterBytes, true);
  const selected = { id, trace: `/demo/hero-story/${prefix}/trace.json`, traceSha256: sha256(trace), artifacts: `/demo/hero-story/${prefix}/artifact-hashes.json`, nomination: `/demo/hero-story/${prefix}/nomination.json`, nominationSha256: sha256(nominationBytes) };
  const session = { id, chapters: ['spring-boot'], metadata: raw.metadata, selected, timing: { ...timing, map: undefined }, checks: raw.metadata.checks };
  const originalId = manifest.sessions?.[0]?.id ?? manifest.id;
  const originalSession = evidence.sessions?.[0] ?? { id: originalId, chapters: manifest.chapters.slice(0, 4).map(item => item.id), metadata: evidence.metadata, selected: evidence.selected, timing: evidence.timing, checks: evidence.checks };
  manifest.sessions = [{ id: originalId, chapters: originalSession.chapters, trace: originalSession.selected.trace }, { id, chapters: ['spring-boot'], trace: selected.trace }];
  manifest.composition = 'Chapters 1–4 retain the original recording. The native command-line finale is a separately recorded session with its own build and monotonic clock; timings are never combined into a single-session claim.';
  manifest.chapters = manifest.chapters.map(item => item.id === 'spring-boot'
    ? { id: chapter.id, title: chapter.title, url: `/demo/hero-story/${chapterName}`, durationMs: chapter.durationMs, sha256: sha256(chapterBytes), sessionId: id }
    : { ...item, sessionId: originalId });
  manifest.timing = { policy: 'Each session has its own monotonic clock and editorial cut map. All recorded operations remain at 1×.', durationMs: manifest.chapters.reduce((total, item) => total + item.durationMs, 0), sessions: manifest.sessions };
  evidence.sessions = [originalSession, session];
  evidence.composition = manifest.composition;
  evidence.chapters = manifest.chapters;
  evidence.measurements = [...evidence.measurements.filter(item => item.chapter !== 'spring-boot').map(item => ({ ...item, sessionId: originalId })), ...raw.measurements.map(item => ({ ...item, sessionId: id }))];
  evidence.events = [...evidence.events.filter(item => item.chapter !== 'spring-boot').map(item => ({ ...item, sessionId: originalId })), ...events.map(({ state, ...event }) => ({ ...event, sessionId: id, screenshot: `/demo/hero-story/${prefix}/${event.screenshot}`, state: { ...state, sourceId: `source-${state.sourceSha256}`, consoleId: `output-${sha256(state.console)}`, externalId: `output-${sha256('')}`, source: undefined, console: undefined, externalTerminal: undefined } }))];
  for (const event of raw.events) {
    const state = event.state;
    evidence.sources[`source-${state.sourceSha256}`] = { file: state.file, text: state.source, sha256: state.sourceSha256, url: `/demo/hero-story/${prefix}/${raw.sourceRevisions[state.sourceSha256]}` };
    const digest = sha256(state.console); write(`outputs/${digest}.txt`, state.console);
    evidence.outputs[`output-${digest}`] = { text: state.console, sha256: digest, url: `/demo/hero-story/outputs/${digest}.txt` };
  }
  evidence.projectFiles = evidence.projectFiles.filter(file => !file.file.startsWith('spring-hello/'));
  for (const [name, digest] of Object.entries(artifacts).filter(([name]) => name.startsWith('project-files/'))) {
    write(`project-downloads/${digest}.txt`, readFileSync(path.join(directory, name)));
    evidence.projectFiles.push({ file: name.slice('project-files/'.length), sha256: digest, url: `/demo/hero-story/project-downloads/${digest}.txt`, originalUrl: `/demo/hero-story/${prefix}/${name}`, sessionId: id });
  }
  // Original metadata remains intact in sessions[0]; current Spring observations
  // are explicit separate-session fields for the active final chapter.
  evidence.finale = session;
  write('evidence.json', json(evidence)); write('manifest.json', json(manifest));
  writeFileSync(path.join(destination, 'checksums.json'), json(checksums));
  return { id, durationMs: chapter.durationMs, events: events.length, measurements: raw.measurements.length };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert(process.argv[2] && process.argv[3], 'Usage: node prepare-console-finale.mjs <capture directory> <pre-capture nomination.json>');
  console.log(JSON.stringify(prepareFinale(process.argv[2], process.argv[3], process.argv.slice(4))));
}
