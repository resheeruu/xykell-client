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

## SUBSCRIBE_ONLY mode (Stage 9D, read-side only)

Default remains pure observation (server never sends). With
`SUBSCRIBE_ONLY=1`, the server sends exactly two documented subscribe
frames on connect — `PlayerMessage` and `PlayerTravelled` — and nothing
else. No Exec, no commands, no other frames exist in this lab.

```sh
SUBSCRIBE_ONLY=1 npm run start
```

## ENCRYPTED_SESSION mode (Stage 9F, documented handshake only)

After Stage 9E proved plaintext subscribes are rejected with "Encrypted
session required", the lab can perform ONLY the documented encryption
handshake: offer `com.microsoft.minecraft.wsencrypt`, generate an
ephemeral local P-384 keypair + 16-byte salt, send ONE `enableencryption`
command request, derive the session key from the client's public key,
then send the existing two subscribes over the encrypted session.
Private keys never leave memory, are never logged or persisted.

```sh
SUBSCRIBE_ONLY=1 ENCRYPTED_SESSION=1 npm run start
```

Manual test: `/wsserver ws://127.0.0.1:8765` in the owned 1.26.52.3 test
world, then chat once → move/stop/move → wait 10s. Watch for
[HANDSHAKE] established (exactly once) → [SUBSCRIBE] ×2 →
[MESSAGE] lines (with `encrypted=true` once the session is up).

## Manual Minecraft experiment (user-performed, authorized test world)

1. `SUBSCRIBE_ONLY=1 npm run start` (localhost only; refuses `0.0.0.0`).
2. Launch the owned Minecraft Bedrock copy; open an owned/test world.
3. Enable cheats for that world (required by `/wsserver`).
4. Run `/wsserver ws://127.0.0.1:8765` in game chat.
5. After `[CONNECTED]` + `[SUBSCRIBED]`, perform, pausing between steps:
   send one chat message → move several times → stop → move again → wait.
6. Watch for `[MESSAGE]` lines with `classification=CHAT` / `PLAYER`.
7. Do NOT infer game-state semantics until payloads are understood
   (CONNECTED + 0 MESSAGES proves transport only).

Captures land in `captures/*.jsonl` with secret-like values redacted.
`captures/` is git-ignored (never commit raw observations).
