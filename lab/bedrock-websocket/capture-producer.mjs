// Stage-14 capture producer: recorded evidence -> pollable observations.
//
// CAPTURE / TEST SOURCE ONLY. Reads already-recorded lab capture records
// (parsed JSONL objects), reuses the Stage-11 translator, and exposes the
// Stage-13 poll contract ({outcome: Observation|NoObservation|Unavailable}).
// It NEVER opens a socket, connects anywhere, performs crypto, subscribes,
// sends, or controls. No connection state exists: `kind()` always reports
// 'capture-test-source', never Minecraft/live/connected.
//
// Record protocol (caller supplies parsed record objects + indices):
//   - Only records at index >= firstEventIndex are visited (handshake, key
//     exchange, subscribes, outbound frames, and pre-establishment U0 stay
//     transport evidence, never gameplay observations).
//   - parsed record  -> translateEnvelope (PlayerMessage / PlayerTravelled /
//     Unknown for well-formed-but-unproven envelopes).
//   - unparsed record -> translateUnparsed (U1 metadata only; content never
//     examined or stored).
//   - malformed record (missing timestamp/direction/raw, bad timestamp) ->
//     SKIPPED with a counted skip (never fabricated into Unknown or an
//     event). `skippedMalformed` is reported, never silent.
//   - Exhaustion -> { outcome: 'NoObservation' }, deterministically, forever.
//   - 'Unavailable' is never produced by file-backed evidence; the outcome
//     exists for live-source failures per the Stage-13 contract.
//
// Identity/timestamps (Stage-10/11 rule): eventId =
// `xykell-obs-<epochMs>-<recordIndex>` (same record -> same id);
// observedAtMs = record timestamp (lab observation time, NOT action time);
// wireLength = decoded binary bytes (base64 records) or UTF-8 bytes (text).
//
// Memory: index over the caller-supplied array; no copy of records, no
// history accumulation, no persistence, no queue. Test-only bounded use.
import { translateEnvelope, translateUnparsed } from './translate.mjs';

export const SOURCE_KIND = 'capture-test-source';

function epochMs(value) {
  const t = Date.parse(value);
  return Number.isFinite(t) ? t : null;
}

function wireLengthOf(record) {
  if (record.encoding === 'base64' && typeof record.raw === 'string') {
    try {
      return Buffer.from(record.raw, 'base64').length;
    } catch {
      return 0;
    }
  }
  if (typeof record.raw === 'string') return Buffer.byteLength(record.raw, 'utf8');
  return 0;
}

function wellFormedRecord(record) {
  return (
    record !== null &&
    typeof record === 'object' &&
    !Array.isArray(record) &&
    record.direction === 'minecraft->xykell-lab' &&
    epochMs(record.timestamp) !== null &&
    typeof record.raw === 'string'
  );
}

export class CaptureProducer {
  constructor(records, firstEventIndex = 0) {
    if (!Array.isArray(records)) throw new TypeError('records must be an array');
    this.records = records;
    this.index = Math.max(0, firstEventIndex | 0);
    this.skippedMalformed = 0;
    this.emitted = 0;
  }

  kind() {
    return SOURCE_KIND;
  }

  // Stage-13 poll contract shape (lab JS equivalent).
  pollNext() {
    while (this.index < this.records.length) {
      const recordIndex = this.index++;
      const record = this.records[recordIndex];
      if (!wellFormedRecord(record)) {
        this.skippedMalformed += 1;
        continue;
      }
      const atMs = epochMs(record.timestamp);
      const ctx = {
        eventId: `xykell-obs-${atMs}-${recordIndex}`,
        observedAtMs: atMs,
        wireLength: wireLengthOf(record),
      };
      if (record.parsed) {
        let envelope;
        try {
          envelope = JSON.parse(record.raw);
        } catch {
          this.skippedMalformed += 1;
          continue;
        }
        const res = translateEnvelope(envelope, ctx);
        if (res.kind === 'Invalid') {
          this.skippedMalformed += 1;
          continue;
        }
        this.emitted += 1;
        return { outcome: 'Observation', observation: res.observation, resultKind: res.kind };
      }
      const res = translateUnparsed(ctx);
      if (res.kind === 'Invalid') {
        this.skippedMalformed += 1;
        continue;
      }
      this.emitted += 1;
      return { outcome: 'Observation', observation: res.observation, resultKind: res.kind };
    }
    return { outcome: 'NoObservation' };
  }
}
