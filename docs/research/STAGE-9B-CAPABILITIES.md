# Stage-9B/C capabilities (DOCUMENTED vs LOCALLY OBSERVED vs IMPLEMENTED)

No capability is PROVEN locally. DOCUMENTED = reputable external source;
LOCALLY OBSERVED = our lab/device evidence; IMPLEMENTED = Xykell code.

| Capability | Evidence | Status |
|---|---|---|
| WebSocket connection | terminal CONNECTED x2 | LOCALLY OBSERVED (transport) |
| Message reception | 0 messages in 2 captures | NOT OBSERVED |
| Command response | mcwss Exec model | DOCUMENTED only |
| Player position/travel | mcwss PlayerTravelled/Transform | DOCUMENTED only |
| Player health | — | UNKNOWN (no source) |
| Player identity | mcwss Name()/EduInformation | DOCUMENTED only |
| World information | mcwss WorldLoaded/Generated | DOCUMENTED only |
| Entity information | mcwss Mob* handlers | DOCUMENTED only |
| Inventory | mcwss Item* handlers | DOCUMENTED only |
| Block information | mcwss BlockBroken/Placed | DOCUMENTED only |
| Chat events | mcwss PlayerMessage | DOCUMENTED only |
| Generic game events | mcwss ~40 OnXxx handlers | DOCUMENTED only |

Stage 9D note (lab mechanics only, no Minecraft evidence): the subscribe
envelope + PlayerMessage/PlayerTravelled allowlist mechanism is VERIFIED
against two independent community sources and lab-tested (17/17 incl.
live self-connect emitting exactly 2 correct frames). No capability above
changes status — verification covers the request format, not game data.

Stage 9E note (local evidence, no promotions): both subscribes were
parsed, routed, and rejected 1:1 with "Encrypted session required"
(statusCode -2147418107), deterministically across 2 sessions. New status:
rejection mechanism LOCALLY OBSERVED; game-state payloads still NOT
OBSERVED across the board. Next gate is the documented encrypted-session
handshake (research: STAGE-9E-SUBSCRIBE-ERRORS.md).

Xykell IMPLEMENTED capabilities depending on any of the above: NONE.
Rule preserved: DOCUMENTED ≠ OBSERVED ≠ IMPLEMENTED. Subscription-gated
delivery (Stage-9C) explains the all-NOT-OBSERVED column without
contradicting the DOCUMENTED column.
