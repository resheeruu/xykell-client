# Stage-9E subscribe errors (read-only capture analysis)

## 1. Minecraft version
1.26.52.3 (972605203), user's owned copy, cheat-enabled test world.

## 2–3. Captures (both read in full; 4 records each, 1494 bytes each)
- capture-2026-10-03T04-21-35-186Z.jsonl and capture-2026-10-03T04-25-06-738Z.jsonl.
- Each: 2 outbound subscribes (172B PlayerMessage, 174B PlayerTravelled,
  correct documented envelope, UUID requestIds) + 2 inbound ERRORs
  (187B each, ~9–65ms after subscribes). No other records. Captures
  unmodified.

## 4. Exact inbound error structure (identical shape, all 4 instances)
- direction minecraft->xykell-lab, text, parsed true.
- body: {statusCode: -2147418107, statusMessage (text, see §5)}.
- header: {messagePurpose: "error", requestId: <echo of one subscribe>,
  version: 17104896} — no messageType field.
- (Values summarized structurally; no sensitive content exists in these
  records — no tokens/credentials observed anywhere.)

## 5. The error text and correlation (PROVEN BY CAPTURE)
- statusMessage: "Encrypted session required".
- Error 1 requestId == PlayerMessage subscribe requestId; Error 2
  requestId == PlayerTravelled subscribe requestId — in BOTH sessions.
  The game parsed, routed, and individually rejected each subscribe.
- Deterministic across sessions (same code/message twice).

## 6. Error interpretation
- Meaning: the game requires an ENCRYPTED WebSocket session before
  honoring subscribes; our plaintext lab session was refused at the
  application layer. DOCUMENTED (see §7). This is a documented gate,
  not a malformed frame: correlation proves the frames parsed.
- version 17104896 in the error header is an observed game-side field
  whose exact meaning is UNKNOWN (recorded, not interpreted).

## 7. Documentation (no bypass attempted, none needed)
- Minecraft Wiki `/enableencryption` (Bedrock/Education, hidden cheat
  command): "Used to enable encryption for WebSocket connections...
  If the player has the encrypted WebSocket option turned on, the
  connected WebSocket server must execute this command" with publicKey
  + salt (+ cipher cfb/cfb128/cfb8). The server — not the player — runs
  it. (https://minecraft.wiki/w/Commands/enableencryption)
- mcwss (Sandertv, MIT): encrypted-websockets settings toggle exists in
 -game; `com.microsoft.minecraft.wsencrypt` subprotocol; library
  implements the encryption. (https://pkg.go.dev/github.com/sandertv/mcwss)
- Official `/wsserver` docs cover connect-only; no official subscribe/
  encryption semantics found — the mechanism above is community/wikidoc
  grade, treated as strong-but-not-official.

## 8. Version compatibility analysis
- No evidence the subscribe envelope changed: 1.26.52.3 parsed, routed,
  and semantically rejected our frames (correlation proves parseable
  format). REASONABLE INFERENCE: format remains valid; the blocker is
  the encryption gate, not the frame shape. Applicability of older
  community references to 1.26.52.3 otherwise UNKNOWN.
- Whether the test world had the encrypted-WS setting on: UNKNOWN
  (not recorded during the experiment — process gap for next run).

## 9. Encryption analysis (documented only)
- Path: server offers `com.microsoft.minecraft.wsencrypt`, runs
  enableencryption-style handshake with own keypair, then resubscribes.
  No MC-side certificates/credentials are needed by us; the keypair
  would be OURS, generated locally. No interception, no bypass — the
  game explicitly invites this flow via documented command + setting.
- NOT attempted this stage (research-only mandate).

## 10. Documented facts
(a) `/wsserver` = dial-out + cheats (official). (b) Subscribes are
per-event opt-ins (community). (c) Encrypted session can be required;
server-side enableencryption handshake is the documented remedy (wiki +
mcwss). (d) Our frames were well-formed enough to be routed + rejected
with requestId correlation (local capture).

## 11. Local evidence
Transport connect ×4 sessions total; 2 subscribes ×2 sessions; 2
correlated "Encrypted session required" errors ×2 sessions; 0 event
payloads ever received. Redaction: nothing to redact (no secret-like
keys in any record).

## 12. Inferences
The sole demonstrated blocker is the missing encrypted session
(REASONABLE INFERENCE → strong: named explicitly by the game twice,
remedy documented twice). Everything else (exact handshake bytes,
setting state, per-version quirks) is UNKNOWN.

## 13. Unknowns
Setting state during test; exact handshake frame sequence on 1.26.52.3;
whether plaintext subscribes ever succeed on this build; cipher default;
error-header version field meaning.

## 14. Security review
No credentials/tokens observed or handled; no auth flow touched (XAL
untouched); no injection/packets/servers; captures read-only;
redaction had nothing to trigger on (verified by scan, not assumed).

## 15. Decision gate: PATH A
Subscription rejected for a DOCUMENTED reason: missing encrypted session
("Encrypted session required", statusCode -2147418107), remedy
documented (`enableencryption` handshake + `wsencrypt` subprotocol).
Next: implement ONLY the documented encryption handshake in the lab
(new stage, still read-side; still no Exec) and re-run subscribes.
