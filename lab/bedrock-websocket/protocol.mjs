// Purely descriptive classifier for observed Bedrock WebSocket payloads.
// Conservative by design: anything unrecognized is UNKNOWN. Field names
// alone never promote a capability; see docs/research/STAGE-9A-RESULTS.md.
export const Categories = Object.freeze({
  UNKNOWN: 'UNKNOWN',
  COMMAND_RESPONSE: 'COMMAND_RESPONSE',
  EVENT: 'EVENT',
  ERROR: 'ERROR',
  IDENTITY: 'IDENTITY',
  WORLD: 'WORLD',
  PLAYER: 'PLAYER',
  ENTITY: 'ENTITY',
  CHAT: 'CHAT',
  OTHER: 'OTHER',
  // Stage 9H: an encrypted session failed to yield valid application JSON.
  // Never a game-state claim; always a lab-side or key-state problem.
  DECRYPTION_ERROR: 'DECRYPTION_ERROR',
});

// Bedrock scripting/code-connection envelopes observed in the wild use
// { header: {...}, body: {...} }. We key ONLY on envelope shape, never on
// the semantic meaning of inner fields.
export function classify(parsed) {
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return Categories.UNKNOWN;
  }
  const header = parsed.header;
  if (header === null || typeof header !== 'object' || Array.isArray(header)) {
    return Categories.UNKNOWN;
  }
  const purpose = typeof header.messagePurpose === 'string' ? header.messagePurpose : '';
  const type = typeof header.messageType === 'string' ? header.messageType : '';
  if (purpose === 'commandResponse') return Categories.COMMAND_RESPONSE;
  if (purpose === 'error') return Categories.ERROR;
  if (purpose === 'event') {
    const eventName = typeof header.eventName === 'string' ? header.eventName : '';
    if (eventName === 'PlayerMessage') return Categories.CHAT;
    if (eventName.startsWith('Player')) return Categories.PLAYER;
    if (eventName.startsWith('World') || eventName.startsWith('Block')) return Categories.WORLD;
    if (eventName.startsWith('Entity') || eventName.startsWith('Mob')) return Categories.ENTITY;
    return Categories.EVENT;
  }
  if (purpose !== '') return Categories.OTHER;
  if (type !== '') return Categories.OTHER;
  return Categories.UNKNOWN;
}
export function tryParseJson(text) {
  try {
    const value = JSON.parse(text);
    return { parsed: true, json: value };
  } catch {
    return { parsed: false, json: null };
  }
}
