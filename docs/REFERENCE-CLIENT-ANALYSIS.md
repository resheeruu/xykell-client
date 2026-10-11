# Reference-client analysis and port boundary

What was reverse-engineered, what was ported, and what was deliberately not.

Analysis artifacts (jadx 0.7.1 headless via the REA bridge, static only, no
target was ever executed):

- `com.project.lumina.client` v4.1.16 — see `w/re-lumina/REPORT.md` in the
  analysis workspace. 9161 classes; 28 app classes + 31 relay classes are
  first-party, the rest are Cloudburst protocol/netty, RaphiMC minecraftauth,
  Netty, Gson, Firebase and Compose.

## Ported, clean-room

Implemented from public protocol documentation and this repository's existing
abstractions. No source, offsets, signatures or constants were copied.

| Capability | Lands in |
|---|---|
| Relay socket/MTU/thread policy, caching upstream resolver | `runtime/network/RelayTuning.kt` |
| Bounded Bedrock text-component rendering | `runtime/observation/ComponentSanitizer.kt` |
| Protocol-number sanity + leg comparison | `runtime/relay/CodecDiagnostics.kt` |
| Hashed and indexed block network ids | `runtime/relay/BlockPalette.kt` |
| Bounded decode limits + per-packet budget | `runtime/relay/DecodeLimits.kt` |
| `.mcpack` container scan | `runtime/packs/PackArchive.kt` |
| First-run state + launch mode | `runtime/Onboarding.kt` |
| HSV theme re-tint | `ui/ThemeColors.kt` |

## Not ported, and why

`docs/LEGAL-LICENSE.md` section 3 requires that this project "never bypass
authentication, DRM, or server protections. No credential/token harvesting, no
auth bypass". Section 5 requires "no hidden telemetry or network requests".
Section 1 forbids copying from other clients. The following reference-client
capabilities fall under one of those, so they are out of scope here.

**Authentication bypass.** The reference client rewrites the Bedrock login:
it self-signs a `certificateAuthority: true` JWT leaf, prepends it to the
Mojang chain, and serialises chain and multiplayer token *together* through a
custom serializer that stock Bedrock login serializers reject. That is a
deliberate circumvention of the Mojang/Microsoft authentication contract.

WClient's relay terminates the connection and rewrites the login payload
without forging credentials — the game logs in normally and the relay holds
both handshake keys. See `docs/PROXY-DESIGN.md` section 6.

**Credential storage.** The reference client persists MSA refresh tokens and
identity tokens as plain JSON. WClient stores tokens through
`runtime/accounts/SecretBox` on an Android Keystore key
(`runtime/accounts/KeystoreKeyProvider`), which is strictly better and is what
ships.

**Hidden telemetry.** The reference client ships an analytics endpoint invoked
with a full `Context` on app start and on auth success, plus Firebase
Analytics, Crashlytics, Sessions and Installations, and declares `AD_ID` and
ad-attribution permissions. WClient's update check is explicit, signed and
checksum-verified, and there is no analytics stack.

**Monetisation gate.** The reference client blocks app start behind a
Linkvertise redirect, authenticated by a key derived from a salt that ships
inside the APK. That is not a security boundary.

**Other refusals.** Anti-tamper signature checking of unrelated installed apps;
an unreviewed remote pack-injection path; and unbounded codec decode limits.
That last one is worth spelling out: setting every decode limit to "whatever
fits in memory" does not read as an omission in review, it reads as a decision.
`runtime/relay/DecodeLimits.kt` exists to make the opposite explicit.

## Known analysis gaps

The REA jadx bridge cannot decompile `<clinit>`, so no `const val` or static
String constant in the analysed artifact was recoverable. Endpoint literals,
stored-file names and similar were therefore not established, and no claim about
them is made anywhere in this repository.

Call-graph tracing was unavailable (`rea_trace_android_references` resolved no
targets), so relationship data was read from decompiled bodies rather than from
xrefs.