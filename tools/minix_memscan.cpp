#include <sys/uio.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr std::size_t kChunkBytes = 256U * 1024U;
constexpr std::size_t kOverlapBytes = 32U;
constexpr std::size_t kMaxHits = 512U;

struct Region {
    std::uint64_t start = 0;
    std::uint64_t end = 0;
    std::string perms;
    std::string path;
};

bool ParseRegion(const std::string& line, Region* output) {
    if (output == nullptr) return false;
    unsigned long long start = 0;
    unsigned long long end = 0;
    char perms[5]{};
    int consumed = 0;
    if (std::sscanf(line.c_str(), "%llx-%llx %4s %*s %*s %*s %n", &start, &end, perms,
                    &consumed) < 3 || end <= start) {
        return false;
    }
    output->start = static_cast<std::uint64_t>(start);
    output->end = static_cast<std::uint64_t>(end);
    output->perms = perms;
    output->path = consumed > 0 && static_cast<std::size_t>(consumed) < line.size()
        ? line.substr(static_cast<std::size_t>(consumed)) : std::string();
    while (!output->path.empty() && output->path.front() == ' ') output->path.erase(0, 1);
    return true;
}

bool IsCandidateRegion(const Region& region) {
    if (region.perms.size() < 2 || region.perms[0] != 'r' || region.perms[1] != 'w') {
        return false;
    }
    if (region.path.empty() || region.path == "[heap]") return true;
    if (region.path.find("liblibGameApp.so") != std::string::npos) return true;
    if (region.path.rfind("[anon:", 0) == 0) {
        return region.path.find("dalvik") == std::string::npos &&
            region.path.find("jit") == std::string::npos &&
            region.path.find("thread signal stack") == std::string::npos &&
            region.path.find("stack_and_tls") == std::string::npos;
    }
    return false;
}

ssize_t ReadRemote(pid_t pid, std::uint64_t address, void* buffer, std::size_t count) {
    iovec local{buffer, count};
    iovec remote{reinterpret_cast<void*>(static_cast<std::uintptr_t>(address)), count};
    return process_vm_readv(pid, &local, 1, &remote, 1, 0);
}

float LoadFloat(const unsigned char* data) {
    float value = 0.0F;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

std::int32_t LoadInt32(const unsigned char* data) {
    std::int32_t value = 0;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

bool Near(float value, double expected, double tolerance) {
    return std::isfinite(value) && std::fabs(static_cast<double>(value) - expected) <= tolerance;
}

const char* MatchFloatOrder(const unsigned char* data, double x, double y, double z,
                            double tolerance) {
    const float a = LoadFloat(data);
    const float b = LoadFloat(data + 4);
    const float c = LoadFloat(data + 8);
    if (Near(a, x, tolerance) && Near(b, y, tolerance) && Near(c, z, tolerance)) return "XYZ";
    if (Near(a, x, tolerance) && Near(b, z, tolerance) && Near(c, y, tolerance)) return "XZY";
    if (Near(a, y, tolerance) && Near(b, x, tolerance) && Near(c, z, tolerance)) return "YXZ";
    if (Near(a, z, tolerance) && Near(b, y, tolerance) && Near(c, x, tolerance)) return "ZYX";
    return nullptr;
}

bool MatchIntOrder(const unsigned char* data, std::int32_t x, std::int32_t y, std::int32_t z,
                   const char** order) {
    const std::int32_t a = LoadInt32(data);
    const std::int32_t b = LoadInt32(data + 4);
    const std::int32_t c = LoadInt32(data + 8);
    if (a == x && b == y && c == z) { *order = "XYZ"; return true; }
    if (a == x && b == z && c == y) { *order = "XZY"; return true; }
    return false;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5 || argc > 6) {
        std::fprintf(stderr, "usage: %s PID X Y Z [FLOAT_TOLERANCE]\n", argv[0]);
        return 2;
    }
    const long parsed_pid = std::strtol(argv[1], nullptr, 10);
    const double x = std::strtod(argv[2], nullptr);
    const double y = std::strtod(argv[3], nullptr);
    const double z = std::strtod(argv[4], nullptr);
    const double tolerance = argc == 6 ? std::strtod(argv[5], nullptr) : 1.25;
    if (parsed_pid <= 0 || parsed_pid > std::numeric_limits<pid_t>::max() ||
        !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) ||
        !std::isfinite(tolerance) || tolerance < 0.0 || tolerance > 100.0) {
        std::fprintf(stderr, "invalid arguments\n");
        return 2;
    }
    const pid_t pid = static_cast<pid_t>(parsed_pid);
    std::ifstream maps("/proc/" + std::to_string(pid) + "/maps");
    if (!maps) {
        std::fprintf(stderr, "open maps failed: %s\n", std::strerror(errno));
        return 3;
    }

    std::vector<Region> regions;
    std::string line;
    while (std::getline(maps, line)) {
        Region region;
        if (ParseRegion(line, &region) && IsCandidateRegion(region)) regions.push_back(region);
    }

    std::vector<unsigned char> buffer(kChunkBytes + kOverlapBytes);
    std::size_t hits = 0;
    std::size_t readable_regions = 0;
    std::uint64_t read_bytes = 0;
    const std::int32_t ix = static_cast<std::int32_t>(std::llround(x));
    const std::int32_t iy = static_cast<std::int32_t>(std::llround(y));
    const std::int32_t iz = static_cast<std::int32_t>(std::llround(z));

    for (const Region& region : regions) {
        bool region_read = false;
        std::size_t carry = 0;
        for (std::uint64_t cursor = region.start; cursor < region.end;) {
            const std::uint64_t remaining = region.end - cursor;
            const std::size_t request = static_cast<std::size_t>(
                std::min<std::uint64_t>(kChunkBytes, remaining));
            const ssize_t count = ReadRemote(pid, cursor, buffer.data() + carry, request);
            if (count <= 0) {
                carry = 0;
                cursor += request;
                continue;
            }
            region_read = true;
            read_bytes += static_cast<std::uint64_t>(count);
            const std::size_t available = carry + static_cast<std::size_t>(count);
            const std::uint64_t base = cursor - carry;
            for (std::size_t offset = 0; offset + 12U <= available; offset += 4U) {
                const char* float_order = MatchFloatOrder(buffer.data() + offset, x, y, z, tolerance);
                const char* int_order = nullptr;
                const bool int_match = MatchIntOrder(buffer.data() + offset, ix, iy, iz, &int_order);
                if (float_order == nullptr && !int_match) continue;
                const float a = LoadFloat(buffer.data() + offset);
                const float b = LoadFloat(buffer.data() + offset + 4U);
                const float c = LoadFloat(buffer.data() + offset + 8U);
                std::printf(
                    "HIT address=0x%llx kind=%s order=%s f=[%.6f,%.6f,%.6f] i=[%d,%d,%d] map=%s\n",
                    static_cast<unsigned long long>(base + offset),
                    float_order != nullptr ? "float32" : "int32",
                    float_order != nullptr ? float_order : int_order,
                    a, b, c,
                    LoadInt32(buffer.data() + offset),
                    LoadInt32(buffer.data() + offset + 4U),
                    LoadInt32(buffer.data() + offset + 8U),
                    region.path.empty() ? "<anonymous>" : region.path.c_str());
                if (++hits >= kMaxHits) {
                    std::fprintf(stderr, "hit limit reached (%zu)\n", hits);
                    std::printf("SUMMARY candidateRegions=%zu readableRegions=%zu readBytes=%llu hits=%zu\n",
                                regions.size(), readable_regions + (region_read ? 1U : 0U),
                                static_cast<unsigned long long>(read_bytes), hits);
                    return 0;
                }
            }
            carry = std::min<std::size_t>(kOverlapBytes, available);
            std::memmove(buffer.data(), buffer.data() + available - carry, carry);
            cursor += static_cast<std::uint64_t>(count);
            if (static_cast<std::size_t>(count) < request) cursor += request - count;
        }
        if (region_read) ++readable_regions;
    }

    std::printf("SUMMARY candidateRegions=%zu readableRegions=%zu readBytes=%llu hits=%zu\n",
                regions.size(), readable_regions,
                static_cast<unsigned long long>(read_bytes), hits);
    return 0;
}
