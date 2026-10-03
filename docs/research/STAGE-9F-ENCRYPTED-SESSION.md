# Stage-9F encrypted session (lab mechanics proven; Minecraft pending)

1. Objective: test whether the documented encrypted handshake unblocks
   the 9E "Encrypted session required" rejection.
2. Minecraft version: 1.26.52.3 (unchanged target).
3. Protocol sources: mcwss encryption.go/server.go/player.go +
   protocol/command/enable_encryption.go (Sandertv, MIT) + wiki
   `/enableencryption` + `wsencrypt` subprotocol constant. Verified
   sequence: offer subprotocol → ephemeral P-384 keypair + 16B salt →
   `enableencryption "<b64url DER>" "<b64url salt>"` commandRequest →
   client publicKey in response → ECDH x → SHA256(salt+secret) →
   AES-256-CFB8 both ways, IVs = key[:16].
4. Handshake design: encryption.mjs (ephemeral identity, builders,
   predicate, session transforms); server.mjs offers subprotocol only in
   encrypted mode, sends handshake first, sends subscribes ONLY after
   establishment, decrypts inbound before parsing. Plain/SUBSCRIBE_ONLY
   paths byte-identical to before.
5. Key management: webcrypto P-384 generated per connection in memory;
   CryptoKey non-serializable (test-asserted); never logged/persisted;
   dropped after key derivation; salt is public handshake material.
6. Test results: 25/25 PASS (11 protocol + 6 subscribe + 8 encryption:
   mode default-off, subprotocol exact, ephemeral uniqueness, privkey
   non-serializability, command format/validation, predicate shape,
   CFB8 roundtrip/streaming, single-command construction site).
7. Manual experiment: NOT YET RUN (see README procedure).
8. Encryption-session result: PENDING.
9. Subscription result: PENDING.
10. Event result: PENDING.
11. Capture filename: none yet.
12. Capability matrix: UNCHANGED — no promotions without Minecraft
    evidence (capabilities doc stands as written).
13. Security review: standard platform crypto only (webcrypto + AES-CFB8);
    no primitives implemented; no validation weakened; no static keys;
    no secrets logged/persisted/committed; no Exec beyond the single
    documented handshake command (source-asserted); no production code
    touched.
14. Known limitations: handshake verified mechanically + live against a
    synthetic peer (subprotocol negotiated, handshake-first ordering,
    no subscribes pre-establishment); MC 1.26.52.3 acceptance UNKNOWN
    until manual test; cipher/frame details beyond mcwss unverified.
15. Decision gate: lab READY for manual test; adapter work remains
    STOPPED; if encrypted session succeeds and events appear, STOP and
    analyze envelopes first (no adapter yet).

Live self-check evidence (synthetic peer, 2026-10-03): PROTO negotiated
`com.microsoft.minecraft.wsencrypt`; first frame = enableencryption
commandRequest (359B); zero SUBSCRIBE lines before establishment.
