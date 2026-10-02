# Proxy design (RESEARCH_ONLY — no packets, no proxy)

Two candidate architectures (decision requires protocol evidence):
A) Bedrock → native packet API → Xykell modules → server (needs a packet
   API that does not exist in preloader 0.2.3 — currently impossible).
B) Bedrock → Xykell network layer → local proxy/relay → server (separate
   subsystem; needs RakNet/Bedrock protocol work; console/remote clients
   only via this path).

Rules (non-negotiable): native client works without proxy; no credential
interception, no auth bypass, no server attacks, no hidden traffic; all proxy
functionality user-initiated and inspectable. Respects GPL boundaries
(Nova/WClient patterns are inspiration only).

Status: RESEARCH_ONLY. No code, no protocol work started.
