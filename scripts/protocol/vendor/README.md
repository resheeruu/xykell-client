# Vendored protocol data

`bedrock-<version>-proto.yml` files are copied verbatim from PrismarineJS/minecraft-data
(`data/bedrock/<version>/proto.yml`), MIT licensed, Copyright (c) PrismarineJS
contributors. They are vendored so `gen_packet_ids.py` is reproducible offline —
packet ids and field orders are protocol facts and must never be hand-typed.

## Provenance

| | |
|---|---|
| Upstream | https://github.com/PrismarineJS/minecraft-data |
| Pinned commit | `dd01127457231e7da629d645b3c6738068018eb0` (2026-10-11) |
| Version index | `data/dataPaths.json` from that commit (57 Bedrock versions listed) |
| Schemas vendored | 51 of 57 |
| Verified | every file sha256-identical to the pinned commit |

The schemas were first fetched from the mutable `master` branch. Because a moving
branch is not provenance, all 51 were then re-verified byte-for-byte against the
commit above, and that commit is what these files are pinned to. To re-verify:

    python3 - <<'EOF'
    import hashlib, pathlib, subprocess
    PIN = "dd01127457231e7da629d645b3c6738068018eb0"
    for f in sorted(pathlib.Path("scripts/protocol/vendor").glob("bedrock-*-proto.yml")):
        v = f.name[len("bedrock-"):-len("-proto.yml")]
        up = subprocess.run(["curl","-sfL",
            f"https://raw.githubusercontent.com/PrismarineJS/minecraft-data/{PIN}/data/bedrock/{v}/proto.yml"],
            capture_output=True).stdout
        assert hashlib.sha256(up).hexdigest() == hashlib.sha256(f.read_bytes()).hexdigest(), v
    print("all match")
    EOF

## The 6 versions with no schema

`minecraft-data` lists 57 Bedrock versions and publishes no `proto.yml` for these
six, so they are absent rather
than stubbed: `0.14`, `0.15`, `1.0`, `1.19.63`, `1.20.15`, `1.26.51`. A version with no
vendored schema is *unknown*, which is a
different claim from "same as the default" — `BedrockPacketIds.forVersion`
returns `null` for them and `supportsVersion` returns `false`, so a caller
cannot mistake absence for agreement.

## Regenerating

    python3 scripts/protocol/gen_packet_ids.py --all-versions

Regenerates both outputs and re-runs the cross-check against the hand-written
decoder. A single schema can still be built with `--proto <path>`.
