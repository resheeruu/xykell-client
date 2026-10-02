// Host unit test: friends, notifications, server profiles, waypoints,
// extended themes. All local-only, no runtime needed.
#include <cassert>
#include <iostream>

#include "xykell/friends.h"
#include "xykell/hud_model.h"
#include "xykell/notifications.h"
#include "xykell/server_profiles.h"
#include "xykell/theme.h"
#include "xykell/waypoints.h"

int main() {
    using namespace xykell;
    std::string err;

    // Friends.
    social::FriendManager fr;
    assert(fr.add({"Steve", "#FF0000", "builder", "hive"}, err));
    assert(!fr.add({"Steve", "#FF0000", "", ""}, err)); // duplicate
    assert(!fr.add({"", "#FF0000", "", ""}, err)); // bad name
    assert(!fr.add({"X", "red", "", ""}, err)); // bad color
    assert(fr.isFriend("Steve") && !fr.isFriend("Alex"));
    assert(fr.rename("Steve", "Alex", err) && fr.isFriend("Alex"));
    assert(fr.setColor("Alex", "#00FF00") && !fr.setColor("Alex", "nope"));
    assert(fr.remove("Alex") && !fr.isFriend("Alex"));
    assert(fr.add({"A", "#111111", "", ""}, err));
    const std::string fblob = json::stringify(fr.serialize());
    social::FriendManager fr2;
    assert(fr2.deserialize(json::parse(fblob).value, err) && fr2.isFriend("A"));
    social::FriendManager fr3;
    assert(!fr3.deserialize(json::parse("[{\"color\":\"#111111\"}]").value, err));

    // Notifications: bounded, draining.
    ui::NotificationCenter nc;
    nc.post("", ui::NotifyPriority::Info); // empty ignored
    assert(nc.pending() == 0);
    for (int i = 0; i < 40; ++i) {
        nc.post("n", ui::NotifyPriority::Warning);
    }
    assert(nc.pending() == ui::NotificationCenter::kCap);
    const auto drained = nc.drain();
    assert(drained.size() == ui::NotificationCenter::kCap && nc.pending() == 0);
    assert(drained.front().priority == ui::NotifyPriority::Warning);

    // Server profiles.
    net::ServerManager sm;
    assert(sm.add({"Hive", "play.hivemc.com", 19132, "", true, "PvP", "Default"}, err));
    assert(!sm.add({"Hive", "x", 1, "", false, "", ""}, err)); // dup
    assert(!sm.add({"Bad", "x", 0, "", false, "", ""}, err)); // bad port
    assert(!sm.add({"Bad", "", 19132, "", false, "", ""}, err)); // no address
    assert(sm.get("Hive")->favorite && sm.setFavorite("Hive", false));
    assert(!sm.get("Hive")->favorite);
    assert(sm.remove("Hive") && sm.get("Hive") == nullptr);
    assert(sm.add({"A", "a.b.c", 19133, "", false, "", ""}, err));
    const std::string sblob = json::stringify(sm.serialize());
    net::ServerManager sm2;
    assert(sm2.deserialize(json::parse(sblob).value, err));
    assert(sm2.get("A")->port == 19133);
    net::ServerManager sm3;
    assert(!sm3.deserialize(json::parse("[{\"name\":\"x\"}]").value, err)); // no address

    // Waypoints.
    world::WaypointManager wm;
    assert(wm.add({"home", 0, 64, 0, "overworld", "#FF0000", true}, err));
    assert(!wm.add({"home", 0, 0, 0, "", "", true}, err)); // dup
    assert(!wm.add({"", 0, 0, 0, "", "", true}, err));
    assert(wm.setVisible("home", false) && !wm.get("home")->visible);
    assert(world::distance3d(0, 0, 0, 3, 4, 0) == 5.0);
    const std::string wblob = json::stringify(wm.serialize());
    world::WaypointManager wm2;
    assert(wm2.deserialize(json::parse(wblob).value, err));
    assert(wm2.get("home")->x == 0.0);
    world::WaypointManager wm3;
    assert(!wm3.deserialize(json::parse("[{\"name\":1}]").value, err));

    // Themes: 4 builtins, extended fields round-trip + range-checked.
    ui::Theme amoled, minimal;
    assert(ui::ThemeManager::find("Xykell AMOLED", amoled));
    assert(ui::ThemeManager::find("Xykell Minimal", minimal));
    assert(amoled.background == "#000000" && minimal.radius == 4.0);
    const std::string tbl = json::stringify(amoled.serialize());
    ui::Theme rt;
    assert(rt.deserialize(json::parse(tbl).value, err));
    assert(rt.background == "#000000" && rt.opacity == 1.0);
    ui::Theme bad;
    assert(!bad.deserialize(json::parse(R"({"opacity":9})").value, err));
    assert(!bad.deserialize(json::parse(R"({"radius":-1})").value, err));

    std::cout << "test_local_systems: PASS\n";
    return 0;
}
