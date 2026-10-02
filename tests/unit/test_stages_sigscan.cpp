// Host unit test: load checkpoints + signature pipeline (no real signatures).
#include <cassert>
#include <iostream>

#include "xykell/load_stages.h"
#include "xykell/sigscan.h"

int main() {
    using namespace xykell;
    // Checkpoints: ordered marks, exact failure report.
    LoadTracker t;
    assert(!t.failed() && t.lastStage() == LoadStage::ProcessStarted);
    t.mark(LoadStage::CoreInitialized, "core");
    t.mark(LoadStage::Ready, "done");
    assert(!t.failed() && t.lastStage() == LoadStage::Ready);
    assert(t.report().find("READY") != std::string::npos);
    LoadTracker f;
    f.mark(LoadStage::CoreInitialized, "core");
    f.fail(LoadStage::HudInitialized, "xykell-hud", "menu entry",
           "registerModule false", "preloader busy");
    assert(f.failed() && f.lastStage() == LoadStage::HudInitialized);
    const std::string r = f.report();
    assert(r.find("stage: HUD_INITIALIZED") != std::string::npos);
    assert(r.find("component: xykell-hud") != std::string::npos);
    assert(r.find("expected: menu entry") != std::string::npos);
    assert(r.find("observed: registerModule false") != std::string::npos);
    assert(r.find("error: preloader busy") != std::string::npos);

    // Signature patterns: validation rules.
    using namespace xykell::sigs;
    std::string err;
    SignaturePattern good{{0x55, 0x48, 0x00}, {0xFF, 0xFF, 0x00}};
    assert(good.valid(err));
    SignaturePattern allWild{{0x00}, {0x00}};
    assert(!allWild.valid(err) && !err.empty());
    SignaturePattern mismatch{{0x55}, {}};
    assert(!mismatch.valid(err));

    // Scan + validate over synthetic buffers (fixtures, not game data).
    const std::vector<Byte> buf = {0x90, 0x55, 0x48, 0xAB, 0x55, 0x48, 0xCD};
    auto res = scan(buf, good);
    assert(res.offsets.size() == 2); // ambiguous on purpose
    auto v = validate(res, buf.size(), buf.size());
    assert(v.verdict == Verdict::Ambiguous);
    const std::vector<Byte> buf1 = {0x90, 0x55, 0x48, 0xAB};
    auto v1 = validate(scan(buf1, good), buf1.size(), buf1.size());
    assert(v1.verdict == Verdict::Supported && v1.offset == 1);
    const std::vector<Byte> buf0 = {0x90, 0x90};
    assert(validate(scan(buf0, good), buf0.size(), buf0.size()).verdict
           == Verdict::NotFound);
    // Out-of-range candidate is not accepted.
    auto vr = validate(scan(buf1, good), buf1.size(), 0);
    assert(vr.verdict == Verdict::NotFound);

    // Database requires provenance + evidence; rejects dups.
    SignatureDatabase db;
    assert(db.versions().empty());
    VersionProfile vp;
    vp.version = "9.9.9-test";
    vp.entries.push_back({"frame.tick.v1", "9.9.9-test", good, "synthetic-test",
                          "unit-test-only"});
    assert(db.add(std::move(vp), err));
    assert(db.versions().size() == 1 && db.profile("9.9.9-test") != nullptr);
    assert(db.profile("0.0.0") == nullptr);
    VersionProfile dup;
    dup.version = "9.9.9-test";
    assert(!db.add(std::move(dup), err) && !err.empty());
    VersionProfile noprov;
    noprov.version = "1.2.3";
    noprov.entries.push_back({"x", "1.2.3", good, "", ""});
    assert(!db.add(std::move(noprov), err)); // no provenance: rejected

    std::cout << "test_stages_sigscan: PASS\n";
    return 0;
}
