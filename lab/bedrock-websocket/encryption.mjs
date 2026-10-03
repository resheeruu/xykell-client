// Documented encrypted-session handshake (Stage 9F, lab only).
//
// Protocol verified against Sandertv/mcwss (MIT, reputable): server offers
// `com.microsoft.minecraft.wsencrypt`, generates an ephemeral ECDSA P-384
// keypair + 16-byte salt, sends `enableencryption "<b64 DER>" "<b64
// salt>"` as a commandRequest, receives the client's DER public key in the
// commandResponse, derives ECDH x-coordinate, key = SHA256(salt + secret),
// AES-256-CFB8 both directions with IVs = key[:16].
// Wire encoding (Stage 9L): standard base64 alphabet ("+"/"/"),
// UNPADDED — equivalent to Go base64.RawStdEncoding used by mcwss.
// base64url ("-"/"_") is rejected by Minecraft (badsalt / bad-key).
//
// This module performs ONLY this documented handshake. The single
// `enableencryption` command is the handshake itself — no other command
// construction exists anywhere in the lab (test-enforced).
import { randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { webcrypto, createHash } from 'node:crypto';

export const WSENCRYPT_SUBPROTOCOL = 'com.microsoft.minecraft.wsencrypt';

export function isEncryptedSessionEnabled(env = process.env) {
  return env.ENCRYPTED_SESSION === '1';
}

// Documented handshake command ONLY. No other commandLine may be built.
export function buildEnableEncryptionCommand(publicKeyDer, salt) {
  if (!(publicKeyDer instanceof Uint8Array) || publicKeyDer.length === 0) {
    throw new Error('refusing handshake: invalid public key');
  }
  if (!(salt instanceof Uint8Array) || salt.length !== 16) {
    throw new Error('refusing handshake: salt must be exactly 16 bytes');
  }
  const b64 = (b) => Buffer.from(b).toString('base64').replace(/=+$/, '');
  return `enableencryption "${b64(publicKeyDer)}" "${b64(salt)}"`;
}

export function buildCommandRequest(commandLine, requestId) {
  return JSON.stringify({
    header: { version: 1, requestId, messageType: 'commandRequest', messagePurpose: 'commandRequest' },
    body: { version: 1, commandLine },
  });
}

// Ephemeral P-384 identity. Private key lives in memory only: never
// logged, never persisted, never returned to callers.
export async function generateEphemeralIdentity() {
  const salt = randomBytes(16);
  const pair = await webcrypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-384' }, true, [
    'deriveBits',
  ]);
  const publicDer = Buffer.from(await webcrypto.subtle.exportKey('spki', pair.publicKey));
  return { privateKey: pair.privateKey, publicDer, salt };
}

export async function deriveSessionKey(privateKey, clientPublicDer, salt) {
  const peer = await webcrypto.subtle.importKey(
    'spki',
    clientPublicDer,
    { name: 'ECDH', namedCurve: 'P-384' },
    false,
    [],
  );
  const bits = Buffer.from(await webcrypto.subtle.deriveBits({ name: 'ECDH', public: peer }, privateKey, 384));
  // ECDH x-coordinate may need left-padding to 48 bytes for P-384.
  const secret = bits.length >= 48 ? bits.subarray(bits.length - 48) : Buffer.concat([Buffer.alloc(48 - bits.length), bits]);
  return createHash('sha256').update(Buffer.concat([Buffer.from(salt), secret])).digest();
}

// Single-establishment guard (Stage 9H Fix 1). Synchronous claim: the
// first matching key frame flips `establishing` immediately, so a second
// key frame arriving during the async derive can never re-enter.
export function claimEstablishment(state) {
  if (state.session || state.establishing) return false;
  state.establishing = true;
  return true;
}

// True only for the documented enableencryption response: a parsed JSON
// object whose header requestId echoes our handshake request AND whose
// body carries a non-empty client publicKey. Error payloads, events, and
// unrelated responses never satisfy this.
export function isHandshakeResponse(json, handshakeRequestId) {
  if (json === null || typeof json !== 'object' || Array.isArray(json)) return false;
  if (json?.header?.requestId !== handshakeRequestId) return false;
  return typeof json?.body?.publicKey === 'string' && json.body.publicKey.length > 0;
}

// Inbound decoder (Stage 9H Fix 2). Single choke point for turning wire
// bytes into a safe record: decrypts first when a session exists (binary
// AND text frames), parses only decrypted/plaintext, and yields
// DECRYPTION_ERROR — never a JSON.parse on ciphertext — when an encrypted
// session cannot produce valid application JSON.
import { tryParseJson, classify } from './protocol.mjs';

export function decodeInbound(data, isBinary, session) {
  const bytes = Buffer.from(data);
  if (session) {
    let plain;
    try {
      plain = session.decrypt(bytes);
    } catch {
      return {
        raw: bytes.toString('base64'), encoding: 'base64', parsed: false,
        json: null, category: 'DECRYPTION_ERROR', wasEncrypted: true, error: 'decrypt-failed',
      };
    }
    const text = plain.toString('utf8');
    const { parsed, json } = tryParseJson(text);
    if (!parsed) {
      return {
        raw: bytes.toString('base64'), encoding: 'base64', parsed: false,
        json: null, category: 'DECRYPTION_ERROR', wasEncrypted: true, error: 'parse-failed',
      };
    }
    return {
      raw: text, encoding: 'text', parsed: true,
      json, category: classify(json), wasEncrypted: true, error: null,
    };
  }
  if (isBinary) {
    return {
      raw: bytes.toString('base64'), encoding: 'base64', parsed: false,
      json: null, category: 'UNKNOWN', wasEncrypted: false, error: null,
    };
  }
  const text = bytes.toString('utf8');
  const { parsed, json } = tryParseJson(text);
  return {
    raw: text, encoding: 'text', parsed, json,
    category: parsed ? classify(json) : 'UNKNOWN', wasEncrypted: false, error: null,
  };
}
// Streaming AES-256-CFB8 session (matches mcwss byte-chained CFB8 use).
export function createEncryptedSession(sessionKey) {
  if (sessionKey.length !== 32) throw new Error('refusing session: key must be 32 bytes');
  const iv = Buffer.from(sessionKey.subarray(0, 16));
  const encipher = createCipheriv('aes-256-cfb8', sessionKey, Buffer.from(iv));
  const decipher = createDecipheriv('aes-256-cfb8', sessionKey, Buffer.from(iv));
  return {
    encrypt: (bytes) => Buffer.concat([encipher.update(Buffer.from(bytes))]),
    decrypt: (bytes) => Buffer.concat([decipher.update(Buffer.from(bytes))]),
  };
}
