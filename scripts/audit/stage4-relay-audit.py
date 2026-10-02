#!/usr/bin/env python3
"""Stage-4 relay structural audit (HARD GATE).

Verifies the relay/provider implementation is structurally incapable of
gameplay actions: no transmit/mutate/forge/inject APIs in the Stage-4
substrate sources. Scoped to the substrate files only -- forbidden words
elsewhere (docs, unrelated code) do not fail this gate.

Exit 0 = PASS, 1 = FAIL with offending file:line.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCOPED = [
    ROOT / "native/include/xykell/runtime_provider.h",
    ROOT / "native/src/xykell_runtime_provider.cpp",
    ROOT / "tests/unit/test_runtime_provider.cpp",
    # Stage-5 JNI/Kotlin boundary: status-only surface, same gate.
    ROOT / "app/src/main/cpp/bridge.cpp",
    ROOT / "app/src/main/java/dev/xykell/client/runtime/RuntimeStatus.kt",
]

# Case-insensitive substrings that must never appear in scoped files.
FORBIDDEN = [
    "sendmove", "sendattack", "sendinteract", "sendinventoryaction",
    "sendchatcommand", "sendinput", "injectpacket", "forgepacket",
    "writegamepacket", "mov player", "moveplayer", "attack", "gameplaypacket",
    "inject", "forge", "sendpacket", "writepacket", "transmit",
]

# Allowed: the words "socket", "send(" etc. are not in the list on purpose:
# the gate targets gameplay-action-shaped APIs, and Stage-4 code opens no
# sockets at all (verified separately by searching for socket APIs).
SOCKET_APIS = ["socket(", "connect(", "send(", "recv(", "bind(", "listen("]


def code_only(text: str) -> str:
    # Strip block comments, then line comments. The gate polices code, not
    # prose: rule statements themselves name the forbidden APIs.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def main() -> int:
    failures = []
    for path in SCOPED:
        if not path.is_file():
            return f"MISSING SCOPED FILE: {path}"
        low = code_only(path.read_text()).lower()
        for word in FORBIDDEN:
            for m in re.finditer(re.escape(word), low):
                line = low.count("\n", 0, m.start()) + 1
                failures.append(f"{path.name}:{line}: forbidden '{word}'")
        # Stage-4 provider must not open sockets (in-memory only).
        if "test_" not in path.name:
            for api in SOCKET_APIS:
                if api in low:
                    failures.append(f"{path.name}: socket API '{api}' present")
    # Positive checks: the boundary must be explicit, not accidental.
    header = (ROOT / "native/include/xykell/runtime_provider.h").read_text()
    for marker in ["RuntimeProvider", "SyntheticRelayProvider",
                   "NativeProviderStub", "UNAVAILABLE"]:
        if marker not in header:
            failures.append(f"header: expected marker '{marker}' absent")
    if failures:
        print("STAGE4-RELAY-AUDIT: FAIL")
        for f in failures:
            print("  " + f)
        return 1
    print(f"STAGE4-RELAY-AUDIT: PASS ({len(SCOPED)} scoped files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
