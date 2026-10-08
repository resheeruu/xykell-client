// Process-wide observation consumer instance (shared JNI feed + HUD bind).
// Header-only consumer, one static per process. No I/O, no platform.
#include "xykell/runtime_observation_consumer.h"

namespace xykell::runtime {

ObservationConsumer& sharedObservationConsumer() {
    static ObservationConsumer instance;
    return instance;
}

} // namespace xykell::runtime
