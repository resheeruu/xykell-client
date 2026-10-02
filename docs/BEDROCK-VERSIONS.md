# Bedrock versions

- Tracked at research time: 1.26.x line (e.g. 1.26.23.1, protocol 975); Flarial Android 1.2.x targets 1.26.30/1.26.30.5; Atlas V2 cites 26.44; LeviLaunchroid rejects < 1.21.80.
- Bedrock ships monthly-ish; GDK runtime for builds after 1.21.120 (per Flarial launcher docs).
- Rule: never hard-code one version. `VersionAdapter` + per-release compat data in `client/compatibility/`. Unsupported → warn + disable module with message, never crash.
- Re-check latest Bedrock + protocol before any native/signature work.
