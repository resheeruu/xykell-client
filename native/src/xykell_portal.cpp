// Portal backend storage: backend-agnostic (no SDK includes).
#include "xykell/portal.h"

namespace xykell::portal {
namespace {

Backend gBackend;

} // namespace

void setBackend(const Backend& b) { gBackend = b; }
const Backend& backend() { return gBackend; }

} // namespace xykell::portal
