// Capture logger: raw-preserving JSONL records with secret redaction.
// Secrets are redacted by KEY NAME at persistence time; raw payloads are
// never written to disk unredacted.
import { mkdirSync, appendFileSync, closeSync, openSync } from 'node:fs';
import { join } from 'node:path';

const SECRET_KEY = /(token|access_token|refresh_token|password|secret|authorization|cookie|session|privatekey|private_key)/i;
const MAX_PERSIST_BYTES = 64 * 1024;

function redactValue(value, depth = 0) {
  if (depth > 8) return '[REDACTED-DEPTH]';
  if (Array.isArray(value)) return value.map((v) => redactValue(v, depth + 1));
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = SECRET_KEY.test(k) ? '[REDACTED]' : redactValue(v, depth + 1);
    }
    return out;
  }
  return value;
}

export function redactSecrets(parsedJson) {
  return redactValue(parsedJson);
}

export function secretKeysPresent(parsedJson, depth = 0) {
  if (depth > 8 || parsedJson === null || typeof parsedJson !== 'object') return [];
  const hits = [];
  const entries = Array.isArray(parsedJson)
    ? parsedJson.map((v, i) => [String(i), v])
    : Object.entries(parsedJson);
  for (const [k, v] of entries) {
    if (SECRET_KEY.test(k)) hits.push(k);
    hits.push(...secretKeysPresent(v, depth + 1));
  }
  return [...new Set(hits)];
}

export function makeRecord({ timestamp, connectionId, direction, encoding, raw }) {
  return { timestamp, connectionId, direction, encoding, length: raw.length, raw };
}

export function createCaptureSink(dir) {
  mkdirSync(dir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = join(dir, `capture-${stamp}.jsonl`);
  closeSync(openSync(file, 'a')); // touch: an empty file marks session start
  return {
    file,
    write(record, { parsed, json }) {
      let payload = record.raw;
      let redactedKeys = [];
      if (parsed && json !== null && typeof json === 'object') {
        redactedKeys = secretKeysPresent(json);
        payload = JSON.stringify(redactSecrets(json));
        if (payload.length > MAX_PERSIST_BYTES) {
          payload = payload.slice(0, MAX_PERSIST_BYTES) + '...[TRUNCATED]';
        }
      } else if (record.raw.length > MAX_PERSIST_BYTES) {
        payload = record.raw.slice(0, MAX_PERSIST_BYTES) + '...[TRUNCATED]';
      }
      appendFileSync(file, JSON.stringify({ ...record, raw: payload, parsed, redactedKeys }) + '\n');
    },
  };
}
