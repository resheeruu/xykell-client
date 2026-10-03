import test from 'node:test';
import assert from 'node:assert/strict';
import { classify, tryParseJson, Categories } from '../protocol.mjs';
import { makeRecord, redactSecrets, secretKeysPresent, createCaptureSink } from '../logger.mjs';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

test('JSON parsing: valid object', () => {
  const r = tryParseJson('{"header":{"messagePurpose":"event"}}');
  assert.equal(r.parsed, true);
  assert.equal(r.json.header.messagePurpose, 'event');
});

test('JSON parsing: malformed', () => {
  const r = tryParseJson('{"header":');
  assert.equal(r.parsed, false);
  assert.equal(r.json, null);
});

test('JSON parsing: non-object JSON stays UNKNOWN-capable', () => {
  const r = tryParseJson('[1,2]');
  assert.equal(r.parsed, true);
  assert.equal(classify(r.json), Categories.UNKNOWN);
});

test('classify: commandResponse envelope', () => {
  assert.equal(classify({ header: { messagePurpose: 'commandResponse' }, body: {} }), Categories.COMMAND_RESPONSE);
});

test('classify: PlayerMessage -> CHAT', () => {
  assert.equal(
    classify({ header: { messagePurpose: 'event', eventName: 'PlayerMessage' }, body: {} }),
    Categories.CHAT,
  );
});

test('classify: PlayerTravelled -> PLAYER (shape only, not position proof)', () => {
  assert.equal(
    classify({ header: { messagePurpose: 'event', eventName: 'PlayerTravelled' }, body: {} }),
    Categories.PLAYER,
  );
});

test('classify: unknown structures stay UNKNOWN', () => {
  assert.equal(classify(null), Categories.UNKNOWN);
  assert.equal(classify('text'), Categories.UNKNOWN);
  assert.equal(classify({}), Categories.UNKNOWN);
  assert.equal(classify({ header: {} }), Categories.UNKNOWN);
  assert.equal(classify({ header: { messagePurpose: 'event' }, body: {} }), Categories.EVENT);
});

test('classify: error envelope', () => {
  assert.equal(classify({ header: { messagePurpose: 'error' }, body: {} }), Categories.ERROR);
});

test('redaction: token-like keys redacted, structure preserved', () => {
  const red = redactSecrets({ header: { messagePurpose: 'event' }, body: { token: 'abc', player: 'steve' } });
  assert.equal(red.body.token, '[REDACTED]');
  assert.equal(red.body.player, 'steve');
  assert.deepEqual(secretKeysPresent({ body: { access_token: 'x', ok: 1 } }), ['access_token']);
});

test('record preserves raw payload verbatim', () => {
  const r = makeRecord({ timestamp: 't', connectionId: 'c', direction: 'd', encoding: 'text', raw: '{"a":1}' });
  assert.equal(r.raw, '{"a":1}');
  assert.equal(r.length, 7);
});

test('capture sink persists redacted JSONL', () => {
  const dir = mkdtempSync(join(tmpdir(), 'xykell-lab-'));
  const sink = createCaptureSink(dir);
  assert.ok(existsSync(sink.file));
  sink.write(
    makeRecord({ timestamp: 't', connectionId: 'c1', direction: 'd', encoding: 'text', raw: '{"a":1}' }),
    { parsed: true, json: { a: 1, password: 'pw' } },
  );
  const line = readFileSync(sink.file, 'utf8').trim();
  const rec = JSON.parse(line);
  assert.equal(rec.connectionId, 'c1');
  assert.deepEqual(JSON.parse(rec.raw), { a: 1, password: '[REDACTED]' });
  assert.deepEqual(rec.redactedKeys, ['password']);
});
