#!/usr/bin/env python3
"""Generate docs/MODULE-CATALOG.md from registry/features.json (single source).
Run after generate.py. Never hand-edit the catalog."""
import json
from collections import Counter
from pathlib import Path


def main() -> None:
    root = Path(__file__).resolve().parent.parent.parent
    data = json.loads((root / "registry" / "features.json").read_text())
    feats = data["features"]
    counts = Counter(f["status"] for f in feats)
    lines = ["# Xykell module catalog",
             "",
             f"Generated from `registry/features.json` ({len(feats)} entries).",
             "Statuses are audit truth, not marketing: a toggle existing never",
             "implies function. See `docs/RUNTIME-CAPABILITIES.md` for the gate.",
             "",
             "## Totals",
             ""]
    for k in ("SUPPORTED", "PARTIAL", "BLOCKED", "RESEARCH_REQUIRED",
              "INCOMPATIBLE", "NOT_IMPLEMENTED"):
        lines.append(f"- {k}: {counts.get(k, 0)}")
    lines += ["", "## By category", ""]
    bycat = {}
    for f in feats:
        bycat.setdefault(f["category"], []).append(f)
    for cat in sorted(bycat):
        lines.append(f"### {cat} ({len(bycat[cat])})")
        lines.append("")
        for f in bycat[cat]:
            req = ",".join(f["requires"]) or "-"
            ev = f["evidence"] or "no runtime evidence"
            lines.append(f"- `{f['id']}` — {f['name']}: **{f['status']}** "
                         f"(requires {req}; evidence: {ev})")
        lines.append("")
    (root / "docs" / "MODULE-CATALOG.md").write_text("\n".join(lines))
    print(f"catalog: {len(feats)} entries")


if __name__ == "__main__":
    main()
