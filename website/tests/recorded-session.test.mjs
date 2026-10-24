// Supporting native-session evidence; independent of the illustrative homepage demo.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { timingMap } from '../scripts/prepare-session.mjs';
const session = JSON.parse(await readFile(new URL('../src/data/recorded-session.json', import.meta.url)));

test('supporting evidence preserves one complete session and all measured intervals', () => {
  assert.equal(session.metadata.completed, true);
  assert.deepEqual(session.chapters.map(({ id }) => id), ['hello-world', 'tests', 'refactoring', 'automation', 'spring-boot']);
  assert.match(session.metadata.commit, /^[a-f0-9]{40}$/);
  assert.match(session.metadata.appJarSha256, /^[a-f0-9]{64}$/);
  assert.ok(session.events.every((event, index) => index === 0 || event.atMs >= session.events[index - 1].atMs));
  for (const measurement of session.measurements) {
    assert.equal(measurement.durationNs, measurement.endNs - measurement.startNs);
    assert.ok(Math.abs(measurement.endMs - measurement.startMs - measurement.durationNs / 1e6) < 1e-7, measurement.id);
  }
});

test('supporting trace, source, output and screenshot bytes retain their original provenance', async () => {
  const directory = new URL(`../public/demo/${session.metadata.id}/`, import.meta.url);
  const rawBytes = await readFile(new URL('trace.json', directory));
  const trace = JSON.parse(rawBytes);
  const manifest = JSON.parse(await readFile(new URL('manifest.json', directory)));
  const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
  assert.equal(sha(rawBytes), manifest.traceSha256);
  assert.equal(trace.metadata.completed, true);
  assert.equal(trace.events.length, session.events.length);
  for (const [index, event] of session.events.entries()) {
    const original = trace.events[index];
    assert.equal(event.id, original.id);
    for (const property of ['source', 'console', 'externalTerminal']) assert.equal(event.state[property], original.state[property]);
    assert.equal(event.atNs, original.atNs);
    const screenshot = await readFile(new URL(original.screenshot, directory));
    assert.equal(sha(screenshot), manifest.files[original.screenshot]);
    assert.doesNotMatch(JSON.stringify(event.state), /\/Users\//);
  }
  assert.deepEqual(JSON.parse(await readFile(new URL('../public/demo/session.json', import.meta.url))), session);
});

test('evidence retains actual successes, failure and recovery rather than editing outputs', () => {
  const event = (id) => session.events.find((item) => item.id === id);
  assert.match(event('hello-run-result').state.console, /Hello world! TurboJ/);
  assert.match(event('tests-before-refactor-result').state.console, /2 passed/);
  assert.match(event('tests-before-refactor-result').state.console, /package org.junit.jupiter.api does not exist/);
  assert.match(event('tests-after-refactor-result').state.console, /2 passed/);
  assert.match(event('autotest-fails-result').state.console, /FAILED/);
  assert.match(event('autotest-passes-result').state.console, /2 passed/);
  assert.match(event('autorun-saved-result').state.console, /Updated price: 800 cents/);
  assert.ok(session.events.some((item) => /Hello from TurboJ!/.test(item.state.externalTerminal ?? '')));
});

test('optional human-pause cuts cannot shorten measured intervals', () => {
  const events = [0, 8, 9, 13, 14, 25].map((second) => ({ atNs: second * 1e9 }));
  const intervals = [{ startNs: 8e9, endNs: 13e9 }, { startNs: 9e9, endNs: 14e9 }];
  const mapping = timingMap(events, intervals);
  assert.equal(mapping.at(13e9) - mapping.at(8e9), 5000);
  assert.equal(mapping.at(14e9) - mapping.at(9e9), 5000);
  assert.deepEqual(mapping.cuts.map(({ originalMs }) => originalMs), [8000, 11000]);
});
