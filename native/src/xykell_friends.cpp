#include "xykell/friends.h"

namespace xykell::social {

bool FriendManager::validName(const std::string& name) {
    return !name.empty() && name.size() <= 32;
}

bool FriendManager::validColor(const std::string& color) {
    if (color.size() != 7 || color[0] != '#') {
        return false;
    }
    for (std::size_t i = 1; i < 7; ++i) {
        const char c = color[i];
        const bool hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                      || (c >= 'A' && c <= 'F');
        if (!hex) {
            return false;
        }
    }
    return true;
}

bool FriendManager::add(const Friend& f, std::string& error) {
    if (!validName(f.name)) {
        error = "invalid friend name";
        return false;
    }
    if (isFriend(f.name)) {
        error = "already a friend";
        return false;
    }
    if (!validColor(f.color)) {
        error = "bad color";
        return false;
    }
    friends_.push_back(f);
    return true;
}

bool FriendManager::remove(const std::string& name) {
    for (auto it = friends_.begin(); it != friends_.end(); ++it) {
        if (it->name == name) {
            friends_.erase(it);
            return true;
        }
    }
    return false;
}

bool FriendManager::rename(const std::string& from, const std::string& to,
                           std::string& error) {
    if (!validName(to)) {
        error = "invalid friend name";
        return false;
    }
    if (isFriend(to)) {
        error = "name taken";
        return false;
    }
    for (auto& f : friends_) {
        if (f.name == from) {
            f.name = to;
            return true;
        }
    }
    error = "no such friend";
    return false;
}

bool FriendManager::setColor(const std::string& name, const std::string& color) {
    if (!validColor(color)) {
        return false;
    }
    for (auto& f : friends_) {
        if (f.name == name) {
            f.color = color;
            return true;
        }
    }
    return false;
}

bool FriendManager::isFriend(const std::string& name) const {
    for (const auto& f : friends_) {
        if (f.name == name) {
            return true;
        }
    }
    return false;
}

json::Value FriendManager::serialize() const {
    json::Array arr;
    for (const auto& f : friends_) {
        json::Object o;
        o.emplace("name", json::Value(f.name));
        o.emplace("color", json::Value(f.color));
        o.emplace("notes", json::Value(f.notes));
        o.emplace("server", json::Value(f.server));
        arr.push_back(json::Value(std::move(o)));
    }
    return json::Value(std::move(arr));
}

bool FriendManager::deserialize(const json::Value& v, std::string& error) {
    if (!v.isArray()) {
        error = "friends is not an array";
        return false;
    }
    std::vector<Friend> tmp;
    for (const auto& e : v.asArray(json::Value::emptyArray())) {
        if (!e.isObject()) {
            error = "friend is not an object";
            return false;
        }
        const auto& o = e.asObject(json::Value::emptyObject());
        Friend f;
        const auto n = o.find("name");
        if (n == o.end() || !n->second.isString()) {
            error = "friend without name";
            return false;
        }
        f.name = n->second.asString("");
        const auto c = o.find("color");
        if (c != o.end()) {
            if (!c->second.isString()) {
                error = "friend color not a string";
                return false;
            }
            f.color = c->second.asString("#4FD8C7");
        }
        const auto nt = o.find("notes");
        if (nt != o.end() && nt->second.isString()) {
            f.notes = nt->second.asString("");
        }
        const auto sv = o.find("server");
        if (sv != o.end() && sv->second.isString()) {
            f.server = sv->second.asString("");
        }
        std::string aerr;
        // Reuse validation by staging through a clean manager.
        FriendManager probe;
        for (const auto& existing : tmp) {
            std::string ignored;
            probe.add(existing, ignored);
        }
        if (!probe.add(f, aerr)) {
            error = aerr;
            return false;
        }
        tmp.push_back(std::move(f));
    }
    friends_ = std::move(tmp);
    return true;
}

} // namespace xykell::social
