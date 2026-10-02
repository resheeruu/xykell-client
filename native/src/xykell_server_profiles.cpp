#include "xykell/server_profiles.h"

namespace xykell::net {

bool ServerManager::valid(const ServerProfile& s, std::string& error) {
    if (s.name.empty() || s.name.size() > 64) {
        error = "bad server name";
        return false;
    }
    if (s.address.empty() || s.address.size() > 253) {
        error = "bad server address";
        return false;
    }
    if (s.port < 1 || s.port > 65535) {
        error = "bad server port";
        return false;
    }
    return true;
}

bool ServerManager::add(const ServerProfile& s, std::string& error) {
    if (!valid(s, error)) {
        return false;
    }
    if (get(s.name) != nullptr) {
        error = "server already saved";
        return false;
    }
    servers_.push_back(s);
    return true;
}

bool ServerManager::remove(const std::string& name) {
    for (auto it = servers_.begin(); it != servers_.end(); ++it) {
        if (it->name == name) {
            servers_.erase(it);
            return true;
        }
    }
    return false;
}

bool ServerManager::setFavorite(const std::string& name, bool fav) {
    for (auto& s : servers_) {
        if (s.name == name) {
            s.favorite = fav;
            return true;
        }
    }
    return false;
}

const ServerProfile* ServerManager::get(const std::string& name) const {
    for (const auto& s : servers_) {
        if (s.name == name) {
            return &s;
        }
    }
    return nullptr;
}

json::Value ServerManager::serialize() const {
    json::Array arr;
    for (const auto& s : servers_) {
        json::Object o;
        o.emplace("name", json::Value(s.name));
        o.emplace("address", json::Value(s.address));
        o.emplace("port", json::Value(s.port));
        o.emplace("notes", json::Value(s.notes));
        o.emplace("favorite", json::Value(s.favorite));
        o.emplace("moduleProfile", json::Value(s.moduleProfile));
        o.emplace("hudProfile", json::Value(s.hudProfile));
        arr.push_back(json::Value(std::move(o)));
    }
    return json::Value(std::move(arr));
}

bool ServerManager::deserialize(const json::Value& v, std::string& error) {
    if (!v.isArray()) {
        error = "servers is not an array";
        return false;
    }
    std::vector<ServerProfile> tmp;
    for (const auto& e : v.asArray(json::Value::emptyArray())) {
        if (!e.isObject()) {
            error = "server is not an object";
            return false;
        }
        const auto& o = e.asObject(json::Value::emptyObject());
        ServerProfile s;
        const auto name = o.find("name");
        const auto addr = o.find("address");
        if (name == o.end() || !name->second.isString() || addr == o.end()
            || !addr->second.isString()) {
            error = "server missing name/address";
            return false;
        }
        s.name = name->second.asString("");
        s.address = addr->second.asString("");
        const auto port = o.find("port");
        if (port != o.end()) {
            if (!port->second.isNumber()) {
                error = "server port not a number";
                return false;
            }
            s.port = static_cast<int>(port->second.asNumber());
        }
        const auto notes = o.find("notes");
        if (notes != o.end() && notes->second.isString()) {
            s.notes = notes->second.asString("");
        }
        const auto fav = o.find("favorite");
        if (fav != o.end()) {
            if (!fav->second.isBool()) {
                error = "server favorite not a bool";
                return false;
            }
            s.favorite = fav->second.asBool();
        }
        const auto mp = o.find("moduleProfile");
        if (mp != o.end() && mp->second.isString()) {
            s.moduleProfile = mp->second.asString("Default");
        }
        const auto hp = o.find("hudProfile");
        if (hp != o.end() && hp->second.isString()) {
            s.hudProfile = hp->second.asString("Default");
        }
        std::string verr;
        if (!valid(s, verr)) {
            error = verr;
            return false;
        }
        for (const auto& existing : tmp) {
            if (existing.name == s.name) {
                error = "duplicate server: " + s.name;
                return false;
            }
        }
        tmp.push_back(std::move(s));
    }
    servers_ = std::move(tmp);
    return true;
}

} // namespace xykell::net
