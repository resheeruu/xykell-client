# Stage-8 runtime architecture (reference corpus study)

Black-box/static comparison only. No code, assets, or behavior copied.

## Where integration appears to occur (per client)

| Client | Boundary | Java/native | Launcher/preloader | Relay/proxy | Wrapper? |
|---|---|---|---|---|---|
| Flarial | file-tree + GLES client lib + backend | both | launcher (PairIP) | no | NO (companion app) |
| Lunar BE | RN/Go app + backend proxy refs | JS/Go/native | Expo launcher | backend service (lunarproxy) | NO |
| Ambient | Yurai dlopen/hook + versioned gamecores | Java + native | launcher + hook fw | no | NO (injects) |
| WClient | Dalvik modules + auth + data tables | Java | thin shell | no | NO |
| LeviLauncher | preloader mod runtime + handoff | Java + native | launcher + preloader | no | NO (hosts MC launch) |
| Atlas | SDL app + loader + opaque payload | native-heavy | PairIP launcher | no | UNKNOWN |
| Lumina | local relay sessions + codecs + LAN adv | Java + native | relay console | YES (local) | NO (proxies) |

## Status per claim class

- OBSERVED (static evidence): file-tree management; preloader mod API;
  Yurai hook/loader API; relay sessions/codecs/advertisement; auth stacks;
  backend endpoints; exported MC activities + `minecraft://` deep links.
- DOCUMENTED (public sources): Android activity export/launch semantics;
  Bedrock Scripting API (in-game JS); dedicated server product; WebSocket
  code-connection feature (game-initiated outbound localhost); XAL browser
  auth flow pattern.
- INFERRED: per-module in-game efficacy (all clients); codec field maps;
  libtap/loader/shin roles; Lunar JS app logic.
- UNKNOWN: exact launch extras; server-side enforcement postures.
- NOT REPRODUCIBLE (by policy or practicality): gamecore redistribution;
  hook/injection frameworks; proprietary relay protocol fields; PairIP
  licensing internals; auth-token flows.

## Independently reproducible portions

System-intent launch; exported-activity invocation; `minecraft://` URI
firing (user-initiated); file-level world/pack management via SAF/user
grant; localhost service hosting (relay/WS) with user-driven game-side
enablement; local discovery advertisement of OWN identity; overlay UI via
system-window permission. All else requires a proven game-side surface.
