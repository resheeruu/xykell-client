# Xykell AI system (design + planner kernel; no model runtime)

Architecture: goal text → `ai::planForGoal` (keyword match over the live
registry model) → per-step availability from the capability gate → plan with
reasons → execution ONLY through Xykell module APIs when AVAILABLE.
STOP/PAUSE/CANCEL always available; quarantined/BLOCKED steps refuse.

Boundaries (non-negotiable): no credential/token/auth access, no server
compromise, no anti-cheat evasion, no hidden traffic, no arbitrary native
execution. The planner only reads registry + probe + module states; it
cannot invent capabilities (unmatched goals yield empty plans).

Status: planner kernel implemented + unit-tested. No LLM, no execution
engine, no game-state access — those need runtime sources first.
