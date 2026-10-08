#pragma once

// App -> game observation feed client (loopback TCP). Pure C++; runs in the
// game process where the HUD lives. One JSON line per observation, same
// field vocabulary as the /wsserver translator (ObservationTranslator):
//   {"t":"travel","id":...,"at":...,"x":...,"y":...,"z":...,
//    "yaw":...,"m":...,"method":...}
//   {"t":"chat","id":...,"at":...,"sender":...,"msg":...}
// The client is a SINK: it only feeds the process observation consumer;
// it never sends on the wire (connect/read/close only). Loopback only,
// no auth, no persistence — same trust model as the 8765 WS server.
#include <atomic>
#include <string>

#include "xykell/runtime_observation_consumer.h"

namespace xykell::feed {

inline constexpr const char* kDefaultHost = "127.0.0.1";
inline constexpr int kDefaultPort = 8790;
inline constexpr int kRetryDelayMs = 200;
inline constexpr int kRecvTimeoutMs = 500;

// Apply one wire line to the consumer. Returns false for malformed or
// unknown lines (state untouched). Pure function; no I/O.
bool applyLine(runtime::ObservationConsumer& consumer, const std::string& line);

// Blocking connect/retry/read loop until `stop` is set. Retries every
// kRetryDelayMs while the server is down; recv timeout bounds shutdown.
void runLoop(runtime::ObservationConsumer& consumer, const char* host, int port,
             std::atomic<bool>& stop);

// Process-wide feed thread bound to sharedObservationConsumer() (the copy
// the in-game HUD reads). start() is idempotent; stop() joins.
void start();
void stop();

} // namespace xykell::feed
