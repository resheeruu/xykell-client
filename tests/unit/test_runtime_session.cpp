// Host unit test: runtime session honesty, game-state defaults, feature gate.
#include <cassert>
#include <iostream>
#include <string>

#include "xykell/runtime_provider.h"
#include "xykell/runtime_session.h"

using namespace xykell::runtime;

namespace {

void assertUnattached(const GameState& g) {
    assert(g.playerPresence.availability == Availability::Unavailable);
    assert(g.worldPresence.availability == Availability::Unavailable);
    assert(g.dimension.availability == Availability::Unavailable);
    assert(g.position.availability == Availability::Unavailable);
    assert(g.rotation.availability == Availability::Unavailable);
    assert(g.health.availability == Availability::Unavailable);
    assert(g.screen.availability == Availability::Unavailable);
    assert(g.entityCount.availability == Availability::Unavailable);
    // No fabricated defaults: values stay empty.
    assert(g.health.value.empty() && g.position.value.empty());
    assert(g.dimension.value.empty());
}

} // namespace

int main() {
    // --- session lifecycle: unique deterministic ids, honest flags ---
    {
        SessionManager m;
        assert(!m.active());
        RuntimeSession a = m.begin("com.mojang.minecraftpe", "1.26.45.1", "synthetic-relay");
        assert(m.active());
        assert(a.sessionId == "xykell-session-1");
        assert(a.minecraftPackage == "com.mojang.minecraftpe");
        assert(!a.launched && !a.connected);  // launch is not connection
        assert(!a.diagnostics.empty());
        assertUnattached(a.gameState);
        m.markLaunched();
        assert(m.current().launched);
        assert(!m.current().connected);  // launching never connects
        RuntimeSession b = m.begin("com.mojang.minecraftpe", "1.26.45.1", "synthetic-relay");
        assert(b.sessionId == "xykell-session-2");  // uniqueness, no reuse
        assert(m.sessionsStarted() == 2);
        m.end("user stopped");
        assert(!m.active());
        assert(m.current().diagnostics == "user stopped");
        m.end("again");  // safe when inactive
    }
    // --- Runtime-integrated session ---
    {
        Runtime rt;
        assert(!rt.sessionActive());
        const RuntimeSession s = rt.beginSession("com.mojang.minecraftpe", "9.9.9");
        assert(rt.sessionActive());
        assert(s.sessionId == "xykell-session-1");
        assert(s.providerName == "synthetic-relay");
        assert(!s.connected);
        rt.markSessionLaunched();
        assert(rt.session().launched && !rt.session().connected);
        rt.endSession("done");
        assert(!rt.sessionActive());
    }
    // --- feature gate: honest mapping, never inflated ---
    {
        using FA = FeatureAvailability;
        assert(availabilityFor(false, false, false) == FA::Available);  // launcher-local
        assert(availabilityFor(true, false, false) == FA::RuntimeRequired);
        assert(availabilityFor(true, false, true) == FA::Available);  // demonstrated wins
        assert(availabilityFor(true, true, false) == FA::Unknown);  // connected, unproven
        assert(std::string(toString(FA::RuntimeRequired)) == "RUNTIME_REQUIRED");
        // Spot classifications matching the capability bridge:
        assert(availabilityFor(false, false, false) == FA::Available);  // HUD shell
        assert(availabilityFor(true, false, false) ==
               FA::RuntimeRequired);  // PLAYER_POSITION etc.
    }

    std::cout << "test_runtime_session: PASS\n";
    return 0;
}
