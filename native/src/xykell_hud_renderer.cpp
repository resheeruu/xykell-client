#include "xykell/hud_renderer.h"

namespace xykell::hud {

std::uint32_t themeColor(const std::string& hex) {
    const auto digit = [](char c, unsigned& out) {
        if (c >= '0' && c <= '9') {
            out = static_cast<unsigned>(c - '0');
        } else if (c >= 'a' && c <= 'f') {
            out = static_cast<unsigned>(c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
            out = static_cast<unsigned>(c - 'A' + 10);
        } else {
            return false;
        }
        return true;
    };
    if (hex.size() != 7 && hex.size() != 9) {
        return 0xFFFFFFFF;
    }
    if (hex[0] != '#') {
        return 0xFFFFFFFF;
    }
    unsigned v = 0;
    for (std::size_t i = 1; i < hex.size(); ++i) {
        unsigned d = 0;
        if (!digit(hex[i], d)) {
            return 0xFFFFFFFF;
        }
        v = (v << 4) | d;
    }
    if (hex.size() == 7) {
        v |= 0xFF000000; // #RRGGBB -> opaque
    }
    return v;
}


namespace {

// Small fixed-size de-dup window for notification seq ids.
constexpr std::size_t kNotificationSeenCap = 64;

// Deterministic module ordering: category, then id. Registration order is not
// stable across sessions, so relying on it would make the arraylist flicker.
bool moduleLess(const ModuleDescriptor& a, const ModuleDescriptor& b) {
    if (a.category != b.category) {
        return a.category < b.category;
    }
    return a.id < b.id;
}

} // namespace

std::vector<std::string> enabledModuleNames(const ModuleManager& mods, int limit) {
    std::vector<const ModuleDescriptor*> on;
    for (const auto& m : mods.list()) {
        // A quarantined module is disabled by definition; never list it.
        if (m.state != ModuleState::Enabled || !m.quarantineReason.empty()) {
            continue;
        }
        on.push_back(&m);
    }
    std::sort(on.begin(), on.end(),
              [](const ModuleDescriptor* a, const ModuleDescriptor* b) { return moduleLess(*a, *b); });
    std::vector<std::string> out;
    const std::size_t cap = limit > 0 ? static_cast<std::size_t>(limit) : on.size();
    for (std::size_t i = 0; i < on.size() && out.size() < cap; ++i) {
        // Display name from the descriptor; never a registry id we invented.
        const std::string& n = on[i]->name;
        if (!n.empty()) {
            out.push_back(n);
        }
    }
    return out;
}

std::vector<std::string> notificationLines(const ui::NotificationCenter& center, int limit) {
    std::vector<std::string> out;
    if (limit <= 0) {
        return out;
    }
    std::uint64_t seen[kNotificationSeenCap] = {};
    std::size_t seenN = 0;
    // Read without draining: render must not consume the queue, or a HUD that
    // is hidden for one frame would silently drop notifications.
    for (const auto& n : center.peek()) {
        if (n.text.empty()) {
            continue;
        }
        bool dup = false;
        for (std::size_t i = 0; i < seenN; ++i) {
            if (seen[i] == n.seq) {
                dup = true;
                break;
            }
        }
        if (dup) {
            continue;
        }
        if (seenN < kNotificationSeenCap) {
            seen[seenN++] = n.seq;
        }
        if (n.priority == ui::NotifyPriority::Error) {
            out.push_back("[!] " + n.text);
        } else if (n.priority == ui::NotifyPriority::Warning) {
            out.push_back("[*] " + n.text);
        } else {
            out.push_back(n.text);
        }
    }
    while (out.size() > static_cast<std::size_t>(limit)) {
        out.erase(out.begin()); // keep the newest
    }
    return out;
}

std::vector<HudLine> renderHud(const HudManager& mgr, const RenderContext& ctx) {
    if (!ctx.hudVisible) {
        return {}; // hidden means absent, not "drawn with no content"
    }
    const std::uint32_t textCol =
        ctx.theme != nullptr ? themeColor(ctx.theme->text) : 0xFFFFFFFF;
    const std::uint32_t mutedCol =
        ctx.theme != nullptr ? themeColor(ctx.theme->muted) : 0xFFFFFFFF;
    const std::uint32_t accentCol =
        ctx.theme != nullptr ? themeColor(ctx.theme->accent) : 0xFFFFFFFF;
    std::vector<HudLine> out;
    for (const auto& el : mgr.layout().elements) {
        if (!el.visible) {
            continue;
        }
        HudLine line;
        line.x = el.x;
        line.y = el.y;
        line.size = 20.0f * el.scale;
        line.color = textCol;
        const std::string v = el.text();
        switch (el.type) {
            case ElementType::Watermark:
                line.text = v == kUnavailable ? "XYKELL" : v;
                line.color = accentCol;
                break;
            case ElementType::Fps:
                line.text = std::string("FPS: ") + v;
                line.color = (v == kUnavailable) ? mutedCol : textCol;
                break;
            case ElementType::Coordinates:
                line.text = std::string("XYZ: ") + v;
                line.color = (v == kUnavailable) ? mutedCol : textCol;
                break;
            case ElementType::ModuleList: {
                // Real list, not a count. Falls back to an explicit unknown
                // marker rather than an empty box when there is no manager.
                std::vector<std::string> names;
                if (ctx.modules != nullptr) {
                    names = enabledModuleNames(*ctx.modules, ctx.arraylistLimit);
                }
                if (names.empty()) {
                    line.text = std::string(typeName(el.type)) + ": " + kUnavailable;
                    line.color = mutedCol;
                    break;
                }
                // Stacked, top-aligned, one line per enabled module.
                std::string joined;
                for (std::size_t i = 0; i < names.size(); ++i) {
                    if (i > 0) {
                        joined += " | ";
                    }
                    joined += names[i];
                }
                line.text = joined;
                line.color = accentCol;
                break;
            }
            case ElementType::Notifications: {
                std::vector<std::string> notes;
                if (ctx.notifications != nullptr) {
                    notes = notificationLines(*ctx.notifications, ctx.notificationLines);
                }
                line.text = notes.empty()
                                ? std::string(typeName(el.type)) + ": " + kUnavailable
                                : notes.back();
                line.color = notes.empty() ? mutedCol : textCol;
                break;
            }
            default:
                line.text = typeName(el.type) + ": " + v;
                line.color = (v == kUnavailable) ? mutedCol : textCol;
                break;
        }
        out.push_back(std::move(line));
    }
    // Real state lines: version + tap count + module states (no fabrication).
    HudLine ver;
    ver.text = ctx.versionLine;
    ver.x = 16.0f;
    ver.y = 160.0f;
    ver.color = mutedCol;
    out.push_back(ver);
    HudLine taps;
    taps.text = "taps: " + std::to_string(ctx.taps);
    taps.x = 16.0f;
    taps.y = 188.0f;
    taps.color = textCol;
    out.push_back(taps);
    if (ctx.modules != nullptr) {
        std::size_t on = 0, total = 0;
        for (const auto& m : ctx.modules->list()) {
            ++total;
            if (m.state == ModuleState::Enabled) {
                ++on;
            }
        }
        HudLine mods;
        mods.text = "modules: " + std::to_string(on) + "/" + std::to_string(total) + " on";
        mods.x = 16.0f;
        mods.y = 216.0f;
        mods.color = mutedCol;
        out.push_back(mods);
    }
    return out;
}
void clampToViewport(HudLayout& layout, float w, float h) {
    if (w <= 0.0f || h <= 0.0f) {
        return; // unknown viewport: no clamping, no guessing
    }
    for (auto& el : layout.elements) {
        if (el.x < 0.0f) {
            el.x = 0.0f;
        }
        if (el.y < 0.0f) {
            el.y = 0.0f;
        }
        if (el.x > w) {
            el.x = w;
        }
        if (el.y > h) {
            el.y = h;
        }
    }
}

void saveHudToProfile(Profile& profile, const HudManager& mgr) {
    profile.hudLayout = mgr.saveLayout();
}

std::vector<HudLine> proofBanner(const std::string& versionLine) {
    const std::string bar(32, '-');
    std::vector<HudLine> out;
    HudLine l0;
    l0.text = "+------------------------------+";
    l0.x = 16.0f;
    l0.y = 16.0f;
    l0.color = 0xFF4FD8C7;
    out.push_back(l0);
    HudLine l1 = l0;
    l1.text = "| XYKELL CLIENT RUNTIME ACTIVE |";
    l1.y = 40.0f;
    out.push_back(l1);
    HudLine l2 = l0;
    l2.text = "+" + bar + "+";
    l2.y = 64.0f;
    out.push_back(l2);
    HudLine l3 = l0;
    l3.text = versionLine;
    l3.y = 88.0f;
    l3.color = 0xFFFFFFFF;
    out.push_back(l3);
    return out;
}

bool loadHudFromProfile(const Profile& profile, HudManager& mgr, std::string& error) {
    return mgr.loadLayout(profile.hudLayout, error);
}

} // namespace xykell::hud
