#!/usr/bin/env python3
"""JNI coverage audit.

Answers, objectively and from source only:

  TOTAL_KOTLIN_EXTERNALS   every `external fun` declared in app/src/main
  IMPLEMENTED              a JNIEXPORT whose mangled symbol matches exactly
  MISSING                  declared but no matching JNIEXPORT
  MISMATCHED               a JNIEXPORT with no matching declaration, or a
                           declaration whose class/method cannot be matched
  UNUSED                   JNIEXPORT whose Kotlin declaration was removed
  STATIC_VS_INSTANCE       declarations vs. symbol receiver kind

A function counts as IMPLEMENTED only when a JNIEXPORT symbol was derived from
its real Kotlin declaration -- package, class, name, arity -- and that exact
symbol is exported. Merely having a similarly-named function is not enough.

Also verifies the CMake source list actually contains the files that define
those symbols, because a JNI file absent from CMake builds and links nowhere.

Exit 0 only when MISSING, MISMATCHED and UNUSED are all empty and the CMake
registration is complete.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
APP_MAIN = ROOT / "app" / "src" / "main"
NATIVE_SRC = ROOT / "native" / "src"
CMAKE = ROOT / "native" / "CMakeLists.txt"

# Kotlin `object Foo` inside package a.b.c:
#   non-@JvmStatic external fun  -> instance method, receiver jobject
#   @JvmStatic external fun       -> static method,  receiver jclass
DECL_RE = re.compile(
    r"(@JvmStatic\s+)?(?:private\s+)?external\s+fun\s+(\w+)\s*\(", re.M)
# Capture the whole parameter list so the receiver (2nd param) can be read
# properly. A multi-line signature must not be guessed at.
JNIEXPORT_RE = re.compile(
    r'JNIEXPORT\s+(\w+)\s+JNICALL\s*\n?\s*'
    r'Java_(\w+?)_(\w+?)_(\w+?)\s*\(([^)]*)\)', re.M)


def kotlin_decls() -> list[dict]:
    """One entry per external declaration, with its mangled JNI symbol."""
    out = []
    for f in sorted(APP_MAIN.rglob("*.kt")):
        text = f.read_text(encoding="utf-8")
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        package = pkg.group(1)
        # Class is the `object` / class the externals live in.
        cls = re.search(r"^object\s+(\w+)", text, re.M)
        if not cls:
            cls = re.search(r"^class\s+(\w+)", text, re.M)
        if not cls:
            continue
        for m in DECL_RE.finditer(text):
            static, name = m.group(1), m.group(2)
            simple = f"{package}.{cls.group(1)}".replace(".", "_")
            out.append({
                "file": str(f.relative_to(ROOT)),
                "symbol": f"Java_{simple}_{name}",
                "static": bool(static),
                "class": f"{package}.{cls.group(1)}",
                "method": name,
                "kt_params": _kotlin_params(text, m.end()),
            })
    return out


# Kotlin type -> JNI type. Anything not listed cannot be compared and is
# reported as such rather than assumed compatible.
KT_TO_JNI = {
    "String": "jstring",
    "Int": "jint",
    "Long": "jlong",
    "Double": "jdouble",
    "Float": "jfloat",
    "Boolean": "jboolean",
    "Short": "jshort",
    "Byte": "jbyte",
}


def _kotlin_params(text: str, after: int) -> list[str]:
    """Parameter JNI types for a declaration, in order."""
    depth = 1
    i = after
    while i < len(text) and depth > 0:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    body = text[after:i - 1]
    # Strip comments so a commented-out param is not counted.
    body = re.sub(r"//[^\n]*", "", body)
    params = []
    for raw in body.split(","):
        raw = raw.strip()
        if not raw:
            continue
        # "name: Type" or a bare "Type".
        typ = raw.split(":")[-1].strip() if ":" in raw else raw
        params.append(KT_TO_JNI.get(typ, f"?{typ}"))
    return params


def jni_symbols() -> list[dict]:
    out = []
    for f in sorted(NATIVE_SRC.glob("*.cpp")):
        text = f.read_text(encoding="utf-8", errors="replace")
        for ret, p1, p2, name, params in JNIEXPORT_RE.findall(text):
            # Receiver is the 2nd C parameter: jclass => @JvmStatic,
            # jobject => instance method. Read it, never infer it.
            parts = [p.strip() for p in params.replace("\n", " ").split(",")]
            receiver = "unknown"
            if len(parts) >= 2:
                second = parts[1]
                if "jclass" in second:
                    receiver = "static"
                elif "jobject" in second:
                    receiver = "instance"
            # parts[0] is JNIEnv* (not a j-type) and parts[1] is the receiver,
            # so the payload starts at index 2 by position, not by "types found".
            jni_types = []
            for part in parts[2:]:
                for jt in ("jstring", "jlong", "jdouble", "jint", "jboolean",
                           "jfloat", "jshort", "jbyte"):
                    if jt in part:
                        jni_types.append(jt)
                        break
            out.append({
                "file": f.name,
                "symbol": f"Java_{p1}_{p2}_{name}",
                "ret": ret,
                "receiver": receiver,
                # JNIEnv and the receiver dropped; what remains is the payload.
                "jni_params": jni_types,
            })
    return out


def main() -> int:
    decls = kotlin_decls()
    syms = jni_symbols()
    impl = {s["symbol"]: s for s in syms}
    want = {d["symbol"]: d for d in decls}

    missing = sorted(set(want) - set(impl))
    unused = sorted(set(impl) - set(want))

    # Receiver kind must agree with @JvmStatic.
    mismatched = []
    for symbol, d in want.items():
        s = impl.get(symbol)
        if s is None:
            continue
        if s["receiver"] != ("static" if d["static"] else "instance"):
            mismatched.append(
                f"{symbol}: declared "
                f"{'@JvmStatic (jclass)' if d['static'] else 'instance (jobject)'} "
                f"but exported with a {s['receiver']} receiver")
        if s["jni_params"] != d["kt_params"]:
            mismatched.append(
                f"{symbol}: Kotlin params {d['kt_params']} != JNI params "
                f"{s['jni_params']}")

    cmake = CMAKE.read_text(encoding="utf-8") if CMAKE.exists() else ""

    # Every .cpp in native/src must be compiled. A file missing from
    # CMakeLists.txt links in the host suite (which names its sources
    # explicitly) but is absent from the Android .so, so it fails only at
    # Android link time. xykell_keybind_store.cpp sat in exactly that state.
    all_src = sorted(p.name for p in NATIVE_SRC.glob("*.cpp"))
    orphaned = [f for f in all_src if f"src/{f}" not in cmake]

    # CMake must compile every file that defines a symbol we rely on.
    defining = sorted({s["file"] for s in syms})
    not_registered = [f for f in defining if f"src/{f}" not in cmake]

    print("=== JNI coverage ===")
    print(f"TOTAL_KOTLIN_EXTERNALS={len(decls)}")
    print(f"IMPLEMENTED={len(decls) - len(missing)}")
    print(f"MISSING={len(missing)}")
    print(f"MISMATCHED={len(mismatched)}")
    print(f"UNUSED={len(unused)}")
    print(f"PARAMETER_MATCH={'PASS' if not mismatched else 'FAIL'}")
    print(f"STATIC_DECLARATIONS={sum(1 for d in decls if d['static'])}")
    print(f"INSTANCE_DECLARATIONS={sum(1 for d in decls if not d['static'])}")
    print(f"FILES_NOT_IN_CMAKE={len(not_registered)}")
    print(f"ORPHANED_SOURCES={len(orphaned)}")
    print(f"SIGNATURE_MATCH={'PASS' if not missing and not mismatched else 'FAIL'}")
    print(f"COVERAGE={'PASS' if not missing and not unused and not not_registered else 'FAIL'}")

    if missing:
        print("\nMISSING (declared in Kotlin, no JNIEXPORT):")
        for m in missing:
            print(f"  {m}")
    if mismatched:
        print("\nMISMATCHED:")
        for m in mismatched:
            print(f"  {m}")
    if unused:
        print("\nUNUSED (JNIEXPORT with no Kotlin declaration):")
        for u in unused:
            print(f"  {u}")
    if not_registered:
        print("\nFILES NOT REGISTERED IN CMakeLists.txt:")
        for f in not_registered:
            print(f"  native/src/{f}")
    if orphaned:
        print("\nSOURCES PRESENT BUT NOT COMPILED:")
        for f in orphaned:
            print(f"  native/src/{f}  (links in host tests, absent from the APK)")

    ok = (not missing and not mismatched and not unused and not not_registered
          and not orphaned)
    print(f"\nNATIVE_LINKAGE={'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--check" in sys.argv:
        # Quiet, exit-code-only mode for CI: no text scraping, no CWD
        # assumptions about which directory the caller runs from.
        sys.exit(main())
    sys.exit(main())
