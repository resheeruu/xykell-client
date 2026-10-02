# Launcher integration (bridges + honest gaps)

## Native store bridge
The launcher app and the Levi game process are separate Android sandboxes:
no JNI path can reach Levi's dirs, so no JNI bridge is built (it would only
read a second store — forbidden). Integration instead:
- Export/import profile JSON through app-visible files (`Profiles` screen,
  SAF picker + external files dir, corrupt imports rejected). Native store
  stays authoritative; launcher states the separation on-screen.
- Registry browser reads the same `registry/features.json` (build-time asset).

## PLAY / Quick Launch verdict
Levi Quick Launch = Minecraft URI actions (screens/servers/Realms/worlds).
Those address the GAME, not a launcher+preloader configuration — no verified
action launches a specific isolated version with Xykell's levipack. PLAY stays
disabled + NOT WIRED. Missing prerequisite: exact Levi launch action for a
configured version (needs device/Levi-source read of the action list).

## Safe mode surfacing
Native CrashGuard owns the flag; launcher shows the static contract
(SAFE MODE / Reason / Disabled modules) until a bridge carries live state.
