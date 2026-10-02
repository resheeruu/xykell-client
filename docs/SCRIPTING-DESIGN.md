# Scripting design (RESEARCH_ONLY — no runtime, no execution)

Possible future: Lua script → Sandbox → Xykell public API → Capability
Manager → Runtime Services. Engine choice (Lua 5.4.x preferred) requires a
license + size review before any code; shortlist must confirm: embeddable C,
sandboxable (no `os`/`io`/loader by default), <500KB stripped.

Scripts may eventually touch: HUD widgets, configs, notifications, module
settings (gated values only), safe events. Scripts must NEVER receive:
credentials/tokens, unrestricted filesystem/network, arbitrary native
execution, auth secrets, hidden persistence. Permissions explicit per script;
distribution signed + checksum-verified, user-installed, no silent auto-run.

Status: RESEARCH_ONLY. No engine vendored, no API frozen, no scripts execute.
