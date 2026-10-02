#pragma once

#include <string>

// Task 4: Mod Menu registration + config callbacks. Uses only the verified
// preloader 0.2.3 pl::modmenu API (ModuleBuilder, ConfigType::Toggle).
namespace xykell {

inline constexpr const char* kMenuModuleId = "xykell-core";

// Registers the "Xykell Core" Mod Menu module. Returns the preloader's verdict.
// Callbacks apply toggle/config changes to XykellCore state and log them.
bool registerMenuModule(const std::string& modId);

// Unregisters on unload. No-op if never registered.
void unregisterMenuModule();

} // namespace xykell
