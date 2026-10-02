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
    ("tap_tp", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("safe_walk", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("no_fall", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
    ("movement_correction", ["PACKET", "HYBRID"], "packet", ["Lunar Proxy"], "locality TBD"),
    ("timer", ["HYBRID"], "native", ["Lunar Proxy"], "locality TBD"),
]

PLAYER = [
    ("auto_eat", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_fish", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], ""),
    ("auto_refill", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_steal", ["HYBRID"], "native", ["Lunar Proxy"], "server rules risk"),
    ("inventory_manager", ["UI", "HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_tool", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_equip", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_armor", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("fast_eat", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("fast_throw", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("fast_interact", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_sign", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_sell", ["HYBRID"], "native", ["Lunar Proxy"], "server rules risk"),
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
]

WORLD = [
    ("scaffold", ["HYBRID"], "native", ["Lunar Proxy", "Apollon"], "server rules risk"),
    ("nuker", ["HYBRID"], "native", ["Lunar Proxy"], "server rules risk; throttled by design"),
    ("auto_mine", ["HYBRID"], "native", ["Lunar Proxy"], ""),
    ("auto_dig", ["HYBRID"], "native", ["Lunar Proxy"], ""),
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
    ("waypoints", ["WORLD", "UI"], "native", ["Atlas", "Flarial"], "see world.waypoints"),
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
]

UTILITY = [
    ("quick_perspective", ["RENDER", "INPUT"], "native", ["Atlas"], "needs camera path"),
    ("quick_drop", ["INPUT"], "native", ["Atlas"], "needs input path; Levi inbuilt Quick Drop exists — integrate, do not duplicate"),
    ("hide_hud", ["UI"], "native", ["Atlas"], "overlay clear available"),
    ("toggle_sprint", ["INPUT"], "native", ["Flarial"], "locality TBD"),
    ("toggle_sneak", ["INPUT"], "native", ["Flarial"], "locality TBD"),
    ("screenshot_tools", ["UI"], "native", ["Xykell"], "needs capture path"),
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
]

CATEGORIES = {
    "combat": COMBAT, "movement": MOVEMENT, "player": PLAYER, "world": WORLD,
    "visual": VISUAL, "hud": HUD, "utility": UTILITY, "network": NETWORK,
    "performance": PERFORMANCE, "misc": MISC, "scripting": SCRIPTING, "client": CLIENT,
}

# (category, suffix) -> forced status (default RR; these are the M1 proofs).
PROVEN = {
    ("client", "core"): PARTIAL,
    ("client", "version_adapter"): PARTIAL,
    ("client", "config_store"): PARTIAL,
    ("hud", "touch_indicators"): PARTIAL,
}

# Capability requirements per entry (Batch 4). Everything here currently
# fails the runtime gate (no verified sources) except LIFECYCLE-backed infra.
# Format: (category, suffix) -> [caps]; CATEGORY_DEFAULTS applies otherwise.
REQUIRES = {
    ("hud", "fps"): ["FRAME"],
    ("hud", "coordinates"): ["PLAYER"],
    ("hud", "position"): ["PLAYER"],
    ("hud", "ping"): ["PACKET"],
    ("hud", "tps"): ["FRAME"],
    ("hud", "armor"): ["PLAYER"],
    ("hud", "health"): ["PLAYER"],
    ("hud", "hunger"): ["PLAYER"],
    ("hud", "direction"): ["PLAYER"],
    ("hud", "biome"): ["WORLD"],
    ("hud", "clock"): ["OVERLAY_DELIVERY"],
    ("hud", "cps"): ["INPUT_SEMANTICS"],
    ("hud", "keystrokes"): ["INPUT_SEMANTICS"],
    ("hud", "touch_indicators"): ["INPUT_SEMANTICS"],
    ("hud", "target_info"): ["ENTITY"],
    ("hud", "arraylist"): ["OVERLAY_DELIVERY"],
    ("hud", "watermark"): ["OVERLAY_DELIVERY"],
    ("hud", "notifications"): ["OVERLAY_DELIVERY"],
    ("hud", "session_stats"): ["OVERLAY_DELIVERY"],
    ("hud", "server_info"): ["PACKET"],
    ("client", "core"): ["LIFECYCLE"],
    ("client", "version_adapter"): ["LIFECYCLE", "VERSION_STRING"],
    ("client", "config_store"): ["LIFECYCLE", "CONFIG_DIRS"],
    ("client", "profile_manager"): ["LIFECYCLE", "CONFIG_DIRS"],
    ("client", "crash_guard"): ["LIFECYCLE", "CONFIG_DIRS"],
    ("client", "updater"): ["LIFECYCLE"],
}
CATEGORY_DEFAULTS = {
    "combat": ["FRAME", "PLAYER"],
    "movement": ["FRAME", "PLAYER"],
    "player": ["FRAME", "PLAYER"],
    "world": ["FRAME", "WORLD"],
    "visual": ["FRAME", "WORLD"],
    "utility": ["FRAME"],
    "network": ["PACKET"],
    "performance": ["FRAME"],
    "misc": ["OVERLAY_DELIVERY"],
    "scripting": ["SCRIPTING"],
    "hud": ["OVERLAY_DELIVERY"],
    "client": ["LIFECYCLE"],
}


def humanize(suffix):
    return " ".join(w.capitalize() for w in suffix.replace("_", " ").split())


def main() -> None:
    entries = []
    for category, mods in CATEGORIES.items():
        for suffix, caps, impl, sources, notes in mods:
            req = REQUIRES.get((category, suffix),
                               CATEGORY_DEFAULTS.get(category, []))
            entries.append({
                "id": f"{category}.{suffix}",
                "name": humanize(suffix),
                "category": category,
                "description": notes or f"{humanize(suffix)} ({category}).",
                "status": PROVEN.get((category, suffix), RR if category != "scripting" else NI),
                "requires": req,
                "settings": [],
                "platforms": ["android"],
                "version_constraints": [],
                "implementation": impl,
                "capabilities": caps,
                "versions": [],
                "sourceReferences": sources,
                "evidence": "",
                "notes": notes,
            })
    # scripting + client non-proven default to NOT_IMPLEMENTED
    for e in entries:
        if e["category"] in ("scripting",) and e["status"] == RR:
            e["status"] = NI
        if e["category"] == "client" and e["id"] not in {
                "client.core", "client.version_adapter", "client.config_store"}:
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
