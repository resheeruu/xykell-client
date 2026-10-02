#include "xykell/json_min.h"

#include <cctype>
#include <cstdio>

namespace xykell::json {

namespace {

const Object kEmptyObject;
const Array kEmptyArray;
const std::string kEmptyString;

} // namespace

const Object& Value::emptyObject() { return kEmptyObject; }
const Array& Value::emptyArray() { return kEmptyArray; }
const std::string& Value::emptyString() { return kEmptyString; }

bool Value::asBool(bool fallback) const {
    return isBool() ? std::get<bool>(data) : fallback;
}

double Value::asNumber(double fallback) const {
    return isNumber() ? std::get<double>(data) : fallback;
}

const std::string& Value::asString(const std::string& fallback) const {
    return isString() ? std::get<std::string>(data) : fallback;
}

const Array& Value::asArray(const Array& fallback) const {
    return isArray() ? std::get<Array>(data) : fallback;
}

const Object& Value::asObject(const Object& fallback) const {
    if (!isObject()) {
        return fallback;
    }
    return *std::get<std::shared_ptr<Object>>(data);
}

namespace {

class Parser {
  public:
    explicit Parser(const std::string& t) : text_(t) {}

    ParseResult run() {
        skipWs();
        Value v;
        if (!parseValue(v)) {
            return {Value{}, false, err_};
        }
        skipWs();
        if (pos_ != text_.size()) {
            return {Value{}, false, "trailing characters after value"};
        }
        return {std::move(v), true, {}};
    }

  private:
    const std::string& text_;
    std::size_t pos_ = 0;
    std::string err_;

    void skipWs() {
        while (pos_ < text_.size()
               && (text_[pos_] == ' ' || text_[pos_] == '\t' || text_[pos_] == '\n'
                   || text_[pos_] == '\r')) {
            ++pos_;
        }
    }

    bool fail(const std::string& e) {
        err_ = e + " at offset " + std::to_string(pos_);
        return false;
    }

    bool parseValue(Value& out) {
        if (pos_ >= text_.size()) {
            return fail("unexpected end of input");
        }
        const char c = text_[pos_];
        if (c == '{') {
            return parseObject(out);
        }
        if (c == '[') {
            return parseArray(out);
        }
        if (c == '"') {
            std::string s;
            if (!parseString(s)) {
                return false;
            }
            out = Value(std::move(s));
            return true;
        }
        if (c == 't' || c == 'f' || c == 'n') {
            return parseLiteral(out);
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber(out);
        }
        return fail("unexpected character");
    }

    bool parseObject(Value& out) {
        ++pos_; // {
        Object obj;
        skipWs();
        if (pos_ < text_.size() && text_[pos_] == '}') {
            ++pos_;
            out = Value(std::move(obj));
            return true;
        }
        while (true) {
            skipWs();
            if (pos_ >= text_.size() || text_[pos_] != '"') {
                return fail("expected string key");
            }
            std::string key;
            if (!parseString(key)) {
                return false;
            }
            skipWs();
            if (pos_ >= text_.size() || text_[pos_] != ':') {
                return fail("expected ':'");
            }
            ++pos_;
            skipWs();
            Value v;
            if (!parseValue(v)) {
                return false;
            }
            obj.emplace(std::move(key), std::move(v));
            skipWs();
            if (pos_ >= text_.size()) {
                return fail("unterminated object");
            }
            if (text_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (text_[pos_] == '}') {
                ++pos_;
                out = Value(std::move(obj));
                return true;
            }
            return fail("expected ',' or '}'");
        }
    }

    bool parseArray(Value& out) {
        ++pos_; // [
        Array arr;
        skipWs();
        if (pos_ < text_.size() && text_[pos_] == ']') {
            ++pos_;
            out = Value(std::move(arr));
            return true;
        }
        while (true) {
            skipWs();
            Value v;
            if (!parseValue(v)) {
                return false;
            }
            arr.push_back(std::move(v));
            skipWs();
            if (pos_ >= text_.size()) {
                return fail("unterminated array");
            }
            if (text_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (text_[pos_] == ']') {
                ++pos_;
                out = Value(std::move(arr));
                return true;
            }
            return fail("expected ',' or ']'");
        }
    }

    void encodeUtf8(std::string& out, unsigned code) {
        if (code < 0x80) {
            out.push_back(static_cast<char>(code));
        } else if (code < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (code >> 6)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xE0 | (code >> 12)));
            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        }
    }

    bool parseHex4(unsigned& out) {
        if (pos_ + 4 > text_.size()) {
            return false;
        }
        unsigned v = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = text_[pos_++];
            v <<= 4;
            if (c >= '0' && c <= '9') {
                v |= static_cast<unsigned>(c - '0');
            } else if (c >= 'a' && c <= 'f') {
                v |= static_cast<unsigned>(c - 'a' + 10);
            } else if (c >= 'A' && c <= 'F') {
                v |= static_cast<unsigned>(c - 'A' + 10);
            } else {
                return false;
            }
        }
        out = v;
        return true;
    }

    bool parseString(std::string& out) {
        ++pos_; // opening "
        while (pos_ < text_.size()) {
            const char c = text_[pos_++];
            if (c == '"') {
                return true;
            }
            if (c == '\\') {
                if (pos_ >= text_.size()) {
                    return fail("unterminated escape");
                }
                const char e = text_[pos_++];
                switch (e) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        unsigned code = 0;
                        if (!parseHex4(code)) {
                            return fail("bad \\u escape");
                        }
                        encodeUtf8(out, code);
                        break;
                    }
                    default: return fail("bad escape");
                }
            } else if (static_cast<unsigned char>(c) < 0x20) {
                return fail("unescaped control character");
            } else {
                out.push_back(c);
            }
        }
        return fail("unterminated string");
    }

    bool parseLiteral(Value& out) {
        if (text_.compare(pos_, 4, "true") == 0) {
            pos_ += 4;
            out = Value(true);
            return true;
        }
        if (text_.compare(pos_, 5, "false") == 0) {
            pos_ += 5;
            out = Value(false);
            return true;
        }
        if (text_.compare(pos_, 4, "null") == 0) {
            pos_ += 4;
            out = Value(nullptr);
            return true;
        }
        return fail("bad literal");
    }

    bool parseNumber(Value& out) {
        const std::size_t start = pos_;
        if (text_[pos_] == '-') {
            ++pos_;
        }
        if (pos_ >= text_.size()) {
            return fail("bad number");
        }
        if (text_[pos_] == '0') {
            ++pos_;
        } else if (text_[pos_] >= '1' && text_[pos_] <= '9') {
            while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
                ++pos_;
            }
        } else {
            return fail("bad number");
        }
        if (pos_ < text_.size() && text_[pos_] == '.') {
            ++pos_;
            if (pos_ >= text_.size()
                || !std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
                return fail("bad fraction");
            }
            while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
                ++pos_;
            }
        }
        if (pos_ < text_.size() && (text_[pos_] == 'e' || text_[pos_] == 'E')) {
            ++pos_;
            if (pos_ < text_.size() && (text_[pos_] == '+' || text_[pos_] == '-')) {
                ++pos_;
            }
            if (pos_ >= text_.size()
                || !std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
                return fail("bad exponent");
            }
            while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
                ++pos_;
            }
        }
        try {
            out = Value(std::stod(text_.substr(start, pos_ - start)));
        } catch (...) {
            return fail("number out of range");
        }
        return true;
    }
};

void writeString(const std::string& s, std::string& out) {
    out.push_back('"');
    for (const char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(c);
                }
        }
    }
    out.push_back('"');
}

void writeValue(const Value& v, std::string& out) {
    if (v.isNull()) {
        out += "null";
    } else if (v.isBool()) {
        out += v.asBool() ? "true" : "false";
    } else if (v.isNumber()) {
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%.17g", v.asNumber());
        out += buf;
    } else if (v.isString()) {
        writeString(v.asString(Value::emptyString()), out);
    } else if (v.isArray()) {
        out.push_back('[');
        bool first = true;
        for (const auto& e : v.asArray(Value::emptyArray())) {
            if (!first) {
                out.push_back(',');
            }
            first = false;
            writeValue(e, out);
        }
        out.push_back(']');
    } else {
        out.push_back('{');
        bool first = true;
        for (const auto& [k, e] : v.asObject(Value::emptyObject())) {
            if (!first) {
                out.push_back(',');
            }
            first = false;
            writeString(k, out);
            out.push_back(':');
            writeValue(e, out);
        }
        out.push_back('}');
    }
}

} // namespace

ParseResult parse(const std::string& text) { return Parser(text).run(); }

std::string stringify(const Value& v) {
    std::string out;
    writeValue(v, out);
    return out;
}

} // namespace xykell::json
