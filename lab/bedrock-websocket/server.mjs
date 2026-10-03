// Xykell Bedrock WebSocket observation lab. OBSERVATION ONLY: log what
// Minecraft sends; never reply with commands, never execute anything.
import { WebSocketServer } from 'ws';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
// (Inbound decoding lives in encryption.mjs decodeInbound; protocol.mjs
// owns classification used through it.)
import { makeRecord, createCaptureSink } from './logger.mjs';
import { isSubscribeOnlyEnabled, subscribeFrames, SUBSCRIBED_EVENTS } from './subscribe.mjs';
import {
  isEncryptedSessionEnabled,
  isHandshakeResponse,
  claimEstablishment,
  decodeInbound,
  WSENCRYPT_SUBPROTOCOL,
  buildEnableEncryptionCommand,
  buildCommandRequest,
  generateEphemeralIdentity,
  deriveSessionKey,
  createEncryptedSession,
} from './encryption.mjs';

const HOST = process.env.BEDROCK_WS_HOST ?? '127.0.0.1';
const PORT = Number(process.env.BEDROCK_WS_PORT ?? '8765');
const SUBSCRIBE_ONLY = isSubscribeOnlyEnabled();
const ENCRYPTED_SESSION = isEncryptedSessionEnabled();
const HERE = dirname(fileURLToPath(import.meta.url));
const CAPTURE_DIR = join(HERE, 'captures');

if (HOST === '0.0.0.0') {
  console.error('Refusing to bind 0.0.0.0: observation lab is localhost-only.');
  process.exit(2);
}

mkdirSync(CAPTURE_DIR, { recursive: true });
const sink = createCaptureSink(CAPTURE_DIR);
let nextId = 1;

console.log('=== XYKELL BEDROCK WEBSOCKET LAB ===');
console.log(`Host: ${HOST}`);
console.log(`Port: ${PORT}`);
console.log('Mode:\n  OBSERVATION ONLY' + (SUBSCRIBE_ONLY ? ' + SUBSCRIBE_ONLY (read-side events only)' : '') + (ENCRYPTED_SESSION ? ' + ENCRYPTED_SESSION (documented wsencrypt handshake)' : ''));
console.log(`Captures: ${sink.file}`);
console.log('Waiting for Minecraft...');

const wss = new WebSocketServer({
  host: HOST,
  port: PORT,
  maxPayload: 256 * 1024,
  // Offer the documented encryption subprotocol; the client selects it.
  // Plain mode never offers it (unchanged default behavior).
  handleProtocols: ENCRYPTED_SESSION ? () => WSENCRYPT_SUBPROTOCOL : undefined,
});

// Outbound capture (Stage 9H Fix 3): the semantic plaintext frame is
// recorded parsed; wire bytes are counted, never JSON-parsed. Ciphertext
// therefore can never reach JSON.parse through this path.
function sendOutbound(ws, id, sinkRef, wireBytes, plaintextJson, note) {
  ws.send(wireBytes);
  const wireLength = Buffer.from(wireBytes).length;
  sinkRef.write(
    makeRecord({
      timestamp: new Date().toISOString(),
      connectionId: id,
      direction: 'xykell-lab->minecraft',
      encoding: 'text',
      raw: JSON.stringify(plaintextJson),
    }),
    { parsed: true, json: plaintextJson },
  );
  console.log(`[${note}] connection=${id} bytes=${wireLength}`);
}

wss.on('connection', (ws) => {
  const id = `conn-${nextId++}`;
  console.log(`[CONNECTED] connection=${id}`);
  // Per-connection encrypted-handshake state. Private key never leaves
  // this closure; never logged, never persisted.
  const hs = { session: null, handshakeRequestId: null, identity: null };
  const sendSubscribeSet = () => {
    for (const frame of subscribeFrames()) {
      const plaintext = JSON.parse(frame); // our own built frame — safe
      const out = hs.session ? hs.session.encrypt(Buffer.from(frame)) : frame;
      sendOutbound(ws, id, sink, out, plaintext, 'SUBSCRIBE');
    }
    console.log(`[SUBSCRIBED] connection=${id} events=${SUBSCRIBED_EVENTS.join(',')}`);
  };
  if (ENCRYPTED_SESSION) {
    // Documented handshake first: enableencryption with our ephemeral
    // public key + salt. Subscribes go out ONLY after session established.
    generateEphemeralIdentity()
      .then((identity) => {
        hs.identity = identity;
        const cmd = buildEnableEncryptionCommand(identity.publicDer, identity.salt);
        hs.handshakeRequestId = `hs-${id}`;
        const frame = buildCommandRequest(cmd, hs.handshakeRequestId);
        sendOutbound(ws, id, sink, frame, JSON.parse(frame), 'HANDSHAKE');
        console.log(`[HANDSHAKE] connection=${id} state=awaiting-key`);
      })
      .catch((err) => console.log(`[ERROR] connection=${id} message=${err.message}`));
  } else if (SUBSCRIBE_ONLY) {
    // Read-side only: documented subscribe frames for the allowlisted
    // events. No Exec, no commands, no other frames exist in this lab.
    sendSubscribeSet();
  }
  ws.on('message', async (data, isBinary) => {
    // Single decode choke point (Fixes 2+3): decrypt-first when a session
    // exists (binary AND text), parse only the result, DECRYPTION_ERROR
    // otherwise. Ciphertext never reaches JSON.parse.
    const decoded = decodeInbound(data, isBinary, hs.session);
    const record = makeRecord({
      timestamp: new Date().toISOString(),
      connectionId: id,
      direction: 'minecraft->xykell-lab',
      encoding: decoded.encoding,
      raw: decoded.raw,
    });
    const { parsed, json, category, wasEncrypted } = decoded;
    sink.write(record, { parsed, json });
    // Single-establishment guard (Fix 1): only the first matching key
    // frame may claim establishment; duplicates are ignored synchronously.
    if (
      ENCRYPTED_SESSION &&
      hs.identity &&
      parsed &&
      isHandshakeResponse(json, hs.handshakeRequestId) &&
      claimEstablishment(hs)
    ) {
      try {
        const clientDer = Buffer.from(json.body.publicKey, 'base64');
        const key = await deriveSessionKey(hs.identity.privateKey, clientDer, hs.identity.salt);
        hs.session = createEncryptedSession(key);
        // hs.identity holds the only private-key reference; drop it now
        // that the session key is derived (cleanup on scope exit).
        hs.identity = null;
        console.log(`[HANDSHAKE] connection=${id} state=established`);
        // Subscribes go out ONLY after session establishment, exactly the
        // existing allowlisted set (read-side events, no Exec).
        sendSubscribeSet();
      } catch (err) {
        // Derive failed: release the claim so a later key frame may retry.
        // Success path keeps the claim (exactly-once establishment).
        hs.establishing = false;
        console.log(`[HANDSHAKE-FAIL] connection=${id} message=${err.message}`);
      }
    }
    console.log('[MESSAGE]');
    console.log(`connection=${id}`);
    console.log(`bytes=${record.length}`);
    console.log(`classification=${category}`);
    console.log(`parsed=${parsed}`);
    if (wasEncrypted) console.log(`encrypted=true`);
  });
  ws.on('close', () => console.log(`[DISCONNECTED] connection=${id}`));
  ws.on('error', (err) => console.log(`[ERROR] connection=${id} message=${err.message}`));
});
