# Compatibility (pointer)

Canonical: `docs/VERSION-COMPATIBILITY.md` (adapter, states, device baseline,
Levi line coverage). Compatibility enforcement lives in
`VersionAdapter::check` + `canEnable` gate + per-module `requires`.
