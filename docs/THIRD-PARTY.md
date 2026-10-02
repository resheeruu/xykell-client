# Third-party ledger (code/assets actually used — not inspiration)

| Component | Source | License | Version/commit | Modifications | Notices/redistribution |
|---|---|---|---|---|---|
| preloader-android headers (`third_party/preloader-android/include` + root CMakeLists) | https://github.com/LiteLDev/preloader-android | Apache-2.0 (confirm LICENSE file at next vendor) | tag 0.2.3 (`92a5b2d4`) | none (verbatim, sparse) | keep provenance.md; attribute LiteLDev |
| fmt (CI-only fetch, `native/CMakeLists.txt`) | https://github.com/fmtlib/fmt | MIT (fmt license) | tag 11.2.0 | none | fetched, not vendored |
| preloader runtime lib (CI-only link, `XYKELL_LINK_PRELOADER`) | https://github.com/LiteLDev/preloader-android | Apache-2.0 | tag 0.2.3 | none | linked per Levi docs |

Everything else (Atlas/Lunar Proxy/Flarial/Apollon/WClient/Nova/Xelo ideas):
behavior/architecture references only — zero code/assets copied. See
docs/LICENSES.md for the per-project table. GPL/AGPL material stays out of
the MIT tree.
