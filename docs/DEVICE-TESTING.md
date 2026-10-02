# Device testing (runbooks + results index)

- M1 native proof: `docs/M1-RESULTS.md` — runbook + build evidence.
  **Device result: PENDING** (no results returned yet).
- M1.5 launcher APK: install `XykellClient-debug` (CI artifact, sha256 in
  M1.5-RESULTS.md), open all 6 screens, confirm PLAY disabled + NOT WIRED
  texts, confirm no crash on back/home/rotate. **PENDING.**
- Batch 4 probe attempt (2026-10-02, Termux shell): `pm install` of the Levi
  APK fails with `SecurityException` (needs MANAGE_USERS/CREATE_USERS; app UID
  lacks it). Conclusion: install/launch/tap steps require the human.
  APK-manifest inspection (read-only) succeeded: MC 1.26.45.1 confirmed.
- Rule: BUILD VERIFIED ≠ RUNTIME VERIFIED. Every runbook entry needs observed
  output (photo/log/transcription) before PASS. Future runbooks live here.
