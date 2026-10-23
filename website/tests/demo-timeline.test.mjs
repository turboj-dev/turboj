import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEMO_SOURCE,
  DEMO_OUTPUT,
  DEMO_EVENTS,
  DEMO_DURATION_MS,
  createDemoState,
  advanceDemoState,
} from '../src/scripts/demo-timeline.ts';

test('the demo has exactly the reviewed source and final output', () => {
  assert.equal(DEMO_SOURCE, 'public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println("Hello TurboJ!");\n    }\n}');
  assert.equal(DEMO_OUTPUT, 'FAST RUN · HelloWorld\nHello TurboJ!\n\n[exit 0] Completed');
  assert.equal(DEMO_SOURCE.endsWith('\n'), false);
});

test('timeline events are ordered and characters arrive at deterministic intervals', () => {
  assert.ok(DEMO_EVENTS.every((event, index) => index === 0 || event.at >= DEMO_EVENTS[index - 1].at));
  const characters = DEMO_EVENTS.filter((event) => event.type === 'character');
  assert.equal(characters.length, DEMO_SOURCE.length);
  assert.equal(characters[0].at, 800);
  for (let index = 1; index < characters.length; index += 1) {
    assert.equal(characters[index].at - characters[index - 1].at, DEMO_SOURCE[index - 1] === '\n' ? 115 : 25);
  }
  assert.ok(characters.at(-1).at < 5500);
});

test('filename, typing, compile, run, and final hold occur at the specified boundaries', () => {
  const at = (elapsed) => advanceDemoState(createDemoState(), elapsed);
  assert.equal(at(350).filename, 'Untitled.java');
  assert.equal(at(499).sourceLength, 0);
  assert.equal(at(500).filename, 'HelloWorld.java');
  assert.equal(at(799).sourceLength, 0);
  assert.equal(at(800).sourceLength, 1);
  assert.equal(at(5500).phase, 'compiling');
  assert.equal(at(5500).status, 'COMPILING...');
  assert.equal(at(5500).shortcut, 'F9 MAKE');
  assert.equal(at(6200).status, 'BUILD OK · 0 errors');
  assert.equal(at(6200).phase, 'compiling');
  assert.equal(at(6700).phase, 'running');
  assert.equal(at(6700).shortcut, 'CTRL+F9 RUN');
  assert.equal(at(7199).output, 'FAST RUN · HelloWorld');
  assert.equal(at(7200).phase, 'running');
  assert.equal(at(7200).output, 'FAST RUN · HelloWorld\nHello TurboJ!\n');
  assert.equal(at(7999).phase, 'running');
  assert.ok(at(7999).output.includes('Hello TurboJ!'));
  assert.equal(at(7999).output.includes('[exit 0]'), false);
  assert.equal(at(8000).phase, 'completed');
  assert.equal(at(8000).output, DEMO_OUTPUT);
  assert.deepEqual(at(9000), at(8000));
});

test('stepwise playback and skip produce identical final state without mutation', () => {
  const initial = createDemoState();
  const preserved = { ...initial };
  let state = initial;
  for (let elapsed = 0; elapsed <= DEMO_DURATION_MS; elapsed += 16) {
    state = advanceDemoState(state, elapsed);
  }
  state = advanceDemoState(state, DEMO_DURATION_MS);
  assert.deepEqual(state, advanceDemoState(initial, DEMO_DURATION_MS));
  assert.deepEqual(initial, preserved);
  assert.equal(state.sourceLength, DEMO_SOURCE.length);
  assert.equal(state.cursorLine, 4);
  assert.equal(state.cursorColumn, 1);
  assert.equal(state.progress, 1);
});

test('elapsed time never runs backwards or changes for invalid readings', () => {
  const state = advanceDemoState(createDemoState(), 2000);
  assert.deepEqual(advanceDemoState(state, 1000), state);
  assert.deepEqual(advanceDemoState(state, Number.NaN), state);
  assert.deepEqual(advanceDemoState(state, Number.POSITIVE_INFINITY), state);
  assert.equal(advanceDemoState(createDemoState(), -10).elapsed, 0);
});
