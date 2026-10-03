import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  SUBSCRIBED_EVENTS,
  isSubscribeOnlyEnabled,
  buildSubscribeFrame,
  subscribeFrames,
} from '../subscribe.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const LAB = join(HERE, '..');

test('allowlist is exactly the two read-side events', () => {
  assert.deepEqual([...SUBSCRIBED_EVENTS], ['PlayerMessage', 'PlayerTravelled']);
});

test('subscribe frame matches documented envelope', () => {
  const frame = JSON.parse(buildSubscribeFrame('PlayerMessage', 'req-1'));
  assert.deepEqual(frame, {
    header: {
      version: 1,
      requestId: 'req-1',
      messageType: 'commandRequest',
      messagePurpose: 'subscribe',
    },
    body: { eventName: 'PlayerMessage' },
  });
});

test('non-allowlisted events are refused', () => {
  assert.throws(() => buildSubscribeFrame('PlayerTeleport'), /non-allowlisted/);
  assert.throws(() => buildSubscribeFrame('commandRequest'), /non-allowlisted/);
});

test('subscribeFrames emits one frame per allowlisted event', () => {
  const frames = subscribeFrames((i) => `req-${i}`).map((f) => JSON.parse(f));
  assert.equal(frames.length, 2);
  assert.deepEqual(
    frames.map((f) => f.body.eventName),
    ['PlayerMessage', 'PlayerTravelled'],
  );
  assert.deepEqual(
    frames.map((f) => f.header.messagePurpose),
    ['subscribe', 'subscribe'],
  );
});

test('mode defaults to observation-only (off unless SUBSCRIBE_ONLY=1)', () => {
  assert.equal(isSubscribeOnlyEnabled({}), false);
  assert.equal(isSubscribeOnlyEnabled({ SUBSCRIBE_ONLY: '1' }), true);
  assert.equal(isSubscribeOnlyEnabled({ SUBSCRIBE_ONLY: '0' }), false);
});

test('no Exec/command builder exists anywhere in the lab', () => {
  // Read-side-only boundary: the lab must not gain a write-side API.
  // commandLine is the documented commandRequest payload field; its only
  // acceptable occurrence would be inside a sender, which must not exist.
  const sources = ['server.mjs', 'subscribe.mjs', 'protocol.mjs', 'logger.mjs'].map((f) =>
    readFileSync(join(LAB, f), 'utf8'),
  );
  for (let src of sources) {
    src = src
      .split('\n')
      .map((line) => line.split('//', 1)[0])
      .join('\n'); // code only: comments state the prohibition itself
    assert.doesNotMatch(src, /commandLine/);
    assert.doesNotMatch(src, /\bExec\b/);
    // NOTE: ws.send(frame) for subscribe frames is intended (tested above);
    // the ban targets command/Exec construction, not the subscribe path.
  }
});
