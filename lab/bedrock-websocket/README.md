# Xykell Bedrock WebSocket Lab (Stage 9A — observation only)

Isolated laboratory for the documented Minecraft Bedrock `/wsserver`
code-connection feature. NOT production code; nothing here is wired into
the Xykell runtime.

## Run

```sh
cd lab/bedrock-websocket
npm install   # one dependency: ws
npm test      # synthetic protocol/unit tests (node:test, deterministic)
npm run start # HOST=127.0.0.1 PORT=8765 (override via BEDROCK_WS_HOST/PORT)
```

## Manual Minecraft experiment (user-performed, authorized test world)

1. `npm run start` (localhost only; refuses `0.0.0.0`).
2. Launch the owned Minecraft Bedrock copy; open an owned/test world.
3. Enable cheats for that world (required by `/wsserver` per Microsoft docs).
4. Run `/wsserver ws://127.0.0.1:8765` in game chat.
5. Observe lab output; perform small controlled actions; record captures.
6. Do NOT infer game-state semantics until payloads are understood
   (CONNECTED + 0 MESSAGES proves transport only).

Captures land in `captures/*.jsonl` with secret-like values redacted.
`captures/` is git-ignored (never commit raw observations).
