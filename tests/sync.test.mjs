import test from 'node:test';
import assert from 'node:assert/strict';
import { positionAt, syncStep } from '../src/handoff/sync.mjs';

const sample = (position, extra = {}) => ({ at: 1000, position, playing: true, rate: 1, ...extra });

test('positions advance only while playing and at the reported rate', () => {
  assert.equal(positionAt(sample(10), 3000), 12);
  assert.equal(positionAt(sample(10, { rate: 0.9 }), 3000), 11.8);
  assert.equal(positionAt(sample(10, { playing: false }), 3000), 10);
});

test('a follower behind or ahead is nudged by speed, never past 8%', () => {
  const behind = syncStep(sample(100), sample(99.6), 1000);
  assert.equal(behind.action, 'rate');
  assert.equal(behind.rate, 1.08);
  const ahead = syncStep(sample(100), sample(100.2), 1000);
  assert.equal(ahead.action, 'rate');
  assert.equal(ahead.rate, 0.95);
});

test('in step returns to normal speed, then holds', () => {
  assert.deepEqual(syncStep(sample(100), sample(100.05), 1000, { currentRate: 0.95 }).action, 'rate');
  assert.equal(syncStep(sample(100), sample(100.05), 1000, { currentRate: 0.95 }).rate, 1);
  assert.equal(syncStep(sample(100), sample(100.05), 1000).action, 'hold');
});

test('a large gap seeks ahead of the leader by the expected seek latency', () => {
  const step = syncStep(sample(100), sample(90), 1000, { seekLatencySeconds: 0.5 });
  assert.equal(step.action, 'seek');
  assert.equal(step.position, 100.5);
});

test('play state follows the leader, and paused screens line up exactly', () => {
  assert.equal(syncStep(sample(100, { playing: false }), sample(100), 1000).action, 'pause');
  assert.equal(syncStep(sample(100), sample(100, { playing: false }), 1000).action, 'play');
  const paused = syncStep(sample(100, { playing: false }), sample(98, { playing: false }), 1000);
  assert.deepEqual([paused.action, paused.position], ['seek', 100]);
  assert.equal(syncStep(sample(100, { playing: false }), sample(100.3, { playing: false }), 1000).action, 'hold');
});
