# Module implementation status (machine truth: `registry/features.json`)

- 192 entries: 0 SUPPORTED (gameplay) / 4 PARTIAL (infra proofs) /
  181 RESEARCH_REQUIRED / 7 NOT_IMPLEMENTED. CI fails on evidence-less SUPPORTED.
- Real implementations: core lifecycle, version-adapter table, menu toggles,
  HUD proof, tap routing, gating, engines (pure), diagnostics formatting.
- Per-feature rows (requires/tests/device/evidence): run
  `python3 scripts/audit/full-feature-audit.py`.
- Policy: RESEARCH_REQUIRED → research → candidate → compile proof → runtime
  verification → SUPPORTED. Toggle existence never implies function.
