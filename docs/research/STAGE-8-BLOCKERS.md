# Stage-8 blockers (formal record)

1. Runtime attachment surface: BLOCKED-ENV — MC 1.26.52.3 exports
   launch-only components; no attachable interface found by legitimate
   inspection (aapt manifest + exported-component analysis).
2. Game-state observation: BLOCKED-ENV — no exported providers, no state
   broadcasts, no documented external API on retail Android.
3. Protocol mapping: BLOCKED-ENV — no authorized account/server/lab.
4. Real-LAN visibility: BLOCKED-ENV (single device) — unchanged.
5. On-device lifecycle soak: BLOCKED-ENV (cannot install from Termux UID).
6. Native provider: LAB-GATED (unchanged) — no legitimate load path.
7. Authorized environment that would unblock 1–3: interactive lab with
   (a) user-driven MC (enable WebSocket/code-connection, join world),
   (b) owned test accounts only, (c) localhost capture with credential
   redaction at capture time, (d) optional self-hosted dedicated server.
   WebSocket localhost observation is the first experiment to run there.

Nothing in this stage weakens prior validations; launch/session/gate work
stands as the honest boundary.
