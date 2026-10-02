# Native size audit (2026-10-02, `libxykell.so` 445,816 B pre-strip)

## Sections (size -A, top)
- `.text` 136,208 (code; exceptions live here — EventBus isolation needs them)
- `.dynstr` 53,689 (dynamic symbol names: fmt + libc++ + preloader refs)
- `.eh_frame` 31,968 + `.gcc_except_table` 7,764 (unwind; required)
- `.rodata` 8,716, `.dynsym` 10,416, `.rela.plt` 7,968, `.plt` 5,344
- `.debug_*`: **76 B** (MinSizeRel already drops debug info)
- Remainder (~165 KB): `.symtab`/`.strtab` static symbol tables — strip-safe.

## Object contributors (with static symtabs, largest first)
profile_manager 174K, config_store 123K, hud_model 99K, crash_guard 95K,
xykell.cpp 80K, theme 77K, json_min 66K, clickgui_model 48K, event_bus 38K,
menu 37K, hud 34K, version_adapter 33K. (Batch-2 systems dominate, as expected.)

## Decision
Strip release-facing artifacts at package time only (`llvm-strip` copy;
`build/` keeps symbols for diagnostics). Verified: entry
`PLGetModRegistration` survives; behavior identical (strip touches no code).
- Before: 445,816 B → after: **280,360 B** (-37%).
- Applied in `scripts/build/package-levipack.sh` and CI staging step.
- No other optimization: no dead code found (all objects linked into live
  paths); parser/writer and GUI/HUD contributions are the product, not bloat.
