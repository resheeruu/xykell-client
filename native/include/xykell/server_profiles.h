#pragma once

// Server profiles: user-supplied servers only (address/port/notes/favorites
// + module/HUD profile association). No credentials, no auto-discovery, no
// connection attempts here — this is configuration, not networking.
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell::net {

struct ServerProfile {
    std::string name;
    std::string address;
    int port = 19132;
    std::string notes;
    bool favorite = false;
    std::string moduleProfile = "Default";
    std::string hudProfile = "Default";
};

class ServerManager {
  public:
    bool add(const ServerProfile& s, std::string& error);
    bool remove(const std::string& name);
    bool setFavorite(const std::string& name, bool fav);
    const ServerProfile* get(const std::string& name) const;
    const std::vector<ServerProfile>& list() const { return servers_; }

    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);

  private:
    std::vector<ServerProfile> servers_;
    static bool valid(const ServerProfile& s, std::string& error);
};

} // namespace xykell::net
