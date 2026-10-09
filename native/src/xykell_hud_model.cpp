#include "xykell/hud_model.h"

namespace xykell::hud {

std::string typeName(ElementType t) {
    switch (t) {
        case ElementType::Watermark: return "watermark";
        case ElementType::Fps: return "fps";
        case ElementType::Coordinates: return "coordinates";
        case ElementType::Cps: return "cps";
        case ElementType::ModuleList: return "module_list";
        case ElementType::Notifications: return "notifications";
        case ElementType::Armor: return "armor";
        case ElementType::Health: return "health";
        case ElementType::LowHealth: return "low_health";
        case ElementType::EntityCounter: return "entity_counter";
        case ElementType::Tps: return "tps";
        case ElementType::TabList: return "tab_list";
        case ElementType::Hunger: return "hunger";
        case ElementType::Keystrokes: return "keystrokes";
        case ElementType::TargetHud: return "target_hud";
        case ElementType::HardwareStats: return "hardware_stats";
        case ElementType::Direction: return "direction";
        case ElementType::SpeedMeter: return "speed_meter";
    }
    return "watermark";
}

bool typeFromName(const std::string& name, ElementType& out) {
    const ElementType all[] = {ElementType::Watermark,     ElementType::Fps,
                               ElementType::Coordinates,    ElementType::Cps,
                               ElementType::ModuleList,     ElementType::Notifications,
                               ElementType::Armor,          ElementType::Health,
                               ElementType::LowHealth,       ElementType::Hunger,
                               ElementType::EntityCounter,   ElementType::Tps,
                               ElementType::TabList,         ElementType::Keystrokes,
                               ElementType::TargetHud,      ElementType::HardwareStats,
                               ElementType::Direction,      ElementType::SpeedMeter};
    for (const auto t : all) {
        if (typeName(t) == name) {
            out = t;
            return true;
        }
    }
    return false;
}

std::string HudElement::text() const {
    if (!provider) {
        return kUnavailable;
    }
    try {
        return provider();
    } catch (...) {
        return kUnavailable; // a failing provider degrades, never crashes
    }
}

json::Value HudElement::serialize() const {
    json::Object o;
    o.emplace("type", json::Value(typeName(type)));
    o.emplace("x", json::Value(static_cast<double>(x)));
    o.emplace("y", json::Value(static_cast<double>(y)));
    o.emplace("scale", json::Value(static_cast<double>(scale)));
    o.emplace("visible", json::Value(visible));
    o.emplace("align", json::Value(align));
    return json::Value(std::move(o));
}

bool HudElement::deserialize(const json::Value& v, std::string& error) {
    if (!v.isObject()) {
        error = "element is not an object";
        return false;
    }
    const auto& o = v.asObject(json::Value::emptyObject());
    HudElement tmp;
    const auto t = o.find("type");
    if (t == o.end() || !t->second.isString() || !typeFromName(t->second.asString(""), tmp.type)) {
        error = "element has unknown type";
        return false;
    }
    const auto num = [&](const char* k, float& slot) {
        const auto it = o.find(k);
        if (it != o.end()) {
            if (!it->second.isNumber()) {
                error = std::string("element.") + k + " is not a number";
                return false;
            }
            slot = static_cast<float>(it->second.asNumber());
        }
        return true;
    };
    if (!num("x", tmp.x) || !num("y", tmp.y) || !num("scale", tmp.scale)) {
        return false;
    }
    const auto vis = o.find("visible");
    if (vis != o.end()) {
        if (!vis->second.isBool()) {
            error = "element.visible is not a bool";
            return false;
        }
        tmp.visible = vis->second.asBool();
    }
    const auto al = o.find("align");
    if (al != o.end()) {
        if (!al->second.isString() || (al->second.asString("") != "left"
                                       && al->second.asString("") != "center"
                                       && al->second.asString("") != "right")) {
            error = "element.align invalid";
            return false;
        }
        tmp.align = al->second.asString("left");
    }
    *this = std::move(tmp);
    return true;
}

HudLayout HudLayout::m1Default() {
    HudLayout l;
    l.name = "Default";
    HudElement watermark;
    watermark.type = ElementType::Watermark;
    watermark.provider = [] { return std::string("XYKELL"); };
    HudElement fps;
    fps.type = ElementType::Fps;
    fps.y = 76.0f;
    HudElement coords;
    coords.type = ElementType::Coordinates;
    coords.y = 104.0f;
    HudElement keys;
    keys.type = ElementType::Keystrokes;
    keys.y = 132.0f;
    l.elements = {watermark, fps, coords, keys};
    return l;
}

json::Value HudLayout::serialize() const {
    json::Object o;
    o.emplace("name", json::Value(name));
    json::Array arr;
    for (const auto& e : elements) {
        arr.push_back(e.serialize());
    }
    o.emplace("elements", json::Value(std::move(arr)));
    return json::Value(std::move(o));
}

bool HudLayout::deserialize(const json::Value& v, std::string& error) {
    if (!v.isObject()) {
        error = "layout is not an object";
        return false;
    }
    const auto& o = v.asObject(json::Value::emptyObject());
    HudLayout tmp;
    const auto n = o.find("name");
    if (n != o.end()) {
        if (!n->second.isString()) {
            error = "layout.name is not a string";
            return false;
        }
        tmp.name = n->second.asString("Default");
    }
    const auto e = o.find("elements");
    if (e != o.end()) {
        if (!e->second.isArray()) {
            error = "layout.elements is not an array";
            return false;
        }
        for (const auto& ev : e->second.asArray(json::Value::emptyArray())) {
            HudElement el;
            if (!el.deserialize(ev, error)) {
                return false;
            }
            tmp.elements.push_back(std::move(el));
        }
    }
    *this = std::move(tmp);
    return true;
}

bool HudEditor::apply(HudLayout& layout) {
    if (selected < 0 || selected >= static_cast<int>(layout.elements.size())) {
        return false;
    }
    auto& el = layout.elements[static_cast<std::size_t>(selected)];
    el.x += dx;
    el.y += dy;
    el.scale = scale;
    el.visible = visible;
    dx = dy = 0.0f;
    return true;
}

bool HudEditor::applySnapped(HudLayout& layout, float grid) {
    if (selected < 0 || selected >= static_cast<int>(layout.elements.size())) {
        return false;
    }
    dx = snapToGrid(dx, grid);
    dy = snapToGrid(dy, grid);
    return apply(layout);
}

void HudEditor::reset(HudLayout& layout, const HudLayout& defaults) {
    layout = defaults;
    clear();
    scale = 1.0f;
    visible = true;
}

bool HudManager::loadLayout(const json::Value& v, std::string& error) {
    HudLayout tmp;
    if (!tmp.deserialize(v, error)) {
        return false; // keep current layout on corrupt input
    }
    layout_ = std::move(tmp);
    editor_.clear();
    return true;
}

} // namespace xykell::hud
