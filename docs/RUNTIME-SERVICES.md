# Runtime services (interfaces where useful, implementations where verified)

Services are thin views over probe capabilities — no raw SDK internals leak
to modules. State per service: AVAILABLE / UNAVAILABLE / BLOCKED /
RESEARCH_REQUIRED (from `RuntimeProbe`).

| Service | State | Source |
|---|---|---|
| VersionService | PARTIAL (policy table, no live string) | VersionAdapter + Levi changelog |
| LifecycleService | AVAILABLE | PL_REGISTER_MOD load/unload |
| InputService | PARTIAL (transport) | touch callbacks; semantics assumed |
| HudService | PARTIAL (submit path) | ModMenu draw commands |
| Config/ProfileService | PARTIAL (host-tested) | file stores; device paths pending |
| CrashService | PARTIAL (host-tested) | CrashGuard persistence |
| FrameService | RESEARCH_REQUIRED | no callback in SDK |
| Player/Entity/World/CameraService | RESEARCH_REQUIRED | no APIs in SDK |
| RenderService | BLOCKED | hook mechanism, no target |
| Network/PacketService | BLOCKED | no packet API |
| ScriptService | NOT_IMPLEMENTED | design only |

Engines (`engines.{h,cpp}`) compose services through `engineGate()`:
target selection, movement validation, world-query budgets, frame budgets —
pure logic, unit-tested, zero game access.
