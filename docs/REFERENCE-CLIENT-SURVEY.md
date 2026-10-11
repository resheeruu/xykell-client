# Reference-client survey

Six artifacts were reviewed. This records what each one actually is, what was
taken, and what was not — including the cases where the honest answer is
"nothing portable".

Static analysis only. No target was ever executed.

| Artifact | Package | Verdict |
|---|---|---|
| Lumina Client 4.1.16 | `com.project.lumina.client` | MITM relay client — surface ported in 2.0.0 |
| WClient 37.0 | `com.retrivedmods.wclient` | MITM relay client — replay ported in 2.1.0 |
| Atlas Client 2.4.4 | `net.atlasclient.atlaspe` | Repackaged Minecraft — **architecture incompatible** |
| Ambient 1.2.0 | `io.kitsuri.mayape` | Repackaged Minecraft — **architecture incompatible** |
| Lunar 2.4.3 | `localhost.lunar` | Expo launcher + subscriptions — **nothing portable** |
| Flarial 1.2.4 | — | Not an application (resource pack) — **nothing to analyse** |

---

## WClient 37.0 — `com.retrivedmods.wclient`

74 first-party classes, ~7.5k lines, almost entirely obfuscated. The module
system, previously recorded as UNKNOWN, is now resolved:

- **Module base class** — constructor `(name, Category, enabledByDefault, key)`,
  one packet hook taking a context that carries both the incoming Bedrock
  packet and a *consumed* flag. That flag is the veto: returning true stops the
  packet, which is the same shape as the listener chain used by the relay.
- **Category enum** — grouping only.
- **Chat sender** — replies are written back as `TextPacket`.
- **Config** — `filesDir/replays/<name>.json`, kotlinx.serialization.
- **Relay service** — foreground service, partial wake lock, spawns a RakNet
  thread. It also force-launches the battery-optimisation Settings screen from
  `onCreate`. WClient never auto-sends the user to Settings; that is a
  deliberate difference, not an oversight.

### Ported (2.1.0)

Session replay recording and playback — see `runtime/replay/`. The capability is
recorded in the 2.1.0 changelog.

### Considered and not ported

**In-game `.`-prefixed command console.** The reference client parses commands
out of chat and replies into the game, which is a write path. WClient's
observation layer is read-only, and a console whose replies are injected into
the game is an automation surface, not an observation one. A local command
console over observed state would be a legitimate future feature; the injected
one is not, and the difference is the whole point.

**Fake Netty machine id.** `AppContext.onCreate` pins
`io.netty.machineId` to a constant so Netty cannot derive a machine identifier
from the device. It is a good idea and it is not applicable here: WClient's
observation feed server uses a plain `ServerSocket` and ships no Netty, so there
is nothing to pin. Adding the mitigation for a library this project does not use
would be theatre.

---

## Atlas Client 2.4.4 — `net.atlasclient.atlaspe`

**Atlas is not a mod client. It is Minecraft.**

Evidence:

- The manifest declares `com.mojang.minecraftpe.MainActivity` directly.
- The arm64 config split carries `lib/arm64-v8a/libSDL3.so` and
  `libPlayFabMultiplayer.so` — the game engine and its multiplayer library.
- The base APK ships `assets/mc_icon.png` and `assets/menu_logo.png`.
- Its own native core is `lib/arm64-v8a/libatlas.so` (3.1 MB).

Everything else follows from that: `io.kitsuri`-style injection is unnecessary
because the Atlas UI is compiled into the game it wraps, and
`com.pairip.licensecheck.LicenseActivity` is Google's integrity SDK for the
Mojang binary, still checking out.

Monetisation is the advertising model, not a gate:

```
https://atlasclient.net/premium
https://atlasclient.net/premium/gplay_status
https://atlasclient.net/privacy_gplay
https://atlasclient.net/support
https://atlasclient.net/tos_gplay
```

plus Play Billing (`com.android.vending.BILLING`), Google Mobile Ads
(`admob-gmats.uc.r.appspot.com`, `fundingchoicesmessages.google.com`), Firebase
Analytics (`app-measurement.com`), Crashlytics, Firebase Installations, and
Play Games.

**Why nothing was ported.** Atlas's architecture *is* shipping a repackaged
Minecraft. WClient's stated direction is the opposite — `docs/LEGAL-LICENSE.md`
requires that game binaries are never shipped, and `docs/LEVI-REMOVAL-AUDIT.md`
records the project deliberately moving away from that shape toward a standalone
loader. Porting Atlas's architecture would reverse that decision, and its ads,
billing and analytics would violate sections 3 and 5 besides.

Atlas's permission posture is otherwise notably tighter than the MITM clients: no
`SYSTEM_ALERT_WINDOW`, no `MANAGE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES`.

---

## Ambient 1.2.0 — `io.kitsuri.mayape`

Same architecture as Atlas, different author. The manifest declares
`com.mojang.minecraftpe.ImportService` and
`com.mojang.minecraftpe.NotificationListenerService`, and ships
`io.kitsuri.ambientcore.AmbientCore` — an embedded Minecraft core — alongside its
own `BedrockCore`, `EasyLoader` and activity set.

Unlike Atlas it uses the genuine Microsoft XAL SDK against `auth.xboxlive.com`,
so its account handling is real authentication rather than a bypass. The
archetype is still "repackaged Minecraft", so the same reasoning applies and
nothing was ported.

---

## Lunar 2.4.3 — `localhost.lunar`

Not a mod client either. It is an Expo/React Native application
(`expo.modules.*`) built around subscriptions:

- RevenueCat for entitlements, including Amazon IAP
- Superwall for paywalls and a customer center
- Firebase messaging for notifications
- minSdk 24, the lowest in the set

There is no module system, no relay, no protocol handling. The surface area that
would transfer is a paywall and a subscription backend, and WClient ships neither
by design. Nothing ported.

---

## Flarial 1.2.4

Not an application. `aapt dump badging` returns no package, manifest or
components, the archive contains no native libraries, and there are no network
endpoints. It is the Flarial resource/texture pack, which ships as an APK-shaped
archive so it can be opened by the game.

There was nothing to analyse.

---

## Native layer

Native libraries were enumerated from every artifact and their JNI boundaries
mapped from ELF symbol tables. This closed the largest gap in the analysis: the
Lumina codec table is native code, and it is now known.

### Lumina Client — complete native boundary

Four first-party libraries. Unlike Atlas, these export statically bound JNI
symbols, so the boundary is fully enumerable from the symbol table.

| Library | Size | JNI class | Methods (size in bytes) |
|---|---|---|---|
| `liblunaris.so` | 260 KB | `relay.LuminaRelay`, `relay.listener.AutoCodecPacketListener` | `createNativeAdvertisement` (916), `getNativeDefaultIp` (20), `getNativeDefaultPort` (8), `getNativeRemoteIp` (20), `getNativeRemotePort` (8), **`pickProtocolCodec` (684)** |
| `libclient.so` | 264 KB | `client.CPPBridge.NativeHsvToRgb` | `hsvToRgb` |
| `libnative-lib.so` | 257 KB | `client.util.HashCat` | `getToken` (88), `checkSha1` (48), `getSignaturesSha1` (24) |
| `libpixie.so` | 1.1 MB | `client.util.UpdateCheck` | `resolveEndpoint` (1380), `verifySignature` (1044), `retrieveFallback` (396) |

`libpixie.so` also carries the whole of Dear ImGui — its exported symbols are
overwhelmingly `ImGui::*` and `ImDrawList::*`. So the ImGui overlay is not a
separate library; it is linked into the update-check library.

**No native cryptography is linked anywhere.** No OpenSSL, BoringSSL or
platform crypto symbols are exported from any Lumina library, which means
`verifySignature` — 1044 bytes of native code — delegates the digest to the JVM
rather than computing it. The algorithm is therefore chosen on the Java side and
remains unestablished; what is established is that it is not native.

### The codec table — the significant finding

`pickProtocolCodec` is 684 bytes: a lookup ladder, not a parser. The codec
classes it selects between are embedded as Cloudburst class-path strings and
are directly enumerable from `.rodata`.

**63 codecs spanning protocol 291 through 2193:**

```
291 313 332 340 354 361 388 389 390 407 408 419 422 428 431 440 448 465 471
475 486 503 527 534 544 545 554 557 560 567 568 575 582 589 594 618 622 630
649 662 671 685 686 712 729 748 766 786 800 818 819 827 844 859 860 898 924
944 975 1001 2168 2169 2193
```

The jump from 1001 to 2168 is a change in Mojang's protocol numbering scheme,
not a gap in coverage. `LuminaRelay` additionally pins `Bedrock_v2193` as its
default codec, and the Java layer mentions `Bedrock_v2168` and `Bedrock_v2169`
as login-serializer targets.

### What this means here, honestly

This is a **real capability gap**, and it is worth naming precisely rather than
papering over.

This repository generates its packet-id table from **one** vendored protocol
schema (`scripts/protocol/vendor/bedrock-1.26.45-proto.yml`). The reference
client carries 63. It therefore speaks one protocol generation where the
reference speaks many.

`runtime/relay/CodecDiagnostics` was written to refuse to claim a support matrix
for exactly this reason, and that decision now has evidence behind it rather than
caution behind it: a single-schema table cannot honestly describe a 63-codec
capability.

The upgrade path is data, not code: vendor one `proto.yml` per supported
protocol generation, run `scripts/protocol/gen_packet_ids.py` across all of them,
and let the generated tables drive a version→codec map. That is a data-acquisition
task and it was deliberately not attempted here — vendoring 63 schemas is a
large, provenance-recording job (`docs/LEGAL-LICENSE.md` section 6 requires
recording where each version's data came from), and doing it badly would produce
a table that looks broad and is wrong, which is worse than being honestly narrow.

### Atlas Client — native boundary

Atlas registers its JNI methods dynamically. Every first-party library exports
`JNI_OnLoad` and **not one `Java_*` symbol**, so the method table is registered
at runtime and lives in `.rodata` rather than in the symbol table. That is a
deliberate choice to hide the boundary from casual inspection, and it is why the
Atlas findings in this document are structural rather than method-level.

| Library | Size | Role |
|---|---|---|
| `libatlas.so` | 3.1 MB | Atlas's own core |
| `libloader.so` | 3.8 MB | Loader / injector |
| `libtap.so` | 1.3 MB | Input injection |
| `libmaesdk.so` | 7.0 MB | Mojang authentication SDK |
| `libPlayFabMultiplayer.so` | 4.3 MB | Minecraft multiplayer |
| `libSDL3.so` | 2.4 MB | Minecraft engine |
| `libcrashlytics*.so` | 0.9 MB | Crash reporting |

`libSDL3.so` and `libPlayFabMultiplayer.so` are the game engine and its
multiplayer library, which is the direct confirmation that Atlas ships
Minecraft.

None of this native code was decompiled. Conclusions about its behaviour are
limited to what symbol tables and embedded strings support.

---

## Method and its limits

The REA jadx bridge cannot decompile `<clinit>`, so **no `const val` or static
String constant was recoverable in any artifact**. Endpoint literals in the
MITM clients were therefore not established, and this repository makes no claim
about them.

Call-graph tracing was unavailable — `rea_trace_android_references` resolved no
targets in any artifact — so relationships were read from decompiled bodies and
manifest structure rather than from cross-references.

Native libraries were identified by archive listing but **not analysed**;
`libatlas.so` in particular is 3.1 MB of unread code.

The REA tools became unavailable partway through this survey. The later artifacts
were examined with the local `jadx`, `apktool` and `aapt` binaries instead —
same decompiler, but no per-class inspection tool, so those findings are
structural (manifest, components, permissions, libraries, endpoints) rather than
method-level. That difference in depth is reflected in the detail above.

---

## Status of each ported subsystem

Recorded so a reader does not have to grep to find out what is actually
reachable from the UI.

| Subsystem | Reachable from | Notes |
|---|---|---|
| Replay (record/list/scrub) | Session screen | Fed by polling observed motion |
| Command console | Chat screen | Local only; reads, cannot send |
| Text-component sanitising | Chat screen, display path | Control bytes stripped before render |
| `.mcpack` scanning | Content screen | Real shape reported, never extracted |
| Decode limits | Diagnostics, `/limits` | Active policy shown, and since 0.4.1 the same constant the wire path enforces |
| Protocol diagnostics | Diagnostics | Observed values only |
| Relay tuning | Diagnostics | Requested vs granted buffers |
| Onboarding / launch mode | Session screen | Persisted atomically |
| **Block palette** | **nothing** | **[RESEARCH REQUIRED]** — see below |

### Block palette — RESEARCH REQUIRED

`runtime/relay/BlockPalette.kt` implements the protocol's two block
network-id addressing modes (indexed and hashed) and is fully tested, but
**nothing constructs one at runtime**: no code path currently reads a block
palette out of a start-game handshake.

It is deliberately left unwired. Showing it on a screen as "none loaded" would
present a placeholder as a state, and the repository's rule is that a capability
is either reachable or explicitly marked — never displayed as though it were
working. Wiring it needs a producer: parse the palette from `StartGame`, select
the registry by the handshake's hashed/indexed flag, and hand it to the relay.

The capability is worth having. A decoder that assumes the wrong addressing
mode reads a hash as an ordinal and resolves every block to a wrong definition
with no error — which is the failure mode this type exists to prevent.

### Decode limits — one source of truth (0.4.1)

Worth recording because it was a live defect, not a tidy-up.

`BedrockBatch.MAX_INFLATED` was its own `10 * 1024 * 1024`. `DecodeLimits`
declared `MAX_PACKET_BYTES` as 16 MB, and both the diagnostics screen and the
`/limits` command reported the DecodeLimits figure. The UI therefore showed a
16 MB policy on a path that refused anything over 10 MB.

The two constants answered the same question — how large may one decode get —
and only one of them was visible. The batch ceiling now lives in
`DecodeLimits.MAX_INFLATED_BATCH_BYTES` and `BedrockBatch` reads it. It is kept
separate from the packet bound because a batch is a container of packets and
decompresses to more than one packet's worth; collapsing them would either
weaken the wire cap or make the packet cap unreachable.

Three tests pin the relationship, including one that asserts `describe()`
carries the enforced number, so a future divergence fails the build rather than
quietly making the screen wrong again.
