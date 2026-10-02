# Roadmap

- [x] Phase 0 — ecosystem research (Lunar/W/Nova/Atlas/Flarial/Levi/Apollon + Lunar Proxy/WClient/BedrockTools depth).
- [x] Phase 1 — repo skeleton + architecture docs. No fake code.
- [x] M1 — native proof: Levi → `.so` → version detect → config → Mod Menu → input → overlay → enable/disable. Build PASS; **device PENDING**.
- [x] M1.5 — launcher shell (6 screens, NOT WIRED) + CI APK green (`XykellClient-debug`, sha256 recorded). **Device PENDING.**
- [x] Registry (192 modules, honest statuses) + EventBus + ModuleManager + host unit tests (4/4 PASS).
- [ ] **Next: native Config/Profile store → CrashGuard → ClickGUI → HUD engine**
  (dependency graph in `docs/GAP-ANALYSIS.md` §12).
- [ ] M3 — profiles (all 11), touchbind/gesture system, settings sync.
- [ ] M4 — visual QoL batch (Zoom/Fullbright/FOV/crosshair), each version-gated.
- [ ] M5 — network engine (diagnostics set) + packet-event monitoring.
- [ ] M6 — scripting sandbox (design → minimal runtime).
- [ ] Later — cosmetics (original), Xykell Manager integrations via Levi, advanced modules (each ADVANCED/EXPERIMENTAL, OFF default, separate review).
- Never: fake toggles; any malware-adjacent capability (§16/§20 prohibitions stand).
