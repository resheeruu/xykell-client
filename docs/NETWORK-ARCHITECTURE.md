# Network architecture (optional, modular, never the only path)

```
Xykell Native Core → Packet Adapter → Packet Events → Network Modules
```

## 1. Scope
M1: ping, connection status, server info, latency graph, diagnostics, dev packet logging. Reconnect helpers where the game API allows. Everything behind the PACKET capability + feature flags, OFF unless enabled.

## 2. What it is not
Packet functionality is one engine beside Native/Render/Script — never the sole architecture. No credential/token collection, no auth bypass, no server-compromise/DoS/packet-attack tooling, no account or protection bypasses. Lunar Proxy/WClient prove what packet-level features look like; Xykell reimplements equivalents independently and only the non-malicious subset (server-rule-respecting QoL + diagnostics).

## 3. Console/MITM note
A future opt-in MITM companion (Nova/WClient pattern) could extend packet modules to consoles, but it is a separate product decision with its own review — not M1, not assumed.
