// Host unit test: shared runtime substrate + synthetic relay provider.
// Deterministic: manual clock, in-memory transport, fixed synthetic ids.
#include <cassert>
#include <iostream>
#include <string>
#include <vector>

#include "xykell/runtime_provider.h"

using namespace xykell::runtime;

namespace {

// Read-only diagnostic consumer: records snapshots, mutates nothing.
struct RecordingSink : CapabilitySink {
    std::vector<PlayerObservation> players;
    std::vector<SessionObservation> sessions;
    std::vector<ConnectionObservation> connections;
    void onPlayer(const PlayerObservation& o) override { players.push_back(o); }
    void onSession(const SessionObservation& o) override { sessions.push_back(o); }
    void onConnection(const ConnectionObservation& o) override { connections.push_back(o); }
};

} // namespace

int main() {
    // --- initial state ---
    {
        Runtime rt;
        assert(rt.selectedProviderName() == "synthetic-relay");
        assert(rt.state() == ProviderState::Stopped);
        assert(!rt.capabilities().empty());
    }
    // --- lifecycle: start/stop/repeated ---
    {
        Runtime rt;
        assert(rt.start());
        assert(rt.state() == ProviderState::Running);
        assert(!rt.start());  // no duplicate starts
        rt.stop();
        assert(rt.state() == ProviderState::Stopped);
        rt.stop();  // idempotent
        assert(rt.state() == ProviderState::Stopped);
        assert(rt.start());  // recovery after stop
        rt.stop();
    }
    // --- failure + recovery via native stub (no fake success) ---
    {
        Runtime rt;
        assert(rt.selectProvider("native"));
        assert(!rt.start());
        assert(rt.state() == ProviderState::Failed);
        assert(rt.diagnostics().lastError.find("UNAVAILABLE") != std::string::npos);
        assert(rt.provider()->capabilities().empty());
        assert(rt.selectProvider("synthetic-relay"));  // recovery path
        assert(rt.start());
        assert(rt.state() == ProviderState::Running);
        rt.stop();
    }
    // --- unknown provider fails safely ---
    {
        Runtime rt;
        assert(!rt.selectProvider("does-not-exist"));
        assert(rt.selectedProviderName() == "synthetic-relay");
    }
    // --- endpoints: coexistence, selection, disappearance, TTL ---
    {
        SyntheticRelayProvider p;
        p.setManualTimeMs(1000);
        assert(p.start());
        auto eps = p.discover();
        assert(eps.size() == 2);  // synthetic-a + synthetic-b coexist
        assert(eps[0].id != eps[1].id);
        p.advertise(EndpointDescriptor{"custom", "Custom", "127.0.0.1", 19999,
                                       {"player-observation"}, 0, 500});
        assert(p.discover().size() == 3);
        p.setManualTimeMs(2000);  // custom TTL (1000+500) expired
        assert(p.discover().size() == 2);
        p.withdraw("synthetic-a");
        assert(p.discover().size() == 1);
        p.advertise(EndpointDescriptor{"synthetic-a", "Synthetic A", "127.0.0.1",
                                       19132, {"player-observation"}, 0, 0});
        assert(p.discover().size() == 2);  // reappearance
        std::string sid;
        assert(p.openSession("synthetic-b", &sid));
        assert(!sid.empty());
        assert(!p.openSession("gone", nullptr));  // unknown endpoint refused
        assert(p.closeSession(sid));
        p.stop();
    }
    // --- read-only module consumption via sink ---
    {
        Runtime rt;
        RecordingSink sink;
        rt.setSink(&sink);
        static_cast<SyntheticRelayProvider*>(rt.provider())->setManualTimeMs(5000);
        assert(rt.start());
        assert(sink.connections.size() == 1 && sink.connections[0].connected);
        std::string sid;
        auto* p = static_cast<SyntheticRelayProvider*>(rt.provider());
        assert(p->openSession("synthetic-a", &sid));
        assert(sink.players.size() == 1);
        assert(sink.players[0].playerId == "synthetic-player");
        assert(sink.players[0].worldId == "synthetic-world");
        assert(!sink.sessions.empty());
        rt.stop();  // sink sees disconnect...
        assert(!sink.connections.empty() && !sink.connections.back().connected);
        const std::size_t n = sink.players.size();
        rt.stop();
        assert(sink.players.size() == n);  // ...and nothing after shutdown
    }
    // --- config key contract (persistence owned by caller/XykellConfig) ---
    {
        assert(std::string(Runtime::kConfigSection) == "runtime");
        assert(std::string(Runtime::kConfigKeyProvider) == "provider");
        assert(std::string(Runtime::kDefaultProvider) == "synthetic-relay");
    }

    std::cout << "test_runtime_provider: PASS\n";
    return 0;
}
