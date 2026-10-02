#pragma once

// Signature/discovery pipeline infrastructure (§6). Pure pattern matching
// over byte buffers with validation — NO real signatures live here (the
// database ships empty; entries arrive only from verified derivation with
// provenance). Multi-match resolves to AMBIGUOUS, never SUPPORTED.
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace xykell::sigs {

using Byte = std::uint8_t;

// Pattern bytes with per-byte mask: mask bit 1 = must match, 0 = wildcard.
struct SignaturePattern {
    std::vector<Byte> bytes;
    std::vector<Byte> mask;

    bool valid(std::string& error) const;
};

struct ScanResult {
    std::vector<std::size_t> offsets; // every match offset in the buffer
};

// Naive first implementation: correctness first, Boyer-Moore later IF a
// profile shows scanning is hot (measure first — phone-first rule).
ScanResult scan(const std::vector<Byte>& buffer, const SignaturePattern& pat);

enum class Verdict { Supported, Ambiguous, NotFound, Invalid };

struct Validation {
    Verdict verdict = Verdict::NotFound;
    std::size_t offset = 0; // valid only when Supported
    std::string reason;
};

// Exactly one candidate + constraints satisfied -> Supported.
// Zero -> NotFound. More than one -> AMBIGUOUS (never Supported).
Validation validate(const ScanResult& r, std::size_t bufferSize,
                    std::size_t expectedMaxOffset);

struct SignatureEntry {
    std::string id;        // e.g. "frame.tick.v1"
    std::string version;   // Bedrock build this was derived for
    SignaturePattern pattern;
    std::string source;    // provenance: derivation method + analyst + date
    std::string evidence;  // what validated it (device run id, log ref)
};

struct VersionProfile {
    std::string version;
    std::vector<SignatureEntry> entries;
};

class SignatureDatabase {
  public:
    bool add(VersionProfile profile, std::string& error); // false on dup id
    const VersionProfile* profile(const std::string& version) const;
    std::vector<std::string> versions() const;

  private:
    std::map<std::string, VersionProfile> profiles_;
};

} // namespace xykell::sigs
