#!/usr/bin/env python3
"""Generate registry/features.json — the honest Xykell feature universe.

Every entry carries a compatibility state; automation without a verified
Bedrock runtime source is RESEARCH_REQUIRED, never fake-supported.
M1-proven items are PARTIAL (build-verified, runtime pending device test).
Run: python3 scripts/registry/generate.py. Validate: validate.py.
"""
import json
from pathlib import Path

RR = "RESEARCH_REQUIRED"
NI = "NOT_IMPLEMENTED"
PARTIAL = "PARTIAL"

# (suffix, [capabilities], implementation, [sources], notes)
COMBAT = [
    ("aim_assist", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_clicker", ["INPUT", "HYBRID"], "native", ["Lunar Proxy", "Apollon"], ""),
    ("afk_clicker", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("trigger_bot", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("kill_aura", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "needs targeting+attack sources"),
    ("tp_aura", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("reach", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], ""),
    ("hitbox", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("velocity", ["PACKET", "HYBRID"], "packet", ["Lunar Proxy"], "locality TBD"),
    ("knockback", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("knockback_delay", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("backtrack", ["PACKET", "HYBRID"], "packet", ["Lunar Proxy"], ""),
    ("auto_crit", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_totem", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_potion", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_crystal", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("anti_crystal", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_cart", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_web", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_switch", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("mace_swap", ["HYBRID"], "native", ["Lunar Proxy"], "Bedrock mechanic mapping TBD"),
    ("mace_damage", ["HYBRID"], "native", ["Lunar Proxy"], "Bedrock mechanic mapping TBD"),
    ("shield_disabler", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("anchor_aura", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("target_hud", ["UI", "HYBRID"], "native", ["Lunar Proxy"], "needs target source"),
    ("target_selector", ["HYBRID"], "native", ["Lunar Proxy", "Flarial"], ""),
    ("friend_filter", ["UI"], "native", ["Lunar Proxy"], "local list; needs target source"),
    ("combat_settings", ["UI"], "native", ["Lunar Proxy"], ""),
    ("double_click", ["HYBRID"], "native", ["Lunar Proxy"], "attack timing source TBD"),
    ("auto_log", ["HYBRID"], "native", ["Lunar Proxy"], "safe-disconnect rules TBD"),
    ("mob_aura", ["HYBRID"], "native", ["Apollon"], "mob targeting; needs entity source"),
]

MOVEMENT = [
    ("sprint", ["INPUT"], "native", ["Flarial", "Lunar Proxy"], "locality TBD"),
    ("auto_sprint", ["INPUT"], "native", ["Lunar Proxy", "Flarial"], "locality TBD; Levi inbuilt AutoSprint exists — integrate, do not duplicate"),
    ("speed", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "locality TBD"),
    ("no_slow", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("step", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("air_jump", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("fly", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "server-visible risk; locality TBD"),
    ("motion_fly", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("jetpack", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("phase", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("glide", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("auto_elytra", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("auto_rocket", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("bunny_hop", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "locality TBD"),
    ("jump_boost", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("levitate", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("slow_falling", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("water_walk", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("tap_tp", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),    ("safe_walk", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("no_fall", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("movement_correction", ["PACKET", "HYBRID"], "packet", ["Lunar Proxy"], "locality TBD"),
    ("timer", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("ladder_fly", ["HYBRID"], "native", ["Apollon"], "locality TBD; ladder detection TBD"),
    ("block_fly", ["HYBRID"], "native", ["Apollon"], "locality TBD; placement source TBD"),
]

PLAYER = [
    ("inventory_manager", ["UI", "HYBRID"], "native", ["Lunar Proxy"], ""),
    ("fast_eat", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("fast_interact", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("haste", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("slow_mine", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("no_fall", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("no_blindness", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_nausea", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_fire", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_hurt_cam", ["RENDER"], "native", ["Lunar Proxy", "Atlas"], "needs render path"),
    ("anti_immobile", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("death_position", ["WORLD", "UI"], "native", ["Apollon", "Flarial"], "needs death source"),
    ("friend_alerts", ["UI"], "native", ["Lunar Proxy"], ""),
    ("nickname", ["UI"], "native", ["BedrockTools"], "local-only"),
    ("fake_stats", ["UI"], "native", ["Lunar Proxy"], "local display only"),
    ("spam", ["HYBRID"], "native", ["Lunar Proxy"], "rate-limited; server rules risk"),
    ("mod_alerts", ["UI"], "native", ["Lunar Proxy"], "server-specific mod lists (e.g. Lifeboat/DonutSMP)"),
]

AUTOMATION = [
    ("auto_eat", ["HYBRID"], "native", ["Lunar Proxy"], "interruptible; cooldowns required"),
    ("auto_fish", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "interruptible"),
    ("auto_refill", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_steal", ["HYBRID"], "native", ["Lunar Proxy"], "server rules risk"),
    ("auto_tool", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_equip", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_armor", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_sign", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_sell", ["HYBRID"], "native", ["Lunar Proxy"], "server rules risk"),
    ("auto_mine", ["HYBRID"], "native", ["Lunar Proxy"], "throttled; cancellable"),
    ("auto_dig", ["HYBRID"], "native", ["Lunar Proxy"], "throttled; cancellable"),
    ("inventory_cleaner", ["HYBRID"], "native", ["Apollon"], "rules for protected items TBD"),
    ("no_break_delay", ["HYBRID"], "native", ["Apollon"], "mechanism TBD"),
    ("ghost", ["HYBRID"], "native", ["Lunar Proxy"], "Lunar Ghost hides commands/chat for recording — NOT phase-through"),
    ("auto_tool_swap", ["HYBRID"], "native", ["Lunar Proxy"], "best-tool-for-target-block; distinct from auto_tool"),
    ("auto_gg", ["HYBRID"], "native", ["Flarial"], "server-specific win messages (Hive/Zeqa/CubeCraft/Lifeboat/Galaxite/Mineville)"),
    ("command_hotkey", ["INPUT", "UI"], "native", ["Flarial"], "shortcut buttons send commands"),
    ("text_hotkey", ["INPUT", "UI"], "native", ["Flarial"], "shortcut buttons send chat text"),
    ("death_logger", ["WORLD", "UI"], "native", ["Flarial"], "logs death coords; needs death source"),
    ("inventory_lock", ["HYBRID"], "native", ["Flarial"], "lock items from being dropped"),
    ("item_tracker", ["HYBRID", "UI"], "native", ["Flarial"], "picked/dropped item display"),
    ("tnt_timer", ["WORLD", "UI"], "native", ["Flarial"], "TNT countdown; needs world source"),
    ("player_notifier", ["UI"], "native", ["Flarial"], "notify when a player is on server; needs player-list source"),
]

WORLD = [
    ("scaffold", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "server rules risk"),
    ("nuker", ["HYBRID"], "native", ["Lunar Proxy"], "server rules risk; throttled by design"),
    ("fast_break", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("fast_place", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("spawner_protect", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("block_esp", ["RENDER", "WORLD"], "native", ["Lunar Proxy"], "needs world+render sources"),
    ("block_tracer", ["RENDER", "WORLD"], "native", ["Lunar Proxy"], "needs world+render sources"),
    ("xray", ["RENDER", "WORLD"], "native", ["Lunar Proxy", "Apollon"], "needs world+render sources"),
    ("ore_esp", ["RENDER", "WORLD"], "native", ["Lunar Proxy"], "needs world+render sources"),
    ("chunk_borders", ["RENDER", "WORLD"], "native", ["Lunar Proxy", "BedrockTools"], "needs world source"),
    ("chunk_finder", ["WORLD", "UI"], "native", ["Lunar Proxy"], "needs chunk data"),
    ("new_chunks", ["WORLD", "UI"], "native", ["Lunar Proxy"], "needs chunk data"),
    ("hole_esp", ["RENDER", "WORLD"], "native", ["Lunar Proxy"], "needs world+render sources"),
    ("schematic", ["WORLD", "UI"], "native", ["Lunar Proxy"], ""),
    ("waypoints", ["WORLD", "UI"], "native", ["Atlas", "Flarial"], "needs position source"),
    ("minimap", ["WORLD", "UI"], "native", ["Atlas"], "needs world+position sources"),
    ("world_markers", ["WORLD", "UI"], "native", ["Atlas", "Flarial"], "needs position source"),
]

VISUAL = [
    ("esp", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("player_esp", ["RENDER"], "native", ["Lunar Proxy", "Apollon"], "needs entity source"),
    ("entity_esp", ["RENDER"], "native", ["Lunar Proxy"], "needs entity source"),
    ("item_esp", ["RENDER"], "native", ["Lunar Proxy"], "needs entity source"),
    ("tracers", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("block_tracers", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("free_look", ["RENDER", "INPUT"], "native", ["Flarial"], "needs camera path"),
    ("free_cam", ["RENDER", "INPUT"], "native", ["Lunar Proxy"], "needs camera path"),
    ("xray", ["RENDER", "WORLD"], "native", ["Lunar Proxy", "Apollon"], "see world.xray"),
    ("fullbright", ["RENDER"], "native", ["Flarial", "Apollon", "BedrockTools"], "needs lighting path"),
    ("no_invisible", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_fire", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_weather", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_blindness", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("no_nausea", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("chunk_borders", ["RENDER", "WORLD"], "native", ["Lunar Proxy", "BedrockTools"], "see world.chunk_borders"),
    ("new_chunks", ["WORLD", "UI"], "native", ["Lunar Proxy"], "see world.new_chunks"),
    ("hole_esp", ["RENDER", "WORLD"], "native", ["Lunar Proxy"], "see world.hole_esp"),
    ("spawner_esp", ["RENDER", "WORLD"], "native", ["Lunar Proxy"], "needs world+render sources"),
    ("spawner_ping", ["WORLD", "UI"], "native", ["Lunar Proxy"], "ping on chunk load; optional auto-disconnect is server-rules sensitive"),
    ("sus_chunk_finder", ["WORLD", "UI"], "native", ["Lunar Proxy"], "suspicious-chunk heuristics TBD"),
    ("bed_esp", ["RENDER", "WORLD"], "native", ["Apollon"], "needs world+render sources"),    ("waypoints", ["WORLD", "UI"], "native", ["Atlas", "Flarial"], "see world.waypoints"),
    ("minimap", ["WORLD", "UI"], "native", ["Atlas"], "see world.minimap"),
    ("schematic", ["WORLD", "UI"], "native", ["Lunar Proxy"], "see world.schematic"),
    ("view_model", ["RENDER"], "native", ["Atlas", "BedrockTools"], "needs viewmodel path"),
    ("zoom", ["RENDER", "INPUT"], "native", ["Atlas", "Flarial", "BedrockTools"], "needs camera/FOV path"),
    ("motion_blur", ["RENDER"], "native", ["BedrockTools"], "needs render path"),
    ("shader_loader", ["RENDER"], "native", ["Atlas"], "MaterialBin-style; needs material path"),
    ("custom_crosshair", ["UI", "RENDER"], "native", ["Atlas", "Flarial"], "overlay-drawn first"),
    ("hit_effects", ["RENDER"], "native", ["Lunar Proxy"], "needs render path"),
    ("particle_controls", ["RENDER"], "native", ["Atlas"], "needs particle path"),
    ("fog_controls", ["RENDER"], "native", ["Atlas", "BedrockTools"], "needs fog path"),
    ("camera_controls", ["RENDER"], "native", ["Lunar Proxy"], "needs camera path"),
    ("block_outline", ["RENDER"], "native", ["Flarial"], "block outline color/style; needs render path"),
    ("item_physics", ["RENDER"], "native", ["Flarial"], "dropped-item rotation; needs render path"),
    ("nametag", ["RENDER"], "native", ["Flarial"], "third-person self nametag; needs render path"),
    ("gui_scale", ["RENDER", "UI"], "native", ["Flarial"], "GUI scale beyond limits; needs render path"),
    ("time_changer", ["RENDER"], "native", ["Flarial"], "client-side presentation; needs render path"),
    ("weather_changer", ["RENDER"], "native", ["Flarial"], "client-side presentation; needs render path"),
]

HUD = [
    ("fps", ["UI"], "native", ["Atlas", "Flarial", "BedrockTools"], "needs frame-tick source"),
    ("cps", ["UI", "INPUT"], "native", ["Flarial", "Nova"], "needs input tap stream (have callback)"),
    ("coordinates", ["UI"], "native", ["Flarial", "BedrockTools"], "needs player-position source"),
    ("ping", ["UI", "PACKET"], "packet", ["Flarial", "BedrockTools"], "needs latency source"),
    ("tps", ["UI"], "native", ["Flarial"], "measurability TBD"),
    ("armor", ["UI"], "native", ["Flarial"], "needs inventory source"),
    ("health", ["UI"], "native", ["Flarial"], "needs player source"),
    ("hunger", ["UI"], "native", ["Flarial"], "needs player source"),
    ("position", ["UI"], "native", ["Flarial"], "see coordinates"),
    ("direction", ["UI"], "native", ["Flarial"], "needs rotation source"),
    ("biome", ["UI"], "native", ["Flarial"], "needs world source"),
    ("clock", ["UI"], "native", ["Flarial"], "device clock available; game-time TBD"),
    ("keystrokes", ["UI", "INPUT"], "native", ["Flarial", "BedrockTools"], "needs input stream (have callback)"),
    ("touch_indicators", ["UI", "INPUT"], "native", ["Xykell"], "have touch callback"),
    ("target_info", ["UI"], "native", ["Lunar Proxy"], "needs target source"),
    ("arraylist", ["UI"], "native", ["Lunar Proxy"], "module-list display"),
    ("watermark", ["UI"], "native", ["Flarial"], "static text via draw commands"),
    ("notifications", ["UI"], "native", ["Flarial"], "event-driven; needs EventBus"),
    ("session_stats", ["UI"], "native", ["Flarial"], "local timers available"),
    ("server_info", ["UI"], "native", ["Flarial"], "needs connection source"),
    ("entity_counter", ["UI"], "native", ["Flarial"], "needs client entity registry source"),
    ("hardware_stats", ["UI"], "native", ["Flarial"], "device mem/CPU via OS APIs"),
    ("inventory_hud", ["UI"], "native", ["Flarial"], "needs inventory source"),
    ("ip_display", ["UI"], "native", ["Flarial"], "needs connection source"),
    ("low_health", ["UI"], "native", ["Flarial"], "warning; needs health source"),
    ("potion_hud", ["UI"], "native", ["Flarial"], "needs potion-effect source"),
    ("speed_meter", ["UI"], "native", ["Flarial"], "needs movement source"),
    ("stop_watch", ["UI"], "native", ["Flarial"], "local timer; no game source needed"),
    ("subtitles", ["UI"], "native", ["Flarial"], "needs sound-event source"),
    ("tab_list", ["UI"], "native", ["Flarial"], "needs player-list source"),
    ("totem_counter", ["UI"], "native", ["Flarial"], "needs inventory source"),
    ("movable_hud", ["UI"], "native", ["Flarial"], "editor feature; see hud editor"),
]

NETWORK = [
    ("ping", ["PACKET", "UI"], "packet", ["WClient", "Nova"], "needs packet/latency source"),
    ("connection_status", ["PACKET", "UI"], "packet", ["WClient"], "needs connection source"),
    ("latency_graph", ["PACKET", "UI"], "packet", ["Xykell"], "needs latency source"),
    ("network_diagnostics", ["PACKET", "UI"], "packet", ["WClient"], "needs connection source"),
    ("packet_monitor", ["PACKET", "UI"], "packet", ["WClient", "Nova"], "no verified packet API"),
    ("packet_logger", ["PACKET", "UI"], "packet", ["WClient"], "dev only; no verified packet API"),
]

PERFORMANCE = [
    ("fps_limiter", ["NATIVE", "RENDER"], "native", ["Atlas", "Flarial"], "needs frame path"),
    ("fps_unlocker", ["NATIVE", "RENDER"], "native", ["Atlas", "BedrockTools"], "needs frame path; measure gains"),
    ("dynamic_fps", ["NATIVE"], "native", ["Flarial"], "needs frame path"),
    ("background_fps", ["NATIVE"], "native", ["Flarial"], "needs frame path"),
    ("frame_graph", ["UI"], "native", ["Xykell"], "needs frame-tick source"),
    ("entity_opt", ["NATIVE"], "native", ["Atlas"], "needs entity path"),
    ("render_opt", ["NATIVE", "RENDER"], "native", ["Atlas"], "needs render path"),
    ("particle_controls", ["RENDER"], "native", ["Atlas"], "see visual.particle_controls"),
    ("animation_controls", ["RENDER"], "native", ["Atlas"], "needs animation path"),
    ("cloud_controls", ["RENDER"], "native", ["Atlas"], "needs render path"),
    ("weather_opt", ["RENDER"], "native", ["Atlas"], "needs weather path"),
    ("fog_controls", ["RENDER"], "native", ["Atlas", "BedrockTools"], "see visual.fog_controls"),
    ("memory_info", ["UI"], "native", ["Xykell"], "OS APIs available"),
    ("cpu_info", ["UI"], "native", ["Xykell"], "OS APIs available"),
    ("gpu_info", ["UI"], "native", ["Xykell"], "OS APIs available"),
    ("perf_profiles", ["UI"], "native", ["Xykell"], "needs PerformanceManager"),
    ("low_end_mode", ["NATIVE"], "native", ["Atlas"], "needs perf paths"),
    ("render_distance", ["NATIVE"], "native", ["Atlas", "Flarial"], "needs settings path"),
]

MISC = [
    ("streamer_mode", ["UI"], "native", ["Xykell"], "overlay redaction"),
    ("privacy_mode", ["UI"], "native", ["Xykell"], "overlay redaction"),
    ("chat_timestamps", ["UI"], "native", ["BedrockTools"], "needs chat source"),
    ("chat_filter", ["UI"], "native", ["Xykell"], "needs chat source"),
    ("custom_nicknames", ["UI"], "native", ["BedrockTools"], "local-only"),
    ("screenshot_share", ["UI"], "native", ["Xykell"], "needs capture path"),
    ("quick_perspective", ["RENDER", "INPUT"], "native", ["Atlas"], "needs camera path"),
    ("quick_drop", ["INPUT"], "native", ["Atlas"], "needs input path; Levi inbuilt Quick Drop exists — integrate, do not duplicate"),
    ("hide_hud", ["UI"], "native", ["Atlas"], "overlay clear available"),
    ("toggle_sprint", ["INPUT"], "native", ["Flarial"], "locality TBD"),
    ("toggle_sneak", ["INPUT"], "native", ["Flarial"], "locality TBD"),
    ("screenshot_tools", ["UI"], "native", ["Xykell"], "needs capture path"),
    ("fake_op", ["UI"], "native", ["Lunar Proxy"], "client-side display only; never server authority"),
    ("shulker_tooltip", ["UI"], "native", ["Lunar Proxy"], "needs container source"),
    ("death_lightning", ["UI", "RENDER"], "native", ["Lunar Proxy"], "client-side effect only"),
    ("java_mode", ["UI"], "native", ["Lunar Proxy"], "client-side timings; Bedrock mapping TBD"),
    ("timer", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("disabler", ["PACKET"], "packet", ["Lunar Proxy"], "no verified packet API; server rules risk"),
    ("skin_stealer", ["UI"], "native", ["Lunar Proxy"], "Lunar claims server-visible wear; treat as server-authoritative until proven; never touch account data"),
    ("anti_weather", ["RENDER"], "native", ["Lunar Proxy"], "hide rain/storms client-side; needs weather path"),
    ("fast_throw", ["HYBRID"], "native", ["Lunar Proxy"], "moved from player QoL set"),
    ("friends", ["UI"], "native", ["Xykell"], "local list; never inferred from private data"),
]

SCRIPTING = [
    ("script_runtime", ["SCRIPT"], "script", ["Flarial"], "engine choice needs license/size review"),
    ("script_sandbox", ["SCRIPT"], "script", ["Xykell"], "design in docs/SCRIPTING.md"),
    ("script_api", ["SCRIPT"], "script", ["Flarial"], "design in docs/SCRIPTING.md"),
    ("script_manager", ["SCRIPT", "UI"], "script", ["Xykell"], "needs runtime first"),
]

CLIENT = [
    ("core", ["NATIVE"], "native", ["Xykell"], "M1 proven core"),
    ("version_adapter", ["NATIVE"], "native", ["Xykell"], "M1 table logic; runtime source pending"),
    ("config_store", ["NATIVE", "UI"], "native", ["Xykell", "WClient"], "M1 menu proof; file store pending"),
    ("profile_manager", ["NATIVE", "UI"], "native", ["Xykell"], "Default only in M1.5 shell"),
    ("crash_guard", ["NATIVE"], "native", ["Xykell"], "quarantine design pending"),
    ("updater", ["NATIVE", "UI"], "native", ["Xykell"], "signed+checksum design pending"),
    ("hud_editor", ["UI"], "native", ["Xykell"], "editor state+serialization exist; overlay binding partial"),
]

SERVER = [
    ("browser", ["UI"], "native", ["Xykell"], "user-supplied servers only"),
    ("saved", ["UI"], "native", ["Xykell"], "address/port/notes/favorites"),
    ("profile", ["UI"], "native", ["Xykell"], "per-server modules/settings/HUD"),
]

PROXY = [
    ("mode", ["PACKET"], "packet", ["Xykell"], "design only; separate subsystem"),
    ("relay", ["PACKET"], "packet", ["Xykell", "Nova", "WClient"], "console/remote concept; protocol work required"),
]

LAUNCHER = [
    ("play", ["UI"], "native", ["Xykell"], "disabled until verified launch action"),
    ("versions", ["UI"], "native", ["Xykell", "LeviLaunchroid"], "integrate Levi version management"),
    ("profiles", ["UI"], "native", ["Xykell"], "JNI bridge build-verified; runtime pending"),
    ("worlds", ["UI"], "native", ["Xykell", "LeviLaunchroid"], "integrate Levi content management"),
    ("packs", ["UI"], "native", ["Xykell", "LeviLaunchroid"], "integrate Levi content management"),
    ("servers", ["UI"], "native", ["Xykell"], "user configuration only"),
    ("servers_probe", ["UI"], "native", ["Xykell"], "read-only TCP reachability check"),
    ("settings", ["UI"], "native", ["Xykell"], "native-backed settings pending"),
    ("diagnostics", ["UI"], "native", ["Xykell"], "probe report module exists; build-verified"),
    ("accounts", ["UI"], "native", ["Xykell"], "Microsoft/Xbox auth handoff scaffold"),
]

CATEGORIES = {
    "CLIENT": CLIENT, "HUD": HUD, "PERFORMANCE": PERFORMANCE, "VISUAL": VISUAL,
    "PLAYER": PLAYER, "MOVEMENT": MOVEMENT, "COMBAT": COMBAT, "WORLD": WORLD,
    "AUTOMATION": AUTOMATION, "NETWORK": NETWORK, "PROXY": PROXY,
    "SCRIPTING": SCRIPTING, "SERVER": SERVER, "MISC": MISC, "LAUNCHER": LAUNCHER,
}

# (CATEGORY, suffix) -> forced status (default RR; these are the M1 proofs).
PROVEN = {
    ("CLIENT", "core"): PARTIAL,
    ("CLIENT", "version_adapter"): PARTIAL,
    ("CLIENT", "config_store"): PARTIAL,
    ("CLIENT", "profile_manager"): PARTIAL,
    ("CLIENT", "crash_guard"): PARTIAL,
    ("CLIENT", "updater"): PARTIAL,
    ("HUD", "touch_indicators"): PARTIAL,
    ("HUD", "fps"): PARTIAL,
    ("HUD", "cps"): PARTIAL,
    ("HUD", "clock"): PARTIAL,
    ("HUD", "session_stats"): PARTIAL,
    ("HUD", "stop_watch"): PARTIAL,
    ("CLIENT", "hud_editor"): PARTIAL,
    ("LAUNCHER", "diagnostics"): PARTIAL,
    ("LAUNCHER", "profiles"): PARTIAL,
    ("LAUNCHER", "play"): PARTIAL,
    ("LAUNCHER", "servers"): PARTIAL,
    ("LAUNCHER", "servers_probe"): PARTIAL,
    ("LAUNCHER", "versions"): PARTIAL,
    ("LAUNCHER", "settings"): PARTIAL,
    ("LAUNCHER", "worlds"): PARTIAL,
    ("LAUNCHER", "packs"): PARTIAL,
    ("LAUNCHER", "accounts"): PARTIAL,
    ("CLIENT", "profile_manager"): PARTIAL,
}

EVIDENCE = {
    ("CLIENT", "core"): "M1: PL_REGISTER_MOD lifecycle builds; host test_core",
    ("CLIENT", "version_adapter"): "M1: table logic; host test_adapter",
    ("CLIENT", "config_store"): "M1: menu toggles + file store; host test_config",
    ("CLIENT", "hud_editor"): "Batch 2/3: editor state + serialization; host test_hud_theme",
    ("CLIENT", "crash_guard"): "Batch X: uncaught exception handler with safe crash reports, redaction, bounded storage; host test_crashguard",
    ("CLIENT", "updater"): "Batch Y: local update metadata model with version comparison, validation, local metadata; host test_updater",
    ("HUD", "touch_indicators"): "M1: touch callback counter; host test_input_router",
    ("HUD", "fps"): "Batch 1: FrameTimer provider + honest unknown; host test_hud_sources",
    ("HUD", "cps"): "Batch 1: TapCounter provider + verified zero; host test_hud_sources",
    ("HUD", "clock"): "Batch 1: UTC clock provider; host test_hud_sources",
    ("HUD", "session_stats"): "Batch 1: session elapsed provider; host test_hud_sources",
    ("HUD", "stop_watch"): "Batch 1: elapsed formatter; host test_hud_sources",
    ("LAUNCHER", "diagnostics"): "Batch 5: probe-report ModMenu module; host test_probe",
    ("LAUNCHER", "profiles"): "Batch 7: JNI bridge + native-backed screen; CI builds",
    ("LAUNCHER", "play"): "handoff: pre-checks + Levi MainActivity intent; device run pending",
    ("LAUNCHER", "servers"): "Batch A: local server book with schema-tolerant JSON, favorites, search, SAF import/export; host test_serverstore",
    ("LAUNCHER", "servers_probe"): "Batch A: read-only TCP reachability check per server; host test_serverstore_probe",
    ("LAUNCHER", "worlds"): "Batch B: local world book with level.dat NBT import via SAF tree picker; host test_worldstore",
    ("LAUNCHER", "packs"): "Batch C: local pack book with manifest.json import via SAF file picker; host test_packstore",
    ("LAUNCHER", "performance"): "Batch D: app performance dashboard (FPS, memory, storage, startup); host test_perfstore",
    ("LAUNCHER", "versions"): "Batch H: installed Minecraft detection via PackageManager + native verdicts; host test_versions",
    ("LAUNCHER", "settings"): "Batch H: native-backed settings catalog with search/validation/reset; host test_settings",
    ("LAUNCHER", "worlds"): "Batch B: local world book with level.dat NBT import via SAF tree picker; host test_worldstore",
    ("LAUNCHER", "packs"): "Batch C: local pack book with manifest.json import via SAF file picker; host test_packstore",
    ("LAUNCHER", "accounts"): "Batch E: Microsoft auth handoff scaffold with client_id config; host test_accountstore",
    ("CLIENT", "profile_manager"): "Batch 7: JNI bridge + native-backed CRUD + UI; host test_profiles",
    ("LAUNCHER", "accounts"): "Batch E: Microsoft auth handoff scaffold with client_id config; tokens stored in plaintext SharedPreferences (SCAFFOLD — production requires EncryptedSharedPreferences + Android Keystore); host test_accountstore",
}

# Capability requirements per entry. Everything here currently
# fails the runtime gate (no verified sources) except LIFECYCLE-backed infra.
# Format: (CATEGORY, suffix) -> [caps]; CATEGORY_DEFAULTS applies otherwise.
REQUIRES = {
    ("HUD", "fps"): ["FRAME"],
    ("HUD", "coordinates"): ["PLAYER"],
    ("HUD", "position"): ["PLAYER"],
    ("HUD", "ping"): ["PACKET"],
    ("HUD", "tps"): ["FRAME"],
    ("HUD", "armor"): ["PLAYER"],
    ("HUD", "health"): ["PLAYER"],
    ("HUD", "hunger"): ["PLAYER"],
    ("HUD", "direction"): ["PLAYER"],
    ("HUD", "biome"): ["WORLD"],
    ("HUD", "clock"): ["OVERLAY_DELIVERY"],
    ("HUD", "cps"): ["INPUT_SEMANTICS"],
    ("HUD", "keystrokes"): ["INPUT_SEMANTICS"],
    ("HUD", "touch_indicators"): ["INPUT_SEMANTICS"],
    ("HUD", "target_info"): ["ENTITY"],
    ("HUD", "arraylist"): ["OVERLAY_DELIVERY"],
    ("HUD", "watermark"): ["OVERLAY_DELIVERY"],
    ("HUD", "notifications"): ["OVERLAY_DELIVERY"],
    ("HUD", "session_stats"): ["OVERLAY_DELIVERY"],
    ("HUD", "server_info"): ["PACKET"],
    ("CLIENT", "core"): ["LIFECYCLE"],
    ("CLIENT", "version_adapter"): ["LIFECYCLE", "VERSION_STRING"],
    ("CLIENT", "config_store"): ["LIFECYCLE", "CONFIG_DIRS"],
    ("CLIENT", "profile_manager"): ["LIFECYCLE", "CONFIG_DIRS"],
    ("CLIENT", "crash_guard"): ["LIFECYCLE", "CONFIG_DIRS"],
    ("CLIENT", "updater"): ["LIFECYCLE"],
    ("CLIENT", "hud_editor"): ["OVERLAY_DELIVERY"],
    ("LAUNCHER", "diagnostics"): ["LIFECYCLE"],
    ("LAUNCHER", "profiles"): ["LIFECYCLE", "CONFIG_DIRS"],
}
CATEGORY_DEFAULTS = {
    "COMBAT": ["FRAME", "PLAYER"],
    "MOVEMENT": ["FRAME", "PLAYER"],
    "PLAYER": ["FRAME", "PLAYER"],
    "WORLD": ["FRAME", "WORLD"],
    "VISUAL": ["FRAME", "WORLD"],
    "AUTOMATION": ["FRAME", "PLAYER"],
    "NETWORK": ["PACKET"],
    "PROXY": ["PACKET"],
    "SERVER": ["PACKET"],
    "PERFORMANCE": ["FRAME"],
    "MISC": ["OVERLAY_DELIVERY"],
    "SCRIPTING": ["SCRIPTING"],
    "HUD": ["OVERLAY_DELIVERY"],
    "CLIENT": ["LIFECYCLE"],
    "LAUNCHER": [],
}


def humanize(suffix):
    return " ".join(w.capitalize() for w in suffix.replace("_", " ").split())


def setting(key, type, default, description, min=None, max=None, options=None):
    s = {"key": key, "type": type, "default": default, "description": description}
    if min is not None:
        s["min"] = min
    if max is not None:
        s["max"] = max
    if options is not None:
        s["options"] = options
    return s


def toggle():
    return [setting("enabled", "bool", False, "master switch (gated by capability)")]


SETTINGS_DEFAULTS = {
    "COMBAT": lambda: toggle() + [
        setting("range", "float", 4.5, "engagement range (blocks)", 1.0, 8.0),
        setting("max_cps", "float", 10.0, "clicks per second cap", 1.0, 20.0),
        setting("ignore_friends", "bool", True, "skip friend-list targets")],
    "MOVEMENT": lambda: toggle() + [
        setting("speed_multiplier", "float", 1.0, "speed factor", 0.1, 5.0)],
    "PLAYER": lambda: toggle() + [
        setting("cooldown_ms", "int", 500, "action cooldown", 0, 10000)],
    "AUTOMATION": lambda: toggle() + [
        setting("cooldown_ms", "int", 500, "action cooldown", 0, 10000),
        setting("interruptible", "bool", True, "any input cancels")],
    "WORLD": lambda: toggle() + [
        setting("range", "int", 32, "scan/act radius (blocks)", 8, 128)],
    "VISUAL": lambda: toggle() + [
        setting("range", "int", 32, "render radius (blocks)", 8, 128),
        setting("show_distance", "bool", True, "distance labels")],
    "HUD": lambda: toggle() + [
        setting("scale", "float", 1.0, "element scale", 0.5, 3.0),
        setting("opacity", "float", 1.0, "element opacity", 0.0, 1.0)],
    "NETWORK": lambda: toggle() + [
        setting("interval_ms", "int", 1000, "sample interval", 100, 60000)],
    "PERFORMANCE": lambda: toggle(),
    "MISC": lambda: toggle(),
    "SCRIPTING": lambda: toggle(),
    "CLIENT": lambda: toggle(),
    "LAUNCHER": lambda: toggle(),
    "SERVER": lambda: toggle(),
    "PROXY": lambda: toggle(),
}

SETTINGS_OVERRIDES = {
    ("COMBAT", "kill_aura"): [setting("target_mode", "enum", "single",
                                       "single or multi target", None, None,
                                       ["single", "multi"])],
    ("HUD", "fps"): [setting("show_graph", "bool", False, "frame-time graph")],
    ("VISUAL", "zoom"): [setting("factor", "float", 3.0, "zoom factor", 1.5, 10.0)],
    ("VISUAL", "esp"): [setting("box_color", "color", "#FF5555", "ESP box color")],
    ("WORLD", "waypoints"): [setting("beacon", "bool", True, "HUD beacon marker")],
}


def settings_for(category, suffix):
    base = [dict(s) for s in SETTINGS_DEFAULTS.get(category, lambda: toggle())()]
    base += [dict(s) for s in SETTINGS_OVERRIDES.get((category, suffix), [])]
    return base


def risk_for(category, impl, notes):
    low_notes = ("local-only", "local display only", "overlay redaction",
                 "hides commands/chat", "device mem/CPU", "local timer")
    if impl in ("packet", "script") or category == "COMBAT":
        return "HIGH"
    if any(n in notes for n in ("server rules", "server-visible", "server-authoritative")):
        return "HIGH"
    if any(n in notes for n in low_notes):
        return "LOW"
    if category in ("MOVEMENT", "WORLD", "AUTOMATION", "VISUAL", "PROXY",
                    "SERVER", "NETWORK", "SCRIPTING"):
        return "MEDIUM"
    return "LOW"


def main() -> None:
    entries = []
    for category, mods in CATEGORIES.items():
        for suffix, caps, impl, sources, notes in mods:
            req = REQUIRES.get((category, suffix),
                               CATEGORY_DEFAULTS.get(category, []))
            entries.append({
                "id": f"xykell.{category.lower()}.{suffix}",
                "name": humanize(suffix),
                "category": category,
                "description": notes or f"{humanize(suffix)} ({category}).",
                "status": PROVEN.get((category, suffix),
                                     RR if category != "SCRIPTING" else NI),
                "requires": req,
                "settings": settings_for(category, suffix),
                "risk_level": risk_for(category, impl, notes),
                "platforms": ["android"],
                "version_constraints": [],
                "implementation": impl,
                "capabilities": caps,
                "versions": [],
                "sourceReferences": sources,
                "evidence": EVIDENCE.get((category, suffix), ""),
                "notes": notes,
            })
    # scripting + client/launcher non-proven default to NOT_IMPLEMENTED
    proven_ids = {"xykell.client.core", "xykell.client.version_adapter",
                  "xykell.client.config_store", "xykell.hud.touch_indicators",
                  "xykell.client.hud_editor", "xykell.launcher.diagnostics",
                  "xykell.launcher.profiles", "xykell.launcher.play",
                  "xykell.launcher.servers", "xykell.launcher.servers_probe",
                  "xykell.launcher.worlds", "xykell.launcher.packs",
                  "xykell.launcher.performance", "xykell.launcher.accounts",
                  "xykell.client.profile_manager", "xykell.launcher.versions",
                  "xykell.launcher.settings", "xykell.client.crash_guard",
                  "xykell.client.updater"}
    for e in entries:
        if e["category"] in ("SCRIPTING",) and e["status"] == RR:
            e["status"] = NI
        if e["category"] in ("CLIENT", "LAUNCHER") and e["id"] not in proven_ids:
            e["status"] = NI
    out = {
        "meta": {"format": 1, "product": "Xykell Client",
                 "generated_by": "scripts/registry/generate.py",
                 "count": len(entries)},
        "features": entries,
    }
    root = Path(__file__).resolve().parent.parent.parent
    dest = root / "registry" / "features.json"
    dest.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {dest} ({len(entries)} features)")


if __name__ == "__main__":
    main()
