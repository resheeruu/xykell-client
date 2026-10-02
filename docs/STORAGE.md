# Storage (targets + current)

Targets: APK < 20 MB, native minimal, repository < 15 MB.
Current (2026-10-02): repo ~9.4 MB, APK 9.1 MB, `.so` 545 KB unstripped.
Phone free: ~4.2–4.4 GB throughout (no SDK downloads on-device; CI builds APKs).

Policy: no giant deps without a size report; no duplicate registries/stores/
assets; build dirs, APKs, levipacks, Gradle caches never committed;
`llvm-strip` at package time only (symbols kept in `build/`).
