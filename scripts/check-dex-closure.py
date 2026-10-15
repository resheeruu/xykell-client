#!/usr/bin/env python3
"""Fail the build when the dex references a type it does not define.

A dex that references an undefined type still loads, still passes `d8`, and
still produces a signed, installable APK. ART only finds out when it tries to
verify a class that needs the missing type, and reports it as a bare
`ClassNotFoundException` for a class that is plainly present in the dex -- so
the failure surfaces as a launch crash on someone else's phone, with nothing in
the build log to explain it.

This is not hypothetical. `androidx.lifecycle:lifecycle-common:2.8.4` is a
Kotlin-Multiplatform artifact whose base jar contains only `commonMain` linkdata
and zero `.class` entries; the JVM classes ship in `lifecycle-common-jvm`.
Feeding the base artifact to `d8` silently produced a dex missing
`Lifecycle`, `LifecycleOwner`, and `LifecycleObserver`, so `AppCompatActivity`
failed verification and `MainActivity` could not be instantiated at all.

Checked here instead: types the Android framework provides itself are excluded,
so the only things that must be bundled are the ones we actually ship.
"""
import struct
import sys
from collections import Counter

# Provided by the platform, not by the APK. Anything from these namespaces is
# expected to be absent from the dex.
FRAMEWORK_PREFIXES = (
    "Landroid/",
    "Landroidx/compose/",
    "Ljava/",
    "Ljavax/",
    "Lkotlin/",
    "Lsun/",
    "Ldalvik/",
    "Lorg/json/",
    "Lorg/xmlpull/",
    "Lorg/conscrypt/",
    "Lorg/bouncycastle/",
    "Lorg/openjsse/",
    "Lorg/apache/",
    "Lorg/w3c/",
    "Lorg/xml/",
    "Lkotlinx/",
)


def read_u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def load_dex(path):
    data = open(path, "rb").read()
    if data[:4] != b"dex\n":
        raise SystemExit(f"{path}: not a dex file")
    string_ids_off = read_u32(data, 60)
    type_ids_off = read_u32(data, 68)
    class_defs_off = read_u32(data, 100)
    string_count = read_u32(data, 56)
    type_count = read_u32(data, 64)
    class_count = read_u32(data, 96)

    def read_uleb128(offset):
        result = 0
        shift = 0
        while True:
            byte = data[offset]
            offset += 1
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result, offset
            shift += 7

    def string(idx):
        offset = read_u32(data, string_ids_off + 4 * idx)
        _, offset = read_uleb128(offset)  # utf16 length, unused
        end = data.index(b"\x00", offset)
        return data[offset:end].decode("utf-8", "replace")

    def type_desc(idx):
        """Resolve a type index to its descriptor."""
        return string(read_u32(data, type_ids_off + 4 * idx))

    # class_defs carry a type index; type_ids enumerate type indices.
    defined = {type_desc(read_u32(data, class_defs_off + 32 * i)) for i in range(class_count)}
    referenced = [type_desc(i) for i in range(type_count)]
    return referenced, defined


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: check-dex-closure.py <classes.dex>")
    referenced, defined = load_dex(sys.argv[1])

    missing = [
        t
        for t in set(referenced)
        if t not in defined
        and t.startswith(("Landroidx/", "Ldev/", "Lkotlinx/coroutines/"))
    ]
    if not missing:
        print(f"dex closure: OK ({len(defined)} classes defined)")
        return 0

    by_package = Counter("/".join(t[1:].split("/")[:2]) for t in missing)
    print("dex closure: FAIL -- referenced but never defined:", file=sys.stderr)
    for package, count in by_package.most_common():
        print(f"  {count:>5}  {package}", file=sys.stderr)
    print("", file=sys.stderr)
    for t in sorted(missing)[:40]:
        print(f"    {t}", file=sys.stderr)
    print(
        "\nAn empty or metadata-only jar in DEX_INPUTS is the usual cause.\n"
        "Kotlin-Multiplatform androidx artifacts put their classes in a\n"
        "separate -jvm artifact; check the jar actually contains .class entries.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())