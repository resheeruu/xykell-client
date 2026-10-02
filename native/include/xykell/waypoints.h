#pragma once

// Waypoints: named positions with dimension/color/visibility, persisted to
// profiles. Distance is pure math over caller-supplied coordinates — without
// a verified player position the caller passes nothing and distance stays
// unavailable (no fabrication at this layer).
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell::world {

struct Waypoint {
    std::string name;
    double x = 0.0, y = 0.0, z = 0.0;
    std::string dimension = "overworld";
    std::string color = "#4FD8C7";
    bool visible = true;
};

double distance3d(double ax, double ay, double az, double bx, double by, double bz);

class WaypointManager {
  public:
    bool add(const Waypoint& w, std::string& error);
    bool remove(const std::string& name);
    bool setVisible(const std::string& name, bool visible);
    const Waypoint* get(const std::string& name) const;
    const std::vector<Waypoint>& list() const { return points_; }

    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);

  private:
    std::vector<Waypoint> points_;
};

} // namespace xykell::world
