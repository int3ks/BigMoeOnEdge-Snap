#include "chat_parse.h"

#include <cstdio>
#include <mutex>
#include <string>

namespace bmoe::detail {

common_chat_parser_params build_parse_params(const common_chat_params & cp) {
    common_chat_parser_params pp(cp);
    // The converting constructor drops the grammar; copy it so the parser can actually match.
    // Whether reasoning is split out was already decided by the template inputs (reasoning_format).
    pp.parser = cp.parser;
    return pp;
}

void warn_parse_failed_once(const char * what) {
    static std::once_flag flag;
    std::call_once(flag, [what] {
        std::fprintf(stderr, "bmoe: reasoning parse failed (%s); showing raw model output\n", what ? what : "unknown");
    });
}

namespace {
constexpr const char * kOpen = "<think>";
constexpr const char * kClose = "</think>";
bool is_ws(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }
} // namespace

std::string strip_empty_think(const std::string & s) {
    std::string out;
    out.reserve(s.size());
    const size_t lo = std::char_traits<char>::length(kOpen), lc = std::char_traits<char>::length(kClose);
    size_t i = 0;
    while (i < s.size()) {
        if (s.compare(i, lo, kOpen) == 0) {
            size_t j = i + lo;
            while (j < s.size() && is_ws(s[j])) ++j;
            if (s.compare(j, lc, kClose) == 0) {
                i = j + lc;
                while (i < s.size() && is_ws(s[i])) ++i;
                continue;
            }
        }
        out.push_back(s[i++]);
    }
    return out;
}

bool only_empty_think(const std::string & raw, int & spans) {
    spans = 0;
    const size_t lo = std::char_traits<char>::length(kOpen), lc = std::char_traits<char>::length(kClose);
    size_t i = 0;
    for (;;) {
        while (i < raw.size() && is_ws(raw[i])) ++i;
        if (i >= raw.size()) return true;
        if (raw.compare(i, lo, kOpen) == 0) {
            i += lo;
            while (i < raw.size() && is_ws(raw[i])) ++i;
            if (i >= raw.size()) { ++spans; return true; }
            if (raw.compare(i, lc, kClose) != 0) return false;
            i += lc;
            ++spans;
            continue;
        }
        // A tag still arriving token by token: a prefix of either tag at the very end.
        const std::string rest = raw.substr(i);
        if (rest.size() < lc && (std::string(kOpen).compare(0, rest.size(), rest) == 0 ||
                                 std::string(kClose).compare(0, rest.size(), rest) == 0))
            return true;
        return false;
    }
}

} // namespace bmoe::detail
