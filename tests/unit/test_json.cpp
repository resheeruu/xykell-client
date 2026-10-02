// Host unit test: json_min round-trip + strict rejections.
#include <cassert>
#include <iostream>

#include "xykell/json_min.h"

int main() {
    using namespace xykell::json;
    auto ok = parse(R"({"a":1,"b":[true,false,null],"c":"x\ny","d":{"e":-2.5e3}})");
    assert(ok.ok);
    assert(ok.value.asObject(Value::emptyObject()).at("a").asNumber() == 1.0);
    assert(ok.value.asObject(Value::emptyObject()).at("b").asArray(Value::emptyArray()).size() == 3);
    // Round-trip stability.
    auto rt = parse(stringify(ok.value));
    assert(rt.ok && stringify(rt.value) == stringify(ok.value));
    // Strict rejections.
    for (const char* bad : {"{", "[1,]", "{\"a\":}", "tru", "[01]", "\"\\x\"",
                             "{\"a\":1} trailing", "", "0x1", "{'a':1}"}) {
        auto r = parse(bad);
        assert(!r.ok && !r.error.empty());
    }
    // Writer escapes.
    Value v(std::string("q\"\n\\"));
    assert(stringify(v) == "\"q\\\"\\n\\\\\"");
    std::cout << "test_json: PASS\n";
    return 0;
}
