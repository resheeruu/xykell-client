// SUBSCRIBE-ONLY mode (Stage 9D, read-side only).
//
// Envelope shape verified against two independent community sources:
//  - Sandertv/mcwss (MIT): subscribe = header{version,messagePurpose} +
//    body{eventName}; server opts into named events.
//  - sanand0/minecraft-websocket protocol reference: messagePurpose
//    "subscribe" with messageType "commandRequest", one eventName per
//    subscribe frame, header version 1, UUID requestId.
// Tested upstream on Bedrock 1.16-1.18/Windows; applicability to our
// 1.26.52.3/Android target is UNKNOWN until the manual experiment.
//
// This module can ONLY build subscribe frames for the allowlisted read-side
// events below. There is deliberately no command/Exec builder anywhere in
// the lab: adding one would violate the Stage 9D read-side-only boundary.
import { randomUUID } from 'node:crypto';

export const SUBSCRIBED_EVENTS = Object.freeze(['PlayerMessage', 'PlayerTravelled']);

export function isSubscribeOnlyEnabled(env = process.env) {
  return env.SUBSCRIBE_ONLY === '1';
}

export function buildSubscribeFrame(eventName, requestId = randomUUID()) {
  if (!SUBSCRIBED_EVENTS.includes(eventName)) {
    throw new Error(`refusing to subscribe to non-allowlisted event: ${eventName}`);
  }
  return JSON.stringify({
    header: {
      version: 1,
      requestId,
      messageType: 'commandRequest',
      messagePurpose: 'subscribe',
    },
    body: { eventName },
  });
}

export function subscribeFrames(requestIdFor) {
  return SUBSCRIBED_EVENTS.map((eventName, i) =>
    buildSubscribeFrame(eventName, requestIdFor ? requestIdFor(i) : undefined),
  );
}
