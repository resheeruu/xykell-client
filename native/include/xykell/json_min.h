#pragma once

// Minimal strict JSON subset: null/bool/number/string/array/object.
// Enough for Xykell config/profile/layout files; no third-party dependency.
// Pure C++17, no platform headers. Rejects trailing garbage and malformed
// input with a reason string instead of throwing past the caller.
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <variant>
#include <vector>

namespace xykell::json {

struct Value;
using Object = std::map<std::string, Value>;
using Array = std::vector<Value>;

struct Value {
    using Storage = std::variant<std::nullptr_t, bool, double, std::string, Array,
                                  std::shared_ptr<Object>>;
    Storage data;

    Value() : data(nullptr) {}
    Value(std::nullptr_t) : data(nullptr) {}
    Value(bool b) : data(b) {}
    Value(double d) : data(d) {}
    Value(int i) : data(static_cast<double>(i)) {}
    Value(const char* s) : data(std::string(s)) {}
    Value(std::string s) : data(std::move(s)) {}
    Value(Array a) : data(std::move(a)) {}
    Value(Object o) : data(std::make_shared<Object>(std::move(o))) {}

    bool isNull() const { return std::holds_alternative<std::nullptr_t>(data); }
    bool isBool() const { return std::holds_alternative<bool>(data); }
    bool isNumber() const { return std::holds_alternative<double>(data); }
    bool isString() const { return std::holds_alternative<std::string>(data); }
    bool isArray() const { return std::holds_alternative<Array>(data); }
    bool isObject() const { return std::holds_alternative<std::shared_ptr<Object>>(data); }

    bool asBool(bool fallback = false) const;
    double asNumber(double fallback = 0.0) const;
    const std::string& asString(const std::string& fallback) const;
    const Array& asArray(const Array& fallback) const;
    const Object& asObject(const Object& fallback) const;
    static const Object& emptyObject();
    static const Array& emptyArray();
    static const std::string& emptyString();
};

struct ParseResult {
    Value value;
    bool ok = false;
    std::string error;
};

ParseResult parse(const std::string& text);
std::string stringify(const Value& v);

} // namespace xykell::json
