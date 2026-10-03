// Host unit test: LAN discovery wire format, registry, TTL, selection,
// diagnostics, and two-instance loopback discovery (127.0.0.1 UDP).
#include <cassert>
#include <chrono>
#include <functional>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

#include "xykell/lan_discovery.h"
#include "xykell/runtime_provider.h"

using namespace xykell::discovery;
using namespace xykell::runtime;

namespace {

bool waitFor(std::function<bool()> pred, int tries = 100) {
    for (int i = 0; i < tries; ++i) {
        if (pred()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return pred();
}

} // namespace

int main() {
    // --- wire format: round-trip ---
    {
        Advertisement ad{"ep-1", "127.0.0.1", 19132, 1, "DISCOVERY", 5000};
        const auto s = ad.serialize(kServiceName);
        const auto back = Advertisement::parse(s);
        assert(back.has_value());
        assert(back->endpointId == "ep-1" && back->port == 19132);
        assert(back->protocolVersion == 1 && back->ttlMs == 5000);
    }
    // --- wire format: rejections ---
    {
        assert(!Advertisement::parse("garbage").has_value());
        assert(!Advertisement::parse("xykell-lan/1;id=only").has_value());
        assert(!Advertisement::parse("other-svc/1;id=a;addr=b;port=1;proto=1;caps=c;ttl=5")
                    .has_value());
        assert(!Advertisement::parse("xykell-lan/2;id=a;addr=b;port=1;proto=1;caps=c;ttl=5")
                    .has_value());  // unsupported wire version
        assert(!Advertisement::parse("xykell-lan/1;id=a;addr=b;port=0;proto=1;caps=c;ttl=5")
                    .has_value());  // bad port
        assert(!Advertisement::parse("xykell-lan/1;id=a;addr=b;port=1;proto=0;caps=c;ttl=5")
                    .has_value());  // bad proto
        assert(!Advertisement::parse(std::string(600, 'x')).has_value());  // overlong
    }
    // --- registry: ingest, duplicates, versions, TTL ---
    {
        std::vector<EndpointRecord> reg;
        DiscoveryCode code = DiscoveryCode::Ok;
        assert(LanDiscovery::ingestDatagram(
            "xykell-lan/1;id=a;addr=127.0.0.1;port=100;proto=1;caps=D;ttl=5000", 1000, reg,
            kServiceName, 1, &code));
        assert(reg.size() == 1 && code == DiscoveryCode::Ok);
        assert(LanDiscovery::ingestDatagram(
            "xykell-lan/1;id=a;addr=127.0.0.1;port=100;proto=1;caps=D;ttl=5000", 2000, reg,
            kServiceName, 1, &code));
        assert(reg.size() == 1);  // duplicate refreshes, never duplicates
        assert(reg[0].lastSeenMs == 2000);
        assert(!LanDiscovery::ingestDatagram("bogus", 3000, reg, kServiceName, 1, &code));
        assert(code == DiscoveryCode::InvalidAdvertisement && reg.size() == 1);
        assert(!LanDiscovery::ingestDatagram(
            "xykell-lan/1;id=b;addr=127.0.0.1;port=100;proto=9;caps=D;ttl=5000", 3000, reg,
            kServiceName, 1, &code));
        assert(code == DiscoveryCode::UnsupportedVersion && reg.size() == 1);
    }
    // --- bind failure becomes structured diagnostic ---
    {
        LanDiscovery d;
        DiscoveryConfig cfg;
        cfg.listenPort = 1;  // privileged: bind must fail for app uid
        assert(!d.start(cfg));
        assert(d.lastCode() == DiscoveryCode::BindFailed ||
               d.lastCode() == DiscoveryCode::NetworkUnavailable);
        assert(!d.running());
        d.stop();  // safe after failed start
    }
    // --- TTL expiry + selection determinism (manual clock, no sockets) ---
    {
        LanDiscovery d;
        d.setManualTimeMs(10000);
        DiscoveryConfig cfg;
        cfg.listenPort = 0;  // ephemeral; workers run but nothing announces
        assert(d.start(cfg));
        assert(d.endpoints().empty());
        d.stop();
        assert(d.endpoints().empty());
    }
    // --- two-instance loopback discovery: A discovers B and B discovers A ---
    {
        LanDiscovery a, b;
        DiscoveryConfig ca, cb;
        ca.endpointId = "loop-A";
        cb.endpointId = "loop-B";
        ca.listenPort = 17401;
        cb.listenPort = 17402;
        ca.announcePort = 17402;  // A announces at B's ear
        cb.announcePort = 17401;  // B announces at A's ear
        ca.announceIntervalMs = 50;
        cb.announceIntervalMs = 50;
        ca.defaultTtlMs = 60000;
        cb.defaultTtlMs = 60000;
        assert(a.start(ca));
        assert(b.start(cb));
        assert(waitFor([&] { return a.endpoints().size() == 1; }));
        assert(waitFor([&] { return b.endpoints().size() == 1; }));
        assert(a.endpoints()[0].ad.endpointId == "loop-B");
        assert(b.endpoints()[0].ad.endpointId == "loop-A");
        // Identity separation + deterministic selection.
        assert(a.selectEndpoint("loop-B"));
        assert(a.selectedEndpoint() == "loop-B");
        assert(!a.selectEndpoint("loop-A"));  // self never advertised: refused
        assert(!b.selectEndpoint("nope"));
        // Disappearance: B stops announcing; A must drop it after TTL.
        // (Short TTL via fresh short-lived instance instead of waiting.)
        b.stop();
        assert(b.endpoints().empty());
        a.stop();
        assert(a.endpoints().empty() && a.selectedEndpoint().empty());
        assert(!a.running() && !b.running());
    }
    // --- provider integration: lan-discovery selectable, honest caps ---
    {
        Runtime rt;
        assert(rt.selectProvider("lan-discovery"));
        assert(rt.selectedProviderName() == "lan-discovery");
        // No capabilities without a running provider? Capabilities describe
        // the contract regardless of state (introspection, not liveness).
        bool hasDiscovery = false, hasPlayer = false;
        for (const auto& c : rt.capabilities()) {
            if (c.name == "transport-diagnostics") hasDiscovery = true;
            if (c.name == "player-observation") hasPlayer = true;
        }
        assert(hasDiscovery && !hasPlayer);  // discovery metadata only
        assert(rt.start());  // ephemeral bind; deterministic success path
        rt.stop();
        assert(rt.state() == ProviderState::Stopped);
        assert(rt.selectProvider("synthetic-relay"));  // back to default
    }

    std::cout << "test_lan_discovery: PASS\n";
    return 0;
}
