#pragma once

// File helpers: atomic write (tmp + rename), full read, directory creation.
// Pure C++17 <filesystem>/<fstream>. No platform assumptions beyond what
// those provide. Storage ROOTS always come from the caller (runtime: verified
// preloader ModContext::configDir()/dataDir(); tests: temp dirs).
#include <string>

namespace xykell::fs {

struct ReadResult {
    std::string content;
    bool ok = false;
    std::string error;
};

ReadResult readFile(const std::string& path);
bool ensureDir(const std::string& path, std::string& error);

// Atomically replaces path: writes path + ".tmp.<pid>" then renames.
// Returns false (leaving the old file intact) on any failure.
bool atomicWrite(const std::string& path, const std::string& content,
                 std::string& error);

bool removeFile(const std::string& path);

} // namespace xykell::fs
