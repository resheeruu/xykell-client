// Xykell Bedrock WebSocket observation lab. OBSERVATION ONLY: log what
// Minecraft sends; never reply with commands, never execute anything.
import { WebSocketServer } from 'ws';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { classify, tryParseJson } from './protocol.mjs';
import { makeRecord, createCaptureSink } from './logger.mjs';

const HOST = process.env.BEDROCK_WS_HOST ?? '127.0.0.1';
const PORT = Number(process.env.BEDROCK_WS_PORT ?? '8765');
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
console.log('Mode:\n  OBSERVATION ONLY');
console.log(`Captures: ${sink.file}`);
console.log('Waiting for Minecraft...');

const wss = new WebSocketServer({ host: HOST, port: PORT, maxPayload: 256 * 1024 });

wss.on('connection', (ws) => {
  const id = `conn-${nextId++}`;
  console.log(`[CONNECTED] connection=${id}`);
  ws.on('message', (data, isBinary) => {
    const raw = isBinary ? Buffer.from(data).toString('base64') : Buffer.from(data).toString('utf8');
    const record = makeRecord({
      timestamp: new Date().toISOString(),
      connectionId: id,
      direction: 'minecraft->xykell-lab',
      encoding: isBinary ? 'base64' : 'text',
      raw,
    });
    const { parsed, json } = !isBinary ? tryParseJson(record.raw) : { parsed: false, json: null };
    const category = !isBinary && parsed ? classify(json) : 'UNKNOWN';
    sink.write(record, { parsed, json });
    console.log('[MESSAGE]');
    console.log(`connection=${id}`);
    console.log(`bytes=${record.length}`);
    console.log(`classification=${category}`);
    console.log(`parsed=${parsed}`);
  });
  ws.on('close', () => console.log(`[DISCONNECTED] connection=${id}`));
  ws.on('error', (err) => console.log(`[ERROR] connection=${id} message=${err.message}`));
});
