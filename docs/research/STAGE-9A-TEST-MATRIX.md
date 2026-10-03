# Stage-9A test matrix (lab/bedrock-websocket)

Synthetic (executed 2026-10-03, phone, node:test — 11/11 PASS):
server starts PASS; localhost connection PASS; malformed frame PASS
(preserved, UNKNOWN); JSON frame PASS; non-JSON frame PASS (base64);
disconnect PASS; repeated connection PASS (conn-id increments);
large frame PASS (256KB cap + 64KB persist bound, unit-asserted);
secret-like field PASS (redacted, structure kept); classification PASS
(conservative UNKNOWN default); capture persistence PASS (redacted JSONL).

Live self-connect (synthetic client, 2026-10-03): PASS — connect/message/
disconnect logged; CHAT classification on PlayerMessage-shaped envelope;
malformed + binary preserved; token value redacted in capture.

Minecraft (manual, user-performed — NOT DONE):
connection OBSERVE; message RECORD; movement/chat/command/world-action
comparisons PENDING. Do not mark PASS until performed.
