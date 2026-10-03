# Stage-9K handshake differential (research-only, no code changed)

Goal: why Minecraft accepted the Stage-9H key/salt construction but
rejected Stage-9J with `commands.generic.encryption.badsalt` + `Bad
public key given. Expected 120 byte key after PEM formatting.`
No keypair generated, no connection attempted, no code modified,
no commit/push. No private keys in this report (only public handshake
strings already present in the captures, plus structural lengths).

File note: the named accepted capture
`capture-2026-10-03T04-44-36-666Z.jsonl` (analyzed in Stage-9G as 1821
bytes / 4 records) is no longer on disk (searched home + tmp; only an
unrelated synthetic redaction-test file matched that timestamp pattern
in `$PREFIX/tmp`). The accepted reference below is therefore the two
re-verified accepted captures `capture-2026-10-03T05-25-11-246Z.jsonl`
and `capture-2026-10-03T05-32-04-888Z.jsonl` (2557 bytes / 6 records
each, read in full), which 9G/9H establish as accepted: gate opened (no
"Encrypted session required"), client key delivered, 156B frame, no
error frames. The rejected reference is
`capture-2026-10-03T07-55-12-660Z.jsonl` (1296 bytes / 3 records, read
in full). All three outbound `enableencryption` frames are 359B with
`cmd_len=204`, framing byte-identical.

## STAGE 9H

Accepted sessions (05-25 shown; 05-32 structurally identical, fresh
ephemeral key/salt/UUIDs):

- Out 359B: `enableencryption "i5mmk5GubSAW5ADvbMem2uS-3k3ZdlzU6xJDGUaZZLCNje6gEI9sYPcqNoHZOMdboeNWqf0seHh9DexsNvyFwScJB77hMn9nac0jPdIplcicUwh3hdJiEuMZtKR4Vnxt" "ZQQioCC3YOcphCDc8jaZyQ"`
  (05-32: key `IUinoqlDXjJ-_McJuUT_pfGgxEjK7YiDIfs4IWNzJT779X8s7I9LkoiBeo7FpuvIGsyX6zY4wsRRP4rfxgEdCQeHQ5IiHBYBW0y_hZZyERnDEc3CZUoYzzl95oUfEhNW`, salt `zRLFHieLkqCFQ7zDvMnv4Q`.)
- In 270B `ws:encrypt` key + in 275B `commandResponse` key (same client
  ephemeral key). No error frames.
- In 156B BINARY (pre-establishment, decrypt never attempted).
- Out 172B PlayerMessage + 174B PlayerTravelled subscribes.
- What "accepted" actually consisted of: no `badsalt`, no bad-key
  error, client public key delivered, gate opened. There was never an
  explicit Minecraft OK for OUR key/salt — acceptance was inferred from
  absence of errors plus key delivery.

## STAGE 9J

Rejected session (07-55, full transcript):

1. Out 359B: `enableencryption "BTuNL4VgIopZJKxS21APreLrA6QDSfuM_AdCLLw7146LcLy18zwxKQ2rWeG9U07jEVjwuQrGD17GLxVa6uGDNqbWXxlL788AFmvebzkujaN2uNCxYDpE9QAMZQHpjofz" "Yu8Eu5pp6trXWB-ddD3SvA"`.
2. In 141B `{body:{error:"commands.generic.encryption.badsalt"},
   header:{messagePurpose:"ws:encrypt", requestId:"hs-conn-1"}}`.
3. In 209B `{body:{statusCode:-2147352576, statusMessage:"Bad public key
   given. Expected 120 byte key after PEM formatting."},
   header:{messagePurpose:"commandResponse", requestId:"hs-conn-1"}}`.
- No client public key was ever delivered, so `deriveSessionKey` never
  ran: cryptographic derivation cannot be the cause of this failure.
  The failure is at parse/validation of our two handshake strings.

## PUBLIC KEY ANALYSIS

All three outbound keys measured (strict validators, no guessing):

- Command-string length: 160 chars in all three sessions.
- Alphabet: all three contain URL-safe chars and NO `+/` or padding —
  9H-a has `-`, 9H-b has `-` and `_`, 9J has `_`. All three FAIL strict
  standard-base64 validation (`Only base64 data is allowed`) and all
  three decode under base64url (unpadded) to exactly 120 bytes.
- DER structure (decoded via base64url): all three are
  `30 76 30 10 ...`, contain the ecPublicKey OID
  (1.2.840.10045.2.1) and the secp384r1 OID (1.3.132.0.34), and carry
  `03 62 00 04` + 97-byte point with `0x04` uncompressed prefix at
  offset 23. All three are structurally valid P-384 SubjectPublicKeyInfo.
- PEM check vs Minecraft's message: PEM body of a 120-byte DER is 160
  base64 chars; all three decode to exactly 120 bytes, so the message's
  length half ("120 byte key") is SATISFIED by every session including
  9J. The failure is therefore in the "PEM formatting" half: our
  strings are base64url (`-_`), not the PEM/standard alphabet (`+/`).
- Consistency verdict: the 9J key is NOT cryptographically or
  structurally malformed (valid P-384 SPKI, 120 bytes, same as 9H). Its
  representation deviates from the documented construction in exactly
  the same way the accepted 9H keys do (see DOCUMENTED PROTOCOL
  COMPARISON). Key encoding alone cannot explain accepted-vs-rejected.

## SALT ANALYSIS

- Command-string length: 22 chars in all three sessions; decoded length
  (base64url): 16 bytes in all three — the documented salt length is
  satisfied everywhere.
- Alphabet is the whole story:
  - 9H-a salt `ZQQioCC3YOcphCDc8jaZyQ`: alphanumeric only — valid under
    BOTH base64url and strict standard base64 (16 bytes either way).
  - 9H-b salt `zRLFHieLkqCFQ7zDvMnv4Q`: alphanumeric only — same, valid
    under both.
  - 9J salt `Yu8Eu5pp6trXWB-ddD3SvA`: contains `-` — decodes under
    base64url (16 bytes) but FAILS strict standard-base64 validation.
- So the lab's salt construction is identical in all sessions
  (base64url, unpadded); the 9H salts passed Minecraft's check by
  byte-luck (alphabet overlap), while the 9J salt's bytes required a `-`
  and tripped `commands.generic.encryption.badsalt`. This is the proven
  mechanism for the salt half of the rejection.
- Escaping ruled out: `cmd_len=204` in all sessions, framing
  `enableencryption "KEY" "SALT"` with straight quotes identical; the
  `\"` sequences in captures are only the JSONL record encoding, not
  wire bytes. Shell/JSON escaping did not alter either value.

## BYTE-LENGTH ANALYSIS

- Lengths cannot distinguish the sessions and are all correct:
  key 160 chars -> 120 bytes DER (P-384 SPKI, exact); salt 22 chars ->
  16 bytes. Lengths are also identical under base64url vs RawStd for
  these sizes (120 % 3 == 0 -> 160 chars, no padding either way;
  16 bytes -> 22 chars unpadded either way). Only the ALPHABET
  (`-_` vs `+/`) differs, and only when the underlying bytes map to
  those positions — which happened for every key and for the 9J salt.
- "120 byte key after PEM formatting" is consistent with the observed
  representation in the length dimension (all decode to 120) and points
  at the alphabet dimension for the failure (our `-_` chars are not PEM
  alphabet). A value was never judged correct merely for looking like
  base64: every claim above is a measured decode, not a visual one.

## DOCUMENTED PROTOCOL COMPARISON

- mcwss (MIT, Sandertv, current master, fetched for this analysis):
  `protocol/command/enable_encryption.go` —
  `EnableEncryptionRequest(publicKey, salt)` sends
  `fmt.Sprintf(`enableencryption "%v" "%v"`,
  base64.RawStdEncoding.EncodeToString(publicKey),
  base64.RawStdEncoding.EncodeToString(salt))` with salt required to be
  exactly 16 bytes. `RawStdEncoding` = STANDARD alphabet (`+/`), NO
  padding. The client key is read back with `base64.StdEncoding`.
  Key source: `x509.MarshalPKIXPublicKey` (P-384 SPKI, 120-byte DER).
  Crypto: ECDH x-coordinate, `SHA256(salt + secret)`, AES CFB streaming
  per-byte with IVs from key[:16] (`encryption.go`, `server.go`).
- Lab (`encryption.mjs`, read-only): `buildEnableEncryptionCommand`
  uses `Buffer.toString('base64url')` (URL-safe `-_`, no padding) for
  both key and salt; key is `exportKey('spki')` P-384 (120-byte DER);
  salt is 16 bytes; derivation is SHA256(salt+secret), AES-256-CFB8,
  IVs = key[:16]. The Stage-9F doc records the choice explicitly:
  `enableencryption "<b64url DER>" "<b64url salt>"`.
- Delta: the ONLY construction deviation is the alphabet — `base64url`
  vs documented `RawStd` — for both strings. Curve (P-384), DER form
  (SPKI, 120 B), salt length (16 B), quoting, command name, subprotocol,
  and derivation all match the documented construction.

## DIFFERENTIAL TABLE

| Property | Stage 9H accepted (05-25 / 05-32) | Stage 9J rejected (07-55) | Difference |
|---|---|---|---|
| Outbound frame / cmd length | 359B / 204 chars | 359B / 204 chars | NONE |
| Command framing/escaping | `enableencryption "KEY" "SALT"`, straight quotes | identical | NONE |
| Key string length | 160 chars (both) | 160 chars | NONE |
| Key alphabet | base64url (`-` / `-_` present) | base64url (`_` present) | NONE (same construction) |
| Key decoded length | 120 bytes (both) | 120 bytes | NONE |
| Key DER structure | valid P-384 SPKI, `04`+97B point (both) | valid P-384 SPKI, `04`+97B point | NONE |
| Key vs documented RawStd | DEVIATES (url-safe chars) | DEVIATES (url-safe chars) | NONE — yet 9H passed, 9J failed |
| Salt string / decoded length | 22 chars / 16 bytes (both) | 22 chars / 16 bytes | NONE |
| Salt alphabet | alphanumeric only (valid under BOTH alphabets) | contains `-` (base64url-only) | THE difference |
| Salt vs documented RawStd | conformant by luck | DEVIATES | explains `badsalt` |
| Salt length vs documented 16 B | OK | OK | NONE |
| Escaping mutation | none | none | NONE |
| Inbound Minecraft verdict | key delivery, no errors | `badsalt` + bad-key errors, no key | verdict differs, construction (near-)identical |
| Derivation executed | yes (client key present) | no (no client key) | consequence, not cause |

Determinations:

1. Same key representation in 9H and 9J: YES (base64url, 160 chars,
   120-byte valid P-384 SPKI in all three sessions).
2. Same salt representation: YES as construction (base64url, 22 chars,
   16 bytes); NO as alphabet-membership (9H overlap-safe, 9J `-`).
3. 9J salt malformed: structurally NO (well-formed base64url, correct
   16 bytes); malformed RELATIVE TO Minecraft's RawStd expectation YES
   (proven by `badsalt` + strict-decode failure on `-`).
4. 9J public key malformed: structurally/cryptographically NO (valid
   P-384 SPKI, exact 120 bytes); encoding-wise it shares the lab's
   base64url deviation, which is real but cannot alone explain the
   verdict difference since 9H's keys deviate identically.
5. Encoding/formatting vs derivation: the failure is encoding-side —
   PROVEN (derivation never ran in 9J; no client key delivered).
6. 9F/9H implementation violates the documented construction: YES, in
   exactly one place — base64url instead of RawStd alphabet for the two
   handshake strings (Stage-9F's recorded `<b64url>` choice). All other
   construction elements match mcwss.
7. 9H acceptance transient/ambiguous, not independently trustworthy:
   YES — 9H never produced an explicit Minecraft OK for our strings, and
   its keys carry the same deviation that 9J's verdict condemns. The 9H
   "acceptance" (no errors + key delivery + gate opened) cannot be used
   as proof the base64url form is correct.

## PROVEN

- Lab sends base64url (`-_`, unpadded) for key+salt; documented mcwss
  sends RawStd (`+/`, unpadded). One documented encoding deviation,
  located in `buildEnableEncryptionCommand` (Stage-9F choice).
- All observed keys: 160 chars -> 120-byte valid P-384 SPKI
  (`04` + 97B point, both OIDs). All observed salts: 22 chars ->
  16 bytes. Lengths correct in every session.
- 9H salts are alphabet-overlap (decode under both alphabets); 9J salt
  is base64url-only (`-` fails strict Std). This explains `badsalt`
  deterministically.
- 9J DERivation never executed (no client key), so crypto derivation is
  excluded as the cause; the rejection is a handshake-string
  parse/validation failure.
- Key-encoding deviation is necessary but NOT sufficient to explain the
  verdict split: identical deviation passed in 9H, failed in 9J.

## UNKNOWN

- Why the identically-deviating 9H keys drew no bad-key error while
  9J's did: validation strictness vs key-vs-salt error coupling vs
  build/transient behavior on the Minecraft side. The captures contain
  no further distinguishing bytes (framing identical, lengths
  identical, structures identical).
- Whether Minecraft validates salt-first and reports the key error as a
  coupled consequence, or validates independently (9J shows both errors
  together; 9H shows neither — coupling is plausible but unproven).
- Whether a RawStd-conformant resend would be accepted (predicted yes
  for the salt half; the key half is expected to follow since length and
  structure already satisfy the message — but this is prediction, not
  observation; no experiment was run per mandate).
- The absent named 04-44 file's exact strings (9G records only its
  sizes/structure, not the key/salt text); conclusions rest on the two
  re-verified accepted captures.

## CONCLUSION

Minecraft rejected 9J at handshake-string validation, not at
cryptography: the salt bytes required a `-` under base64url while
Minecraft expects the RawStd (`+/`) alphabet, and the key carries the
same alphabet deviation (its 120-byte length and P-384 SPKI structure
are correct and identical to the accepted sessions). The 9H sessions
passed only because their salt bytes happened to avoid `+/`-positions;
their keys' identical deviation going unflagged means 9H acceptance
cannot be trusted as format approval. Single documented encoding bug
identified; nothing else in the construction is implicated.

## MINIMAL NEXT CHANGE

(Reported only — NOT implemented per mandate; no code touched.)

- In the handshake-string builder ONLY, encode both the SPKI DER and
  the 16-byte salt with the standard alphabet unpadded (mcwss
  `RawStdEncoding` semantics; Node equivalent of
  `base64(nopad, std-alphabet)`, i.e. `toString('base64')` with `=`
  padding stripped), keeping P-384 SPKI bytes, 16-byte salt, quoting,
  command name, subprotocol, and derivation byte-identical. Expected
  effect: outbound strings keep lengths 160/22 chars with `+/` where
  the bytes require it; the 9J salt becomes Std-valid; the key matches
  the documented form Minecraft PEM-parses. Re-run the identical manual
  experiment afterwards and read Minecraft's verdict verbatim; do not
  assume success.

Security: no private keys printed, reconstructed, or persisted (only
public ephemeral handshake strings already in captures, plus lengths
and OIDs); no secrets in report; no production/lab code changed; no
network experiment run; no commit/push.
