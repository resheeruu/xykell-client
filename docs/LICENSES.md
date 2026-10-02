# Licenses (researched 2026-10-02; re-verify before reusing anything)

Xykell own code: MIT (root LICENSE). Learn from others; never paste their code, assets, branding, or offsets.

| Source | Bedrock? | Android? | Source open? | License | Reuse in Xykell? |
|---|---|---|---|---|---|
| LeviLaunchroid (LiteLDev) | yes (launcher) | yes | yes | Apache-2.0 | Use as foundation via SDK dependency; attribute. No fork without reason. |
| preloader-android SDK | yes | yes | yes | public SDK (pin tag; confirm exact license file at Task 0) | Link as external dep, don't vendor. |
| LeviLamina | server (BDS), not client | no | yes | LGPL-3.0 (non-closed parts) | Architecture ideas only; no client code to take. |
| BedrockTools (QYCottage) | yes, native Levi mod | yes | yes | README states **GPL-3.0** (repo badge says MIT — discrepancy, treat as GPL-3.0 until verified) | Pattern reference only. **Do not copy code into MIT tree.** |
| WClient (RetrivedMods) | yes, packet-level MITM-style | yes (primary) | yes, legacy archive (now closed) | GPL-3.0 | Behavior ideas only (JSON config, categories). No code copying. |
| Nova Client (TeamNovaMC) | yes, MITM proxy | yes | yes | GPL-3.0 | Same boundary as WClient. |
| Flarial dll-oss slice | yes (Windows; Android closed) | partial | partial (delayed, private parts withheld) | AGPL-3.0 | Pattern-level only, never code/offsets. |
| Lunar Proxy (lunarproxy.net) | yes, proxy (PC/mobile/console) | yes | no | proprietary, commercial | Feature-catalog inspiration (101 modules) only. |
| Lunar Client (Moonsworth) | no (Java Edition) | no | peripheral repos only | proprietary | Category/UX inspiration only. |
| Atlas Client | yes, native | yes (+iOS) | no | proprietary/commercial | Inspiration only (FPS unlock, mod menu, MaterialBin shaders). |
| Apollon Client | yes (closed APK, cheat-oriented) | yes | no authoritative source ([REQUIRES-RESEARCH]) | unknown/proprietary | UI-pinning ideas only. Never its binaries. |
| Xelo-Client | yes, launcher-based | yes | yes | GPL-3.0 | Launcher/module-pattern ideas only, same GPL boundary. |
| W Client (original prompt name) | unverified | — | — | — | [REQUIRES-RESEARCH]; WClient above is the verified artifact. |

## Reuse log (required for every reused component)
No third-party code vendored yet. When reuse happens, record: source, license, version/commit, modifications, required notices, redistribution requirements — here.

## Rules
- GPL/AGPL material stays outside the MIT tree (separate optional boundary or not at all).
- Offsets/signatures are derived via our own pipeline with provenance, never pasted.
- Xykell branding, UI, icons, assets, cosmetics are original.
