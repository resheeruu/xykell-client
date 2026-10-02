# Profile system

`Profile` + `ProfileManager` (`profile_manager.{h,cpp}`), schema-versioned JSON
(`schemaVersion: 1`) at `<root>/profiles/<name>.json`; active name in
`<root>/active.profile`.

- Content: module states, settings, HUD layout, GUI settings, theme,
  input binds, version metadata. Presets only — never implies support.
- Builtins: Default, PvP, Survival, Performance, Builder, Minimal
  (auto-created on first `setActive`).
- Ops: create (inherits Default), load, save, duplicate, rename, delete,
  reset, import/export. Default cannot be renamed/deleted. Names validated
  (`[A-Za-z0-9 _-]{1,64}`). Corrupt import/file rejected, existing untouched.
- Tests: `test_profile` (full CRUD + corrupt + import/export + active).
