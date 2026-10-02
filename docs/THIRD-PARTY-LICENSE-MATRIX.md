# Third-party license matrix (§43)

| Source | URL | License | Files copied | Modifications | Attribution/redistribution |
|---|---|---|---|---|---|
| preloader-android headers | https://github.com/LiteLDev/preloader-android | Apache-2.0 | `third_party/preloader-android/include/**` + root CMakeLists (verbatim, sparse @0.2.3 `92a5b2d`) | none | keep `third_party/provenance.md`; Apache notice preserved via upstream files |
| fmt (CI-only) | https://github.com/fmtlib/fmt | MIT | none vendored (FetchContent @11.2.0 when system fmt absent) | none | fetched at build; MIT notice travels with fmt |
| preloader runtime lib (CI-only link) | https://github.com/LiteLDev/preloader-android | Apache-2.0 | none vendored | none | documented Levi path |
| LeviLaunchroid (docs + behavior) | https://github.com/LiteLDev/LeviLaunchroid | Apache-2.0 | none | n/a | launcher UX patterns referenced, not copied |
| BedrockTools (shape reference) | https://github.com/QYCottage/BedrockTools | GPL-3.0 (claimed; badge says MIT — unresolved) | none | n/a | NO code taken; boundary kept |
| WClient / Nova / Xelo (behavior) | GitHub archives | GPL-3.0 | none | n/a | NO code taken; boundary kept |
| Flarial dll-oss (pattern) | https://github.com/flarialmc/dll-oss | AGPL-3.0 | none | n/a | NO code/offsets taken |
| Lunar Proxy / Atlas / Apollon (catalog/UX) | sites | proprietary/unknown | none | n/a | inspiration only |

Rule: GPL/AGPL material never enters the MIT tree. New entries must be added
here BEFORE the code lands.
