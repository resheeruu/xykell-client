#pragma once

// Client-side friend manager (local list only — never inferred from private
// data, never synced anywhere). Used for ESP/combat exclusion + alerts.
#include <string>
#include <vector>

#include "xykell/json_min.h"

namespace xykell::social {

struct Friend {
    std::string name;
    std::string color = "#4FD8C7";
    std::string notes;
    std::string server; // optional association, may be empty
};

class FriendManager {
  public:
    bool add(const Friend& f, std::string& error);
    bool remove(const std::string& name);
    bool rename(const std::string& from, const std::string& to, std::string& error);
    bool setColor(const std::string& name, const std::string& color);
    bool isFriend(const std::string& name) const;
    const std::vector<Friend>& list() const { return friends_; }

    json::Value serialize() const;
    bool deserialize(const json::Value& v, std::string& error);

  private:
    std::vector<Friend> friends_;
    static bool validName(const std::string& name);
    static bool validColor(const std::string& color);
};

} // namespace xykell::social
