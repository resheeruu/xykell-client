# Runtime signature pipeline (infrastructure present, zero real signatures)

Components (`sigscan.{h,cpp}`, tested in `test_stages_sigscan`):
`SignaturePattern` (bytes + 0x00/0xFF mask; rejects empty/mismatched/
all-wildcard), `scan()` (correctness-first linear match), `validate()`
(unique-in-range → Supported; multi → AMBIGUOUS; none/out-of-range →
NotFound), `SignatureDatabase` (per-version profiles; rejects duplicates,
invalid patterns, and entries missing source/evidence).

Rules: no copied signatures (closed-source or GPL) ever enter the database;
every entry needs id/version/pattern/mask/constraints/validation/source/
confidence/evidence. First match is never accepted blindly. Nothing derived
yet — the database ships empty by design.
