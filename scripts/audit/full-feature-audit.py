#!/usr/bin/env python3
"""Full feature audit (§41). Fails on fake SUPPORTED entries: SUPPORTED status
requires a non-empty evidence string. Read-only; prints totals + per-feature
rows (id, status, requires, implementation, tests, device, evidence)."""
import json
import sys
from collections import Counter
from pathlib import Path

# Registry id -> unit test suites covering it (host). Everything else: none.
COVERAGE = {
    "client.core": ["test_core", "test_manager"],
    "client.version_adapter": ["test_adapter"],
    "client.config_store": ["test_config"],
    "client.profile_manager": ["test_profile", "test_hud_render"],
    "client.crash_guard": ["test_crash"],
    "hud.touch_indicators": ["test_input_router", "test_hud_render"],
    "hud.watermark": ["test_hud_render"],
}

DEVICE_VERIFIED = set()  # populated only by DEVICE-TESTING.md evidence


def main() -> int:
    root = Path(__file__).resolve().parent.parent.parent
    data = json.loads((root / "registry" / "features.json").read_text())
    feats = data["features"]
    counts = Counter(f["status"] for f in feats)
    fake = [f["id"] for f in feats
            if f["status"] == "SUPPORTED" and not f.get("evidence")]
    print(f"TOTAL FEATURES: {len(feats)}")
    for k in ("SUPPORTED", "PARTIAL", "RESEARCH_REQUIRED", "BLOCKED",
              "NOT_IMPLEMENTED"):
        print(f"{k}: {counts.get(k, 0)}")
    print("QUARANTINED: 0 (runtime state, see CrashGuard)")
    print()
    print("id | status | requirements | implementation | tests | device | evidence")
    for f in feats:
        tests = ",".join(COVERAGE.get(f["id"], ["none"]))
        dev = "YES" if f["id"] in DEVICE_VERIFIED else "NO"
        print(f"{f['id']} | {f['status']} | {','.join(f['requires'])} | "
              f"{f['implementation']} | {tests} | {dev} | {f['evidence']}")
    if fake:
        print("\nFAKE SUPPORTED (no evidence):")
        print("\n".join(fake))
        return 1
    print("\nNo fake SUPPORTED entries.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
