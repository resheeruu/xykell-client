# Privacy (default: NO telemetry)

Xykell collects nothing: no account credentials, no auth tokens, no private
worlds, no chat, no server data, no device identifiers, no telemetry of any
kind (verified: no network calls in `native/` or `app/`, only build-time
FetchContent URLs in CMake). Crash logs stay on-device and redact secrets.
If telemetry is ever proposed: explicit opt-in, documented, disableable,
minimal — otherwise it does not ship.
