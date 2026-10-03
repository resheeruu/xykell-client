# Stage-9D subscribe results (lab mechanics proven; Minecraft pending)

1. Objective: test whether subscribed read-side events elicit traffic.
2. Minecraft version: 1.26.52.3 (unchanged target).
3. Subscription source: mcwss (Sandertv, MIT) + sanand0 protocol
   reference (two independent community sources); official docs cover
   connect-only. Envelope: header{version:1, requestId:UUID,
   messageType:"commandRequest", messagePurpose:"subscribe"} +
   body{eventName}; one event per frame. Tested upstream on 1.16–1.18;
   applicability to 1.26.52.3/Android UNKNOWN until experiment.
4. Subscribed events: PlayerMessage, PlayerTravelled (allowlisted, refused
   all others; no Exec/command builder exists — test-enforced).
5. Lab changes (lab/ only): subscribe.mjs (frame builder + allowlist),
   server.mjs SUBSCRIBE_ONLY mode (default off; outbound frames captured
   with direction xy kell-lab->minecraft), test/subscribe.test.mjs,
   README + package.json test script. Production untouched.
6. Test results: 17/17 PASS (11 protocol + 6 subscribe). Live
   self-connect verified exactly 2 correct frames on connect.
7. Manual procedure: SUBSCRIBE_ONLY=1 npm run start → /wsserver
   ws://127.0.0.1:8765 → chat once → move/stop/move → wait → watch
   [MESSAGE] lines (see README).
8. Capture filename: PENDING (no manual run yet).
9. Message count: 0 Minecraft messages to date.
10. Observed structures: none yet (synthetic only).
11. Capability matrix: UNCHANGED — no Minecraft evidence, no promotions.
    Subscribe-envelope format: VERIFIED against two sources + lab-tested
    mechanically (not a game-state claim).
12. Security review: read-side only preserved (test-enforced no-Exec);
    capture redaction unchanged; no credentials handled.
13. Limitations: version applicability unknown; subscription
    acknowledgement behavior unknown; event payload shapes unverified.
14. Decision: awaiting manual experiment; adapter work remains STOPPED.
