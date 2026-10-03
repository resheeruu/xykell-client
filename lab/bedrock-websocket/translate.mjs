// Stage-11 lab translator: verified envelope -> Xykell observation object.
//
// LAB-ONLY read-only adapter. Input is an already-decrypted,
// already-parsed JSON envelope (the output of decodeInbound); this
// module performs NO network I/O, NO crypto, NO subscription, and owns
// NO command path. Output shapes mirror the production model
// (native/include/xykell/runtime_event_observation.h) field-for-field;
// nothing here is wired to RuntimeProvider/JNI/UI.
//
// Result vocabulary:
//   { kind: 'PlayerMessage' | 'PlayerTravelled', observation }  verified event
//   { kind: 'Unknown', observation }   well-formed input, meaning unproven
//   { kind: 'Invalid' }                malformed/unusable input (no observation)
// Unknown !== Invalid: Unknown is a valid receipt with no proven meaning
// (e.g. an unrecognized event envelope); Invalid is garbage.
//
// Identity/timestamps are caller-supplied (deterministic, Stage-10 rule):
//   ctx = { eventId, observedAtMs, wireLength }
//   - eventId: deterministic id for this input (same input -> same id).
//     The validation harness derives it as
//     `xykell-obs-<captureEpochMs>-<recordIndex>`; unit fixtures use fixed ids.
//   - observedAtMs: when the lab observed/processed the event (NOT the
//     Minecraft action time; Stage 9 never clock-proved causality).
//   - wireLength: decoded binary byte count for Unknown metadata (0 if n/a).
//
// Outputs are Object.freeze()-d (publication by frozen value).

const EVENT = 'event';

// Reason vocabulary for Unknown results (fixed strings, no inference).
export const UnknownReasons = Object.freeze({
  UNRECOGNIZED_EVENT: 'unrecognized-event',
  NON_EVENT_ENVELOPE: 'non-event-envelope',
  UNPARSED_INPUT: 'unparsed-input',
});

function isRecord(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isFiniteNumber(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

function validCtx(ctx) {
  return (
    isRecord(ctx) &&
    typeof ctx.eventId === 'string' &&
    ctx.eventId.length > 0 &&
    typeof ctx.observedAtMs === 'number' &&
    Number.isFinite(ctx.observedAtMs)
  );
}

function wireLengthOf(ctx) {
  return isRecord(ctx) && isFiniteNumber(ctx.wireLength) && ctx.wireLength >= 0
    ? ctx.wireLength
    : 0;
}

function freezeObservation(kind, observation) {
  return { kind, observation: Object.freeze({ ...observation }) };
}

function asPlayerMessage(envelope, ctx) {
  const body = envelope.body;
  if (!isRecord(body)) return null;
  if (typeof body.sender !== 'string' || body.sender.length === 0) return null;
  if (typeof body.message !== 'string' || body.message.length === 0) return null;
  // message preserved EXACTLY: no case/whitespace/punctuation normalization.
  // receiver deliberately absent (Stage-10 model: unproven as signal).
  return freezeObservation('PlayerMessage', {
    type: 'PlayerMessageObservation',
    eventId: ctx.eventId,
    observedAtMs: ctx.observedAtMs,
    sender: body.sender,
    message: body.message,
  });
}

function asPlayerTravelled(envelope, ctx) {
  const body = envelope.body;
  if (!isRecord(body)) return null;
  const player = body.player;
  if (!isRecord(player)) return null;
  const pos = player.position;
  if (!isRecord(pos)) return null;
  if (!isFiniteNumber(pos.x) || !isFiniteNumber(pos.y) || !isFiniteNumber(pos.z)) return null;
  // Rotation is yaw-only: only yRot exists in evidence (pitch unobserved).
  if (!isFiniteNumber(player.yRot)) return null;
  if (!isFiniteNumber(body.metersTravelled) || body.metersTravelled < 0) return null;
  // travelMethod stays a raw number (observed 0 and 2; semantics unverified).
  if (!isFiniteNumber(body.travelMethod)) return null;
  return freezeObservation('PlayerTravelled', {
    type: 'PlayerTravelObservation',
    eventId: ctx.eventId,
    observedAtMs: ctx.observedAtMs,
    position: Object.freeze({ x: pos.x, y: pos.y, z: pos.z }),
    yawDegrees: player.yRot,
    metersTravelled: body.metersTravelled,
    travelMethod: body.travelMethod,
  });
}

// translateEnvelope(value, ctx): value is a parsed envelope object, or a
// JSON string (parsed here; unparsable string -> Invalid). Ciphertext,
// Buffers, and binary frames are NOT valid input (U1 never parses, so it
// can only reach here as unparsed -> Invalid; the harness maps unparsed
// capture records to Unknown directly with their wire length).
export function translateEnvelope(value, ctx) {
  if (!validCtx(ctx)) return { kind: 'Invalid' };
  let envelope = value;
  if (typeof envelope === 'string') {
    try {
      envelope = JSON.parse(envelope);
    } catch {
      return { kind: 'Invalid' };
    }
  }
  if (!isRecord(envelope)) return { kind: 'Invalid' };
  const header = envelope.header;
  if (!isRecord(header)) return { kind: 'Invalid' };
  if (header.messagePurpose === EVENT && typeof header.eventName === 'string') {
    if (header.eventName === 'PlayerMessage') {
      return asPlayerMessage(envelope, ctx) ?? { kind: 'Invalid' };
    }
    if (header.eventName === 'PlayerTravelled') {
      return asPlayerTravelled(envelope, ctx) ?? { kind: 'Invalid' };
    }
    // Well-formed event envelope, unproven meaning -> Unknown (never guessed).
    return freezeObservation('Unknown', {
      type: 'UnknownObservation',
      eventId: ctx.eventId,
      observedAtMs: ctx.observedAtMs,
      wireLength: wireLengthOf(ctx),
      reason: UnknownReasons.UNRECOGNIZED_EVENT,
    });
  }
  // Well-formed non-event envelope (commandResponse, subscribe, ...) ->
  // Unknown: valid receipt, not a gameplay observation.
  if (typeof header.messagePurpose === 'string' || typeof header.messageType === 'string') {
    return freezeObservation('Unknown', {
      type: 'UnknownObservation',
      eventId: ctx.eventId,
      observedAtMs: ctx.observedAtMs,
      wireLength: wireLengthOf(ctx),
      reason: UnknownReasons.NON_EVENT_ENVELOPE,
    });
  }
  return { kind: 'Invalid' };
}

// translateUnparsed(ctx): for capture records that never parsed (U1-class
// binary, pre-establishment frames). No content is examined or stored.
export function translateUnparsed(ctx, reason = UnknownReasons.UNPARSED_INPUT) {
  if (!validCtx(ctx)) return { kind: 'Invalid' };
  return freezeObservation('Unknown', {
    type: 'UnknownObservation',
    eventId: ctx.eventId,
    observedAtMs: ctx.observedAtMs,
    wireLength: wireLengthOf(ctx),
    reason,
  });
}
