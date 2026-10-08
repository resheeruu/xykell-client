# Changelog

Maintained in `docs/CHANGELOG.md`. Summary:

## [Unreleased]
- **Relay now terminates both Bedrock legs at runtime.** `RelayService` drives a
  `RelaySession` through a new `RelaySessionDriver` (SERVER role toward the game,
  CLIENT upstream) instead of the transparent pipe, and installs a
  `RelayObservation -> ModuleRuntime` listener chain. Status distinguishes
  STOPPED / HANDSHAKING / ONLINE, because a bound socket answers pings long
  before the upstream login is rewritten.
- **48 module transforms are reachable.** They were written and host-tested but
  nothing called them: `RelaySession` was only ever constructed in its own test
  with `listener = RelayListener.PASS`, and the transparent pipe cannot decrypt,
  so no transform could run at all. `ModuleRuntime` owns the session
  `ModuleContext` and dispatches every implemented id in a fixed order;
  `ModuleFlags` reads the active profile; `ModuleTapRunner` replays the
  input-injection plans through the accessibility service, the same gesture
  surface a finger uses — no UseItem or Interact packet is ever forged.
- **Four HUD ids delivered from the observation path** (`hud.health`,
  `hud.low_health`, `hud.entity_counter`, `hud.tps`), all previously blocked on
  "no observation source". Vitals fields are optional and merge independently;
  ticks-per-second is derived from two SetTime samples and stays unavailable
  rather than reporting zero from one.
- **Two further dead-code bugs fixed.** `EntityTable.observe` was called from
  nowhere, so all 18 derived entity views saw zero entities; and the self id was
  only learned while a movement module happened to be enabled, which made the
  player count as their own target.
- Registry evidence corrected: `combat.velocity` claimed `SetActorMotion 0x1B`
  (that is EntityEvent, the id drift the suite exists to catch) and the wrong
  leg; corrected to `0x28`, server->game, with six other stale entries.
- Deleted the superseded raw-datagram `BedrockTap`; only its `Event` vocabulary
  survived, and it moved onto `TapTranslator`.
- Master-spec alignment: Levi-first foundation, capability classes, module API, M1 proof UI, expanded doc set.
- M1 implementation plan saved (`docs/superpowers/plans/2026-10-02-milestone-1-core.md`).

## 2026-10-02
- Approved Approach-A native spec + Phase 0 research baseline + repo skeleton (docs only, no modules).
