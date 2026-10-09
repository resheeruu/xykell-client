// Host unit test: motion HUD formatters + provider binding (pure C++).
// Synthetic observations only; no game data, no I/O, deterministic.
// FormatDirection follows the Minecraft yaw convention (0 = south,
// positive turns westward) — pinned here so a convention change fails loud.
#include <cassert>
#include <iostream>
#include <optional>
#include <string>

#include "xykell/hud_model.h"
#include "xykell/hud_sources.h"

using xykell::hud::ElementType;
using xykell::hud::HudElement;
using xykell::hud::HudLayout;
using xykell::hud::kUnavailable;
using xykell::hud::sources::bindMotionProviders;
using xykell::hud::sources::bindVitalsProviders;
using xykell::hud::sources::formatHealth;
using xykell::hud::sources::formatLowHealth;
using xykell::hud::sources::bindPopulationProviders;
using xykell::hud::sources::formatEntityCount;
using xykell::hud::sources::formatTps;
using xykell::hud::sources::formatTabList;
using xykell::hud::sources::formatServerInfo;
using xykell::hud::sources::formatDeathInfo;
using xykell::hud::sources::formatIpDisplay;
using xykell::hud::sources::formatCoordinates;
using xykell::hud::sources::formatDirection;
using xykell::hud::sources::formatSpeed;
using xykell::runtime::PlayerTravelObservation;
using xykell::runtime::RuntimeObservationSnapshot;
using xykell::runtime::Vec3;

namespace {

PlayerTravelObservation travel(std::uint64_t at, double x, double y, double z,
                               double yaw) {
    PlayerTravelObservation t;
    t.eventId = "xykell-obs-motion";
    t.observedAtMs = at;
    t.position = Vec3{x, y, z};
    t.yawDegrees = yaw;
    return t;
}

} // namespace

int main() {
    // --- formatDirection: Minecraft yaw -> 8-point compass ---
    assert(formatDirection(0.0) == "S");
    assert(formatDirection(90.0) == "W");
    assert(formatDirection(180.0) == "N");
    assert(formatDirection(-180.0) == "N");
    assert(formatDirection(-90.0) == "E");
    assert(formatDirection(45.0) == "SW");
    assert(formatDirection(-45.0) == "SE");
    assert(formatDirection(360.0 + 90.0) == "W"); // normalization
    assert(formatDirection(-360.0 + 90.0) == "W");

    // --- formatCoordinates: observed position, fixed one-decimal ---
    assert(formatCoordinates(travel(1ULL, 12.5, 64.0, -8.3, 0.0)) == "12.5 64.0 -8.3");
    assert(formatCoordinates(travel(2ULL, -485.5, 71.0, 339.25, 0.0)) ==
           "-485.5 71.0 339.2"); // half-even on exact .25 tie

    // --- formatSpeed: unavailable stays unavailable, observed is plain ---
    assert(formatSpeed(std::nullopt) == kUnavailable);
    assert(formatSpeed(3.3) == "3.3 m/s");
    assert(formatSpeed(0.0) == "0.0 m/s");

    // --- binding with a real snapshot: providers return observed values ---
    {
        RuntimeObservationSnapshot snap;
        snap.latestTravel = travel(1000ULL, 12.5, 64.0, -8.3, 90.0);
        snap.speedMps = 4.2;
        snap.travelCount = 2;

        HudLayout layout;
        HudElement coords;
        coords.type = ElementType::Coordinates;
        HudElement dir;
        dir.type = ElementType::Direction;
        HudElement spd;
        spd.type = ElementType::SpeedMeter;
        HudElement mark;
        mark.type = ElementType::Watermark;
        mark.provider = [] { return std::string("XYKELL"); };
        layout.elements = {coords, dir, spd, mark};

        bindMotionProviders(layout, &snap);
        assert(layout.elements[0].text() == "12.5 64.0 -8.3");
        assert(layout.elements[1].text() == "W");
        assert(layout.elements[2].text() == "4.2 m/s");
        assert(layout.elements[3].text() == "XYKELL"); // untouched
    }

    // --- binding with no snapshot (or empty travel): honest "--" ---
    {
        HudLayout layout;
        HudElement coords;
        coords.type = ElementType::Coordinates;
        HudElement dir;
        dir.type = ElementType::Direction;
        HudElement spd;
        spd.type = ElementType::SpeedMeter;
        layout.elements = {coords, dir, spd};

        bindMotionProviders(layout, nullptr);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);
        assert(layout.elements[2].text() == kUnavailable);

        RuntimeObservationSnapshot empty;
        bindMotionProviders(layout, &empty);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);
        assert(layout.elements[2].text() == kUnavailable);
    }

    // --- formatHealth: the server's own unit, never an invented denominator ---
    assert(formatHealth(std::optional<int>{20}) == "20");
    assert(formatHealth(std::optional<int>{0}) == "0");
    assert(formatHealth(std::nullopt) == kUnavailable);
    // formatLowHealth: unknown is NOT an alarm.
    assert(formatLowHealth(std::nullopt, 6) == kUnavailable);
    assert(formatLowHealth(std::optional<int>{6}, 6) == "LOW");
    assert(formatLowHealth(std::optional<int>{4}, 6) == "LOW");
    assert(formatLowHealth(std::optional<int>{20}, 6) == kUnavailable);

    // --- bindVitalsProviders: absent health renders unavailable, not zero ---
    {
        HudElement health;
        health.type = ElementType::Health;
        HudElement low;
        low.type = ElementType::LowHealth;
        HudLayout layout;
        layout.elements = {health, low};

        bindVitalsProviders(layout, nullptr);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);

        RuntimeObservationSnapshot empty;
        bindVitalsProviders(layout, &empty);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);

        RuntimeObservationSnapshot full;
        full.latestHealth = 4;
        bindVitalsProviders(layout, &full);
        assert(layout.elements[0].text() == "4");
        assert(layout.elements[1].text() == "LOW");

        // A snapshot whose health was cleared degrades back to unavailable
        // rather than keeping the last rendered number.
        full.latestHealth.reset();
        bindVitalsProviders(layout, &full);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);
    }

    // --- observed counts and rates render unknown as unknown ---
    assert(formatEntityCount(std::optional<std::uint64_t>{7}, std::optional<std::uint64_t>{2}) ==
           "7 (2p)");
    assert(formatEntityCount(std::optional<std::uint64_t>{7}, std::nullopt) == "7");
    assert(formatEntityCount(std::nullopt, std::optional<std::uint64_t>{2}) == kUnavailable);
    assert(formatTps(std::optional<double>{19.94}) == "19.9 tps");
    assert(formatTps(std::nullopt) == kUnavailable);
    // A negative or non-finite rate is a caller bug, never printed.
    assert(formatTps(std::optional<double>{-1.0}) == kUnavailable);

    // --- bindPopulationProviders: absent observations degrade, never fabricate ---
    {
        HudElement counter;
        counter.type = ElementType::EntityCounter;
        HudElement tps;
        tps.type = ElementType::Tps;
        HudLayout layout;
        layout.elements = {counter, tps};

        bindPopulationProviders(layout, nullptr);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);

        RuntimeObservationSnapshot empty;
        bindPopulationProviders(layout, &empty);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);

        RuntimeObservationSnapshot full;
        full.latestEntityCount = 7;
        full.latestPlayerCount = 2;
        full.ticksPerSecond = 19.94;
        bindPopulationProviders(layout, &full);
        assert(layout.elements[0].text() == "7 (2p)");
        assert(layout.elements[1].text() == "19.9 tps");

        full.latestEntityCount.reset();
        full.ticksPerSecond.reset();
        bindPopulationProviders(layout, &full);
        assert(layout.elements[0].text() == kUnavailable);
        assert(layout.elements[1].text() == kUnavailable);
    }

    // --- type names round-trip (serialize/deserialize depends on this) ---
    ElementType counterType{};
    assert(xykell::hud::typeFromName("entity_counter", counterType) &&
           counterType == ElementType::EntityCounter);
    assert(xykell::hud::typeFromName("tps", counterType) && counterType == ElementType::Tps);
    assert(xykell::hud::typeName(ElementType::EntityCounter) == "entity_counter");
    assert(xykell::hud::typeName(ElementType::Tps) == "tps");
    ElementType lowType{};
    assert(xykell::hud::typeFromName("low_health", lowType) &&
           lowType == ElementType::LowHealth);
    assert(xykell::hud::typeName(ElementType::LowHealth) == "low_health");
    assert(xykell::hud::typeName(ElementType::Health) == "health");
    ElementType t{};
    assert(xykell::hud::typeFromName("direction", t) && t == ElementType::Direction);
    assert(xykell::hud::typeFromName("speed_meter", t) && t == ElementType::SpeedMeter);
    assert(xykell::hud::typeName(ElementType::Direction) == "direction");
    assert(xykell::hud::typeName(ElementType::SpeedMeter) == "speed_meter");
    assert(xykell::hud::typeFromName("tab_list", t) && t == ElementType::TabList);
    assert(xykell::hud::typeName(ElementType::TabList) == "tab_list");
    assert(xykell::hud::typeFromName("server_info", t) && t == ElementType::ServerInfo);
    assert(xykell::hud::typeFromName("ip_display", t) && t == ElementType::IpDisplay);
    assert(xykell::hud::typeFromName("death_info", t) && t == ElementType::DeathInfo);

    // --- death readout: no death observed is not a death at 0,0,0 ---
    {
        const std::optional<xykell::runtime::DeathObservation> none;
        assert(formatDeathInfo(none) == kUnavailable);
        xykell::runtime::DeathObservation d;
        d.cause = "fell from a high place";
        d.x = 10.0; d.y = 64.0; d.z = -3.5;
        assert(formatDeathInfo(d) == "fell from a high place 10.00 64.00 -3.50");
        // A cause with no coordinates would read as a death at the origin.
        xykell::runtime::DeathObservation blank;
        blank.cause = "";
        assert(formatDeathInfo(blank) == kUnavailable);
    }

    // --- server facts: nothing renders before a session actually connects.
    {
        const std::optional<xykell::runtime::SessionEndpointObservation> none;
        assert(formatServerInfo(none) == kUnavailable);
        assert(formatIpDisplay(none) == kUnavailable);
        // Host known but no protocol yet is still not a complete claim.
        xykell::runtime::SessionEndpointObservation partial;
        partial.host = "mc.example.org";
        partial.port = 19132;
        assert(formatServerInfo(partial) == kUnavailable);
        assert(formatIpDisplay(partial) == "mc.example.org:19132");
        partial.protocolVersion = 800;
        assert(formatServerInfo(partial) == "mc.example.org (proto 800)");
    }

    // --- tab list: an unreported roster and an empty one are different claims.
    {
        assert(formatTabList({}, false, 6) == kUnavailable);
        assert(formatTabList({}, true, 6) == kUnavailable);
        const std::vector<std::string> two{"Steve", "Alex"};
        assert(formatTabList(two, true, 6) == "2 online: Steve, Alex");
        // The limit says how many were left out rather than silently truncating.
        const std::vector<std::string> many{"A", "B", "C"};
        assert(formatTabList(many, true, 2) == "3 online: A, B, +1");
    }

    std::cout << "test_motion_hud: PASS\n";
    return 0;
}
