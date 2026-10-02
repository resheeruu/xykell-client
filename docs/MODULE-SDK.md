# Xykell Module SDK (for future third-party modules)

A module declares: id (`xykell.<category>.<suffix>`), name, category,
description, version, platforms, requires[], settings[] (typed schema),
events, dependencies, permissions. Lifecycle: discover → load → initialize
→ enable → disable → shutdown → quarantine, through `ModuleManager` +
`EventBus` + capability gate (`canEnable`). Settings validate against the
schema; corrupt values fall back, never crash. Registry entry required
before any UI appears (validator enforces schema; audit enforces honesty).

Status: interface defined (`module_manager.h`, `event_bus.h`,
`runtime_probe.h`); out-of-tree module loading is NOT_IMPLEMENTED.
