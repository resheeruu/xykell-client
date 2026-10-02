#!/usr/bin/env python3
"""Validate registry/features.json schema. Exit nonzero on any violation."""
import json
import sys
from pathlib import Path

STATUSES = {"SUPPORTED", "PARTIAL", "UNSUPPORTED", "BLOCKED",
            "RESEARCH_REQUIRED", "NOT_IMPLEMENTED"}
CAPS = {"NATIVE", "PACKET", "RENDER", "INPUT", "UI", "WORLD", "SCRIPT", "HYBRID"}
IMPLS = {"native", "packet", "script", "hybrid"}
REQUIRED = {"id", "category", "status", "implementation", "capabilities",
            "versions", "sourceReferences", "notes"}


def main() -> int:
    data = json.loads((Path(__file__).resolve().parent.parent.parent
                       / "registry" / "features.json").read_text())
    feats = data["features"]
    assert data["meta"]["count"] == len(feats), "meta.count mismatch"
    seen = set()
    errors = []
    for i, f in enumerate(feats):
        missing = REQUIRED - set(f)
        if missing:
            errors.append(f"[{i}] missing keys {sorted(missing)}")
        if f.get("id") in seen:
            errors.append(f"[{i}] duplicate id {f.get('id')}")
        seen.add(f.get("id"))
        if f.get("id") != f"{f.get('category')}.{f.get('id', '.').split('.', 1)[-1]}":
            errors.append(f"[{i}] id/category mismatch {f.get('id')}")
        if f.get("status") not in STATUSES:
            errors.append(f"[{i}] bad status {f.get('status')}")
        if f.get("implementation") not in IMPLS:
            errors.append(f"[{i}] bad implementation {f.get('implementation')}")
        if not set(f.get("capabilities", [])) <= CAPS or not f.get("capabilities"):
            errors.append(f"[{i}] bad capabilities {f.get('capabilities')}")
        if not isinstance(f.get("versions"), list) or not isinstance(f.get("sourceReferences"), list):
            errors.append(f"[{i}] versions/sourceReferences must be lists")
    if errors:
        print("\n".join(errors))
        return 1
    from collections import Counter
    print(f"registry OK: {len(feats)} features, "
          + str(dict(Counter(f['status'] for f in feats))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
