# Which packets this repo actually knows how to read

Audit of `registry/bedrock-packets.json` against the ids still open, done to
decide what is buildable without inventing anything. Reproduce with the field
list for a packet: any field typed `ByteArray`, `*Dictionary`, or suffixed `=>`
is a place where the wire layout stops being known here.

## The finding that shaped the plan

Two large id buckets turned out to be gated on tables this repo deliberately does
not carry:

- **Chunk/block ids (17 ids).** `LevelChunk.payload` is `ByteArray`, and
  `UpdateBlock 0x15` carries `block_runtime_id = varint` — a number, not a name.
  The runtime-id-to-name mapping is not on the wire. So decoding the chunk
  payload yields *where* blocks are and *which numeric id*, but "is this ore"
  is unanswerable without a table the relay never receives.
- **Movement flags (10 ids).** This build's `PlayerAuthInput` field list has **no
  `input_data` field at all**. Movement state moved to `actions_presence` plus
  an `Action` set, and the repo does not enumerate those ordinals.

Both are a stance, not an oversight: nested and opaque types are left opaque
rather than guessed.

## The block palette is not in the APK

Checked on device (`com.mojang.minecraftpe` installed):

```
pm path com.mojang.minecraftpe   # base + split_config.arm64_v8a + split_install_pack (930 MB)
unzip -l split_install_pack.apk  # assets/... only: .nbt structure files, texture palettes
```

There is **no `canonical_block_states.nbt`** and no block palette in the install
pack — only structure `.nbt` files and `color_palettes.brarchive`. The block
registry is compiled into the game's native binary. So the earlier suggestion of
"generate the table at build time from the game's resource files" does not hold:
it would mean reverse-engineering `libminecraftpe.so`, which is a different
project with a different risk profile, not a build step.

## Packets with a complete, usable layout

Scanning for packets with no opaque and no enum-flagged field — i.e. ones a
decoder could be written against today without guessing:

| Packet | Id | Fields | Usable for |
|---|---|---|---|
| DeathInfo | 0xbd | cause, messages | `player.death_position` — **delivered** |
| PlayerList | 0x3f | entries | `hud.tab_list` — **delivered** |
| SetHealth | 0x2a | health | vitals, low health — **delivered** |
| SetTime | 0x0a | time | `hud.tps` — **delivered** |
| MovePlayer | 0x13 | 11 fields | entity table, backtrack — **delivered** |
| Text | 0x09 | full struct | ghost, spam — **delivered** |

The pattern that delivered every id so far: find a packet with a layout this
repo already verifies, decode only what the id needs, and never read past the
fields the id has an honest reason to use.

## One-byte header assumptions are a real hazard

`DeathInfo` is `0xbd`. Its high bit is set, so its varuint header is **two
bytes** (`bd 01`). Masking `raw[0] & 0xff` happens to yield the right number
while disagreeing with the framing — so a decoder written that way would look
correct and could mis-step on a different id.

This is the same class of bug that made the knockback id wrong: the old table
claimed `SetActorMotion 0x1B`, and `0x1B` is `EntityEvent`, the jump/hurt
*animation*. Dropping it cancelled a hurt flash, not a push.

Rule that follows: read the header as a real LEB128 varuint, and take packet ids
from the generated table rather than writing them down.