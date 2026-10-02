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

## Per-version data (when the pipeline exists)
`client/compatibility/<build>/`: version, signatures, offsets, patterns,
source, confidence, feature, status. Provenance mandatory; never pasted from
other clients. Table format TBD at first signature derivation.

## Module reporting
ClickGUI/Mod Menu surfaces each module's state; launch logs print the
`X SUPPORTED / Y PARTIAL / Z RESEARCH_REQUIRED` summary (design; M2).
