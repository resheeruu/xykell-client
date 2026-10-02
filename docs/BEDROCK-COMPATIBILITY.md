# Bedrock compatibility

## 1. Rule
Never hard-code one Bedrock version. Every native feature declares the builds it supports; anything else fails closed with a message, never a crash.

## 2. VersionAdapter
- Detects running build (version code, ABI, protocol) at attach time.
- Looks up `client/compatibility/` data: supported builds, per-feature availability, known-broken builds.
- Unsupported → core refuses attach for native modules; launcher features (version mgmt, content) keep working.
- Partial support → loads only the compatible subset; each skipped module logs `unavailable on this Minecraft version (build X)`.

## 3. Known baseline (re-check before native work — Bedrock churns monthly)
- 1.26.x line current at research (e.g. 1.26.23.1, protocol 975; Flarial Android targets 1.26.30/1.26.30.5).
- GDK runtime for builds after 1.21.120 (launcher must handle both paths).
- LeviLaunchroid policy rejects < 1.21.80 — Xykell M1 targets the current stable at build time, floor TBD after on-device check.

## 4. Data layout (to be created with core)
`client/compatibility/<build>/`: symbols/signatures, feature flags, tested-device notes. Provenance recorded per entry. No proprietary dumps.

## 5. Update drill
New Bedrock release → detect → mark affected modules unavailable → update compat data through our pipeline → test on isolated version → ship signed update with checksum. Users are never stranded on a crashing build.
