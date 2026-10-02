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

std::vector<HudLine> renderHud(const HudManager& mgr, const RenderContext& ctx) {
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

bool loadHudFromProfile(const Profile& profile, HudManager& mgr, std::string& error) {
    return mgr.loadLayout(profile.hudLayout, error);
}

} // namespace xykell::hud
