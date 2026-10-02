# Legal & License — Xykell Client

## 1. Originality rule
Xykell is an original implementation inspired by publicly documented feature *categories*. Do NOT copy proprietary source, assets, branding, private offsets/signatures, or closed-source implementations from Lunar / W / Nova / Atlas / Flarial / Levi / Apollon or any other client.

## 2. Licenses observed (2026-10-02)
- Lunar Client: proprietary; peripheral GitHub repos under MIT/EPL/MPL/BSD. Inspiration only.
- Nova Client: GPL-3.0. Do not copy GPL code into Xykell's MIT tree. Clean-room only; keep a license boundary if interop is ever added.
- Atlas: proprietary/commercial. Inspiration only.
- Flarial dll-oss slice: AGPL-3.0 with delayed offsets + withheld private parts. Do not copy code/offsets. Pattern-level reference only.
- LeviLamina: LGPL-3.0 (non-closed parts). LeviLaunchroid: Apache-2.0. preloader-android SDK: public; pin release tags; follow their license attributions.
- Xykell own code: MIT (see LICENSE) to keep launcher + core permissive and store-friendly. Re-evaluate if linking LGPL/AGPL/GPL pieces — keep them as optional, separately-licensed boundaries, never mixed silently.

## 3. Mojang/Microsoft
Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Studios. Require legitimate Play copy; never ship game binaries; never bypass authentication, DRM, or server protections. No credential/token harvesting, no auth bypass, no server compromise, no DoS, no protection bypasses.

## 4. Cheat-safety
QoL/SAFE modules ship first. Advanced combat/movement/world-automation/network experiments live under Advanced/Experimental, OFF by default, with explicit warnings. Respect server rules; packet attacks and theft tooling are out of scope and will not be implemented.

## 5. Telemetry/privacy
No hidden telemetry or network requests. Update checks are explicit, signed + checksum-verified. Crash logs redact secrets. Local backups stay local.

## 6. What this means for contributors
- Write original code; cite public docs, not disassemblies, in comments.
- Never paste offsets/signatures from other clients; derive per-version data through our own pipeline and record provenance.
- Flag any dependency whose license conflicts with MIT before adding it.
