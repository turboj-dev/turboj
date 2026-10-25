import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CHAPTERS, privacyCheck, safePath, sha256, timingMap, validateArtifacts, validateCapture, normalizeCapture } from '../scripts/prepare-hero-story.mjs';

const website = fileURLToPath(new URL('../', import.meta.url));
const publicRoot = path.join(website, 'public');
const readPublic = (url) => readFileSync(path.join(publicRoot, url));
const readJson = (url) => JSON.parse(readPublic(url));
const events = (times) => times.map((atNs, index) => ({ id: `observed-${index}`, atNs }));
const operation = (startNs, endNs) => ({ id: 'native-op', startNs, endNs, durationNs: endNs - startNs });

test('editorial mapping preserves unknown time and every native operation at 1×', () => {
  const observations = events([0, 100e6, 900e6, 1200e6, 2000e6, 2500e6]);
  const op = operation(900e6, 1200e6);
  const timing = timingMap(observations, [op], [{ id: 'read', kind: 'reading', reason: 'Explicit reading pause', startNs: 100e6, endNs: 900e6 }]);
  assert.equal(timing.map(0), 0);
  assert.equal(timing.map(100e6), 100);
  assert.equal(timing.map(1200e6) - timing.map(900e6), 300);
  assert.equal(timing.map(2500e6) - timing.map(1200e6), 1300, 'Unknown gaps cannot be shortened');
  assert.equal(timing.durationMs, 2350);
  assert.equal(timing.cuts[0].removedMs, 150);
});

test('unclassified, overlapping, invalid or operation-covering cuts fail closed', () => {
  const base = { id: 'cut', kind: 'reading', reason: 'Explicit hold', startNs: 100e6, endNs: 900e6 };
  const observed = events([0, 2000e6]);
  assert.throws(() => timingMap(observed, [], [{ ...base, kind: 'unknown' }]), /Unclassified/);
  assert.throws(() => timingMap(observed, [], [base, { ...base, id: 'duplicate' }]), /Overlapping editorial/);
  assert.throws(() => timingMap(observed, [operation(800e6, 1000e6)], [base]), /overlaps operation/);
  assert.throws(() => timingMap(observed, [{ ...operation(100e6, 200e6), durationNs: 10 }]), /Inconsistent/);
  assert.throws(() => timingMap(observed, [operation(100e6, 1000e6), operation(900e6, 1200e6)]), /Overlapping operations/);
  assert.throws(() => timingMap(events([10, 5]), []), /not monotonic/);
});

test('public evidence rejects escaping paths, personal homes and credentials', () => {
  for (const value of ['../secret', '/private/file', 'a/../b', 'a\\b', 'a//b']) assert.throws(() => safePath(value), /Unsafe/);
  assert.equal(safePath('sources/abc.java'), 'sources/abc.java');
  assert.throws(() => privacyCheck('source at /Users/someone/projects'), /Private/);
  assert.throws(() => privacyCheck('home="/home/someone/file"'), /Private/);
  assert.throws(() => privacyCheck('ghp_abcdefghijklmnopqrstuvwx'), /Credential/);
  privacyCheck('/private/tmp/turboj-demo/session/home/.m2/settings.xml');
});

test('published hero is a complete newly captured five-chapter story with verified native effects', () => {
  const manifest = readJson('/demo/hero-story/manifest.json');
  assert.equal(manifest.verified, true);
  assert.deepEqual(manifest.chapters.map((c) => c.id), CHAPTERS.map(([id]) => id));
  const raw = readJson(manifest.rawTrace);
  const checked = validateCapture(raw);
  assert.equal(checked.measurements.length, 18);
  assert(raw.metadata.selectedRepresentativeRun);
  const regenerated = normalizeCapture(raw, manifest.id);
  assert.deepEqual(regenerated.manifest, manifest, 'Export must be reproducible from the one raw trace');
  for (const chapter of manifest.chapters) {
    const bytes = readPublic(chapter.url), data = JSON.parse(bytes);
    assert.equal(sha256(bytes), chapter.sha256);
    assert.deepEqual(data, regenerated.chapters.find((c) => c.id === data.id));
    assert.equal(data.events[0].atMs, 0);
    for (const event of data.events) {
      assert.equal(sha256(data.sources[event.sourceId].text), data.sources[event.sourceId].sha256);
      assert.equal(event.outputId, `output-${sha256(data.outputs[event.outputId])}`);
      assert(event.topLine >= 1);
      const actual = raw.events.find((e) => e.id === event.id);
      assert.equal(data.sources[event.sourceId].text, actual.state.source);
      assert.equal(data.outputs[event.outputId], event.external ? actual.state.externalTerminal : actual.state.console);
      if (event.dialogId) {
        assert.deepEqual(data.dialogs[event.dialogId], actual.state.dialogs);
        assert.equal(event.dialogId, `dialog-${sha256(JSON.stringify(actual.state.dialogs))}`);
      }
      if (event.kind === 'typing') assert(actual.id.includes('-typing-line-'));
    }
    for (const op of data.operations) assert(Math.abs(op.endMs - op.startMs - op.durationMs) < 1e-6);
  }
});

test('every exported source, log, screenshot, raw trace and control artifact retains its SHA-256', () => {
  const checksums = readJson('/demo/hero-story/checksums.json');
  assert.equal(checksums.algorithm, 'SHA-256');
  for (const [name, expected] of Object.entries(checksums.files)) {
    const bytes = readPublic(`/demo/hero-story/${safePath(name)}`);
    assert.equal(sha256(bytes), expected, name);
    if (!name.endsWith('.png')) privacyCheck(bytes.toString('utf8'));
  }
  const evidence = readJson('/demo/hero-story/evidence.json');
  assert.equal(sha256(readPublic(evidence.selected.trace)), evidence.selected.traceSha256);
  const raw = readJson(evidence.selected.trace);
  validateArtifacts(path.dirname(path.join(publicRoot, evidence.selected.trace)), raw);
  for (const [id, source] of Object.entries(evidence.sources)) {
    assert.equal(id, `source-${source.sha256}`);
    assert.equal(sha256(readPublic(source.url)), source.sha256);
  }
  for (const output of Object.values(evidence.outputs)) assert.equal(sha256(readPublic(output.url)), output.sha256);
  for (const project of evidence.projectFiles) {
    assert.equal(project.url, `/demo/hero-story/project-downloads/${project.sha256}.txt`);
    assert.equal(sha256(readPublic(project.url)), project.sha256);
    assert.deepEqual(readPublic(project.url), readPublic(project.originalUrl));
  }
  for (const control of evidence.controls) assert.equal(sha256(readPublic(control.trace)), control.traceSha256);
  for (const check of ['stdoutVerified', 'stderrVerified', 'diagnosticsVerified', 'settingsRestored', 'ownedServerStopped']) assert.equal(evidence.checks[check], true);
});

test('missing actual failures, changed source hashes, interleaved chapters and fake intervals are rejected', () => {
  const manifest = readJson('/demo/hero-story/manifest.json');
  const original = readJson(manifest.rawTrace);
  const mutate = (edit) => { const raw = structuredClone(original); edit(raw); return raw; };
  assert.throws(() => validateCapture(mutate((raw) => raw.events.find((e) => e.id === 'autotest-fails-result').state.testReport.failed = 0)), /JUnit result mismatch/);
  assert.throws(() => validateCapture(mutate((raw) => raw.events[0].state.source += 'invented')), /Source hash mismatch/);
  assert.throws(() => validateCapture(mutate((raw) => raw.events[1].chapter = 'spring-boot')), /Five complete chapters/);
  assert.throws(() => validateCapture(mutate((raw) => raw.metadata.curl.stdout = 'invented')), /External curl/);
  assert.throws(() => validateCapture(mutate((raw) => raw.metadata.springStopped = false)), /not stopped/);
  assert.throws(() => normalizeCapture(mutate((raw) => raw.measurements[0].durationNs = 1), 'test'), /Inconsistent/);
});

test('details are static accessible text with on-demand evidence and no browser localhost requests', () => {
  const page = readFileSync(path.join(website, 'src/pages/demo-session.astro'), 'utf8');
  assert(page.includes('External curl — recorded command and output'));
  assert(page.includes('No request is sent from your browser'));
  assert(page.includes('id="sources"') && page.includes('id="logs"') && page.includes('id="cuts"'));
  assert(!page.includes('<img'), 'Original screenshot archive must load only when explicitly requested');
  assert(!page.includes('set:html') && !page.includes('innerHTML'), 'Captured strings must render as text');
  assert(!/fetch\([^)]*(?:localhost|127\.0\.0\.1)/.test(page));
});
