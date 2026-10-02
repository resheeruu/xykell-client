# Scripting (Flarial-style, sandboxed, later)

## 1. Shape (design only — no runtime in M1)
`Script → module | command | HUD | settings | events | lifecycle`.
Lua-style surface (illustrative):
`module("Example")` with `onEnable()` / `onTick()` / `onRender()`.
Keep the runtime tiny; reuse an existing embeddable engine only if license + size allow (decision at implementation time, recorded in LICENSES.md).

## 2. Sandbox (non-negotiable)
No arbitrary downloaded scripts executing unrestricted native/system commands. Scripts get: module lifecycle, HUD widgets, typed settings, subscribed events. No filesystem/network/process access by default; any extension is allow-listed, versioned, and user-approved per script.

## 3. Distribution
Scripts are signed, checksum-verified, user-installed. No silent auto-install. Malicious-behavior categories (credential access, exfiltration, RCE) are rejected by API design, not just policy.
