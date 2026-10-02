# Security audit (Batch 4 — new runtime-discovery code)

Scope: `runtime_probe`, `gui_controller` availability, engines, registry
`requires`, APK-manifest inspection scripts (diagnostic only, not shipped).

- Arbitrary memory writes: none (no hooks/patches installed anywhere).
- Unsafe/null derefs: probe uses static data; gate checks null via `find()`;
  GUI availability null-checks descriptors; HUD renderer null-checks theme/modules.
- Unchecked casts: `static_cast<int/float>` on JSON numbers only after
  `isNumber()`; version compare guarded by parse success.
- Races: touch callback mutates atomics (`gTaps`, `gHudEnabled`) and
  router/GUI state — single-threaded game callbacks assumed; noted as
  assumption (no threads created by Xykell).
- Filesystem traversal: profile names validated (`[A-Za-z0-9 _-]{1,64}`,
  rejects `.`/`..`); all paths rooted at caller-supplied dirs.
- Deserialization: strict JSON parser (rejects trailing garbage, bad
  escapes, control chars); corrupt files → backup + defaults, never applied.
- Credentials: nothing reads accounts/tokens; manifest scan touched only
  `versionName`/`package` strings; no auth APIs referenced anywhere.
- Network/telemetry/RCE/persistence: none exist in the codebase
  (`grep` for socket/http/url shows only docs + license URLs).
- Fail-closed: unknown version → PARTIAL; missing caps → gate refuses;
  corrupt state → safe mode; throwing provider → "--".

Result: no findings. Next audit when hooks/packets/network code appears.
