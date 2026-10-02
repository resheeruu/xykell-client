#pragma once

// LEGACY COMPATIBILITY MODE backend constructor (preloader 0.2.3).
// Separate header so the ::pl::log::Logger reference resolves against the
// REAL preloader headers (portal.h must stay free of pl/ includes to keep
// all other translation units decoupled).
#include <pl/Logger.hpp>

#include "xykell/portal.h"

namespace xykell::portal {

Backend preloaderBackend(::pl::log::Logger& logger);

} // namespace xykell::portal
