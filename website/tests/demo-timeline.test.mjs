import test from 'node:test';
import assert from 'node:assert/strict';
import { CHAPTER_TITLES, seekChapter, validateManifest, validateChapter } from '../src/scripts/demo-timeline.ts';

// Synthetic data exercises boundary behavior only. It is never exported to the site.
const event = (id, atMs, sourceId, outputId) => ({ id, atMs, kind: id, label: id, sourceId, outputId, filename: 'Synthetic.java', cursorOffset: 0, selectionStart: 0, selectionEnd: 0, topLine: 1, status: id, external: false });
const synthetic = {
  id: 'synthetic', title: 'Synthetic boundary fixture', durationMs: 1500,
  sources: { empty: { file: 'Synthetic.java', text: '', sha256: '' }, source: { file: 'Synthetic.java', text: 'class Synthetic {}', sha256: '' } },
  outputs: { empty: '', done: 'Synthetic control result' },
  events: [event('empty', 0, 'empty', 'empty'), event('source', 100, 'source', 'empty'), event('dispatch', 200, 'source', 'empty'), event('result', 1200, 'source', 'done')],
  operations: [{ id: 'control', label: 'Synthetic operation', startMs: 200, endMs: 1200, durationMs: 1000, boundary: 'Unit test control only' }],
  checkpoints: [{ id: 'initial', label: 'Initial', atMs: 0 }, { id: 'final', label: 'Final', atMs: 1500 }],
};

test('the hero has exactly the five requested chapters', () => {
  assert.deepEqual(CHAPTER_TITLES, ['Hello World', 'Tests', 'Refactoring', 'AutoRun / AutoTest', 'Spring Boot + curl']);
});
test('results never appear before their recorded boundary, including sub-millisecond offsets', () => {
  assert.equal(seekChapter(synthetic, 1199.999).event.outputId, 'empty');
  assert.equal(seekChapter(synthetic, 1200).event.outputId, 'done');
  assert.equal(seekChapter(synthetic, 1200).completed, false);
  assert.equal(seekChapter(synthetic, 1500).completed, true);
});
test('seeking is independent of prior chapters and playback direction', () => {
  const preserved = structuredClone(synthetic);
  for (const at of [1500, 50, 999, 200, 1200, 0, 100]) {
    const expected = synthetic.events.filter((event) => event.atMs <= at).at(-1);
    assert.deepEqual(seekChapter(synthetic, at).event, expected);
  }
  assert.deepEqual(synthetic, preserved);
});
test('stepwise playback and direct skip restore the same exact source/output checkpoint', () => {
  let state;
  for (let at = 0; at < synthetic.durationMs; at += 16.67) state = seekChapter(synthetic, at);
  assert.equal(state.event.id, 'result');
  assert.deepEqual(seekChapter(synthetic, 1600), seekChapter(synthetic, 1500));
  assert.equal(seekChapter(synthetic, 1499.999).completed, false);
});
test('invalid and negative seek times restore the initial checkpoint safely', () => {
  for (const at of [-1, NaN, Infinity, -Infinity]) assert.deepEqual(seekChapter(synthetic, at), seekChapter(synthetic, 0));
});
test('same-timestamp captured observations have deterministic last-event precedence', () => {
  const chapter = { ...synthetic, events: [...synthetic.events, event('later-observation', 1200, 'source', 'done')] };
  assert.equal(seekChapter(chapter, 1200).event.id, 'later-observation');
});
test('runtime rejects unverified sessions and incompatible chapter manifests', () => {
  const manifest = { schemaVersion: 1, verified: true, id: 'synthetic', keymap: 'Synthetic', menus: [], detailsUrl: '/demo-session/', chapters: CHAPTER_TITLES.map((title, index) => ({ title, id: String(index), durationMs: 1, url: `/demo/hero-story/${index}.json`, sha256: '' })) };
  assert.equal(validateManifest(manifest), manifest);
  assert.throws(() => validateManifest({ ...manifest, verified: false }));
  assert.throws(() => validateManifest({ ...manifest, chapters: manifest.chapters.slice(1) }));
  assert.throws(() => validateManifest({ ...manifest, chapters: manifest.chapters.toReversed() }));
  assert.throws(() => validateManifest({ ...manifest, chapters: [{ ...manifest.chapters[0], url: 'http://localhost:8080/hello' }, ...manifest.chapters.slice(1)] }));
});
test('runtime rejects missing source/output, unordered events, absent initial state, and shortened system time', () => {
  assert.equal(validateChapter(synthetic, synthetic.id), synthetic);
  assert.throws(() => validateChapter(synthetic, 'different'));
  assert.throws(() => validateChapter({ ...synthetic, events: synthetic.events.slice(1) }, synthetic.id));
  assert.throws(() => validateChapter({ ...synthetic, events: [synthetic.events[0], synthetic.events[2], synthetic.events[1]] }, synthetic.id));
  assert.throws(() => validateChapter({ ...synthetic, sources: {} }, synthetic.id));
  assert.throws(() => validateChapter({ ...synthetic, outputs: {} }, synthetic.id));
  assert.throws(() => validateChapter({ ...synthetic, operations: [{ ...synthetic.operations[0], endMs: 800 }] }, synthetic.id));
});
