#!/usr/bin/env python3
"""Generate registry/features.json — the honest Xykell feature universe.

Every entry carries a compatibility state; automation without a verified
Bedrock runtime source is RESEARCH_REQUIRED, never fake-supported.
M1-proven items are PARTIAL (build-verified, runtime pending device test).
Run: python3 scripts/registry/generate.py. Validate: validate.py.
"""
import json
from pathlib import Path

PREFIX = "xykell."

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
    ("script_manager", ["SCRIPT", "UI"], "script", ["Xykell"], "safe local rule engine implemented; host test_scriptengine"),
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


# ---------------------------------------------------------------------------
# Classification beyond PARTIAL.
#
# This table is the single source of truth. It is keyed by FULL id, never by
# bare suffix: fog_controls and particle_controls exist in both PERFORMANCE and
# VISUAL, and a suffix-keyed table previously assigned the two copies
# contradictory statuses in the same file.
#
# REFERENCE_ONLY   out of product scope. Cheats, ESP, combat/movement
#                 automation, anti-cheat bypass, packet access, MITM/relay.
#                 Never implemented, not "not yet".
# DEVICE_LIMITED  only reachable by controlling the Bedrock renderer or engine
#                 from inside the game process. No app-level API reaches them.
# NOT_IMPLEMENTED not written yet. Two distinct reasons, tracked separately below
#                 so the registry does not imply live validation exists.
# ---------------------------------------------------------------------------

# Categories that are entirely cheat/ESP surfaces.
PROHIBITED_CATEGORIES = {"COMBAT", "MOVEMENT", "VISUAL", "PROXY"}

# Individual ids outside those categories that are equally out of scope.
# Input injection (quick_drop, toggle_sprint, toggle_sneak, fast_throw,
# quick_perspective) is here because it means unauthorised game control.
PROHIBITED_IDS = {PREFIX + i for i in """
 automation.command_hotkey automation.text_hotkey automation.ghost
 automation.auto_eat automation.auto_fish automation.auto_refill
 automation.auto_steal automation.auto_tool automation.auto_equip
 automation.auto_armor automation.auto_sign automation.auto_sell
 automation.auto_mine automation.auto_dig automation.inventory_cleaner
 automation.no_break_delay automation.auto_tool_swap automation.auto_gg
 automation.inventory_lock
 player.fake_stats player.fast_eat player.fast_interact player.haste
 player.slow_mine player.no_fall player.no_blindness player.no_nausea
 player.no_fire player.no_hurt_cam player.anti_immobile player.spam
 network.packet_monitor network.packet_logger
 misc.fake_op misc.java_mode misc.disabler misc.skin_stealer
 misc.anti_weather misc.fast_throw misc.quick_drop misc.toggle_sprint
 misc.toggle_sneak misc.quick_perspective
 world.scaffold world.nuker world.fast_break world.fast_place
 world.spawner_protect world.block_esp world.block_tracer world.xray
 world.ore_esp world.chunk_borders world.chunk_finder world.new_chunks
 world.hole_esp world.schematic
""".split()}

# Renderer/engine internals. An app process cannot reach these; only code
# running inside the game's own render or engine path can.
DEVICE_LIMITED_IDS = {PREFIX + "performance." + s for s in """
 fps_limiter fps_unlocker dynamic_fps background_fps entity_opt render_opt
 particle_controls animation_controls cloud_controls weather_opt fog_controls
 frame_graph low_end_mode render_distance perf_profiles
""".split()}

# NOT_IMPLEMENTED because the value lives in the Bedrock client and the read
# path does not exist yet. The blocker is code we have not written, not a
# missing device: there is nothing to live-validate until the read path lands.
RUNTIME_GATED_IDS = {PREFIX + "hud." + s for s in """
 coordinates ping tps armor health hunger position direction biome keystrokes
 target_info server_info inventory_hud ip_display entity_counter low_health
 potion_hud speed_meter subtitles tab_list totem_counter
""".split()} | {PREFIX + "player." + s for s in """
 inventory_manager death_position friend_alerts nickname mod_alerts
""".split()} | {PREFIX + "misc." + s for s in """
 chat_timestamps chat_filter custom_nicknames shulker_tooltip death_lightning
""".split()} | {PREFIX + "server." + s for s in "browser saved profile".split()} | {PREFIX + "automation." + s for s in """
 death_logger item_tracker tnt_timer player_notifier
""".split()} | {PREFIX + "world." + s for s in "minimap".split()}

# NOT_IMPLEMENTED, but reachable with ordinary Android/app APIs and no game
# internals. These are the real remaining build backlog, not external blockers.
APP_LEVEL_IDS = {PREFIX + "hud." + s for s in """
 arraylist hardware_stats notifications
""".split()} | {PREFIX + "misc." + s for s in """
 streamer_mode privacy_mode screenshot_share screenshot_tools hide_hud timer
 friends
""".split()} | {PREFIX + "network." + s for s in """
 ping connection_status latency_graph network_diagnostics
""".split()} | {PREFIX + "performance." + s for s in """
 memory_info cpu_info gpu_info
""".split()} | {PREFIX + "world." + s for s in """
 waypoints world_markers
""".split()}

# Per-id notes where the generic reason is too coarse to be useful.
NOTES_BY_ID = {
    PREFIX + "hud.notifications": (
        "NativeNotificationCenter (post/drain/clear + priority) exists and is "
        "host-tested, and ElementType::Notifications is in the layout model, but "
        "nothing binds the queue to a HUD line. Needs that binding, not a "
        "new subsystem."
    ),
    PREFIX + "hud.arraylist": (
        "Renderer prints a module on/total count, not the enabled-module list. "
        "Module state is known app-side, so this is a rendering change only."
    ),
    # Phase 6 boundary: these four automation features and the minimap were
    # catalogued as ordinary app-level build work. They are not. The JNI offer
    # surface is PlayerMessage, PlayerTravelled and Unknown only, so the event
    # each one needs to start from does not exist app-side at all.
    PREFIX + "automation.death_logger": (
        "No death observation exists. The JNI offer surface is PlayerMessage, "
        "PlayerTravelled and Unknown only: nothing observes the local player's "
        "death, health or respawn, so there are no death coordinates to log. "
        "Needs a Stage-20 death/health source first."
    ),
    PREFIX + "automation.item_tracker": (
        "No inventory observation exists. Nothing observes picked-up or dropped "
        "items, and chat does not carry inventory events, so a tracker would "
        "have nothing to count. Needs a Stage-20 inventory source first."
    ),
    PREFIX + "automation.tnt_timer": (
        "No entity observation exists. Nothing observes a primed TNT entity, so "
        "the countdown has no start event. Needs a Stage-20 entity source first."
    ),
    PREFIX + "automation.player_notifier": (
        "No player-list observation exists. Nothing observes joins, leaves or "
        "the current roster; matching a watched name in chat cannot tell whether "
        "a player is on the server. Needs a Stage-20 player-list source first."
    ),
    PREFIX + "world.minimap": (
        "Position now exists (ObservedState.motion from PlayerTravelled), but "
        "there is no terrain, chunk or block source, so a minimap would have "
        "nothing to draw. Needs a Stage-20 world source first."
    ),
    PREFIX + "world.world_markers": (
        "Position now exists (ObservedState.motion from PlayerTravelled) and the "
        "distance/bearing arithmetic is ordinary app-side code, but there is no "
        "overlay surface: the app draws no window over the game, and in-game "
        "markers would need the native render path, which cannot be verified "
        "without a live session. Waypoints already store the same coordinates "
        "in-app."
    ),
}

REFERENCE_ONLY_NOTE = (
    "Out of product scope by policy: cheat/ESP/automation, anti-cheat or ban "
    "evasion, packet access, or MITM/relay. Not scheduled, not partial."
)
RUNTIME_GATED_NOTE = (
    "Value lives in the Bedrock client. No read path written yet, so there is "
    "nothing to live-validate; needs a Stage-20 observation source first."
)
# Screen-and-model complete: written, compiled and host-tested, but never run
# against a live session. PARTIAL is the honest status, and each note must
# state the boundary the feature actually sits behind.
PARTIAL_NOTES = {
    PREFIX + "hud.hardware_stats":
        "Native formatHardwareStats plus the provider install on every "
        "HardwareStats HUD element; host test_hud_sources. Reads device "
        "memory/storage/ABI through OS APIs only. Not run on a device.",
    PREFIX + "misc.chat_timestamps":
        "Chat screen renders observed PlayerMessage lines with optional UTC "
        "timestamps taken from the observed event, never the device clock; "
        "ChatPolicyTest round-trips the flag, ObservedChatTest covers the "
        "format. No observed chat until a Stage-20 session supplies it.",
    PREFIX + "misc.chat_filter":
        "Chat screen edits hide/highlight rules; invalid patterns fail open so "
        "an unparseable rule can never hide a line. Host ObservedChatTest "
        "rule cases, ChatPolicyTest persistence. No observed chat until a "
        "Stage-20 session supplies it.",
    PREFIX + "misc.custom_nicknames":
        "Chat screen edits display-name substitution; validation rejects "
        "injection shapes and caps entries, and substitution stays "
        "display-only. Host ObservedChatTest nickname cases. No observed chat "
        "until a Stage-20 session supplies it.",
    PREFIX + "network.ping":
        "Network screen starts NetworkProbe.Prober against a user-chosen host "
        "and reports a real TCP connect round trip. It is TCP reachability, "
        "never Bedrock latency, and the port is left blank rather than "
        "prefilled. Host NetworkProbeTest.",
    PREFIX + "network.latency_graph":
        "Network screen draws NetworkProbe.LatencyHistory, a bounded ring with "
        "honest empty stats. Host NetworkProbeTest.",
    PREFIX + "network.connection_status":
        "Network screen renders NetworkDiagnostics.link() transport/metered/"
        "validated straight from ConnectivityManager; Android-reported only, "
        "no server round trip implied.",
    PREFIX + "network.network_diagnostics":
        "Network screen combines link status, probe history and a plain "
        "statement that TCP connect is not Bedrock latency. Host "
        "NetworkProbeTest.",
    PREFIX + "world.waypoints":
        "Waypoints screen adds, removes and clears named coordinates with "
        "schema-versioned persistence, and can fill from the last observed "
        "PlayerTravelled sample. Host PrivacyAndWorldTest store cases. In-app "
        "list only: no in-game beacon is rendered.",
    PREFIX + "misc.timer":
        "Timer screen drives CountdownTimer through start/stop/restart on its "
        "fixed deadline with an injected clock. Host PrivacyAndWorldTest timer "
        "cases. Runs only while the screen is resumed; not a background timer.",
    PREFIX + "misc.screenshot_share":
        "Screenshot screen starts a one-shot mediaProjection foreground "
        "service: the user approves Android's system consent dialog per "
        "capture, exactly one VirtualDisplay is created (Android 14+ rule), "
        "the frame goes to app cache and out through the androidx FileProvider "
        "into ACTION_SHARE. Consent is consumed once, never reused; no "
        "background recording, no storage permission. Host PixelPackerTest for "
        "row-stride repack; never run on a device.",
    PREFIX + "misc.screenshot_tools":
        "Screenshot screen starts the same one-shot mediaProjection "
        "foreground service and saves the PNG through scoped-storage "
        "MediaStore (Pictures/Xykell) on API 29+, or the app's own pictures "
        "folder on API 9 with that limitation stated on screen instead of "
        "requesting WRITE_EXTERNAL_STORAGE. Consent consumed once, no "
        "background recording. Host PixelPackerTest for row-stride repack; "
        "never run on a device.",
}

APP_LEVEL_NOTE = (
    "Reachable with ordinary Android/app APIs and no game internals. Not "
    "written yet: this is remaining build work, not an external blocker."
)

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
    # Scripting: runtime, sandbox, API and manager are implemented and
    # unit-tested. No arbitrary code path exists in any of them.
    ("SCRIPTING", "script_runtime"): PARTIAL,
    ("SCRIPTING", "script_sandbox"): PARTIAL,
    ("SCRIPTING", "script_api"): PARTIAL,
    ("SCRIPTING", "script_manager"): PARTIAL,
    # HUD elements whose element model, render case and value source already
    # exist natively (xykell_hud_renderer.cpp / hud_model.cpp). Coordinates
    # still needs a Stage-20 observation source for a live value.
    # Implemented end-to-end in this pass: model, persistence, UI, tests.
    # "performance.*_info" read real Android APIs; GPU *utilisation* is not a
    # public API, so the view reports capability data and states that
    # utilisation is unavailable rather than inventing a percentage.
    ("PERFORMANCE", "memory_info"): PARTIAL,
    ("PERFORMANCE", "cpu_info"): PARTIAL,
    ("PERFORMANCE", "gpu_info"): PARTIAL,
    # Real module list and a real NotificationCenter->HUD binding, both in
    # xykell_hud_renderer.cpp with host renderer tests.
    ("HUD", "arraylist"): PARTIAL,
    ("HUD", "notifications"): PARTIAL,
    # Three switches in SettingsFragment over PrivacySettings, which is
    # tested; hide_hud is wired to RenderContext.hudVisible so a hidden
    # overlay is genuinely absent rather than blank.
    ("MISC", "streamer_mode"): PARTIAL,
    ("MISC", "privacy_mode"): PARTIAL,
    ("MISC", "hide_hud"): PARTIAL,
    ("HUD", "watermark"): PARTIAL,
    ("HUD", "coordinates"): PARTIAL,
    ("HUD", "movable_hud"): PARTIAL,
    # Phase 5: native hardware-stats provider + formatHardwareStats.
    ("HUD", "hardware_stats"): PARTIAL,
    # Phase 7: screen-and-model complete, host-tested, never run live.
    ("MISC", "chat_timestamps"): PARTIAL,
    ("MISC", "chat_filter"): PARTIAL,
    ("MISC", "custom_nicknames"): PARTIAL,
    ("NETWORK", "ping"): PARTIAL,
    ("NETWORK", "connection_status"): PARTIAL,
    ("NETWORK", "latency_graph"): PARTIAL,
    ("NETWORK", "network_diagnostics"): PARTIAL,
    ("WORLD", "waypoints"): PARTIAL,
    ("MISC", "timer"): PARTIAL,
    ("MISC", "screenshot_share"): PARTIAL,
    ("MISC", "screenshot_tools"): PARTIAL,
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
    ("LAUNCHER", "play"): "handoff: pre-checks + Levi MainActivity intent; host HomeStatusTest installed-MC verdict; device run pending",
    ("LAUNCHER", "servers"): "Batch A: local server book with schema-tolerant JSON, favorites, search, SAF import/export; host test_serverstore",
    ("LAUNCHER", "servers_probe"): "Batch A: read-only TCP reachability check per server; host test_serverstore_probe",
    ("LAUNCHER", "worlds"): "Batch B: local world book with level.dat NBT import via SAF tree picker; host test_worldstore",
    ("LAUNCHER", "packs"): "Batch C: local pack book with manifest.json import via SAF file picker; host test_packstore",
    ("LAUNCHER", "performance"): "Batch D: app performance dashboard (FPS, memory, storage, startup); host test_perfstore",
    ("LAUNCHER", "versions"): "Batch H: installed Minecraft detection via PackageManager + native verdicts; host test_versions",
    ("LAUNCHER", "settings"): "Batch H: native-backed settings catalog with search/validation/reset; host test_settings",
    ("SCRIPTING", "script_runtime"): "Batch Z2: ScriptRuntime lifecycle, dispatch, budget, re-entrancy guard, failure isolation; host test_scriptruntime",
    ("SCRIPTING", "script_sandbox"): "Batch Z2: explicit allowlists, limits, injection-shaped payload rejection, rolling budget; host test_scriptsandbox",
    ("SCRIPTING", "script_api"): "Batch Z2: typed capability-gated API over profile/settings/HUD/theme/modules/diagnostics/session/notify; host test_scriptapi",
    ("SCRIPTING", "script_manager"): "Batch Z2: host wiring, CRUD, duplicate, import/export, v1 migration, persistence; host test_scriptruntime",
    ("HUD", "watermark"): "Batch Z2: native render case in xykell_hud_renderer.cpp with real version string; host test_hud_render",
    ("PERFORMANCE", "memory_info"): "Batch Z3: DeviceInfo.memory over ActivityManager/Runtime/Debug/StatFs; detail rows in PerformanceFragment; CI-compiled, device reads pending Stage-20",
    ("PERFORMANCE", "cpu_info"): "Batch Z3: DeviceInfo.cpu - cores, ABI, device strings, max freq, process CPU via injected source; CI-compiled, device reads pending Stage-20",
    ("PERFORMANCE", "gpu_info"): "Batch Z3: DeviceInfo.gpu - GL driver strings and capability limits; utilisation reported unavailable, never estimated; CI-compiled, device reads pending Stage-20",
    ("HUD", "arraylist"): "Batch Z3: enabledModuleNames() renders real module names ordered by (category,id); host test_hud_render",
    ("HUD", "notifications"): "Batch Z3: NotificationCenter bound to a HUD line via peek(); bounded, severity-marked, non-draining; host test_hud_render",
    ("MISC", "streamer_mode"): "Batch Z3: PrivacySettings redaction policy + SettingsFragment toggle; host PrivacyAndWorldTest redaction cases",
    ("MISC", "privacy_mode"): "Batch Z3: PrivacySettings redaction policy + SettingsFragment toggle; host PrivacyAndWorldTest redaction cases",
    ("MISC", "hide_hud"): "Batch Z3: RenderContext.hudVisible returns no lines; SettingsFragment toggle; host test_hud_render hidden case",
    ("MISC", "screenshot_share"): "Batch S: one-shot MediaProjection capture service (mediaProjection FGS) + cache PNG via androidx FileProvider + ACTION_SEND chooser; host PixelPackerTest",
    ("MISC", "screenshot_tools"): "Batch S: one-shot MediaProjection capture service (mediaProjection FGS) + scoped-storage MediaStore save; host PixelPackerTest",
    ("HUD", "coordinates"): "Batch Z2: element + render case exist; live value needs Stage-20 observation source; host test_hud_render",
    ("HUD", "movable_hud"): "Batch Z2: per-profile layouts, hud_editor, setHudElement, clampToViewport; host test_hud_editor",
    ("LAUNCHER", "accounts"): "Batch E: Microsoft auth handoff scaffold with client_id config; host test_accountstore",
    ("CLIENT", "profile_manager"): "Batch 7: JNI bridge + native-backed CRUD + UI; host test_profiles",
    ("HUD", "hardware_stats"): "Phase 5: native formatHardwareStats + provider install on every HardwareStats element; host test_hud_sources",
    ("MISC", "chat_timestamps"): "Phase 7: ChatFragment over observed PlayerMessage with ChatPolicy showTimestamps; host ObservedChatTest + ChatPolicyTest; CI-compiled, live chat pending Stage-20",
    ("MISC", "chat_filter"): "Phase 7: ChatFragment rule editor over ChatFilter (hide/highlight, fail-open invalid patterns); host ObservedChatTest + ChatPolicyTest; CI-compiled",
    ("MISC", "custom_nicknames"): "Phase 7: ChatFragment nickname editor over NicknameMap (validation, cap, display-only); host ObservedChatTest + ChatPolicyTest; CI-compiled",
    ("NETWORK", "ping"): "Phase 7: NetworkFragment starts NetworkProbe.Prober TCP connect, reports reachability not Bedrock latency; host NetworkProbeTest; CI-compiled",
    ("NETWORK", "connection_status"): "Phase 7: NetworkFragment renders NetworkDiagnostics.link transport/metered/validated; CI-compiled",
    ("NETWORK", "latency_graph"): "Phase 7: NetworkFragment draws NetworkProbe.LatencyHistory bars with honest empty stats; host NetworkProbeTest; CI-compiled",
    ("NETWORK", "network_diagnostics"): "Phase 7: NetworkFragment combines link status, probe history and TCP-not-latency notice; host NetworkProbeTest; CI-compiled",
    ("WORLD", "waypoints"): "Phase 7: WaypointsFragment CRUD + fill from observed PlayerTravelled position; host PrivacyAndWorldTest WaypointStore cases; CI-compiled",
    ("MISC", "timer"): "Phase 7: TimerFragment start/stop/restart over CountdownTimer; host PrivacyAndWorldTest timer cases; CI-compiled",
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
    for e in entries:
        fid = e["id"]
        if e["status"] == PARTIAL:
            # Built and host-tested, but not run against a live session. The
            # note must state the boundary, not repeat the feature pitch.
            e["notes"] = PARTIAL_NOTES.get(fid, e["notes"])
            continue  # already proven by PROVEN/EVIDENCE above
        if e["category"] in PROHIBITED_CATEGORIES or fid in PROHIBITED_IDS:
            e["status"] = "REFERENCE_ONLY"
            e["evidence"] = "policy: no cheat/ESP/automation/packet/MITM surface"
            e["notes"] = REFERENCE_ONLY_NOTE
        elif fid in DEVICE_LIMITED_IDS:
            e["status"] = "DEVICE_LIMITED"
            e["evidence"] = "Bedrock renderer/engine internal; no app-level API"
            e["notes"] = (
                "Reachable only from inside the game's render/engine path. "
                "The app process has no API for it."
            )
        elif fid in RUNTIME_GATED_IDS:
            e["status"] = "NOT_IMPLEMENTED"
            e["evidence"] = "no read path; Stage-20 observation source absent"
            e["notes"] = NOTES_BY_ID.get(fid) or RUNTIME_GATED_NOTE
        elif fid in APP_LEVEL_IDS:
            e["status"] = "NOT_IMPLEMENTED"
            e["evidence"] = "app-level APIs available; feature not written"
            e["notes"] = NOTES_BY_ID.get(fid) or APP_LEVEL_NOTE
        else:
            e["status"] = "NOT_IMPLEMENTED"
            e["notes"] = "not implemented"
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
