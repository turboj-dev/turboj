import assert from 'node:assert/strict';
import { test } from 'node:test';
import { orderForSpin, spinPlan, uniformIndex } from '../src/scripts/theme-spin.ts';

test('ASCII spin uses real catalog indices, a uniform explicit target and a final deadline of 3–5 seconds', () => {
  for (const count of [1, 3, 103, 1000]) for (const target of [0, count - 1]) for (const duration of [3000, 3749, 3750, 4499, 5000]) {
    const plan = spinPlan(count, target, duration);
    assert.equal(plan.indices.at(-1), target);
    if (count > 1) assert.equal(plan.deadlines.at(-1), duration);
    assert.ok(plan.indices.every((value) => value >= 0 && value < count));
    assert.equal(plan.indices.length, plan.deadlines.length + 1);
    const intervals = plan.deadlines.map((value, index) => value - (plan.deadlines[index - 1] ?? 0));
    assert.ok(intervals.every((value) => value > 0));
    assert.ok(intervals.every((value, index) => index === 0 || value >= intervals[index - 1]), 'Spin must decelerate');
    assert.equal(new Set(plan.indices).size, plan.indices.length, 'Small filtered pools must not repeat');
    if (count >= 103) {
      assert.equal(plan.deadlines.length, Math.round(duration / 125));
      assert.ok(plan.deadlines[0] > 35 && plan.deadlines[0] < 50, 'Initial velocity should be about24 rows/second');
    }
  }
});

test('drum ordering groups similar background luminance without changing the catalog order', () => {
  const entries = [{ id: 'white', previewBackground: '#FFFFFF' }, { id: 'black', previewBackground: '#000000' },
    { id: 'gray-b', previewBackground: '#555555' }, { id: 'gray-a', previewBackground: '#555555' }];
  assert.deepEqual(orderForSpin(entries).map(({ id }) => id), ['black', 'gray-a', 'gray-b', 'white']);
  assert.equal(entries[0].id, 'white');
});

test('random selection validates bounds and stays inside every catalog size', () => {
  for (const invalid of [0, -1, 0.5, NaN, 2 ** 32 + 1]) assert.throws(() => uniformIndex(invalid), RangeError);
  for (const count of [1, 3, 103, 1000]) for (let i = 0; i < 50; i++) {
    const index = uniformIndex(count);
    assert.ok(Number.isInteger(index) && index >= 0 && index < count);
  }
  assert.throws(() => spinPlan(103, 103, 3000), RangeError);
  assert.throws(() => spinPlan(103, 0, 2999), RangeError);
});
