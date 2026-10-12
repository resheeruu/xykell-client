#!/usr/bin/env python3
"""
Emits a per-version StartGame "skip plan": the ordered primitive reads needed to
walk from the first byte of a StartGame payload up to its block palette.

Hand-typed offsets are not an option here. The palette sits at field 53 of 57 in
1.16.201 and field 55 of 59 in 1.16.210, and between those versions the schema
inserts two fields. A hand-written decoder would be right for one version and
silently misaligned for the other, which is the exact failure mode the packet-id
generator exists to prevent. So the walk order is derived from the vendored
schema instead, and every version gets its own plan.

Only versions whose schema actually declares a block palette get a plan. Of the
50 schemas fetched, 2 declare `block_palette` in StartGame; the other 48 carry
`block_properties` (a name -> NBT-state list with no runtime ids) or, on 1.21+,
nothing but a `block_network_ids_are_hashes` flag. Those versions are recorded
as undecodable rather than given an approximate plan.

Pure Python: no network, no Android, host-testable.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
VENDOR = ROOT / "scripts/protocol/vendor"
OUT_KT = (
    ROOT
    / "app/src/main/java/dev/xykell/client/runtime/relay/StartGamePalettePlan.kt"
)

# Primitives the interpreter knows how to step over. A type outside this set is a
# generator bug, not a runtime condition: the plan must be total.
PRIMITIVES = {
    "bool": "BOOL",
    "u8": "U8",
    "i8": "I8",
    "li8": "LI8",
    "li16": "LI16",
    "li32": "LI32",
    "li64": "LI64",
    "lu64": "LU64",
    "varint": "VARINT",
    "varint64": "VARINT64",
    "zigzag32": "ZIGZAG32",
    "zigzag64": "ZIGZAG64",
    "zigzag16": "ZIGZAG16",
    "lf32": "LF32",
    "string": "STRING",
    "nbt": "NBT",
    "lnbt": "NBT",
    "pstring": "STRING",
    "LittleString": "STRING",
    "LatinString": "STRING",
    "ShortString": "STRING",
    "restBuffer": "REST",
    "maybeIncompleteArray": "REST",
    "optionalOnRemaining": "REST",
    "encapsulated": "REST",
    "byterot": "REST",
    "uuid": "STRING",
}

# Named schema types, expanded into their primitive op sequence.
NAMED = {
    "GameMode": ["ZIGZAG32"],
    "vec3f": ["LF32", "LF32", "LF32"],
    "vec2f": ["LF32", "LF32"],
    "BlockCoordinates": ["ZIGZAG32", "VARINT", "ZIGZAG32"],
    "Experiments": ["EXPERIMENTS"],
    "GameRules": ["GAME_RULES"],
    "BlockPalette": ["BLOCK_PALETTE"],
}


def flatten(spec, types, seen=None):
    """Turns one schema field type into a flat list of interpreter ops."""
    seen = seen or set()

    # "mapper"/"switch" carry the real type in their options dict.
    if isinstance(spec, list) and len(spec) == 2 and isinstance(spec[1], dict):
        head, opts = spec
        if head == "mapper":
            return flatten(opts.get("type", "varint"), types, seen)
        if head == "array":
            count = opts.get("countType", "varint")
            inner = flatten(opts["type"], types, seen | {opts["type"]})
            count_op = flatten(count, types, seen)[0]
            return [f"ARRAY_{count_op}"] + inner
        if head == "container":
            ops = []
            for f in opts:
                ops += flatten(f["type"], types, seen)
            return ops
        if head == "switch":
            # All branches are same-width here (bool / zigzag32 / lf32 are each
            # one primitive read once the discriminator is known). The
            # interpreter reads the discriminator then the matching op; the plan
            # records the branch table.
            branches = {
                k: flatten(v, types, seen)
                for k, v in opts.get("fields", {}).items()
            }
            sizes = {tuple(v) for v in branches.values()}
            if len(sizes) != 1:
                raise SystemExit(
                    "switch branches differ in width; interpreter would need a "
                    f"real branch table: {branches}"
                )
            base = next(iter(sizes))
            return ["SWITCH:" + "/".join(base)]

    if not isinstance(spec, str):
        raise SystemExit(f"cannot flatten non-scalar spec: {spec!r}")

    if spec in NAMED:
        if spec in seen:
            raise SystemExit(f"recursive named type {spec}")
        out = []
        for part in NAMED[spec]:
            # NAMED lists may hold either schema type names (expanded) or
            # already-resolved op names, which must pass through untouched.
            if part.isupper() or part in ("EXPERIMENTS", "GAME_RULES", "BLOCK_PALETTE"):
                out.append(part)
            else:
                out += flatten(part, types, seen)
        return out

    if spec in PRIMITIVES:
        return [PRIMITIVES[spec]]

    if spec in types:
        if spec in seen:
            raise SystemExit(f"recursive schema type {spec}")
        body = types[spec]
        return flatten(body[1] if body[0] == "container" else body, types, seen | {spec})

    raise SystemExit(f"unknown schema type {spec!r}")


def kt_string(text):
    """A Kotlin string literal, with the quotes and backslashes the text needs."""
    escaped = text.replace("\\", "\\\\").replace('"', '\\"')
    return '"%s"' % escaped


def plan_for(path):
    """(ops, palette_present, reason) for one vendored protocol.json."""
    types = json.loads(path.read_text())["types"]
    sg = types.get("packet_start_game")
    if not sg:
        return None, False, "schema has no packet_start_game"

    ops = []
    for field in sg[1]:
        name = field.get("name")
        if name == "block_palette":
            return ops, True, "ok"
        ops += flatten(field["type"], types)

    return ops, False, "StartGame declares no block_palette"


def main():
    if not VENDOR.is_dir():
        print("no vendor directory", file=sys.stderr)
        return 1

    protocols = sorted(VENDOR.glob("bedrock-*-protocol.json"))
    if not protocols:
        print("no vendored protocol.json files", file=sys.stderr)
        return 1

    decodable, refused, protocol_numbers = {}, {}, {}

    # The audit records what every vendored schema declares, so the refusal list
    # covers the versions we deliberately did not vendor (16 MB of protocol.json
    # for 48 versions that provably have no palette is not worth the repo).
    audit_path = VENDOR / "startgame-palette-audit.json"
    if audit_path.is_file():
        audit = json.loads(audit_path.read_text())["versions"]
        refused.update(
            {v: r["reason"] for v, r in audit.items() if not r["palette"]}
        )
        # Bedrock announces a protocol *number*, not a version string, so the
        # decoder has to be selected by number. Where two schemas share a number
        # the first (lowest) version wins, and the collision is recorded rather
        # than resolved silently.
        by_number = {}
        collisions = []
        for version, record in sorted(audit.items()):
            number = record.get("protocol")
            if not isinstance(number, int):
                continue
            if number in by_number:
                collisions.append((number, by_number[number], version))
                continue
            by_number[number] = version
        protocol_numbers.update(by_number)
        for number, kept, dropped in collisions:
            print(
                "protocol %d maps to both %s and %s; using %s"
                % (number, kept, dropped, kept),
                file=sys.stderr,
            )

    for path in protocols:
        version = re.match(
            r"bedrock-(.+)-protocol\.json$", path.name
        ).group(1)
        ops, ok, reason = plan_for(path)
        if ok:
            decodable[version] = ops
            refused.pop(version, None)
        elif version not in refused:
            refused[version] = reason

    lines = [
        "package dev.xykell.client.runtime.relay",
        "",
        "/**",
        " * StartGame skip plans, generated by scripts/protocol/gen_startgame_plan.py",
        " * from the vendored protocol.json schemas (MIT, PrismarineJS/minecraft-data).",
        " * Do not hand-edit: the palette's field index differs per version and the",
        " * walk between the first byte and the palette is version-specific, so a",
        " * hand-written offset would be right for one version and silently",
        " * misaligned for the next.",
        " *",
        " * %d version(s) carry a decodable block palette; %d do not and are refused" % (
            len(decodable), len(refused),
        ),
        " * by [StartGamePaletteDecoder] with a recorded reason.",
        " */",
        "object StartGamePalettePlan {",
        "",
        "    /** Ordered primitive reads from payload start up to the palette. */",
        "    val PLANS: Map<String, List<Op>> = mapOf(",
    ]

    for version in sorted(decodable):
        ops = decodable[version]
        lines.append('        "%s" to listOf(' % version)
        # Wrap so the generated list stays readable in review.
        for i in range(0, len(ops), 8):
            lines.append("            " + ", ".join("Op." + o for o in ops[i:i + 8]) + ",")
        lines.append("        ),")
    lines += [
        "    )",
        "",
        "    /**",
        "     * Bedrock protocol number -> schema version. The handshake carries the",
        "     * number, never the version string, so this is what selects a plan.",
        "     */",
        "    val PROTOCOL_VERSIONS: Map<Int, String> = mapOf(",
    ]
    for number in sorted(protocol_numbers):
        lines.append('        %d to "%s",' % (number, protocol_numbers[number]))
    lines += [
        "    )",
        "",
        "    /** The schema version for a negotiated [protocolNumber], or null. */",
        "    fun versionFor(protocolNumber: Int): String? = PROTOCOL_VERSIONS[protocolNumber]",
        "",
        "    /**",
        "     * Why a version has no plan. A version absent from [PLANS] and from",
        "     * here was never vendored; present here means it was checked and",
        "     * cannot be decoded. Different claims, so they are kept apart.",
        "     */",
        "    val REFUSED: Map<String, String> = mapOf(",
    ]
    for version in sorted(refused):
        lines.append('        "%s" to "%s",' % (version, refused[version]))
    lines += [
        "    )",
        "",
        "    /**",
        "     * Exactly the primitives the vendored schemas need. Deliberately not",
        "     * padded with plausible extras: a schema that needs an op the",
        "     * interpreter does not have must fail to compile here, loudly, rather",
        "     * than quietly skip a field it does not understand and return a",
        "     * palette offset by one field.",
        "     */",
        "    enum class Op {",
        "        BOOL, LI16, LI32, LI64, LF32, STRING,",
        "        VARINT, VARINT64, ZIGZAG32, ZIGZAG64,",
        "        EXPERIMENTS, GAME_RULES,",
        "    }",
        "}",
        "",
    ]

    OUT_KT.write_text("\n".join(lines))
    print(
        "wrote %s (%d decodable, %d refused)" % (OUT_KT, len(decodable), len(refused)),
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
