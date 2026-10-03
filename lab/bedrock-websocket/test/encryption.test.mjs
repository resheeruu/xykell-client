import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  WSENCRYPT_SUBPROTOCOL,
  isEncryptedSessionEnabled,
  isHandshakeResponse,
  claimEstablishment,
  decodeInbound,
  buildEnableEncryptionCommand,
  buildCommandRequest,
  generateEphemeralIdentity,
  deriveSessionKey,
  createEncryptedSession,
} from '../encryption.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const LAB = join(HERE, '..');

test('encrypted mode defaults to off', () => {
  assert.equal(isEncryptedSessionEnabled({}), false);
  assert.equal(isEncryptedSessionEnabled({ ENCRYPTED_SESSION: '1' }), true);
  assert.equal(isEncryptedSessionEnabled({ ENCRYPTED_SESSION: '0' }), false);
});

test('subprotocol identifier is exact', () => {
  assert.equal(WSENCRYPT_SUBPROTOCOL, 'com.microsoft.minecraft.wsencrypt');
});

test('ephemeral identity: keypair + 16-byte salt, unique per generation', async () => {
  const a = await generateEphemeralIdentity();
  const b = await generateEphemeralIdentity();
  assert.equal(a.salt.length, 16);
  assert.ok(a.publicDer.length > 0);
  assert.notDeepEqual(Buffer.from(a.publicDer), Buffer.from(b.publicDer));
  assert.notDeepEqual(Buffer.from(a.salt), Buffer.from(b.salt));
});

test('private material is not serializable into captures', async () => {
  const id = await generateEphemeralIdentity();
  // CryptoKey serializes to {} — a JSON capture can never contain it,
  // and only the public DER is ever handed to capture-adjacent code.
  assert.deepEqual(JSON.parse(JSON.stringify(id.privateKey)), {});
  assert.ok(id.publicDer.length > 0);
});

test('enableencryption command format + input validation', () => {
  const pub = Buffer.from('public-bytes');
  const salt = Buffer.alloc(16, 7);
  const cmd = buildEnableEncryptionCommand(pub, salt);
  // Stage 9L: documented wire form is standard base64 (mcwss
  // RawStdEncoding): "+"/"/" allowed, "-"/"_" never emitted, no padding.
  assert.match(cmd, /^enableencryption "[A-Za-z0-9+/]+" "[A-Za-z0-9+/]+"$/);
  assert.throws(() => buildEnableEncryptionCommand(pub, Buffer.alloc(15)), /salt/);
  assert.throws(() => buildEnableEncryptionCommand(Buffer.alloc(0), salt), /public key/);
});

// Stage 9L: deterministic vectors chosen so standard-base64 output MUST
// contain "+" (sextet 62) and "/" (sextet 63). Bytes FB FF FE give
// groups 111110 111111 111111 111110 -> "+//+".
const PLUS_SLASH_TRIPLE = Buffer.from([0xfb, 0xff, 0xfe]);
const KEY120 = Buffer.from(Array.from({ length: 120 }, (_, i) => [0xfb, 0xff, 0xfe][i % 3]));
const SALT16 = Buffer.from(Array.from({ length: 16 }, (_, i) => [0xfb, 0xff, 0xfe, 0x00][i % 4]));

function commandParts(cmd) {
  const m = cmd.match(/^enableencryption "([^"]+)" "([^"]+)"$/);
  assert.ok(m, `unexpected command shape: ${cmd}`);
  return { key: m[1], salt: m[2] };
}

function decodeRawStd(s) {
  // Go base64.RawStdEncoding semantics: standard alphabet, no padding.
  assert.doesNotMatch(s, /=/);
  const padded = s + '='.repeat((-s.length % 4 + 4) % 4);
  return Buffer.from(padded, 'base64');
}

test('9L key encoding uses standard base64 (+ and / preserved)', () => {
  const { key } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.ok(key.includes('+'), 'expected "+" in key encoding');
  assert.ok(key.includes('/'), 'expected "/" in key encoding');
  assert.match(key, /^[A-Za-z0-9+/]+$/);
});

test('9L salt encoding uses standard base64 (+ and / preserved)', () => {
  const { salt } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.ok(salt.includes('+'), 'expected "+" in salt encoding');
  assert.ok(salt.includes('/'), 'expected "/" in salt encoding');
  assert.match(salt, /^[A-Za-z0-9+/]+$/);
});

test('9L padding is removed', () => {
  // 16-byte salt pads to "==" under padded base64; 1-byte input pads to "==".
  const { salt } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.equal(salt.length, 22);
  assert.doesNotMatch(salt, /=/);
  const one = commandParts(buildEnableEncryptionCommand(PLUS_SLASH_TRIPLE, SALT16)).key;
  assert.equal(one, '+//+');
  assert.doesNotMatch(one, /=/);
});

test('9L encoder never emits base64url characters', () => {
  const { key, salt } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.doesNotMatch(key, /[-_]/);
  assert.doesNotMatch(salt, /[-_]/);
  // A full 0..255 sweep through the encoder path: no "-" or "_" anywhere.
  const sweep = Buffer.from(Array.from({ length: 256 }, (_, i) => i));
  const cmd = buildEnableEncryptionCommand(sweep, Buffer.alloc(16, 0xaa));
  const parts = commandParts(cmd);
  assert.doesNotMatch(parts.key, /[-_]/);
});

test('9L 120-byte SPKI encodes to exactly 160 characters', () => {
  assert.equal(KEY120.length, 120);
  const { key } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.equal(key.length, 160);
});

test('9L 16-byte salt encodes to exactly 22 characters', () => {
  assert.equal(SALT16.length, 16);
  const { salt } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.equal(salt.length, 22);
});

test('9L standard unpadded decode reproduces exact bytes', () => {
  const { key, salt } = commandParts(buildEnableEncryptionCommand(KEY120, SALT16));
  assert.deepEqual(decodeRawStd(key), KEY120);
  assert.deepEqual(decodeRawStd(salt), SALT16);
});

test('9L generated identity public DER encodes standard-only', async () => {
  const id = await generateEphemeralIdentity();
  assert.equal(id.publicDer.length, 120); // P-384 SPKI DER is exactly 120 bytes
  const { key, salt } = commandParts(buildEnableEncryptionCommand(id.publicDer, id.salt));
  assert.match(key, /^[A-Za-z0-9+/]+$/);
  assert.match(salt, /^[A-Za-z0-9+/]+$/);
  assert.doesNotMatch(key, /=/);
  assert.doesNotMatch(salt, /=/);
  assert.deepEqual(decodeRawStd(key), Buffer.from(id.publicDer));
  assert.deepEqual(decodeRawStd(salt), Buffer.from(id.salt));
});

test('handshake-response predicate accepts only the documented shape', () => {
  const ok = { header: { requestId: 'hs-1' }, body: { publicKey: 'abc', statusCode: 0 } };
  assert.equal(isHandshakeResponse(ok, 'hs-1'), true);
  assert.equal(isHandshakeResponse(ok, 'hs-2'), false); // wrong request
  assert.equal(isHandshakeResponse({ header: { messagePurpose: 'error' }, body: {} }, 'hs-1'), false);
  assert.equal(isHandshakeResponse({ header: { requestId: 'hs-1' }, body: {} }, 'hs-1'), false);
  assert.equal(isHandshakeResponse(null, 'hs-1'), false);
  assert.equal(isHandshakeResponse('text', 'hs-1'), false);
});

test('session encrypt/decrypt roundtrip (multi-byte, streaming)', async () => {
  const id = await generateEphemeralIdentity();
  // Self-consistency: both ends derive from the same identity material.
  const key = await deriveSessionKey(id.privateKey, id.publicDer, id.salt);
  assert.equal(key.length, 32);
  const a = createEncryptedSession(key);
  const b = createEncryptedSession(key);
  const msg = Buffer.from('{"header":{"version":1},"body":{"eventName":"PlayerMessage"}}');
  const enc = a.encrypt(msg);
  assert.notDeepEqual(enc, msg);
  assert.deepEqual(b.decrypt(enc), msg);
  // Streaming continues correctly across chunks.
  const c = createEncryptedSession(key);
  const d = createEncryptedSession(key);
  const p1 = c.encrypt(Buffer.from('hello '));
  const p2 = c.encrypt(Buffer.from('world'));
  assert.equal(d.decrypt(p1).toString() + d.decrypt(p2).toString(), 'hello world');
});

test('no command construction beyond the documented handshake', () => {
  // The ONLY commandLine in the lab must be the enableencryption handshake
  // request builder. SUBSCRIBE frames carry no commandLine (verified by the
  // subscribe tests); gameplay commands must not exist.
  const hits = [];
  for (const f of ['server.mjs', 'subscribe.mjs', 'encryption.mjs', 'protocol.mjs', 'logger.mjs']) {
    const code = readFileSync(join(LAB, f), 'utf8')
      .split('\n')
      .map((line) => line.split('//', 1)[0])
      .join('\n');
    const count = (code.match(/commandLine/g) ?? []).length;
    if (count > 0) hits.push(`${f}:${count}`);
  }
  assert.deepEqual(hits, ['encryption.mjs:2']);
  const serverCode = readFileSync(join(LAB, 'server.mjs'), 'utf8');
  assert.equal(serverCode.split('buildCommandRequest(').length - 1, 1);
});

test('establishment guard: first claim wins, duplicates refused', async () => {
  
  const state = { session: null, establishing: false };
  assert.equal(claimEstablishment(state), true); // first key frame
  assert.equal(state.establishing, true);
  assert.equal(claimEstablishment(state), false); // near-simultaneous duplicate
  state.session = {};
  assert.equal(claimEstablishment(state), false); // established -> established
  assert.equal(claimEstablishment({ session: {}, establishing: false }), false);
});

test('decodeInbound: plaintext text and binary without session', async () => {
  
  const text = decodeInbound(Buffer.from('{"header":{"messagePurpose":"event"}}'), false, null);
  assert.equal(text.parsed, true);
  assert.equal(text.wasEncrypted, false);
  const bin = decodeInbound(Buffer.from([0, 1, 2]), true, null);
  assert.equal(bin.parsed, false);
  assert.equal(bin.category, 'UNKNOWN');
  assert.equal(bin.encoding, 'base64');
});

test('decodeInbound: encrypted success, mismatch, and garbage', async () => {
  
  const id = await generateEphemeralIdentity();
  const key = await deriveSessionKey(id.privateKey, id.publicDer, id.salt);
  const session = createEncryptedSession(key);
  const envelope = JSON.stringify({
    header: { messagePurpose: 'event', eventName: 'PlayerMessage' }, body: {},
  });
  const wire = session.encrypt(Buffer.from(envelope));
  const good = decodeInbound(wire, true, createEncryptedSession(key));
  assert.equal(good.parsed, true);
  assert.equal(good.category, 'CHAT');
  assert.equal(good.wasEncrypted, true);
  assert.equal(good.raw, envelope);
  // Wrong key state: decrypts to garbage -> DECRYPTION_ERROR, never an event.
  const other = await generateEphemeralIdentity();
  const wrongKey = await deriveSessionKey(other.privateKey, other.publicDer, other.salt);
  const bad = decodeInbound(wire, true, createEncryptedSession(wrongKey));
  assert.equal(bad.parsed, false);
  assert.equal(bad.category, 'DECRYPTION_ERROR');
  // Garbage bytes under a session: DECRYPTION_ERROR, raw preserved as base64.
  const garbage = decodeInbound(Buffer.from([9, 9, 9]), true, createEncryptedSession(key));
  assert.equal(garbage.category, 'DECRYPTION_ERROR');
});

test('decodeInbound: unknown decrypted payload is not an event', async () => {
  
  const id = await generateEphemeralIdentity();
  const key = await deriveSessionKey(id.privateKey, id.publicDer, id.salt);
  const enc = createEncryptedSession(key);
  const dec = createEncryptedSession(key);
  const wire = enc.encrypt(Buffer.from('{"a":1}'));
  const out = decodeInbound(wire, false, dec);
  assert.equal(out.parsed, true);
  assert.equal(out.category, 'UNKNOWN');
});

test('ciphertext never reaches JSON.parse in server paths', () => {
  // sendOutbound parses ONLY caller-supplied plaintext objects; the only
  // JSON.parse call sites in server.mjs operate on locally built frames.
  const code = readFileSync(join(LAB, 'server.mjs'), 'utf8')
    .split('\n')
    .map((line) => line.split('//', 1)[0])
    .join('\n');
  assert.doesNotMatch(code, /JSON\.parse\(\s*(out|cipher|encrypt)/);
  assert.doesNotMatch(code, /JSON\.parse\(\s*raw\s*\)/);
});
