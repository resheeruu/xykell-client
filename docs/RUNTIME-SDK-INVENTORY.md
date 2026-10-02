# Runtime SDK inventory (preloader-android 0.2.3, vendored 2026-10-02)

Source of truth: `third_party/preloader-android/include/pl/*` (verbatim,
see `third_party/provenance.md`). No other SDK is linked.

| Capability | API / source | Headers | Status |
|---|---|---|---|
| init/shutdown | `PL_REGISTER_MOD` + `load/enable/disable/unload`, export `PLGetModRegistration` | `pl/Mod.hpp` (338 lines) | VERIFIED (builds, M1) |
| mod identity/dirs | `NativeMod::current/getId/getName/getConfigDir/getDataDir/getModDir`, `ModContext::{id,configDir,dataDir,resourceDir}` | `pl/Mod.hpp` | VERIFIED |
| logging | `pl::log::Logger::{info,debug,warn,error}`, `__android_log_print` + fmt | `pl/Logger.hpp` (needs `<fmt/*>`, `<android/log.h>`) | VERIFIED |
| typed config | `pl::config::ConfigFile<T>::load/save`, `Schema<T>` customization, nlohmann::json inside | `pl/Config.hpp` (558 lines) | PARTIAL (read, not yet adopted; own json_min used) |
| mod menu | `ModuleBuilder` (Toggle/SliderInt/SliderFloat/Radio/Color/Keybind/Text + onToggle/onConfigChanged), `ButtonBuilder`, `registerModule/unregisterModule` | `pl/ModMenu.hpp` (395 lines) | VERIFIED |
| overlay draw | `submitDrawCommands(span<DrawCommand>)`: Text/Rect/Line/RectFilled/CircleFilled/TriangleFilled/Image; `registerFont/registerImage` | `pl/ModMenu.hpp` | PARTIAL (submit path builds; render-lifecycle tie unknown) |
| input | `registerTouch/Key/TextInput/MouseCallback`, `show/hideKeyboard`; TouchEvent{action,pointerId,x,y} | `pl/Input.hpp` (84 lines) | PARTIAL (transport builds; layer semantics assumed) |
| hooks | `hook/unhook`, `HookHandle` RAII, priorities | `pl/memory/Hook.hpp` | NOT USED (no verified target; mechanism only) |
| patches | `writeBytes/readBytes/revertPatch/revertAllPatches`, `PatchHandle` | `pl/memory/Patch.hpp` | NOT USED (same reason) |
| signatures | `resolveSignature(s)/resolveSignatures` in a named module | `pl/memory/Signature.hpp` | NOT USED (no verified signature) |
| vtable | helpers | `pl/memory/Vtable.hpp` | NOT USED |
| packets | — (nothing in any header) | — | BLOCKED (no surface) |
| frame/tick | — | — | RESEARCH_REQUIRED |
| player/entity/world/camera | — | — | RESEARCH_REQUIRED |

Notes: `pl/Config.hpp` bundles nlohmann::json internally (not exposed to
mods; Xykell keeps its own `json_min` to stay dependency-free). Action-code
mapping of `TouchEvent.action` (assumed Android MotionEvent codes) is the one
unverified reading in the input path — flagged in code and tests.
