# Version compatibility (canonical; `docs/BEDROCK-COMPATIBILITY.md` is a pointer here)

## States
SUPPORTED / PARTIAL / UNSUPPORTED / BLOCKED / RESEARCH_REQUIRED / NOT_IMPLEMENTED.
Unknown build → PARTIAL, never a fake verdict. Below Levi floor (≥1.21.80
policy) → UNSUPPORTED. Verified line (Levi v1.5.25 ⇒ 1.26.50) → SUPPORTED.

## Runtime source
No verified on-device MC version string yet — `VersionAdapter::check` runs on
`"unknown"` until Levi exposes the launched build (RESEARCH_REQUIRED, tracked
in `registry/features.json` via client.version_adapter notes). Levi's
`manifest.json → minecraft_versions` remains the enforcement point.

## Device baseline (Batch 4, read-only evidence)
Installed game on this phone: **Minecraft Bedrock 1.26.45.1** (arm64-v8a split
present), extracted 2026-10-02 from `base.apk`'s string pool via read-only
zipfile inspection (no modification, no credentials touched; `libminecraftpe`
itself lives in the installed lib dir, unreadable from Termux). This is the
test target — not a claim the adapter can yet detect it in-process.

## Levi line coverage (Batch 5, release-notes evidence)
- v1.5.17 (2026-08-31): **"Added Minecraft 1.26.45 support to built-in mods"**
  → the device's 1.26.45.1 sits on a Levi-supported line. No mismatch.
- v1.5.24: adds 1.26.50 inbuilt-mod support. v1.5.25 (pinned): touch + mod-menu
  integration improvements. Newer Levi keeps older MC lines working via
  version isolation; `minecraft_versions: []` (Xykell manifest) means no
  launcher-side gate — preloader loads us on any launched build.
- Adapter `knownGood`: `1.26.45`, `1.26.50` (exact-match, changelog-sourced).

## Per-version data (when the pipeline exists)
`client/compatibility/<build>/`: version, signatures, offsets, patterns,
source, confidence, feature, status. Provenance mandatory; never pasted from
other clients. Table format TBD at first signature derivation.

## Module reporting
ClickGUI/Mod Menu surfaces each module's state; launch logs print the
`X SUPPORTED / Y PARTIAL / Z RESEARCH_REQUIRED` summary (design; M2).
