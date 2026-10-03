import test from 'node:test';
import assert from 'node:assert/strict';
import {
  translateEnvelope,
  translateUnparsed,
  UnknownReasons,
} from '../translate.mjs';

// Synthetic fixtures shaped like verified Stage-9 envelopes. No raw
// capture data. Deterministic: fixed ids, fixed timestamps.
const CTX = { eventId: 'xykell-obs-test-1', observedAtMs: 1720000000000, wireLength: 157 };

function chatEnvelope(message = 'hello', sender = 'TestPlayer') {
  return {
    header: { eventName: 'PlayerMessage', messagePurpose: 'event', version: 17104896 },
    body: { message, receiver: '', sender, type: 'chat' },
  };
}

function travelEnvelope(over = {}) {
  return {
    header: { eventName: 'PlayerTravelled', messagePurpose: 'event', version: 17104896 },
    body: {
      isUnderwater: false,
      metersTravelled: 1.054,
      newBiome: 0,
      player: {
        color: 'ffededed',
        dimension: 0,
        id: -4294967295,
        name: 'TestPlayer',
        position: { x: -485.5, y: 64.62, z: -370.83 },
        type: 'minecraft:player',
        variant: 0,
        yRot: -7.31,
      },
      travelMethod: 0,
      ...over,
    },
  };
}

test('PlayerMessage: valid envelope, exact preservation, deterministic id', () => {
  const r = translateEnvelope(chatEnvelope('  Hello, World!  '), CTX);
  assert.equal(r.kind, 'PlayerMessage');
  assert.equal(r.observation.type, 'PlayerMessageObservation');
  assert.equal(r.observation.eventId, 'xykell-obs-test-1');
  assert.equal(r.observation.observedAtMs, 1720000000000);
  assert.equal(r.observation.sender, 'TestPlayer');
  // Exact preservation: no case/whitespace/punctuation normalization.
  assert.equal(r.observation.message, '  Hello, World!  ');
  assert.ok(!('receiver' in r.observation)); // unproven -> absent
});

test('PlayerMessage: determinism (same input twice -> equivalent output)', () => {
  const a = translateEnvelope(chatEnvelope(), CTX);
  const b = translateEnvelope(chatEnvelope(), CTX);
  assert.deepEqual(a, b);
});

test('PlayerMessage: frozen output cannot be mutated', () => {
  const r = translateEnvelope(chatEnvelope(), CTX);
  assert.ok(Object.isFrozen(r.observation));
  assert.throws(() => {
    r.observation.message = 'forged';
  }, TypeError);
});

test('PlayerTravelled: valid envelope preserves numerics raw', () => {
  const r = translateEnvelope(travelEnvelope(), CTX);
  assert.equal(r.kind, 'PlayerTravelled');
  assert.equal(r.observation.type, 'PlayerTravelObservation');
  assert.equal(r.observation.eventId, 'xykell-obs-test-1');
  assert.equal(r.observation.observedAtMs, 1720000000000);
  assert.deepEqual(r.observation.position, { x: -485.5, y: 64.62, z: -370.83 });
  assert.equal(r.observation.yawDegrees, -7.31);
  assert.equal(r.observation.metersTravelled, 1.054);
  assert.equal(r.observation.travelMethod, 0); // raw, unmapped
  assert.ok(Object.isFrozen(r.observation.position));
  const t2 = translateEnvelope(travelEnvelope({ travelMethod: 2 }), CTX);
  assert.equal(t2.observation.travelMethod, 2); // second observed value, still raw
});

test('Unknown: unrecognized event name is never fabricated', () => {
  const env = {
    header: { eventName: 'PlayerAte', messagePurpose: 'event', version: 1 },
    body: { player: 'x' },
  };
  const r = translateEnvelope(env, CTX);
  assert.equal(r.kind, 'Unknown');
  assert.equal(r.observation.type, 'UnknownObservation');
  assert.equal(r.observation.reason, UnknownReasons.UNRECOGNIZED_EVENT);
  assert.equal(r.observation.wireLength, 157);
});

test('Unknown: well-formed non-event envelope is Unknown, not Invalid', () => {
  const env = { header: { messagePurpose: 'commandResponse', requestId: 'hs-1' }, body: {} };
  const r = translateEnvelope(env, CTX);
  assert.equal(r.kind, 'Unknown');
  assert.equal(r.observation.reason, UnknownReasons.NON_EVENT_ENVELOPE);
});

test('Unknown: unparsed input via translateUnparsed carries metadata only', () => {
  const r = translateUnparsed({ ...CTX, wireLength: 404 });
  assert.equal(r.kind, 'Unknown');
  assert.equal(r.observation.wireLength, 404);
  assert.equal(r.observation.reason, UnknownReasons.UNPARSED_INPUT);
  assert.ok(!('ciphertext' in r.observation || 'plaintext' in r.observation));
});

test('Invalid: malformed JSON string', () => {
  assert.deepEqual(translateEnvelope('{"a":', CTX), { kind: 'Invalid' });
});

test('Invalid: missing required fields', () => {
  assert.deepEqual(translateEnvelope(chatEnvelope('', 's'), CTX), { kind: 'Invalid' }); // empty message
  assert.deepEqual(translateEnvelope(chatEnvelope('m', ''), CTX), { kind: 'Invalid' }); // empty sender
  assert.deepEqual(translateEnvelope({ header: {}, body: {} }, CTX), { kind: 'Invalid' });
  assert.deepEqual(translateEnvelope(null, CTX), { kind: 'Invalid' });
  assert.deepEqual(translateEnvelope([1, 2], CTX), { kind: 'Invalid' });
  assert.deepEqual(translateEnvelope(42, CTX), { kind: 'Invalid' });
});

test('Invalid: wrong field types', () => {
  const e1 = chatEnvelope();
  e1.body.message = 42;
  assert.deepEqual(translateEnvelope(e1, CTX), { kind: 'Invalid' });
  const e2 = travelEnvelope();
  e2.body.player.position = { x: 'far', y: 0, z: 0 };
  assert.deepEqual(translateEnvelope(e2, CTX), { kind: 'Invalid' });
  const e3 = travelEnvelope();
  delete e3.body.player;
  assert.deepEqual(translateEnvelope(e3, CTX), { kind: 'Invalid' });
});

test('Invalid: invalid numeric values', () => {
  for (const bad of [NaN, Infinity, -Infinity, -1]) {
    const e = travelEnvelope();
    e.body.metersTravelled = bad;
    assert.deepEqual(translateEnvelope(e, CTX), { kind: 'Invalid' }, `meters=${bad}`);
  }
  const e = travelEnvelope();
  e.body.player.position.y = NaN;
  assert.deepEqual(translateEnvelope(e, CTX), { kind: 'Invalid' });
  const e2 = travelEnvelope();
  e2.body.player.yRot = Infinity;
  assert.deepEqual(translateEnvelope(e2, CTX), { kind: 'Invalid' });
});

test('Invalid: malformed event envelope shapes', () => {
  assert.deepEqual(translateEnvelope({ body: {} }, CTX), { kind: 'Invalid' }); // no header
  assert.deepEqual(translateEnvelope({ header: null, body: {} }, CTX), { kind: 'Invalid' });
  assert.deepEqual(translateEnvelope(chatEnvelope(), { eventId: '', observedAtMs: 1 }),
    { kind: 'Invalid' }); // bad ctx id
  assert.deepEqual(translateEnvelope(chatEnvelope(), null), { kind: 'Invalid' }); // bad ctx
});

test('Invalid: JSON string input parses then translates', () => {
  const r = translateEnvelope(JSON.stringify(chatEnvelope('hi')), CTX);
  assert.equal(r.kind, 'PlayerMessage');
  assert.equal(r.observation.message, 'hi');
});
