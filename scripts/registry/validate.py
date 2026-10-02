#!/usr/bin/env python3
"""Validate registry/features.json schema. Exit nonzero on any violation."""
import json
import sys
from pathlib import Path

STATUSES = {"SUPPORTED", "PARTIAL", "UNSUPPORTED", "BLOCKED",
            "RESEARCH_REQUIRED", "NOT_IMPLEMENTED"}
CAPS = {"NATIVE", "PACKET", "RENDER", "INPUT", "UI", "WORLD", "SCRIPT", "HYBRID"}
IMPLS = {"native", "packet", "script", "hybrid"}
CATEGORIES = {"CLIENT", "HUD", "PERFORMANCE", "VISUAL", "PLAYER", "MOVEMENT",
              "COMBAT", "WORLD", "AUTOMATION", "NETWORK", "PROXY",
              "SCRIPTING", "SERVER", "MISC", "LAUNCHER"}
KNOWN_CAPS = {"LIFECYCLE", "SHUTDOWN", "CONFIG_DIRS", "INPUT_TRANSPORT",
              "INPUT_SEMANTICS", "MODMENU", "OVERLAY_DELIVERY", "FRAME",
              "PLAYER", "ENTITY", "WORLD", "CAMERA", "RENDER", "PACKET",
              "VERSION_STRING", "VERSION_POLICY", "CONFIG_STORE",
              "PROFILE_STORE", "CRASHGUARD", "SCRIPTING", "PROXY"}
REQUIRED = {"id", "name", "category", "description", "status", "requires",
            "settings", "platforms", "version_constraints", "implementation",
            "capabilities", "versions", "sourceReferences", "evidence", "notes"}


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
        import re
        if not re.fullmatch(r"xykell\.[a-z]+\.[a-z0-9_]+", f.get("id", "")):
            errors.append(f"[{i}] bad id format {f.get('id')}")
        if f.get("category") not in CATEGORIES:
            errors.append(f"[{i}] bad category {f.get('category')}")
        else:
            want = "xykell." + f["category"].lower() + "."
            if not f.get("id", "").startswith(want):
                errors.append(f"[{i}] id/category mismatch {f.get('id')}")
        if f.get("status") not in STATUSES:
            errors.append(f"[{i}] bad status {f.get('status')}")
        if f.get("implementation") not in IMPLS:
            errors.append(f"[{i}] bad implementation {f.get('implementation')}")
        if not set(f.get("capabilities", [])) <= CAPS or not f.get("capabilities"):
            errors.append(f"[{i}] bad capabilities {f.get('capabilities')}")
        req = f.get("requires", None)
        if not isinstance(req, list) or not set(req) <= KNOWN_CAPS:
            errors.append(f"[{i}] bad requires {req}")
        for lk in ("name", "description", "implementation", "evidence", "notes"):
            if not isinstance(f.get(lk), str):
                errors.append(f"[{i}] {lk} must be a string")
        if not isinstance(f.get("settings"), list) or not isinstance(
                f.get("platforms"), list) or not isinstance(
                f.get("version_constraints"), list):
            errors.append(f"[{i}] settings/platforms/version_constraints must be lists")
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
