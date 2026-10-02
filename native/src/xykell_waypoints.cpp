#include "xykell/waypoints.h"

#include <cmath>

namespace xykell::world {

double distance3d(double ax, double ay, double az, double bx, double by, double bz) {
    const double dx = ax - bx, dy = ay - by, dz = az - bz;
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

bool WaypointManager::add(const Waypoint& w, std::string& error) {
    if (w.name.empty() || w.name.size() > 64) {
        error = "bad waypoint name";
        return false;
    }
    if (get(w.name) != nullptr) {
        error = "waypoint already exists";
        return false;
    }
    if (!(std::isfinite(w.x) && std::isfinite(w.y) && std::isfinite(w.z))) {
        error = "non-finite coordinates";
        return false;
    }
    points_.push_back(w);
    return true;
}

bool WaypointManager::remove(const std::string& name) {
    for (auto it = points_.begin(); it != points_.end(); ++it) {
        if (it->name == name) {
            points_.erase(it);
            return true;
        }
    }
    return false;
}

bool WaypointManager::setVisible(const std::string& name, bool visible) {
    for (auto& w : points_) {
        if (w.name == name) {
            w.visible = visible;
            return true;
        }
    }
    return false;
}

const Waypoint* WaypointManager::get(const std::string& name) const {
    for (const auto& w : points_) {
        if (w.name == name) {
            return &w;
        }
    }
    return nullptr;
}

json::Value WaypointManager::serialize() const {
    json::Array arr;
    for (const auto& w : points_) {
        json::Object o;
        o.emplace("name", json::Value(w.name));
        o.emplace("x", json::Value(w.x));
        o.emplace("y", json::Value(w.y));
        o.emplace("z", json::Value(w.z));
        o.emplace("dimension", json::Value(w.dimension));
        o.emplace("color", json::Value(w.color));
        o.emplace("visible", json::Value(w.visible));
        arr.push_back(json::Value(std::move(o)));
    }
    return json::Value(std::move(arr));
}

bool WaypointManager::deserialize(const json::Value& v, std::string& error) {
    if (!v.isArray()) {
        error = "waypoints is not an array";
        return false;
    }
    std::vector<Waypoint> tmp;
    for (const auto& e : v.asArray(json::Value::emptyArray())) {
        if (!e.isObject()) {
            error = "waypoint is not an object";
            return false;
        }
        const auto& o = e.asObject(json::Value::emptyObject());
        Waypoint w;
        const auto n = o.find("name");
        if (n == o.end() || !n->second.isString()) {
            error = "waypoint without name";
            return false;
        }
        w.name = n->second.asString("");
        for (const auto* k : {"x", "y", "z"}) {
            const auto it = o.find(k);
            if (it != o.end()) {
                if (!it->second.isNumber()) {
                    error = "waypoint coordinate not a number";
                    return false;
                }
                const double d = it->second.asNumber();
                if (k[0] == 'x') {
                    w.x = d;
                } else if (k[0] == 'y') {
                    w.y = d;
                } else {
                    w.z = d;
                }
            }
        }
        const auto dim = o.find("dimension");
        if (dim != o.end() && dim->second.isString()) {
            w.dimension = dim->second.asString("overworld");
        }
        const auto vis = o.find("visible");
        if (vis != o.end()) {
            if (!vis->second.isBool()) {
                error = "waypoint visible not a bool";
                return false;
            }
            w.visible = vis->second.asBool();
        }
        std::string verr;
        // Validate through add() on a staging manager.
        WaypointManager probe;
        for (const auto& existing : tmp) {
            std::string ignored;
            probe.add(existing, ignored);
        }
        if (!probe.add(w, verr)) {
            error = verr;
            return false;
        }
        tmp.push_back(std::move(w));
    }
    points_ = std::move(tmp);
    return true;
}

} // namespace xykell::world
