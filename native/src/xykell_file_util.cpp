#include "xykell/file_util.h"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <sstream>

namespace xykell::fs {
namespace stdfs = std::filesystem;

ReadResult readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return {{}, false, "cannot open for read"};
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    if (in.bad()) {
        return {{}, false, "read error"};
    }
    return {ss.str(), true, {}};
}

bool ensureDir(const std::string& path, std::string& error) {
    std::error_code ec;
    stdfs::create_directories(path, ec);
    if (ec) {
        error = ec.message();
        return false;
    }
    return true;
}

bool atomicWrite(const std::string& path, const std::string& content,
                 std::string& error) {
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            error = "cannot open tmp for write";
            return false;
        }
        out << content;
        out.flush();
        if (!out) {
            error = "tmp write failed";
            std::remove(tmp.c_str());
            return false;
        }
    }
    std::error_code ec;
    stdfs::rename(tmp, path, ec);
    if (ec) {
        error = ec.message();
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

bool removeFile(const std::string& path) {
    std::error_code ec;
    return stdfs::remove(path, ec);
}

} // namespace xykell::fs
