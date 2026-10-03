import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CaptureProducer, SOURCE_KIND } from '../capture-producer.mjs';

// End-to-end over REAL Stage-9P captures (read-only; files unmodified).
// Captures are git-ignored lab evidence; if absent the suite fails loudly
// instead of fabricating replacements (Stage-14 §2 rule).
const HERE = dirname(fileURLToPath(import.meta.url));
const LAB = join(HERE, '..');
const CAPTURE_A = 'capture-2026-10-03T08-35-07-457Z.jsonl';
const CAPTURE_B = 'capture-2026-10-03T08-36-29-002Z.jsonl';

function loadCapture(name) {
  const text = readFileSync(join(LAB, 'captures', name), 'utf8');
  assert.ok(text.trim().length > 0, `real capture missing/empty: ${name}`);
  return text.trim().split('\n').map((line) => JSON.parse(line));
}

// First post-establishment index: anything after the last non-inbound
// (handshake/subscribe/outbound) record. U0 + handshake stay transport.
function firstEventIndex(records) {
  let last = -1;
  records.forEach((r, i) => {
    if (r.direction !== 'minecraft->xykell-lab') last = i;
  });
  return last + 1;
}

function drainAll(producer) {
  const out = [];
  for (;;) {
    const r = producer.pollNext();
    if (r.outcome === 'NoObservation') return out;
    assert.equal(r.outcome, 'Observation');
    assert.ok(r.observation);
    out.push(r);
  }
}

function summarize(results) {
  const counts = { PlayerMessage: 0, PlayerTravelled: 0, Unknown: 0 };
  for (const r of results) counts[r.resultKind] += 1;
  return counts;
}

test('producer identifies as capture/test source, never live', () => {
  const p = new CaptureProducer([], 0);
  assert.equal(p.kind(), 'capture-test-source');
  assert.equal(SOURCE_KIND, 'capture-test-source');
  assert.deepEqual(p.pollNext(), { outcome: 'NoObservation' });
});

for (const [name, expectMsg, expectTravel, expectText] of [
  [CAPTURE_A, 1, 24, 'hello'],
  [CAPTURE_B, 1, 26, 'working?'],
]) {
  test(`end-to-end ${name}: counts + snapshot + order`, () => {
    const records = loadCapture(name);
    const producer = new CaptureProducer(records, firstEventIndex(records));
    assert.equal(producer.kind(), 'capture-test-source');
    const results = drainAll(producer);
    const counts = summarize(results);
    assert.equal(counts.PlayerMessage, expectMsg);
    assert.equal(counts.PlayerTravelled, expectTravel);
    assert.equal(counts.Unknown, 1); // U1 metadata-only, once per session
    assert.equal(producer.skippedMalformed, 0); // real captures are clean

    // Consumer-side snapshot semantics over the drained sequence.
    let message = null;
    let travel = null;
    let unknowns = 0;
    for (const r of results) {
      if (r.resultKind === 'PlayerMessage') message = r.observation;
      if (r.resultKind === 'PlayerTravelled') travel = r.observation;
      if (r.resultKind === 'Unknown') unknowns += 1;
    }
    assert.equal(message.message, expectText); // latest (only) chat, verbatim
    assert.ok(travel.position.x !== undefined && travel.metersTravelled > 0);
    assert.equal(unknowns, 1);

    // Order preserved: capture order, first post-establishment item is Unknown (U1).
    assert.equal(results[0].resultKind, 'Unknown');
    assert.equal(results[results.length - 1].resultKind !== 'Unknown', true);

    // Event IDs deterministic: re-drain reproduces identical ids.
    const again = drainAll(new CaptureProducer(records, firstEventIndex(records)));
    assert.deepEqual(
      again.map((r) => r.observation.eventId),
      results.map((r) => r.observation.eventId),
    );
  });
}

test('unknown payload is not retained', () => {
  const records = loadCapture(CAPTURE_A);
  const results = drainAll(new CaptureProducer(records, firstEventIndex(records)));
  const unknowns = results.filter((r) => r.resultKind === 'Unknown');
  assert.equal(unknowns.length, 1);
  const u = unknowns[0].observation;
  assert.deepEqual(Object.keys(u).sort(), ['eventId', 'observedAtMs', 'reason', 'type', 'wireLength']);
  assert.equal(u.wireLength, 404);
  assert.ok(Object.isFrozen(unknowns[0].observation));
});

test('malformed records are skipped, never fabricated', () => {
  const records = loadCapture(CAPTURE_A);
  const start = firstEventIndex(records);
  const poisoned = [
    ...records.slice(0, start),
    null,
    'not-an-object',
    { direction: 'minecraft->xykell-lab', timestamp: 'not-a-date', raw: '{}', parsed: true },
    { direction: 'minecraft->xykell-lab', timestamp: records[start].timestamp }, // no raw
    { direction: 'minecraft->xykell-lab', timestamp: records[start].timestamp, raw: '{"a":', parsed: true },
    ...records.slice(start),
  ];
  const producer = new CaptureProducer(poisoned, start);
  const results = drainAll(producer);
  const counts = summarize(results);
  assert.equal(counts.PlayerMessage, 1);
  assert.equal(counts.PlayerTravelled, 24);
  assert.equal(counts.Unknown, 1);
  assert.equal(producer.skippedMalformed, 5); // all five poisoned lines counted
});

test('exhaustion is deterministic; Unavailable never emitted', () => {
  const records = loadCapture(CAPTURE_B);
  const producer = new CaptureProducer(records, firstEventIndex(records));
  drainAll(producer);
  for (let i = 0; i < 200; ++i) {
    const r = producer.pollNext();
    assert.equal(r.outcome, 'NoObservation');
    assert.deepEqual(r, { outcome: 'NoObservation' });
  }
  assert.equal(producer.emitted, 28);
});

test('boundedness: producer holds no per-poll growth', () => {
  const records = loadCapture(CAPTURE_B);
  const producer = new CaptureProducer(records, firstEventIndex(records));
  drainAll(producer);
  const before = producer.emitted + producer.skippedMalformed + producer.index;
  for (let i = 0; i < 200; ++i) producer.pollNext();
  assert.equal(producer.emitted + producer.skippedMalformed + producer.index, before);
});
