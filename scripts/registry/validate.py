#!/usr/bin/env python3
"""Validate registry/features.json schema. Exit nonzero on any violation."""
import json
import sys
from pathlib import Path

STATUSES = {"SUPPORTED", "PARTIAL", "UNSUPPORTED", "BLOCKED",
            "RESEARCH_REQUIRED", "NOT_IMPLEMENTED",
            "DEVICE_LIMITED", "USER_VALIDATION_REQUIRED", "BACKEND_LIMITED",
            "PLATFORM_LIMITED", "REFERENCE_ONLY", "NOT_APPLICABLE"}
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
            "settings", "risk_level", "platforms", "version_constraints",
            "implementation", "capabilities", "versions", "sourceReferences",
            "evidence", "notes"}


def check_res_duplicates(root: Path) -> list:
    """Duplicate res/ names break MergeResources at build time, and only CI can
    run aapt2. Catch it here instead."""
    import collections
    import xml.etree.ElementTree as ET
    problems = []
    tags = ("string", "style", "color", "string-array", "integer", "bool",
            "dimen", "array", "plurals", "attr", "item")
    for f in sorted((root / "app" / "src" / "main" / "res").rglob("*.xml")):
        try:
            tree = ET.parse(f)
        except ET.ParseError as e:
            problems.append(f"{f.name}: XML parse error: {e}")
            continue
        counts = collections.Counter(
            el.get("name") for el in tree.getroot() if el.tag in tags and el.get("name")
        )
        for name, n in counts.items():
            if n > 1:
                rel = f.relative_to(root)
                problems.append(f"{rel}: <{name}> defined {n} times")
    return problems


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
        if f.get("risk_level") not in ("LOW", "MEDIUM", "HIGH"):
            errors.append(f"[{i}] bad risk_level {f.get('risk_level')}")
        for s in f.get("settings", []):
            if not isinstance(s, dict) or not {"key", "type", "default",
                                               "description"} <= set(s):
                errors.append(f"[{i}] bad setting shape in {f.get('id')}")
                break
            if s["type"] not in ("bool", "int", "float", "enum", "string", "color"):
                errors.append(f"[{i}] bad setting type {s['type']}")
        if not isinstance(f.get("versions"), list) or not isinstance(f.get("sourceReferences"), list):
            errors.append(f"[{i}] versions/sourceReferences must be lists")
    errors.extend(check_res_duplicates(
        Path(__file__).resolve().parent.parent.parent))
    if errors:
        print("\n".join(errors))
        return 1
    from collections import Counter
    print(f"registry OK: {len(feats)} features, "
          + str(dict(Counter(f['status'] for f in feats))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
