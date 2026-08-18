#include <jni.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr std::size_t kMaxMapsBytes = 4U * 1024U * 1024U;
constexpr std::size_t kMaxMapsLines = 50'000U;
constexpr std::size_t kMaxMapsLineBytes = 4'096U;
constexpr std::size_t kMaxOutputModules = 96U;
constexpr std::size_t kMaxFileHeaderChecks = 512U;
constexpr std::size_t kMaxStatBytes = 4'096U;
constexpr std::size_t kMemoryProbeBytes = 64U;
constexpr std::size_t kMaxScalarReadBytes = 8U;
constexpr std::size_t kMaxScalarPatchBytes = 8U;
constexpr std::size_t kMaxMemoryBatchItems = 64U;
constexpr std::size_t kMaxMemoryRegionBytes = 64U * 1024U;
constexpr std::size_t kMaxMemoryBatchBytes = 256U * 1024U;
constexpr std::size_t kU32Bytes = 4U;
constexpr int kMappingFlagWritable = 1;
constexpr int kMappingFlagExecutable = 2;
constexpr int kKnownMappingFlags = kMappingFlagWritable | kMappingFlagExecutable;
constexpr std::size_t kPagemapEntryBytes = 8U;
constexpr std::uint64_t kPagemapPresentBit = 1ULL << 63U;
constexpr std::uint64_t kMaxJavaLong =
    static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
constexpr std::uint64_t kFnvOffsetBasis = 14695981039346656037ULL;
constexpr std::uint64_t kFnvPrime = 1099511628211ULL;

struct ProcessIdentity {
    std::string start_time_ticks;
};

struct MapRegion {
    std::uint64_t start = 0;
    std::uint64_t end = 0;
    std::uint64_t file_offset = 0;
    std::string permissions;
    std::string device;
    std::string inode;
    bool readable = false;
    bool writable = false;
    bool executable = false;
    std::string path;
};

struct ModuleSummary {
    std::string path;
    std::string name;
    std::string origin;
    std::uint64_t mapped_bytes = 0;
    std::uint64_t readable_bytes = 0;
    std::size_t region_count = 0;
    bool executable = false;
    bool has_readable_zero_offset = false;
    std::uint64_t readable_zero_start = 0;
    bool file_readable = false;
    bool elf_file = false;
    bool memory_readable = false;
    std::size_t memory_read_bytes = 0;
    bool memory_elf = false;
};

struct Inspection {
    std::string status = "OK";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::size_t region_count = 0;
    std::size_t module_count = 0;
    std::uint64_t readable_bytes = 0;
    std::size_t readable_file_count = 0;
    std::size_t elf_header_count = 0;
    std::size_t memory_readable_module_count = 0;
    std::uint64_t memory_read_bytes = 0;
    std::size_t memory_elf_header_count = 0;
    std::uint64_t maps_fingerprint = kFnvOffsetBasis;
    bool truncated = false;
    std::vector<ModuleSummary> modules;
};

struct ScalarRead {
    std::string status = "OK";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::size_t byte_count = 0;
    std::uint64_t value_bits = 0;
};

struct ScalarPatch {
    std::string status = "APPLIED";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::size_t byte_count = 0;
    std::uint64_t before_value_bits = 0;
    std::uint64_t after_value_bits = 0;
};

struct PagemapRead {
    std::string status = "OK";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::uint64_t address = 0;
    std::uint64_t page_size = 0;
    std::uint64_t entry_bits = 0;
};

struct MemoryRegionRequest {
    std::uint64_t address = 0;
    std::size_t byte_count = 0;
    int mapping_flags = 0;
};

struct MemoryRegionRead {
    std::uint64_t address = 0;
    std::vector<unsigned char> bytes;
};

struct MemoryRegionBatch {
    std::string status = "OK";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::uint64_t maps_fingerprint = 0;
    bool has_maps_fingerprint = false;
    std::size_t requested_count = 0;
    std::size_t completed_count = 0;
    int failed_index = -1;
    std::uint64_t failed_address = 0;
    std::vector<MemoryRegionRead> regions;
};

struct U32WriteRequest {
    std::uint64_t address = 0;
    std::uint32_t value = 0;
    std::uint32_t expected_current_value = 0;
    bool has_expected_current_value = false;
    int mapping_flags = 0;
};

struct U32Batch {
    std::string status = "OK";
    std::string operation = "ITERATION";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::uint64_t maps_fingerprint = 0;
    bool has_maps_fingerprint = false;
    std::size_t requested_count = 0;
    std::size_t completed_count = 0;
    int failed_index = -1;
    std::uint64_t failed_address = 0;
    std::uint32_t expected_value = 0;
    std::uint32_t observed_value = 0;
    bool has_expected_value = false;
    bool has_observed_value = false;
    std::uint32_t guard_value = 0;
    bool has_guard_value = false;
};

struct AntiFlashCycleRegion {
    std::uint64_t address = 0;
    std::vector<unsigned char> original_bytes;
    std::vector<unsigned char> patch_bytes;
};

struct AntiFlashCycle {
    std::string status = "OK";
    std::string message;
    int pid = 0;
    ProcessIdentity identity;
    std::uint64_t maps_fingerprint = 0;
    bool has_maps_fingerprint = false;
    std::vector<std::vector<unsigned char>> code_region_values;
    std::vector<unsigned char> bss_value;
    std::uint64_t bss_address = 0;
    std::size_t requested_count = 0;
    std::size_t completed_count = 0;
    int failed_index = -1;
    std::uint64_t failed_address = 0;
    bool write_attempted = false;
};

class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) : fd_(fd) {}
    ~ScopedFd() {
        if (fd_ >= 0) {
            close(fd_);
        }
    }

    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;

    int get() const { return fd_; }

private:
    int fd_;
};

std::string ProcPath(int pid, std::string_view leaf) {
    return "/proc/" + std::to_string(pid) + "/" + std::string(leaf);
}

std::uint64_t SaturatingAdd(std::uint64_t left, std::uint64_t right) {
    if (right > kMaxJavaLong - left) {
        return kMaxJavaLong;
    }
    return left + right;
}

bool ReadBoundedFile(
    const std::string& path,
    std::size_t max_bytes,
    std::string* output,
    int* error_number
) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (error_number != nullptr) {
            *error_number = errno;
        }
        return false;
    }

    output->clear();
    std::array<char, 512> buffer{};
    while (output->size() < max_bytes) {
        const std::size_t remaining = max_bytes - output->size();
        const std::size_t request = std::min(buffer.size(), remaining);
        ssize_t count;
        do {
            count = read(fd, buffer.data(), request);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            if (error_number != nullptr) {
                *error_number = errno;
            }
            close(fd);
            return false;
        }
        if (count == 0) {
            close(fd);
            return true;
        }
        output->append(buffer.data(), static_cast<std::size_t>(count));
    }

    close(fd);
    return true;
}

bool ParseProcessIdentity(int pid, ProcessIdentity* identity, int* error_number) {
    std::string stat_text;
    if (!ReadBoundedFile(ProcPath(pid, "stat"), kMaxStatBytes, &stat_text, error_number)) {
        return false;
    }

    const std::size_t command_end = stat_text.rfind(')');
    if (command_end == std::string::npos || command_end + 2U >= stat_text.size()) {
        if (error_number != nullptr) {
            *error_number = EINVAL;
        }
        return false;
    }

    std::istringstream fields(stat_text.substr(command_end + 2U));
    std::string value;
    for (int field = 3; field <= 22; ++field) {
        if (!(fields >> value)) {
            if (error_number != nullptr) {
                *error_number = EINVAL;
            }
            return false;
        }
        if (field == 22) {
            if (value.empty() || !std::all_of(value.begin(), value.end(), [](unsigned char character) {
                    return character >= '0' && character <= '9';
                })) {
                if (error_number != nullptr) {
                    *error_number = EINVAL;
                }
                return false;
            }
            identity->start_time_ticks = value;
            return true;
        }
    }
    return false;
}

bool ParseHex(std::string_view text, std::uint64_t* value) {
    if (text.empty()) {
        return false;
    }
    const char* begin = text.data();
    const char* end = begin + text.size();
    const auto result = std::from_chars(begin, end, *value, 16);
    return result.ec == std::errc() && result.ptr == end;
}

bool IsHexDigit(unsigned char character) {
    return (character >= '0' && character <= '9') ||
        (character >= 'a' && character <= 'f') ||
        (character >= 'A' && character <= 'F');
}

bool IsValidMapDevice(std::string_view value) {
    const std::size_t separator = value.find(':');
    if (separator == std::string_view::npos || separator == 0U ||
        separator > 16U || separator + 1U >= value.size() ||
        value.size() - separator - 1U > 16U ||
        value.find(':', separator + 1U) != std::string_view::npos) {
        return false;
    }
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return character == ':' || IsHexDigit(character);
    });
}

bool IsValidMapInode(std::string_view value) {
    return !value.empty() && value.size() <= 20U &&
        std::all_of(value.begin(), value.end(), [](unsigned char character) {
            return character >= '0' && character <= '9';
        });
}

std::string TrimLeft(std::string value) {
    const auto first = std::find_if_not(value.begin(), value.end(), [](unsigned char character) {
        return character == ' ' || character == '\t';
    });
    value.erase(value.begin(), first);
    return value;
}

bool ParseMapRegion(const std::string& line, MapRegion* region) {
    std::istringstream fields(line);
    std::string address_range;
    std::string permissions;
    std::string file_offset;
    std::string device;
    std::string inode;
    if (!(fields >> address_range >> permissions >> file_offset >> device >> inode)) {
        return false;
    }
    if (permissions.size() != 4U ||
        (permissions[0] != 'r' && permissions[0] != '-') ||
        (permissions[1] != 'w' && permissions[1] != '-') ||
        (permissions[2] != 'x' && permissions[2] != '-') ||
        (permissions[3] != 'p' && permissions[3] != 's') ||
        !IsValidMapDevice(device) || !IsValidMapInode(inode)) {
        return false;
    }

    const std::size_t separator = address_range.find('-');
    if (separator == std::string::npos) {
        return false;
    }

    std::uint64_t start = 0;
    std::uint64_t end = 0;
    std::uint64_t offset = 0;
    if (!ParseHex(std::string_view(address_range).substr(0, separator), &start) ||
        !ParseHex(std::string_view(address_range).substr(separator + 1U), &end) ||
        !ParseHex(file_offset, &offset) || start >= end) {
        return false;
    }

    std::string path;
    std::getline(fields, path);
    region->start = start;
    region->end = end;
    region->file_offset = offset;
    region->readable = !permissions.empty() && permissions[0] == 'r';
    region->writable = permissions.size() > 1U && permissions[1] == 'w';
    region->executable = permissions.size() > 2U && permissions[2] == 'x';
    region->permissions = std::move(permissions);
    region->device = std::move(device);
    region->inode = std::move(inode);
    region->path = TrimLeft(std::move(path));
    return true;
}

std::string StripDeletedSuffix(std::string path) {
    constexpr std::string_view suffix = " (deleted)";
    if (path.size() >= suffix.size() &&
        path.compare(path.size() - suffix.size(), suffix.size(), suffix) == 0) {
        path.resize(path.size() - suffix.size());
    }
    return path;
}

std::string ModuleName(const std::string& path) {
    const std::string clean_path = StripDeletedSuffix(path);
    const std::size_t slash = clean_path.find_last_of('/');
    const std::string_view name = slash == std::string::npos
        ? std::string_view(clean_path)
        : std::string_view(clean_path).substr(slash + 1U);
    std::string sanitized;
    sanitized.reserve(std::min<std::size_t>(name.size(), 192U));
    for (unsigned char character : name) {
        if (sanitized.size() == 192U) {
            break;
        }
        sanitized.push_back(character >= 0x20U && character <= 0x7eU
            ? static_cast<char>(character)
            : '?');
    }
    return sanitized.empty() ? "[unnamed]" : sanitized;
}

bool StartsWith(std::string_view value, std::string_view prefix) {
    return value.size() >= prefix.size() && value.substr(0, prefix.size()) == prefix;
}

std::string ModuleOrigin(const std::string& path) {
    if (StartsWith(path, "/data/app/") || StartsWith(path, "/data/user/") ||
        StartsWith(path, "/data/data/") || StartsWith(path, "/mnt/expand/")) {
        return "APP";
    }
    if (StartsWith(path, "/apex/")) {
        return "APEX";
    }
    if (StartsWith(path, "/vendor/")) {
        return "VENDOR";
    }
    if (StartsWith(path, "/product/")) {
        return "PRODUCT";
    }
    if (StartsWith(path, "/system/") || StartsWith(path, "/system_ext/")) {
        return "SYSTEM";
    }
    return "OTHER";
}

bool IsFileBackedPath(const std::string& path) {
    return !path.empty() && path[0] == '/';
}

void UpdateFingerprint(std::uint64_t* hash, std::string_view value) {
    for (unsigned char character : value) {
        *hash ^= character;
        *hash *= kFnvPrime;
    }
    *hash ^= static_cast<unsigned char>('\n');
    *hash *= kFnvPrime;
}

bool ParseMapsFingerprint(std::string_view value, std::uint64_t* output) {
    if (output == nullptr || value.size() != 16U ||
        !std::all_of(value.begin(), value.end(), [](unsigned char character) {
            return (character >= '0' && character <= '9') ||
                (character >= 'a' && character <= 'f');
        })) {
        return false;
    }
    return ParseHex(value, output);
}

bool DecodeHexBytes(std::string_view value, std::vector<unsigned char>* output) {
    if (output == nullptr || value.empty() || (value.size() & 1U) != 0U) {
        return false;
    }
    output->clear();
    output->reserve(value.size() / 2U);
    for (std::size_t index = 0; index < value.size(); index += 2U) {
        std::uint64_t byte = 0;
        if (!ParseHex(value.substr(index, 2U), &byte) || byte > 0xffU) {
            output->clear();
            return false;
        }
        output->push_back(static_cast<unsigned char>(byte));
    }
    return true;
}

struct MapsSnapshot {
    std::string status = "OK";
    std::string message;
    std::uint64_t fingerprint = kFnvOffsetBasis;
    bool has_fingerprint = false;
    std::uint64_t scoped_fingerprint = kFnvOffsetBasis;
    bool has_scoped_fingerprint = false;
    std::uint64_t published_fingerprint = 0;
    bool has_published_fingerprint = false;
    std::vector<MapRegion> regions;
};

MapsSnapshot ReadMapsSnapshot(int pid, bool collect_regions) {
    errno = 0;
    std::ifstream maps(ProcPath(pid, "maps"));
    if (!maps.is_open()) {
        const int maps_error = errno;
        return MapsSnapshot{
            .status = maps_error == ENOENT ? "TARGET_NOT_RUNNING" : "MAPS_UNREADABLE",
            .message = maps_error == ENOENT ?
                "Target process is not running" : "Process maps are unreadable",
            .regions = {},
        };
    }

    MapsSnapshot snapshot;
    std::string line;
    std::size_t maps_bytes = 0;
    std::size_t maps_lines = 0;
    while (std::getline(maps, line)) {
        if (line.size() > kMaxMapsLineBytes || maps_lines >= kMaxMapsLines ||
            line.size() + 1U > kMaxMapsBytes - maps_bytes) {
            return MapsSnapshot{
                .status = "MAPS_UNREADABLE",
                .message = "Process maps exceeded the validation limit",
                .regions = {},
            };
        }
        maps_bytes += line.size() + 1U;
        ++maps_lines;
        UpdateFingerprint(&snapshot.fingerprint, line);
        if (collect_regions) {
            MapRegion region;
            if (!ParseMapRegion(line, &region)) {
                return MapsSnapshot{
                    .status = "MAPS_UNREADABLE",
                    .message = "Process maps contains an invalid mapping row",
                    .regions = {},
                };
            }
            snapshot.regions.push_back(std::move(region));
        }
    }
    if (maps.bad()) {
        return MapsSnapshot{
            .status = "MAPS_UNREADABLE",
            .message = "Process maps changed while reading",
            .regions = {},
        };
    }
    snapshot.has_fingerprint = true;
    return snapshot;
}

bool ReadFileHeader(const std::string& raw_path, std::array<unsigned char, 4>* header) {
    const std::string path = StripDeletedSuffix(raw_path);
    if (path.empty() || path.find("!/") != std::string::npos) {
        return false;
    }

    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }

    std::size_t total = 0;
    while (total < header->size()) {
        ssize_t count;
        do {
            count = pread(fd, header->data() + total, header->size() - total, total);
        } while (count < 0 && errno == EINTR);
        if (count <= 0) {
            close(fd);
            return false;
        }
        total += static_cast<std::size_t>(count);
    }

    close(fd);
    return true;
}

bool IsElfHeader(const std::array<unsigned char, 4>& header) {
    return header[0] == 0x7fU && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
}

ssize_t ReadProcessMemory(
    int pid,
    std::uint64_t address,
    unsigned char* buffer,
    std::size_t buffer_size
) {
    const std::uint64_t max_pointer =
        static_cast<std::uint64_t>(std::numeric_limits<std::uintptr_t>::max());
    if (buffer == nullptr || buffer_size == 0U ||
        buffer_size > static_cast<std::size_t>(std::numeric_limits<ssize_t>::max()) ||
        address > kMaxJavaLong || address > max_pointer ||
        buffer_size - 1U > kMaxJavaLong - address ||
        buffer_size - 1U > max_pointer - address) {
        errno = EINVAL;
        return -1;
    }

    std::size_t total = 0;
    while (total < buffer_size) {
        const std::size_t remaining = buffer_size - total;
        iovec local{
            .iov_base = buffer + total,
            .iov_len = remaining,
        };
        iovec remote{
            .iov_base = reinterpret_cast<void*>(
                static_cast<std::uintptr_t>(address + total)
            ),
            .iov_len = remaining,
        };
        ssize_t count;
        do {
            count = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            if (total != 0U) {
                return -1;
            }
            break;
        }
        if (count == 0) {
            errno = EIO;
            if (total != 0U) {
                return -1;
            }
            break;
        }
        if (static_cast<std::size_t>(count) > remaining) {
            errno = EIO;
            return -1;
        }
        total += static_cast<std::size_t>(count);
    }
    if (total == buffer_size) {
        return static_cast<ssize_t>(buffer_size);
    }

    const int mem_fd = open(ProcPath(pid, "mem").c_str(), O_RDONLY | O_CLOEXEC);
    if (mem_fd < 0) {
        return -1;
    }
    while (total < buffer_size) {
        const std::size_t remaining = buffer_size - total;
        ssize_t count;
        do {
            count = pread64(
                mem_fd,
                buffer + total,
                remaining,
                static_cast<off64_t>(address + total)
            );
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            const int read_error = errno;
            close(mem_fd);
            errno = read_error;
            return -1;
        }
        if (count == 0 || static_cast<std::size_t>(count) > remaining) {
            close(mem_fd);
            errno = EIO;
            return -1;
        }
        total += static_cast<std::size_t>(count);
    }
    close(mem_fd);
    return static_cast<ssize_t>(buffer_size);
}

ssize_t WriteProcessMemory(
    int pid,
    std::uint64_t address,
    const unsigned char* buffer,
    std::size_t buffer_size
) {
    const std::uint64_t max_pointer =
        static_cast<std::uint64_t>(std::numeric_limits<std::uintptr_t>::max());
    if (buffer == nullptr || buffer_size == 0U ||
        buffer_size > static_cast<std::size_t>(std::numeric_limits<ssize_t>::max()) ||
        address > kMaxJavaLong || address > max_pointer ||
        buffer_size - 1U > kMaxJavaLong - address ||
        buffer_size - 1U > max_pointer - address) {
        errno = EINVAL;
        return -1;
    }

    std::size_t total = 0;
    while (total < buffer_size) {
        const std::size_t remaining = buffer_size - total;
        iovec local{
            .iov_base = const_cast<unsigned char*>(buffer + total),
            .iov_len = remaining,
        };
        iovec remote{
            .iov_base = reinterpret_cast<void*>(
                static_cast<std::uintptr_t>(address + total)
            ),
            .iov_len = remaining,
        };
        ssize_t count;
        do {
            count = process_vm_writev(pid, &local, 1, &remote, 1, 0);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            if (total != 0U) {
                return -1;
            }
            break;
        }
        if (count == 0) {
            errno = EIO;
            if (total != 0U) {
                return -1;
            }
            break;
        }
        if (static_cast<std::size_t>(count) > remaining) {
            errno = EIO;
            return -1;
        }
        total += static_cast<std::size_t>(count);
    }
    if (total == buffer_size) {
        return static_cast<ssize_t>(buffer_size);
    }

    const int mem_fd = open(ProcPath(pid, "mem").c_str(), O_RDWR | O_CLOEXEC);
    if (mem_fd < 0) {
        return -1;
    }
    while (total < buffer_size) {
        const std::size_t remaining = buffer_size - total;
        ssize_t count;
        do {
            count = pwrite64(
                mem_fd,
                buffer + total,
                remaining,
                static_cast<off64_t>(address + total)
            );
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            const int write_error = errno;
            close(mem_fd);
            errno = write_error;
            return -1;
        }
        if (count == 0 || static_cast<std::size_t>(count) > remaining) {
            close(mem_fd);
            errno = EIO;
            return -1;
        }
        total += static_cast<std::size_t>(count);
    }
    close(mem_fd);
    return static_cast<ssize_t>(buffer_size);
}

enum class ReadableRangeStatus {
    READABLE,
    TARGET_NOT_RUNNING,
    MAPS_UNREADABLE,
    ADDRESS_NOT_READABLE,
};

ReadableRangeStatus ValidateReadableRange(
    int pid,
    std::uint64_t address,
    std::size_t byte_count,
    bool require_writable = false,
    bool require_executable = false
) {
    if (byte_count == 0U || address > std::numeric_limits<std::uint64_t>::max() - byte_count) {
        return ReadableRangeStatus::ADDRESS_NOT_READABLE;
    }
    const std::uint64_t end_address = address + byte_count;
    errno = 0;
    std::ifstream maps(ProcPath(pid, "maps"));
    if (!maps.is_open()) {
        return errno == ENOENT
            ? ReadableRangeStatus::TARGET_NOT_RUNNING
            : ReadableRangeStatus::MAPS_UNREADABLE;
    }

    std::string line;
    std::size_t maps_bytes = 0;
    std::size_t maps_lines = 0;
    while (std::getline(maps, line)) {
        maps_bytes += line.size() + 1U;
        if (maps_bytes > kMaxMapsBytes || maps_lines >= kMaxMapsLines) {
            break;
        }
        ++maps_lines;
        if (line.size() > kMaxMapsLineBytes) {
            continue;
        }
        MapRegion region;
        if (!ParseMapRegion(line, &region) || !region.readable ||
            (require_writable && !region.writable) ||
            (require_executable && !region.executable)) {
            continue;
        }
        if (address >= region.start && end_address <= region.end) {
            return ReadableRangeStatus::READABLE;
        }
    }
    if (maps.bad()) {
        return ReadableRangeStatus::MAPS_UNREADABLE;
    }
    return ReadableRangeStatus::ADDRESS_NOT_READABLE;
}

bool IsValidPinnedIdentity(std::string_view value) {
    return !value.empty() && value.size() <= 32U &&
        std::all_of(value.begin(), value.end(), [](unsigned char character) {
            return character >= '0' && character <= '9';
        });
}

bool RangeFitsAddressSpace(std::uint64_t address, std::size_t byte_count) {
    return address != 0U && address <= kMaxJavaLong && byte_count != 0U &&
        byte_count <= kMaxJavaLong - address + 1U;
}

bool MappingFlagsAreValid(int flags) {
    return flags >= 0 && (flags & ~kKnownMappingFlags) == 0;
}

struct BatchMappingValidation {
    std::string status = "OK";
    std::string message;
    int failed_index = -1;
    std::uint64_t failed_address = 0;
};

BatchMappingValidation ValidateBatchMappings(
    const std::vector<MapRegion>& regions,
    const std::vector<MemoryRegionRequest>& requests
) {
    for (std::size_t index = 0; index < requests.size(); ++index) {
        const MemoryRegionRequest& request = requests[index];
        if (!RangeFitsAddressSpace(request.address, request.byte_count)) {
            return BatchMappingValidation{
                .status = "INVALID_ADDRESS",
                .message = "Batch item has an invalid address range",
                .failed_index = static_cast<int>(index),
                .failed_address = request.address,
            };
        }
        if (!MappingFlagsAreValid(request.mapping_flags)) {
            return BatchMappingValidation{
                .status = "INVALID_REQUEST",
                .message = "Batch item contains unknown mapping flags",
                .failed_index = static_cast<int>(index),
                .failed_address = request.address,
            };
        }
        const std::uint64_t end_address = request.address + request.byte_count;
        const bool require_writable =
            (request.mapping_flags & kMappingFlagWritable) != 0;
        const bool require_executable =
            (request.mapping_flags & kMappingFlagExecutable) != 0;
        const bool matched = std::any_of(
            regions.begin(),
            regions.end(),
            [&](const MapRegion& region) {
                return region.readable &&
                    (!require_writable || region.writable) &&
                    (!require_executable || region.executable) &&
                    request.address >= region.start && end_address <= region.end;
            }
        );
        if (!matched) {
            return BatchMappingValidation{
                .status = "ADDRESS_NOT_READABLE",
                .message = require_executable ?
                    "Batch item is outside a readable executable mapping" :
                    require_writable ?
                        "Batch item is outside a readable writable mapping" :
                        "Batch item crosses or falls outside a readable mapping",
                .failed_index = static_cast<int>(index),
                .failed_address = request.address,
            };
        }
    }
    return {};
}

std::string CanonicalMapRegion(const MapRegion& region) {
    std::ostringstream output;
    output << std::hex << std::nouppercase << std::setfill('0')
           << std::setw(16) << region.start << '-'
           << std::setw(16) << region.end << ' '
           << region.permissions << ' '
           << std::setw(16) << region.file_offset << ' '
           << region.device << ' '
           << region.inode << ' '
           << region.path;
    return output.str();
}

BatchMappingValidation PrepareScopedMapsSnapshot(
    MapsSnapshot* snapshot,
    const std::vector<MemoryRegionRequest>& requests
) {
    if (snapshot == nullptr) {
        return BatchMappingValidation{
            .status = "MAPS_UNREADABLE",
            .message = "Process maps snapshot is unavailable",
        };
    }

    snapshot->scoped_fingerprint = kFnvOffsetBasis;
    snapshot->has_scoped_fingerprint = false;
    const BatchMappingValidation validation =
        ValidateBatchMappings(snapshot->regions, requests);
    if (validation.status != "OK") {
        return validation;
    }

    std::vector<std::size_t> selected_indices;
    selected_indices.reserve(requests.size());
    for (std::size_t request_index = 0; request_index < requests.size(); ++request_index) {
        const MemoryRegionRequest& request = requests[request_index];
        const std::uint64_t end_address = request.address + request.byte_count;
        const bool require_writable =
            (request.mapping_flags & kMappingFlagWritable) != 0;
        const bool require_executable =
            (request.mapping_flags & kMappingFlagExecutable) != 0;

        std::size_t matched_index = snapshot->regions.size();
        for (std::size_t region_index = 0;
             region_index < snapshot->regions.size();
             ++region_index) {
            const MapRegion& region = snapshot->regions[region_index];
            if (region.readable &&
                (!require_writable || region.writable) &&
                (!require_executable || region.executable) &&
                request.address >= region.start && end_address <= region.end) {
                if (matched_index != snapshot->regions.size()) {
                    return BatchMappingValidation{
                        .status = "ADDRESS_NOT_READABLE",
                        .message = "Batch item matched multiple process mappings",
                        .failed_index = static_cast<int>(request_index),
                        .failed_address = request.address,
                    };
                }
                matched_index = region_index;
            }
        }

        if (matched_index == snapshot->regions.size()) {
            return BatchMappingValidation{
                .status = "ADDRESS_NOT_READABLE",
                .message = "Batch item no longer matches a process mapping",
                .failed_index = static_cast<int>(request_index),
                .failed_address = request.address,
            };
        }
        if (std::find(selected_indices.begin(), selected_indices.end(), matched_index) ==
            selected_indices.end()) {
            selected_indices.push_back(matched_index);
        }
    }

    std::sort(selected_indices.begin(), selected_indices.end());
    for (std::size_t region_index : selected_indices) {
        UpdateFingerprint(
            &snapshot->scoped_fingerprint,
            CanonicalMapRegion(snapshot->regions[region_index])
        );
    }
    snapshot->has_scoped_fingerprint = true;
    return {};
}

enum class MapsFingerprintMode {
    FULL,
    SCOPED,
};

bool SelectMapsFingerprintMode(
    MapsSnapshot* snapshot,
    std::uint64_t expected,
    MapsFingerprintMode* mode
) {
    if (snapshot == nullptr || mode == nullptr) {
        return false;
    }
    if (snapshot->has_scoped_fingerprint && snapshot->scoped_fingerprint == expected) {
        *mode = MapsFingerprintMode::SCOPED;
        snapshot->published_fingerprint = snapshot->scoped_fingerprint;
        snapshot->has_published_fingerprint = true;
        return true;
    }
    if (snapshot->has_fingerprint && snapshot->fingerprint == expected) {
        *mode = MapsFingerprintMode::FULL;
        snapshot->published_fingerprint = snapshot->fingerprint;
        snapshot->has_published_fingerprint = true;
        return true;
    }
    if (snapshot->has_scoped_fingerprint) {
        snapshot->published_fingerprint = snapshot->scoped_fingerprint;
        snapshot->has_published_fingerprint = true;
    }
    return false;
}

bool ApplyMapsFingerprintMode(
    MapsSnapshot* snapshot,
    MapsFingerprintMode mode,
    std::uint64_t expected
) {
    if (snapshot == nullptr) {
        return false;
    }
    const bool matched = mode == MapsFingerprintMode::SCOPED
        ? snapshot->has_scoped_fingerprint && snapshot->scoped_fingerprint == expected
        : snapshot->has_fingerprint && snapshot->fingerprint == expected;
    snapshot->published_fingerprint = mode == MapsFingerprintMode::SCOPED
        ? snapshot->scoped_fingerprint : snapshot->fingerprint;
    snapshot->has_published_fingerprint = mode == MapsFingerprintMode::SCOPED
        ? snapshot->has_scoped_fingerprint : snapshot->has_fingerprint;
    return matched;
}

MemoryRegionBatch WithMapsFingerprint(
    MemoryRegionBatch result,
    const MapsSnapshot& snapshot
) {
    if (snapshot.has_published_fingerprint) {
        result.maps_fingerprint = snapshot.published_fingerprint;
        result.has_maps_fingerprint = true;
    }
    return result;
}

U32Batch WithMapsFingerprint(U32Batch result, const MapsSnapshot& snapshot) {
    if (snapshot.has_published_fingerprint) {
        result.maps_fingerprint = snapshot.published_fingerprint;
        result.has_maps_fingerprint = true;
    }
    return result;
}

AntiFlashCycle WithMapsFingerprint(
    AntiFlashCycle result,
    const MapsSnapshot& snapshot
) {
    if (snapshot.has_published_fingerprint) {
        result.maps_fingerprint = snapshot.published_fingerprint;
        result.has_maps_fingerprint = true;
    }
    return result;
}

AntiFlashCycle ErrorAntiFlashCycle(
    int pid,
    std::size_t requested_count,
    std::string status,
    std::string message,
    ProcessIdentity identity = {},
    std::vector<std::vector<unsigned char>> code_region_values = {},
    std::vector<unsigned char> bss_value = {},
    std::uint64_t bss_address = 0,
    std::size_t completed_count = 0,
    int failed_index = -1,
    std::uint64_t failed_address = 0,
    bool write_attempted = false
) {
    AntiFlashCycle result;
    result.pid = pid;
    result.requested_count = requested_count;
    result.status = std::move(status);
    result.message = std::move(message);
    result.identity = std::move(identity);
    result.code_region_values = std::move(code_region_values);
    result.bss_value = std::move(bss_value);
    result.bss_address = bss_address;
    result.completed_count = completed_count;
    result.failed_index = failed_index;
    result.failed_address = failed_address;
    result.write_attempted = write_attempted;
    return result;
}

MemoryRegionBatch ErrorMemoryRegionBatch(
    int pid,
    std::size_t requested_count,
    std::string status,
    std::string message,
    ProcessIdentity identity = {},
    std::size_t completed_count = 0,
    int failed_index = -1,
    std::uint64_t failed_address = 0
) {
    MemoryRegionBatch result;
    result.pid = pid;
    result.requested_count = requested_count;
    result.status = std::move(status);
    result.message = std::move(message);
    result.identity = std::move(identity);
    result.completed_count = completed_count;
    result.failed_index = failed_index;
    result.failed_address = failed_address;
    return result;
}

U32Batch ErrorU32Batch(
    int pid,
    std::size_t requested_count,
    std::string operation,
    std::string status,
    std::string message,
    ProcessIdentity identity = {},
    std::size_t completed_count = 0,
    int failed_index = -1,
    std::uint64_t failed_address = 0,
    std::uint32_t expected_value = 0,
    std::uint32_t observed_value = 0,
    bool has_expected_value = false,
    bool has_observed_value = false,
    std::uint32_t guard_value = 0,
    bool has_guard_value = false
) {
    U32Batch result;
    result.pid = pid;
    result.requested_count = requested_count;
    result.operation = std::move(operation);
    result.status = std::move(status);
    result.message = std::move(message);
    result.identity = std::move(identity);
    result.completed_count = completed_count;
    result.failed_index = failed_index;
    result.failed_address = failed_address;
    result.expected_value = expected_value;
    result.observed_value = observed_value;
    result.has_expected_value = has_expected_value;
    result.has_observed_value = has_observed_value;
    result.guard_value = guard_value;
    result.has_guard_value = has_guard_value;
    return result;
}

MemoryRegionBatch ReadTargetMemoryRegions(
    int pid,
    std::string_view expected_start_time_ticks,
    std::string_view expected_maps_fingerprint,
    const std::vector<MemoryRegionRequest>& requests
) {
    if (pid <= 0) {
        return ErrorMemoryRegionBatch(
            pid, requests.size(), "INVALID_PID", "PID must be positive"
        );
    }
    if (!IsValidPinnedIdentity(expected_start_time_ticks)) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "INVALID_IDENTITY",
            "Pinned process identity is invalid"
        );
    }
    std::uint64_t expected_fingerprint = 0;
    if (!ParseMapsFingerprint(expected_maps_fingerprint, &expected_fingerprint)) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "INVALID_IDENTITY",
            "Pinned maps fingerprint is invalid"
        );
    }
    if (requests.empty() || requests.size() > kMaxMemoryBatchItems) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "INVALID_REQUEST",
            "Memory region count is outside the supported range"
        );
    }

    std::size_t total_bytes = 0;
    for (std::size_t index = 0; index < requests.size(); ++index) {
        const MemoryRegionRequest& request = requests[index];
        if (request.byte_count == 0U || request.byte_count > kMaxMemoryRegionBytes ||
            total_bytes > kMaxMemoryBatchBytes - request.byte_count) {
            return ErrorMemoryRegionBatch(
                pid,
                requests.size(),
                "INVALID_SIZE",
                "Memory region batch exceeds the supported size",
                {},
                0,
                static_cast<int>(index),
                request.address
            );
        }
        total_bytes += request.byte_count;
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target process is not running" : "Process stat is unreadable"
        );
    }
    if (initial_identity.start_time_ticks != expected_start_time_ticks) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "TARGET_CHANGED",
            "PID identity does not match the pinned target",
            initial_identity
        );
    }

    MapsSnapshot initial_maps = ReadMapsSnapshot(pid, true);
    if (initial_maps.status != "OK") {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            initial_maps.status,
            initial_maps.message,
            initial_identity
        );
    }
    const BatchMappingValidation initial_mapping =
        PrepareScopedMapsSnapshot(&initial_maps, requests);
    MapsFingerprintMode fingerprint_mode = MapsFingerprintMode::FULL;
    const bool fingerprint_matches = initial_mapping.status == "OK"
        ? SelectMapsFingerprintMode(&initial_maps, expected_fingerprint, &fingerprint_mode)
        : false;
    if (initial_mapping.status != "OK") {
        return WithMapsFingerprint(
            ErrorMemoryRegionBatch(
                pid,
                requests.size(),
                initial_mapping.status,
                initial_mapping.message,
                initial_identity,
                0,
                initial_mapping.failed_index,
                initial_mapping.failed_address
            ),
            initial_maps
        );
    }
    if (!fingerprint_matches) {
        return WithMapsFingerprint(
            ErrorMemoryRegionBatch(
                pid,
                requests.size(),
                "TARGET_CHANGED",
                "Requested process mappings do not match the pinned target",
                initial_identity
            ),
            initial_maps
        );
    }

    ProcessIdentity pre_open_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &pre_open_identity, &identity_error)) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target exited before memory batch read" :
                "Process stat became unreadable before memory batch read",
            initial_identity
        );
    }
    if (pre_open_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "TARGET_CHANGED",
            "PID identity changed before memory batch read",
            initial_identity
        );
    }

    const int raw_fd = open(ProcPath(pid, "mem").c_str(), O_RDONLY | O_CLOEXEC);
    if (raw_fd < 0) {
        const int open_error = errno;
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            open_error == ENOENT ? "TARGET_NOT_RUNNING" : "MEM_OPEN_FAILED",
            open_error == ENOENT ?
                "Target process is not running" : std::strerror(open_error),
            initial_identity
        );
    }
    ScopedFd mem_fd(raw_fd);

    ProcessIdentity post_open_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &post_open_identity, &identity_error)) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target exited after opening process memory" :
                "Process stat became unreadable after opening process memory",
            initial_identity
        );
    }
    if (post_open_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "TARGET_CHANGED",
            "PID identity changed after opening process memory",
            initial_identity
        );
    }

    MapsSnapshot post_open_maps = ReadMapsSnapshot(pid, true);
    if (post_open_maps.status != "OK") {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            post_open_maps.status,
            post_open_maps.message,
            initial_identity
        );
    }
    const BatchMappingValidation post_open_mapping =
        PrepareScopedMapsSnapshot(&post_open_maps, requests);
    if (post_open_mapping.status != "OK" ||
        !ApplyMapsFingerprintMode(&post_open_maps, fingerprint_mode, expected_fingerprint)) {
        return WithMapsFingerprint(
            ErrorMemoryRegionBatch(
                pid,
                requests.size(),
                "TARGET_CHANGED",
                post_open_mapping.status == "OK" ?
                    "Requested process mappings changed after opening process memory" :
                    post_open_mapping.message,
                initial_identity
            ),
            post_open_maps
        );
    }

    MemoryRegionBatch result;
    result.pid = pid;
    result.identity = initial_identity;
    result.requested_count = requests.size();
    result.regions.reserve(requests.size());
    for (std::size_t index = 0; index < requests.size(); ++index) {
        const MemoryRegionRequest& request = requests[index];
        MemoryRegionRead region;
        region.address = request.address;
        region.bytes.resize(request.byte_count);
        errno = 0;
        ssize_t count;
        do {
            count = pread64(
                mem_fd.get(),
                region.bytes.data(),
                region.bytes.size(),
                static_cast<off64_t>(request.address)
            );
        } while (count < 0 && errno == EINTR);
        const int read_error = errno;
        if (count < 0) {
            return WithMapsFingerprint(
                ErrorMemoryRegionBatch(
                    pid,
                    requests.size(),
                    "READ_FAILED",
                    read_error == 0 ? "Target memory batch read failed" :
                        std::strerror(read_error),
                    initial_identity,
                    index,
                    static_cast<int>(index),
                    request.address
                ),
                post_open_maps
            );
        }
        if (static_cast<std::size_t>(count) != request.byte_count) {
            return WithMapsFingerprint(
                ErrorMemoryRegionBatch(
                    pid,
                    requests.size(),
                    "PARTIAL_READ",
                    "Target memory batch read was incomplete",
                    initial_identity,
                    index,
                    static_cast<int>(index),
                    request.address
                ),
                post_open_maps
            );
        }
        result.regions.push_back(std::move(region));
        result.completed_count = index + 1U;
    }

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error)) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target exited during memory batch read" :
                "Process stat became unreadable during memory batch read",
            initial_identity,
            result.completed_count
        );
    }
    if (final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            "TARGET_CHANGED",
            "PID identity changed during memory batch read",
            initial_identity,
            result.completed_count
        );
    }
    MapsSnapshot final_maps = ReadMapsSnapshot(pid, true);
    if (final_maps.status != "OK") {
        return ErrorMemoryRegionBatch(
            pid,
            requests.size(),
            final_maps.status,
            final_maps.message,
            initial_identity,
            result.completed_count
        );
    }
    const BatchMappingValidation final_mapping =
        PrepareScopedMapsSnapshot(&final_maps, requests);
    if (final_mapping.status != "OK" ||
        !ApplyMapsFingerprintMode(&final_maps, fingerprint_mode, expected_fingerprint)) {
        return WithMapsFingerprint(
            ErrorMemoryRegionBatch(
                pid,
                requests.size(),
                "TARGET_CHANGED",
                final_mapping.status == "OK" ?
                    "Requested process mappings changed during memory batch read" :
                    final_mapping.message,
                initial_identity,
                result.completed_count
            ),
            final_maps
        );
    }
    result.maps_fingerprint = final_maps.published_fingerprint;
    result.has_maps_fingerprint = true;
    return result;
}

std::array<unsigned char, kU32Bytes> EncodeU32(std::uint32_t value) {
    return {
        static_cast<unsigned char>(value & 0xffU),
        static_cast<unsigned char>((value >> 8U) & 0xffU),
        static_cast<unsigned char>((value >> 16U) & 0xffU),
        static_cast<unsigned char>((value >> 24U) & 0xffU),
    };
}

std::uint32_t DecodeU32(const std::array<unsigned char, kU32Bytes>& bytes) {
    return static_cast<std::uint32_t>(bytes[0]) |
        (static_cast<std::uint32_t>(bytes[1]) << 8U) |
        (static_cast<std::uint32_t>(bytes[2]) << 16U) |
        (static_cast<std::uint32_t>(bytes[3]) << 24U);
}

U32Batch WriteVerifyTargetU32Batch(
    int pid,
    std::string_view expected_start_time_ticks,
    std::string_view expected_maps_fingerprint,
    std::string operation,
    const std::vector<U32WriteRequest>& writes
) {
    if (pid <= 0) {
        return ErrorU32Batch(
            pid, writes.size(), std::move(operation), "INVALID_PID", "PID must be positive"
        );
    }
    if (operation != "ITERATION" && operation != "ROLLBACK") {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            "INVALID_REQUEST",
            "Unknown u32 batch operation"
        );
    }
    if (!IsValidPinnedIdentity(expected_start_time_ticks)) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            "INVALID_IDENTITY",
            "Pinned process identity is invalid"
        );
    }
    std::uint64_t expected_fingerprint = 0;
    if (!ParseMapsFingerprint(expected_maps_fingerprint, &expected_fingerprint)) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            "INVALID_IDENTITY",
            "Pinned maps fingerprint is invalid"
        );
    }
    if (writes.empty() || writes.size() > kMaxMemoryBatchItems) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            "INVALID_REQUEST",
            "U32 write count is outside the supported range"
        );
    }

    std::vector<MemoryRegionRequest> mapping_requests;
    mapping_requests.reserve(writes.size());
    for (std::size_t index = 0; index < writes.size(); ++index) {
        const U32WriteRequest& write = writes[index];
        if (!RangeFitsAddressSpace(write.address, kU32Bytes) ||
            (write.address & 0x3U) != 0U) {
            return ErrorU32Batch(
                pid,
                writes.size(),
                std::move(operation),
                "INVALID_ADDRESS",
                "U32 write address must be positive and 4-byte aligned",
                {},
                0,
                static_cast<int>(index),
                write.address,
                write.value,
                0,
                true,
                false
            );
        }
        if (operation == "ROLLBACK" && !write.has_expected_current_value) {
            return ErrorU32Batch(
                pid,
                writes.size(),
                std::move(operation),
                "INVALID_REQUEST",
                "Rollback u32 write is missing its expected current value",
                {},
                0,
                static_cast<int>(index),
                write.address,
                write.value,
                0,
                true,
                false
            );
        }
        mapping_requests.push_back(MemoryRegionRequest{
            .address = write.address,
            .byte_count = kU32Bytes,
            .mapping_flags = write.mapping_flags,
        });
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target process is not running" : "Process stat is unreadable"
        );
    }
    if (initial_identity.start_time_ticks != expected_start_time_ticks) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            "TARGET_CHANGED",
            "PID identity does not match the pinned target",
            initial_identity
        );
    }

    MapsSnapshot initial_maps = ReadMapsSnapshot(pid, true);
    if (initial_maps.status != "OK") {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            initial_maps.status,
            initial_maps.message,
            initial_identity
        );
    }
    const BatchMappingValidation initial_mapping =
        PrepareScopedMapsSnapshot(&initial_maps, mapping_requests);
    MapsFingerprintMode fingerprint_mode = MapsFingerprintMode::FULL;
    const bool fingerprint_matches = initial_mapping.status == "OK"
        ? SelectMapsFingerprintMode(&initial_maps, expected_fingerprint, &fingerprint_mode)
        : false;
    if (initial_mapping.status != "OK") {
        const std::size_t failed_index = initial_mapping.failed_index < 0
            ? 0U
            : static_cast<std::size_t>(initial_mapping.failed_index);
        const bool has_failed_write =
            initial_mapping.failed_index >= 0 && failed_index < writes.size();
        return WithMapsFingerprint(
            ErrorU32Batch(
                pid,
                writes.size(),
                std::move(operation),
                initial_mapping.status,
                initial_mapping.message,
                initial_identity,
                0,
                initial_mapping.failed_index,
                initial_mapping.failed_address,
                has_failed_write ? writes[failed_index].value : 0U,
                0,
                has_failed_write,
                false
            ),
            initial_maps
        );
    }
    if (!fingerprint_matches) {
        return WithMapsFingerprint(
            ErrorU32Batch(
                pid,
                writes.size(),
                std::move(operation),
                "TARGET_CHANGED",
                "Requested process mappings do not match the pinned target",
                initial_identity
            ),
            initial_maps
        );
    }

    ProcessIdentity pre_open_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &pre_open_identity, &identity_error)) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target exited before u32 batch write" :
                "Process stat became unreadable before u32 batch write",
            initial_identity
        );
    }
    if (pre_open_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            "TARGET_CHANGED",
            "PID identity changed before u32 batch write",
            initial_identity
        );
    }

    const int raw_fd = open(ProcPath(pid, "mem").c_str(), O_RDWR | O_CLOEXEC);
    if (raw_fd < 0) {
        const int open_error = errno;
        return ErrorU32Batch(
            pid,
            writes.size(),
            std::move(operation),
            open_error == ENOENT ? "TARGET_NOT_RUNNING" : "MEM_OPEN_FAILED",
            open_error == ENOENT ?
                "Target process is not running" : std::strerror(open_error),
            initial_identity
        );
    }
    ScopedFd mem_fd(raw_fd);

    ProcessIdentity post_open_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &post_open_identity, &identity_error)) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            operation,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target exited after opening process memory" :
                "Process stat became unreadable after opening process memory",
            initial_identity
        );
    }
    if (post_open_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            operation,
            "TARGET_CHANGED",
            "PID identity changed after opening process memory",
            initial_identity
        );
    }

    MapsSnapshot post_open_maps = ReadMapsSnapshot(pid, true);
    if (post_open_maps.status != "OK") {
        return ErrorU32Batch(
            pid,
            writes.size(),
            operation,
            post_open_maps.status,
            post_open_maps.message,
            initial_identity
        );
    }
    const BatchMappingValidation post_open_mapping =
        PrepareScopedMapsSnapshot(&post_open_maps, mapping_requests);
    if (post_open_mapping.status != "OK" ||
        !ApplyMapsFingerprintMode(&post_open_maps, fingerprint_mode, expected_fingerprint)) {
        return WithMapsFingerprint(
            ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "TARGET_CHANGED",
                post_open_mapping.status == "OK" ?
                    "Requested process mappings changed after opening process memory" :
                    post_open_mapping.message,
                initial_identity
            ),
            post_open_maps
        );
    }

    U32Batch result;
    result.pid = pid;
    result.operation = operation;
    result.identity = initial_identity;
    result.requested_count = writes.size();
    for (std::size_t index = 0; index < writes.size(); ++index) {
        const U32WriteRequest& write = writes[index];
        if (operation == "ROLLBACK") {
            std::array<unsigned char, kU32Bytes> current_bytes{};
            errno = 0;
            ssize_t current_count;
            do {
                current_count = pread64(
                    mem_fd.get(),
                    current_bytes.data(),
                    current_bytes.size(),
                    static_cast<off64_t>(write.address)
                );
            } while (current_count < 0 && errno == EINTR);
            const int current_error = errno;
            if (current_count < 0) {
                return WithMapsFingerprint(ErrorU32Batch(
                    pid,
                    writes.size(),
                    operation,
                    "READ_FAILED",
                    current_error == 0 ? "Rollback guard read failed" :
                        std::strerror(current_error),
                    initial_identity,
                    index,
                    static_cast<int>(index),
                    write.address,
                    write.value,
                    0,
                    true,
                    false,
                    write.expected_current_value,
                    true
                ), post_open_maps);
            }
            if (static_cast<std::size_t>(current_count) != current_bytes.size()) {
                return WithMapsFingerprint(ErrorU32Batch(
                    pid,
                    writes.size(),
                    operation,
                    "PARTIAL_READ",
                    "Rollback guard read was incomplete",
                    initial_identity,
                    index,
                    static_cast<int>(index),
                    write.address,
                    write.value,
                    0,
                    true,
                    false,
                    write.expected_current_value,
                    true
                ), post_open_maps);
            }
            const std::uint32_t current_value = DecodeU32(current_bytes);
            if (current_value != write.expected_current_value) {
                return WithMapsFingerprint(ErrorU32Batch(
                    pid,
                    writes.size(),
                    operation,
                    "PROFILE_MISMATCH",
                    "Rollback guard no longer matches the preflight value",
                    initial_identity,
                    index,
                    static_cast<int>(index),
                    write.address,
                    write.value,
                    current_value,
                    true,
                    true,
                    write.expected_current_value,
                    true
                ), post_open_maps);
            }
        }
        const auto bytes = EncodeU32(write.value);
        if (operation == "ROLLBACK" && write.expected_current_value == write.value) {
            result.completed_count = index + 1U;
            continue;
        }
        errno = 0;
        ssize_t write_count;
        do {
            write_count = pwrite64(
                mem_fd.get(),
                bytes.data(),
                bytes.size(),
                static_cast<off64_t>(write.address)
            );
        } while (write_count < 0 && errno == EINTR);
        const int write_error = errno;
        if (write_count < 0) {
            return WithMapsFingerprint(ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "WRITE_FAILED",
                write_error == 0 ? "Target memory batch write failed" :
                    std::strerror(write_error),
                initial_identity,
                index,
                static_cast<int>(index),
                write.address,
                write.value,
                0,
                true,
                false
            ), post_open_maps);
        }
        if (static_cast<std::size_t>(write_count) != bytes.size()) {
            return WithMapsFingerprint(ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "PARTIAL_WRITE",
                "Target memory batch write was incomplete",
                initial_identity,
                index,
                static_cast<int>(index),
                write.address,
                write.value,
                0,
                true,
                false
            ), post_open_maps);
        }

        std::array<unsigned char, kU32Bytes> verify_bytes{};
        errno = 0;
        ssize_t verify_count;
        do {
            verify_count = pread64(
                mem_fd.get(),
                verify_bytes.data(),
                verify_bytes.size(),
                static_cast<off64_t>(write.address)
            );
        } while (verify_count < 0 && errno == EINTR);
        const int verify_error = errno;
        if (verify_count < 0) {
            return WithMapsFingerprint(ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "READ_FAILED",
                verify_error == 0 ? "Target memory batch verification read failed" :
                    std::strerror(verify_error),
                initial_identity,
                index,
                static_cast<int>(index),
                write.address,
                write.value,
                0,
                true,
                false
            ), post_open_maps);
        }
        if (static_cast<std::size_t>(verify_count) != verify_bytes.size()) {
            return WithMapsFingerprint(ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "PARTIAL_READ",
                "Target memory batch verification read was incomplete",
                initial_identity,
                index,
                static_cast<int>(index),
                write.address,
                write.value,
                0,
                true,
                false
            ), post_open_maps);
        }
        const std::uint32_t observed_value = DecodeU32(verify_bytes);
        if (observed_value != write.value) {
            return WithMapsFingerprint(ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "VERIFY_FAILED",
                "Target memory batch verification did not match the requested value",
                initial_identity,
                index,
                static_cast<int>(index),
                write.address,
                write.value,
                observed_value,
                true,
                true
            ), post_open_maps);
        }
        result.completed_count = index + 1U;
    }

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error)) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            operation,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ?
                "Target exited during u32 batch verification" :
                "Process stat became unreadable during u32 batch verification",
            initial_identity,
            result.completed_count
        );
    }
    if (final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorU32Batch(
            pid,
            writes.size(),
            operation,
            "TARGET_CHANGED",
            "PID identity changed during u32 batch verification",
            initial_identity,
            result.completed_count
        );
    }
    MapsSnapshot final_maps = ReadMapsSnapshot(pid, true);
    if (final_maps.status != "OK") {
        return ErrorU32Batch(
            pid,
            writes.size(),
            operation,
            final_maps.status,
            final_maps.message,
            initial_identity,
            result.completed_count
        );
    }
    const BatchMappingValidation final_mapping =
        PrepareScopedMapsSnapshot(&final_maps, mapping_requests);
    if (final_mapping.status != "OK" ||
        !ApplyMapsFingerprintMode(&final_maps, fingerprint_mode, expected_fingerprint)) {
        return WithMapsFingerprint(
            ErrorU32Batch(
                pid,
                writes.size(),
                operation,
                "TARGET_CHANGED",
                final_mapping.status == "OK" ?
                    "Requested process mappings changed during u32 batch verification" :
                    final_mapping.message,
                initial_identity,
                result.completed_count
            ),
            final_maps
        );
    }
    result.maps_fingerprint = final_maps.published_fingerprint;
    result.has_maps_fingerprint = true;
    return result;
}

AntiFlashCycle RunAntiFlashCycle(
    int pid,
    std::string_view expected_start_time_ticks,
    std::string_view expected_maps_fingerprint,
    const std::vector<AntiFlashCycleRegion>& code_regions,
    std::uint64_t bss_address,
    const std::vector<U32WriteRequest>& writes
) {
    constexpr std::size_t kCodeRegionCount = 6U;
    constexpr std::size_t kWriteCount = 17U;
    if (pid <= 0 || !IsValidPinnedIdentity(expected_start_time_ticks)) {
        return ErrorAntiFlashCycle(
            pid, writes.size(), "INVALID_IDENTITY", "Pinned process identity is invalid"
        );
    }
    std::uint64_t expected_fingerprint = 0;
    if (!ParseMapsFingerprint(expected_maps_fingerprint, &expected_fingerprint)) {
        return ErrorAntiFlashCycle(
            pid, writes.size(), "INVALID_IDENTITY", "Pinned maps fingerprint is invalid"
        );
    }
    if (code_regions.size() != kCodeRegionCount || writes.size() != kWriteCount ||
        !RangeFitsAddressSpace(bss_address, kU32Bytes) || (bss_address & 0x3U) != 0U) {
        return ErrorAntiFlashCycle(
            pid, writes.size(), "INVALID_REQUEST", "Anti-flash cycle shape is invalid"
        );
    }

    std::vector<MemoryRegionRequest> mapping_requests;
    mapping_requests.reserve(code_regions.size() + 1U + writes.size());
    for (std::size_t index = 0; index < code_regions.size(); ++index) {
        const AntiFlashCycleRegion& region = code_regions[index];
        if (region.original_bytes.empty() ||
            region.original_bytes.size() != region.patch_bytes.size() ||
            region.original_bytes.size() > kMaxMemoryRegionBytes ||
            !RangeFitsAddressSpace(region.address, region.original_bytes.size())) {
            return ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "INVALID_REQUEST",
                "Anti-flash code region is invalid",
                {},
                {},
                {},
                bss_address,
                0,
                index == 0U ? 0 : static_cast<int>(1U + (index - 1U) * 3U),
                region.address
            );
        }
        mapping_requests.push_back(MemoryRegionRequest{
            .address = region.address,
            .byte_count = region.original_bytes.size(),
            .mapping_flags = kMappingFlagExecutable,
        });
    }
    mapping_requests.push_back(MemoryRegionRequest{
        .address = bss_address,
        .byte_count = kU32Bytes,
        .mapping_flags = kMappingFlagWritable,
    });
    for (std::size_t index = 0; index < writes.size(); ++index) {
        const U32WriteRequest& write = writes[index];
        if (!RangeFitsAddressSpace(write.address, kU32Bytes) ||
            (write.address & 0x3U) != 0U || !MappingFlagsAreValid(write.mapping_flags)) {
            return ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "INVALID_REQUEST",
                "Anti-flash write is invalid",
                {},
                {},
                {},
                bss_address,
                0,
                static_cast<int>(index),
                write.address
            );
        }
        mapping_requests.push_back(MemoryRegionRequest{
            .address = write.address,
            .byte_count = kU32Bytes,
            .mapping_flags = write.mapping_flags,
        });
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorAntiFlashCycle(
            pid,
            writes.size(),
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process is not running" :
                "Process stat is unreadable"
        );
    }
    if (initial_identity.start_time_ticks != expected_start_time_ticks) {
        return ErrorAntiFlashCycle(
            pid,
            writes.size(),
            "TARGET_CHANGED",
            "PID identity does not match the pinned target",
            initial_identity
        );
    }

    MapsSnapshot initial_maps = ReadMapsSnapshot(pid, true);
    if (initial_maps.status != "OK") {
        return ErrorAntiFlashCycle(
            pid, writes.size(), initial_maps.status, initial_maps.message, initial_identity
        );
    }
    const BatchMappingValidation initial_mapping =
        PrepareScopedMapsSnapshot(&initial_maps, mapping_requests);
    MapsFingerprintMode fingerprint_mode = MapsFingerprintMode::FULL;
    const bool fingerprint_matches = initial_mapping.status == "OK"
        ? SelectMapsFingerprintMode(&initial_maps, expected_fingerprint, &fingerprint_mode)
        : false;
    if (initial_mapping.status != "OK") {
        const int failed_index = initial_mapping.failed_index >=
                static_cast<int>(code_regions.size() + 1U)
            ? initial_mapping.failed_index - static_cast<int>(code_regions.size() + 1U)
            : initial_mapping.failed_index == static_cast<int>(code_regions.size())
                ? static_cast<int>(kWriteCount - 1U)
                : initial_mapping.failed_index == 0 ? 0
                    : initial_mapping.failed_index > 0 ?
                        1 + (initial_mapping.failed_index - 1) * 3 : -1;
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                initial_mapping.status,
                initial_mapping.message,
                initial_identity,
                {},
                {},
                bss_address,
                0,
                failed_index,
                initial_mapping.failed_address
            ),
            initial_maps
        );
    }
    if (!fingerprint_matches) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "TARGET_CHANGED",
                "Requested process mappings do not match the pinned target",
                initial_identity
            ),
            initial_maps
        );
    }

    const int raw_fd = open(ProcPath(pid, "mem").c_str(), O_RDWR | O_CLOEXEC);
    if (raw_fd < 0) {
        const int open_error = errno;
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                open_error == ENOENT ? "TARGET_NOT_RUNNING" : "MEM_OPEN_FAILED",
                open_error == ENOENT ? "Target process is not running" :
                    std::strerror(open_error),
                initial_identity
            ),
            initial_maps
        );
    }
    ScopedFd mem_fd(raw_fd);

    ProcessIdentity post_open_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &post_open_identity, &identity_error) ||
        post_open_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "TARGET_CHANGED",
                "Target identity changed after opening process memory",
                initial_identity
            ),
            initial_maps
        );
    }
    MapsSnapshot post_open_maps = ReadMapsSnapshot(pid, true);
    if (post_open_maps.status != "OK") {
        return ErrorAntiFlashCycle(
            pid,
            writes.size(),
            post_open_maps.status,
            post_open_maps.message,
            initial_identity
        );
    }
    const BatchMappingValidation post_open_mapping =
        PrepareScopedMapsSnapshot(&post_open_maps, mapping_requests);
    if (post_open_mapping.status != "OK" ||
        !ApplyMapsFingerprintMode(&post_open_maps, fingerprint_mode, expected_fingerprint)) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "TARGET_CHANGED",
                post_open_mapping.status == "OK" ?
                    "Requested process mappings changed after opening process memory" :
                    post_open_mapping.message,
                initial_identity
            ),
            post_open_maps
        );
    }

    AntiFlashCycle result;
    result.pid = pid;
    result.identity = initial_identity;
    result.maps_fingerprint = post_open_maps.published_fingerprint;
    result.has_maps_fingerprint = true;
    result.bss_address = bss_address;
    result.requested_count = writes.size();
    result.code_region_values.reserve(code_regions.size());
    int profile_mismatch_index = -1;
    std::uint64_t profile_mismatch_address = 0;
    for (std::size_t index = 0; index < code_regions.size(); ++index) {
        const AntiFlashCycleRegion& profile = code_regions[index];
        std::vector<unsigned char> current(profile.original_bytes.size());
        errno = 0;
        ssize_t count;
        do {
            count = pread64(
                mem_fd.get(), current.data(), current.size(), static_cast<off64_t>(profile.address)
            );
        } while (count < 0 && errno == EINTR);
        if (count < 0 || static_cast<std::size_t>(count) != current.size()) {
            return WithMapsFingerprint(
                ErrorAntiFlashCycle(
                    pid,
                    writes.size(),
                    count < 0 ? "READ_FAILED" : "PARTIAL_READ",
                    "Anti-flash code preflight read failed",
                    initial_identity,
                    std::move(result.code_region_values),
                    {},
                    bss_address,
                    0,
                    index == 0U ? 0 : static_cast<int>(1U + (index - 1U) * 3U),
                    profile.address
                ),
                post_open_maps
            );
        }
        result.code_region_values.push_back(current);
        if (profile_mismatch_index < 0 &&
            current != profile.original_bytes && current != profile.patch_bytes) {
            profile_mismatch_index = index == 0U
                ? 0 : static_cast<int>(1U + (index - 1U) * 3U);
            profile_mismatch_address = profile.address;
        }
    }

    result.bss_value.resize(kU32Bytes);
    errno = 0;
    ssize_t bss_count;
    do {
        bss_count = pread64(
            mem_fd.get(), result.bss_value.data(), result.bss_value.size(),
            static_cast<off64_t>(bss_address)
        );
    } while (bss_count < 0 && errno == EINTR);
    if (bss_count < 0 || static_cast<std::size_t>(bss_count) != result.bss_value.size()) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                bss_count < 0 ? "READ_FAILED" : "PARTIAL_READ",
                "Anti-flash BSS preflight read failed",
                initial_identity,
                std::move(result.code_region_values),
                std::move(result.bss_value),
                bss_address,
                0,
                static_cast<int>(kWriteCount - 1U),
                bss_address
            ),
            post_open_maps
        );
    }

    if (profile_mismatch_index >= 0) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "PROFILE_MISMATCH",
                "Anti-flash code region has an unknown value",
                initial_identity,
                std::move(result.code_region_values),
                std::move(result.bss_value),
                bss_address,
                0,
                profile_mismatch_index,
                profile_mismatch_address
            ),
            post_open_maps
        );
    }

    result.write_attempted = true;
    for (std::size_t index = 0; index < writes.size(); ++index) {
        const U32WriteRequest& write = writes[index];
        const auto bytes = EncodeU32(write.value);
        errno = 0;
        ssize_t write_count;
        do {
            write_count = pwrite64(
                mem_fd.get(), bytes.data(), bytes.size(), static_cast<off64_t>(write.address)
            );
        } while (write_count < 0 && errno == EINTR);
        if (write_count < 0 || static_cast<std::size_t>(write_count) != bytes.size()) {
            return WithMapsFingerprint(
                ErrorAntiFlashCycle(
                    pid,
                    writes.size(),
                    write_count < 0 ? "WRITE_FAILED" : "PARTIAL_WRITE",
                    "Anti-flash write failed",
                    initial_identity,
                    std::move(result.code_region_values),
                    std::move(result.bss_value),
                    bss_address,
                    result.completed_count,
                    static_cast<int>(index),
                    write.address,
                    true
                ),
                post_open_maps
            );
        }
        std::array<unsigned char, kU32Bytes> verify{};
        errno = 0;
        ssize_t verify_count;
        do {
            verify_count = pread64(
                mem_fd.get(), verify.data(), verify.size(), static_cast<off64_t>(write.address)
            );
        } while (verify_count < 0 && errno == EINTR);
        if (verify_count < 0 || static_cast<std::size_t>(verify_count) != verify.size() ||
            verify != bytes) {
            return WithMapsFingerprint(
                ErrorAntiFlashCycle(
                    pid,
                    writes.size(),
                    verify_count < 0 ? "READ_FAILED" :
                        static_cast<std::size_t>(verify_count) != verify.size() ?
                            "PARTIAL_READ" : "VERIFY_FAILED",
                    "Anti-flash write verification failed",
                    initial_identity,
                    std::move(result.code_region_values),
                    std::move(result.bss_value),
                    bss_address,
                    result.completed_count,
                    static_cast<int>(index),
                    write.address,
                    true
                ),
                post_open_maps
            );
        }
        result.completed_count = index + 1U;
    }

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error) ||
        final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                "TARGET_CHANGED",
                "Target identity changed during anti-flash cycle",
                initial_identity,
                std::move(result.code_region_values),
                std::move(result.bss_value),
                bss_address,
                result.completed_count,
                -1,
                0,
                true
            ),
            post_open_maps
        );
    }
    MapsSnapshot final_maps = ReadMapsSnapshot(pid, true);
    const BatchMappingValidation final_mapping =
        final_maps.status == "OK" ?
            PrepareScopedMapsSnapshot(&final_maps, mapping_requests) :
            BatchMappingValidation{};
    if (final_maps.status != "OK" || final_mapping.status != "OK" ||
        !ApplyMapsFingerprintMode(&final_maps, fingerprint_mode, expected_fingerprint)) {
        return WithMapsFingerprint(
            ErrorAntiFlashCycle(
                pid,
                writes.size(),
                final_maps.status != "OK" ? final_maps.status : "TARGET_CHANGED",
                final_maps.status != "OK" ? final_maps.message :
                    final_mapping.status != "OK" ? final_mapping.message :
                        "Requested process mappings changed during anti-flash cycle",
                initial_identity,
                std::move(result.code_region_values),
                std::move(result.bss_value),
                bss_address,
                result.completed_count,
                -1,
                0,
                true
            ),
            final_maps.has_published_fingerprint ? final_maps : post_open_maps
        );
    }
    result.maps_fingerprint = final_maps.published_fingerprint;
    return result;
}

int OriginRank(std::string_view origin) {
    if (origin == "APP") return 0;
    if (origin == "APEX") return 1;
    if (origin == "SYSTEM") return 2;
    if (origin == "VENDOR") return 3;
    if (origin == "PRODUCT") return 4;
    return 5;
}

std::string JsonEscape(std::string_view value) {
    std::string escaped;
    escaped.reserve(value.size() + 8U);
    for (unsigned char character : value) {
        switch (character) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\b': escaped += "\\b"; break;
            case '\f': escaped += "\\f"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default:
                escaped.push_back(character >= 0x20U && character <= 0x7eU
                    ? static_cast<char>(character)
                    : '?');
                break;
        }
    }
    return escaped;
}

Inspection ErrorInspection(int pid, std::string status, std::string message) {
    Inspection result;
    result.pid = pid;
    result.status = std::move(status);
    result.message = std::move(message);
    return result;
}

ScalarRead ErrorScalarRead(
    int pid,
    std::size_t byte_count,
    std::string status,
    std::string message
) {
    ScalarRead result;
    result.pid = pid;
    result.byte_count = byte_count;
    result.status = std::move(status);
    result.message = std::move(message);
    return result;
}

ScalarPatch ErrorScalarPatch(
    int pid,
    std::size_t byte_count,
    std::string status,
    std::string message,
    ProcessIdentity identity = {},
    std::uint64_t before_value_bits = 0,
    std::uint64_t after_value_bits = 0
) {
    ScalarPatch result;
    result.pid = pid;
    result.byte_count = byte_count;
    result.status = std::move(status);
    result.message = std::move(message);
    result.identity = std::move(identity);
    result.before_value_bits = before_value_bits;
    result.after_value_bits = after_value_bits;
    return result;
}

PagemapRead ErrorPagemapRead(
    int pid,
    std::uint64_t address,
    std::string status,
    std::string message
) {
    PagemapRead result;
    result.pid = pid;
    result.address = address;
    result.status = std::move(status);
    result.message = std::move(message);
    return result;
}

ScalarRead ReadTargetScalar(int pid, std::uint64_t address, std::size_t byte_count) {
    if (pid <= 0) {
        return ErrorScalarRead(pid, byte_count, "INVALID_PID", "PID must be positive");
    }
    if (address == 0U || address > kMaxJavaLong) {
        return ErrorScalarRead(pid, byte_count, "INVALID_ADDRESS", "Address must be positive");
    }
    if (byte_count != 4U && byte_count != 8U) {
        return ErrorScalarRead(pid, byte_count, "INVALID_SIZE", "Only 4-byte and 8-byte reads are supported");
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorScalarRead(
            pid,
            byte_count,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process is not running" : "Process stat is unreadable"
        );
    }

    switch (ValidateReadableRange(pid, address, byte_count)) {
        case ReadableRangeStatus::READABLE:
            break;
        case ReadableRangeStatus::TARGET_NOT_RUNNING:
            return ErrorScalarRead(pid, byte_count, "TARGET_NOT_RUNNING", "Target process is not running");
        case ReadableRangeStatus::MAPS_UNREADABLE:
            return ErrorScalarRead(pid, byte_count, "MAPS_UNREADABLE", "Process maps are unreadable");
        case ReadableRangeStatus::ADDRESS_NOT_READABLE:
            return ErrorScalarRead(
                pid,
                byte_count,
                "ADDRESS_NOT_READABLE",
                "Requested scalar crosses or falls outside a readable mapping"
            );
    }

    std::array<unsigned char, kMaxScalarReadBytes> memory{};
    errno = 0;
    const ssize_t memory_count = ReadProcessMemory(
        pid,
        address,
        memory.data(),
        byte_count
    );
    const int read_error = errno;

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error)) {
        return ErrorScalarRead(
            pid,
            byte_count,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process exited during scalar read" :
                "Process stat became unreadable during scalar read"
        );
    }
    if (final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorScalarRead(pid, byte_count, "TARGET_CHANGED", "PID identity changed during scalar read");
    }
    if (memory_count < 0) {
        return ErrorScalarRead(
            pid,
            byte_count,
            "READ_FAILED",
            read_error == 0 ? "Target memory read failed" : std::strerror(read_error)
        );
    }
    if (static_cast<std::size_t>(memory_count) != byte_count) {
        return ErrorScalarRead(pid, byte_count, "PARTIAL_READ", "Target memory read was incomplete");
    }

    ScalarRead result;
    result.pid = pid;
    result.identity = std::move(initial_identity);
    result.byte_count = byte_count;
    for (std::size_t index = 0; index < byte_count; ++index) {
        result.value_bits |= static_cast<std::uint64_t>(memory[index]) << (index * 8U);
    }
    return result;
}

std::uint64_t DecodeScalarBits(
    const std::array<unsigned char, kMaxScalarPatchBytes>& bytes,
    std::size_t byte_count
) {
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < byte_count; ++index) {
        value |= static_cast<std::uint64_t>(bytes[index]) << (index * 8U);
    }
    return value;
}

std::array<unsigned char, kMaxScalarPatchBytes> EncodeScalarBits(
    std::uint64_t value,
    std::size_t byte_count
) {
    std::array<unsigned char, kMaxScalarPatchBytes> bytes{};
    for (std::size_t index = 0; index < byte_count; ++index) {
        bytes[index] = static_cast<unsigned char>((value >> (index * 8U)) & 0xffU);
    }
    return bytes;
}

ScalarPatch PatchTargetScalar(
    int pid,
    std::uint64_t address,
    std::size_t byte_count,
    std::string_view expected_start_time_ticks,
    std::uint64_t expected_value_bits,
    std::uint64_t desired_value_bits,
    bool require_writable_mapping,
    bool require_executable_mapping
) {
    if (pid <= 0) {
        return ErrorScalarPatch(pid, byte_count, "INVALID_PID", "PID must be positive");
    }
    if (address == 0U || address > kMaxJavaLong) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "INVALID_ADDRESS",
            "Address must be positive"
        );
    }
    if (byte_count != 4U && byte_count != 8U) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "INVALID_SIZE",
            "Only 4-byte and 8-byte typed patches are supported"
        );
    }
    if (require_executable_mapping && (address & 0x3U) != 0U) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "INVALID_ADDRESS",
            "Typed executable patch address must be 4-byte aligned"
        );
    }
    if (expected_start_time_ticks.empty() || expected_start_time_ticks.size() > 32U ||
        !std::all_of(
            expected_start_time_ticks.begin(),
            expected_start_time_ticks.end(),
            [](unsigned char character) { return character >= '0' && character <= '9'; }
        )) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "INVALID_IDENTITY",
            "Pinned process identity is invalid"
        );
    }
    if (byte_count == 4U &&
        ((expected_value_bits >> 32U) != 0U || (desired_value_bits >> 32U) != 0U)) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "INVALID_SIZE",
            "32-bit patch values must not contain upper bits"
        );
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process is not running" :
                "Process stat is unreadable"
        );
    }
    if (initial_identity.start_time_ticks != expected_start_time_ticks) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "TARGET_CHANGED",
            "PID identity does not match the pinned target",
            initial_identity
        );
    }

    switch (ValidateReadableRange(
        pid,
        address,
        byte_count,
        require_writable_mapping,
        require_executable_mapping
    )) {
        case ReadableRangeStatus::READABLE:
            break;
        case ReadableRangeStatus::TARGET_NOT_RUNNING:
            return ErrorScalarPatch(
                pid,
                byte_count,
                "TARGET_NOT_RUNNING",
                "Target process is not running",
                initial_identity
            );
        case ReadableRangeStatus::MAPS_UNREADABLE:
            return ErrorScalarPatch(
                pid,
                byte_count,
                "MAPS_UNREADABLE",
                "Process maps are unreadable",
                initial_identity
            );
        case ReadableRangeStatus::ADDRESS_NOT_READABLE:
            return ErrorScalarPatch(
                pid,
                byte_count,
                "ADDRESS_NOT_READABLE",
                require_executable_mapping ?
                    "Typed code patch is outside a readable executable mapping" :
                    require_writable_mapping ?
                        "Typed data patch is outside a readable writable mapping" :
                        "Typed patch crosses or falls outside a readable mapping",
                initial_identity
            );
    }

    std::array<unsigned char, kMaxScalarPatchBytes> before_bytes{};
    errno = 0;
    const ssize_t before_count = ReadProcessMemory(
        pid,
        address,
        before_bytes.data(),
        byte_count
    );
    const int before_error = errno;

    ProcessIdentity post_read_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &post_read_identity, &identity_error)) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target exited during patch preflight" :
                "Process stat became unreadable during patch preflight",
            initial_identity
        );
    }
    if (post_read_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "TARGET_CHANGED",
            "PID identity changed during patch preflight",
            initial_identity
        );
    }
    if (before_count < 0) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "READ_FAILED",
            before_error == 0 ? "Target memory preflight read failed" :
                std::strerror(before_error),
            initial_identity
        );
    }
    if (static_cast<std::size_t>(before_count) != byte_count) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "PARTIAL_READ",
            "Target memory preflight read was incomplete",
            initial_identity
        );
    }

    const std::uint64_t before_bits = DecodeScalarBits(before_bytes, byte_count);
    if (before_bits == desired_value_bits) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "ALREADY_APPLIED",
            "Target scalar already has the requested value",
            initial_identity,
            before_bits,
            before_bits
        );
    }
    if (before_bits != expected_value_bits) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "EXPECTED_VALUE_MISMATCH",
            "Target scalar does not match the profile precondition",
            initial_identity,
            before_bits,
            before_bits
        );
    }

    const auto desired_bytes = EncodeScalarBits(desired_value_bits, byte_count);
    errno = 0;
    const ssize_t write_count = WriteProcessMemory(
        pid,
        address,
        desired_bytes.data(),
        byte_count
    );
    const int write_error = errno;
    if (write_count < 0) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "WRITE_FAILED",
            write_error == 0 ? "Target memory write failed" : std::strerror(write_error),
            initial_identity,
            before_bits,
            before_bits
        );
    }
    if (static_cast<std::size_t>(write_count) != byte_count) {
        ProcessIdentity partial_write_identity;
        identity_error = 0;
        if (!ParseProcessIdentity(pid, &partial_write_identity, &identity_error)) {
            return ErrorScalarPatch(
                pid,
                byte_count,
                identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
                identity_error == ENOENT ? "Target exited after a partial typed patch" :
                    "Process stat became unreadable after a partial typed patch",
                initial_identity,
                before_bits,
                before_bits
            );
        }
        if (partial_write_identity.start_time_ticks != initial_identity.start_time_ticks) {
            return ErrorScalarPatch(
                pid,
                byte_count,
                "TARGET_CHANGED",
                "PID identity changed after a partial typed patch",
                initial_identity,
                before_bits,
                before_bits
            );
        }
        const auto rollback_bytes = EncodeScalarBits(before_bits, byte_count);
        const ssize_t rollback_count = WriteProcessMemory(
            pid,
            address,
            rollback_bytes.data(),
            byte_count
        );
        std::array<unsigned char, kMaxScalarPatchBytes> rollback_verify_bytes{};
        const ssize_t rollback_verify_count = rollback_count == static_cast<ssize_t>(byte_count)
            ? ReadProcessMemory(
                pid,
                address,
                rollback_verify_bytes.data(),
                byte_count
            )
            : -1;
        const bool rollback_verified =
            rollback_verify_count == static_cast<ssize_t>(byte_count) &&
            DecodeScalarBits(rollback_verify_bytes, byte_count) == before_bits;
        return ErrorScalarPatch(
            pid,
            byte_count,
            rollback_verified ? "PARTIAL_WRITE" : "ROLLBACK_FAILED",
            rollback_verified ?
                "Target memory write was incomplete and the original scalar was restored" :
                "Target memory write was incomplete and rollback failed",
            initial_identity,
            before_bits,
            before_bits
        );
    }

    ProcessIdentity post_write_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &post_write_identity, &identity_error)) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target exited during typed patch" :
                "Process stat became unreadable during typed patch",
            initial_identity,
            before_bits,
            desired_value_bits
        );
    }
    if (post_write_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "TARGET_CHANGED",
            "PID identity changed during typed patch",
            initial_identity,
            before_bits,
            desired_value_bits
        );
    }

    std::array<unsigned char, kMaxScalarPatchBytes> verify_bytes{};
    errno = 0;
    const ssize_t verify_count = ReadProcessMemory(
        pid,
        address,
        verify_bytes.data(),
        byte_count
    );
    const std::uint64_t verify_bits = verify_count == static_cast<ssize_t>(byte_count)
        ? DecodeScalarBits(verify_bytes, byte_count)
        : 0U;
    if (verify_count != static_cast<ssize_t>(byte_count) || verify_bits != desired_value_bits) {
        ProcessIdentity pre_rollback_identity;
        identity_error = 0;
        if (!ParseProcessIdentity(pid, &pre_rollback_identity, &identity_error)) {
            return ErrorScalarPatch(
                pid,
                byte_count,
                identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
                identity_error == ENOENT ? "Target exited before patch rollback" :
                    "Process stat became unreadable before patch rollback",
                initial_identity,
                before_bits,
                verify_bits
            );
        }
        if (pre_rollback_identity.start_time_ticks != initial_identity.start_time_ticks) {
            return ErrorScalarPatch(
                pid,
                byte_count,
                "TARGET_CHANGED",
                "PID identity changed before patch rollback",
                initial_identity,
                before_bits,
                verify_bits
            );
        }
        const auto rollback_bytes = EncodeScalarBits(before_bits, byte_count);
        const ssize_t rollback_count = WriteProcessMemory(
            pid,
            address,
            rollback_bytes.data(),
            byte_count
        );
        std::array<unsigned char, kMaxScalarPatchBytes> rollback_verify_bytes{};
        const ssize_t rollback_verify_count = rollback_count == static_cast<ssize_t>(byte_count)
            ? ReadProcessMemory(
                pid,
                address,
                rollback_verify_bytes.data(),
                byte_count
            )
            : -1;
        const bool rollback_verified =
            rollback_verify_count == static_cast<ssize_t>(byte_count) &&
            DecodeScalarBits(rollback_verify_bytes, byte_count) == before_bits;
        return ErrorScalarPatch(
            pid,
            byte_count,
            rollback_verified ? "VERIFY_FAILED" : "ROLLBACK_FAILED",
            rollback_verified ?
                "Typed patch verification failed and the original scalar was restored" :
                "Typed patch verification and rollback failed",
            initial_identity,
            before_bits,
            rollback_verified ? before_bits : verify_bits
        );
    }

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error)) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target exited during patch verification" :
                "Process stat became unreadable during patch verification",
            initial_identity,
            before_bits,
            verify_bits
        );
    }
    if (final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorScalarPatch(
            pid,
            byte_count,
            "TARGET_CHANGED",
            "PID identity changed during patch verification",
            initial_identity,
            before_bits,
            verify_bits
        );
    }

    ScalarPatch result;
    result.pid = pid;
    result.identity = std::move(initial_identity);
    result.byte_count = byte_count;
    result.before_value_bits = before_bits;
    result.after_value_bits = verify_bits;
    return result;
}

PagemapRead ReadTargetPagemapEntry(int pid, std::uint64_t address) {
    if (pid <= 0) {
        return ErrorPagemapRead(pid, address, "INVALID_PID", "PID must be positive");
    }
    if (address == 0U || address > kMaxJavaLong) {
        return ErrorPagemapRead(pid, address, "INVALID_ADDRESS", "Address must be positive");
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorPagemapRead(
            pid,
            address,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process is not running" : "Process stat is unreadable"
        );
    }

    const long page_size_value = sysconf(_SC_PAGESIZE);
    if (page_size_value <= 0) {
        return ErrorPagemapRead(
            pid,
            address,
            "PAGE_SIZE_UNAVAILABLE",
            "System page size is unavailable"
        );
    }
    const std::uint64_t page_size = static_cast<std::uint64_t>(page_size_value);
    const std::uint64_t page_index = address / page_size;
    const std::uint64_t max_offset =
        static_cast<std::uint64_t>(std::numeric_limits<off64_t>::max());
    if (page_index > max_offset / kPagemapEntryBytes) {
        return ErrorPagemapRead(
            pid,
            address,
            "OFFSET_OUT_OF_RANGE",
            "Pagemap file offset exceeds off64_t"
        );
    }
    const off64_t file_offset = static_cast<off64_t>(page_index * kPagemapEntryBytes);

    const int fd = open(ProcPath(pid, "pagemap").c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return ErrorPagemapRead(
            pid,
            address,
            errno == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            errno == ENOENT ? "Target process is not running" : "Process pagemap is unreadable"
        );
    }

    std::array<unsigned char, kPagemapEntryBytes> entry_bytes{};
    ssize_t count;
    do {
        count = pread64(fd, entry_bytes.data(), entry_bytes.size(), file_offset);
    } while (count < 0 && errno == EINTR);
    const int read_error = errno;
    close(fd);

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error)) {
        return ErrorPagemapRead(
            pid,
            address,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process exited during pagemap read" :
                "Process stat became unreadable during pagemap read"
        );
    }
    if (final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorPagemapRead(
            pid,
            address,
            "TARGET_CHANGED",
            "PID identity changed during pagemap read"
        );
    }
    if (count < 0) {
        return ErrorPagemapRead(
            pid,
            address,
            "READ_FAILED",
            read_error == 0 ? "Pagemap read failed" : std::strerror(read_error)
        );
    }
    if (static_cast<std::size_t>(count) != entry_bytes.size()) {
        return ErrorPagemapRead(
            pid,
            address,
            "PARTIAL_READ",
            "Pagemap entry read was incomplete"
        );
    }

    PagemapRead result;
    result.pid = pid;
    result.identity = std::move(initial_identity);
    result.address = address;
    result.page_size = page_size;
    for (std::size_t index = 0; index < entry_bytes.size(); ++index) {
        result.entry_bits |= static_cast<std::uint64_t>(entry_bytes[index]) << (index * 8U);
    }
    if ((result.entry_bits & kPagemapPresentBit) == 0U) {
        result.status = "PAGE_NOT_PRESENT";
        result.message = "Virtual page is not present";
    }
    return result;
}

Inspection InspectTarget(int pid) {
    if (pid <= 0) {
        return ErrorInspection(pid, "INVALID_PID", "PID must be positive");
    }

    int identity_error = 0;
    ProcessIdentity initial_identity;
    if (!ParseProcessIdentity(pid, &initial_identity, &identity_error)) {
        return ErrorInspection(
            pid,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process is not running" : "Process stat is unreadable"
        );
    }

    const std::string maps_path = ProcPath(pid, "maps");
    errno = 0;
    std::ifstream maps(maps_path);
    if (!maps.is_open()) {
        const int maps_error = errno;
        return ErrorInspection(
            pid,
            maps_error == ENOENT ? "TARGET_NOT_RUNNING" : "MAPS_UNREADABLE",
            maps_error == ENOENT ? "Target process is not running" : "Process maps are unreadable"
        );
    }

    Inspection result;
    result.pid = pid;
    result.identity = initial_identity;
    std::unordered_map<std::string, std::size_t> module_indices;
    std::string line;
    std::size_t maps_bytes = 0;
    while (std::getline(maps, line)) {
        maps_bytes += line.size() + 1U;
        if (maps_bytes > kMaxMapsBytes || result.region_count >= kMaxMapsLines) {
            result.truncated = true;
            break;
        }
        if (line.size() > kMaxMapsLineBytes) {
            result.truncated = true;
            continue;
        }

        UpdateFingerprint(&result.maps_fingerprint, line);
        MapRegion region;
        if (!ParseMapRegion(line, &region)) {
            continue;
        }

        ++result.region_count;
        const std::uint64_t region_bytes = region.end - region.start;
        if (region.readable) {
            result.readable_bytes = SaturatingAdd(result.readable_bytes, region_bytes);
        }
        if (!IsFileBackedPath(region.path)) {
            continue;
        }

        auto index = module_indices.find(region.path);
        if (index == module_indices.end()) {
            const std::size_t next_index = result.modules.size();
            module_indices.emplace(region.path, next_index);
            result.modules.push_back(ModuleSummary{
                .path = region.path,
                .name = ModuleName(region.path),
                .origin = ModuleOrigin(region.path),
            });
            index = module_indices.find(region.path);
        }

        ModuleSummary& module = result.modules[index->second];
        ++module.region_count;
        module.mapped_bytes = SaturatingAdd(module.mapped_bytes, region_bytes);
        if (region.readable) {
            module.readable_bytes = SaturatingAdd(module.readable_bytes, region_bytes);
        }
        module.executable = module.executable || region.executable;
        if (!module.has_readable_zero_offset && region.readable && region.file_offset == 0U) {
            module.has_readable_zero_offset = true;
            module.readable_zero_start = region.start;
        }
    }

    if (maps.bad()) {
        return ErrorInspection(pid, "MAPS_UNREADABLE", "Process maps changed while reading");
    }
    if (result.region_count == 0U) {
        return ErrorInspection(pid, "MAPS_EMPTY", "No valid map regions were found");
    }

    ProcessIdentity final_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &final_identity, &identity_error)) {
        return ErrorInspection(
            pid,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process exited" : "Process stat became unreadable"
        );
    }
    if (final_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorInspection(pid, "TARGET_CHANGED", "PID identity changed during inspection");
    }

    result.module_count = result.modules.size();
    std::size_t file_checks = 0;
    for (ModuleSummary& module : result.modules) {
        if (!module.has_readable_zero_offset) {
            continue;
        }
        if (file_checks >= kMaxFileHeaderChecks) {
            result.truncated = true;
            break;
        }
        ++file_checks;
        std::array<unsigned char, 4> header{};
        module.file_readable = ReadFileHeader(module.path, &header);
        if (module.file_readable) {
            ++result.readable_file_count;
            module.elf_file = IsElfHeader(header);
            if (module.elf_file) {
                ++result.elf_header_count;
            }
        }

        std::array<unsigned char, kMemoryProbeBytes> memory{};
        const ssize_t memory_count = ReadProcessMemory(
            pid,
            module.readable_zero_start,
            memory.data(),
            memory.size()
        );
        if (memory_count > 0) {
            module.memory_readable = true;
            module.memory_read_bytes = static_cast<std::size_t>(memory_count);
            ++result.memory_readable_module_count;
            result.memory_read_bytes = SaturatingAdd(
                result.memory_read_bytes,
                module.memory_read_bytes
            );
            if (memory_count >= 4) {
                std::array<unsigned char, 4> memory_header{
                    memory[0], memory[1], memory[2], memory[3],
                };
                module.memory_elf = IsElfHeader(memory_header);
                if (module.memory_elf) {
                    ++result.memory_elf_header_count;
                }
            }
        }
    }

    ProcessIdentity post_probe_identity;
    identity_error = 0;
    if (!ParseProcessIdentity(pid, &post_probe_identity, &identity_error)) {
        return ErrorInspection(
            pid,
            identity_error == ENOENT ? "TARGET_NOT_RUNNING" : "PROC_UNREADABLE",
            identity_error == ENOENT ? "Target process exited during memory probe" :
                "Process stat became unreadable during memory probe"
        );
    }
    if (post_probe_identity.start_time_ticks != initial_identity.start_time_ticks) {
        return ErrorInspection(pid, "TARGET_CHANGED", "PID identity changed during memory probe");
    }

    std::sort(result.modules.begin(), result.modules.end(), [](const ModuleSummary& left, const ModuleSummary& right) {
        const int left_origin = OriginRank(left.origin);
        const int right_origin = OriginRank(right.origin);
        if (left_origin != right_origin) return left_origin < right_origin;
        if (left.executable != right.executable) return left.executable > right.executable;
        if (left.readable_bytes != right.readable_bytes) return left.readable_bytes > right.readable_bytes;
        return left.name < right.name;
    });
    if (result.modules.size() > kMaxOutputModules) {
        result.modules.resize(kMaxOutputModules);
        result.truncated = true;
    }
    return result;
}

std::string InspectionJson(const Inspection& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\"" << JsonEscape(result.identity.start_time_ticks)
           << "\",\"regionCount\":" << result.region_count
           << ",\"moduleCount\":" << result.module_count
           << ",\"readableBytes\":" << result.readable_bytes
           << ",\"readableFileCount\":" << result.readable_file_count
           << ",\"elfHeaderCount\":" << result.elf_header_count
           << ",\"memoryReadableModuleCount\":" << result.memory_readable_module_count
           << ",\"memoryReadBytes\":" << result.memory_read_bytes
           << ",\"memoryElfHeaderCount\":" << result.memory_elf_header_count
           << ",\"mapsFingerprint\":\""
           << std::hex << std::setw(16) << std::setfill('0') << result.maps_fingerprint << std::dec
           << "\",\"truncated\":" << (result.truncated ? "true" : "false")
           << ",\"message\":\"" << JsonEscape(result.message) << "\",\"modules\":[";

    for (std::size_t index = 0; index < result.modules.size(); ++index) {
        if (index != 0U) {
            output << ',';
        }
        const ModuleSummary& module = result.modules[index];
        output << "{\"name\":\"" << JsonEscape(module.name)
               << "\",\"path\":\"" << JsonEscape(module.path)
               << "\",\"origin\":\"" << JsonEscape(module.origin)
               << "\",\"regionCount\":" << module.region_count
               << ",\"mappedBytes\":" << module.mapped_bytes
               << ",\"readableBytes\":" << module.readable_bytes
               << ",\"executable\":" << (module.executable ? "true" : "false")
               << ",\"fileReadable\":" << (module.file_readable ? "true" : "false")
               << ",\"elfFile\":" << (module.elf_file ? "true" : "false")
               << ",\"memoryReadable\":" << (module.memory_readable ? "true" : "false")
               << ",\"memoryReadBytes\":" << module.memory_read_bytes
               << ",\"memoryElf\":" << (module.memory_elf ? "true" : "false")
               << ",\"loadBaseHex\":\""
               << std::hex << std::setw(16) << std::setfill('0')
               << module.readable_zero_start << std::dec << "\""
               << '}';
    }
    output << "]}";
    return output.str();
}

std::string ScalarReadJson(const ScalarRead& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\"" << JsonEscape(result.identity.start_time_ticks)
           << "\",\"byteCount\":" << result.byte_count
           << ",\"valueHex\":\""
           << std::hex << std::setw(static_cast<int>(result.byte_count * 2U))
           << std::setfill('0') << result.value_bits << std::dec
           << "\",\"message\":\"" << JsonEscape(result.message) << "\"}";
    return output.str();
}

std::string ScalarPatchJson(const ScalarPatch& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\""
           << JsonEscape(result.identity.start_time_ticks)
           << "\",\"byteCount\":" << result.byte_count;
    if (result.byte_count == 4U || result.byte_count == 8U) {
        output << ",\"beforeValueHex\":\""
               << std::hex << std::setw(static_cast<int>(result.byte_count * 2U))
               << std::setfill('0') << result.before_value_bits
               << "\",\"afterValueHex\":\""
               << std::setw(static_cast<int>(result.byte_count * 2U))
               << std::setfill('0') << result.after_value_bits << std::dec << '"';
    }
    output << ",\"message\":\"" << JsonEscape(result.message) << "\"}";
    return output.str();
}

std::string PagemapReadJson(const PagemapRead& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\"" << JsonEscape(result.identity.start_time_ticks)
           << "\",\"addressHex\":\""
           << std::hex << std::setw(16) << std::setfill('0') << result.address
           << "\",\"pageSize\":" << std::dec << result.page_size
           << ",\"entryHex\":\""
           << std::hex << std::setw(16) << std::setfill('0') << result.entry_bits << std::dec
           << "\",\"message\":\"" << JsonEscape(result.message) << "\"}";
    return output.str();
}

std::string BytesHex(const std::vector<unsigned char>& bytes) {
    static constexpr char kHexDigits[] = "0123456789abcdef";
    std::string output;
    output.resize(bytes.size() * 2U);
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        output[index * 2U] = kHexDigits[(bytes[index] >> 4U) & 0x0fU];
        output[index * 2U + 1U] = kHexDigits[bytes[index] & 0x0fU];
    }
    return output;
}

std::string MemoryRegionBatchJson(const MemoryRegionBatch& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\""
           << JsonEscape(result.identity.start_time_ticks)
           << "\",\"mapsFingerprint\":\"";
    if (result.has_maps_fingerprint) {
        output << std::hex << std::setw(16) << std::setfill('0')
               << result.maps_fingerprint << std::dec;
    }
    output << "\""
           << ",\"requestedCount\":" << result.requested_count
           << ",\"completedCount\":" << result.completed_count;
    if (result.failed_index >= 0) {
        output << ",\"failedIndex\":" << result.failed_index
               << ",\"failedAddressHex\":\""
               << std::hex << std::setw(16) << std::setfill('0')
               << result.failed_address << std::dec << '"';
    }
    output << ",\"message\":\"" << JsonEscape(result.message) << "\",\"regions\":[";
    for (std::size_t index = 0; index < result.regions.size(); ++index) {
        if (index != 0U) {
            output << ',';
        }
        const MemoryRegionRead& region = result.regions[index];
        output << "{\"addressHex\":\""
               << std::hex << std::setw(16) << std::setfill('0') << region.address
               << std::dec << "\",\"byteCount\":" << region.bytes.size()
               << ",\"bytesHex\":\"" << BytesHex(region.bytes) << "\"}";
    }
    output << "]}";
    return output.str();
}

std::string U32BatchJson(const U32Batch& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"operation\":\"" << JsonEscape(result.operation)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\""
           << JsonEscape(result.identity.start_time_ticks)
           << "\",\"mapsFingerprint\":\"";
    if (result.has_maps_fingerprint) {
        output << std::hex << std::setw(16) << std::setfill('0')
               << result.maps_fingerprint << std::dec;
    }
    output << "\""
           << ",\"requestedCount\":" << result.requested_count
           << ",\"completedCount\":" << result.completed_count;
    if (result.failed_index >= 0) {
        output << ",\"failedIndex\":" << result.failed_index
               << ",\"failedAddressHex\":\""
               << std::hex << std::setw(16) << std::setfill('0')
               << result.failed_address << std::dec << '"';
    }
    if (result.has_expected_value) {
        output << ",\"expectedValueHex\":\""
               << std::hex << std::setw(8) << std::setfill('0')
               << result.expected_value << std::dec << '"';
    }
    if (result.has_observed_value) {
        output << ",\"observedValueHex\":\""
               << std::hex << std::setw(8) << std::setfill('0')
               << result.observed_value << std::dec << '"';
    }
    if (result.has_guard_value) {
        output << ",\"guardValueHex\":\""
               << std::hex << std::setw(8) << std::setfill('0')
               << result.guard_value << std::dec << '"';
    }
    output << ",\"message\":\"" << JsonEscape(result.message) << "\"}";
    return output.str();
}

std::string AntiFlashCycleJson(const AntiFlashCycle& result) {
    std::ostringstream output;
    output << "{\"status\":\"" << JsonEscape(result.status)
           << "\",\"pid\":" << result.pid
           << ",\"processStartTimeTicks\":\""
           << JsonEscape(result.identity.start_time_ticks)
           << "\",\"mapsFingerprint\":\"";
    if (result.has_maps_fingerprint) {
        output << std::hex << std::setw(16) << std::setfill('0')
               << result.maps_fingerprint << std::dec;
    }
    output << "\",\"requestedCount\":" << result.requested_count
           << ",\"completedCount\":" << result.completed_count
           << ",\"writeAttempted\":" << (result.write_attempted ? "true" : "false");
    if (result.failed_index >= 0) {
        output << ",\"failedIndex\":" << result.failed_index
               << ",\"failedAddressHex\":\""
               << std::hex << std::setw(16) << std::setfill('0')
               << result.failed_address << std::dec << '"';
    }
    if (result.bss_address != 0U) {
        output << ",\"bssAddressHex\":\""
               << std::hex << std::setw(16) << std::setfill('0')
               << result.bss_address << std::dec << '"';
    }
    if (!result.bss_value.empty()) {
        output << ",\"bssValueHex\":\"" << BytesHex(result.bss_value) << '"';
    }
    output << ",\"message\":\"" << JsonEscape(result.message)
           << "\",\"codeRegionValues\":[";
    for (std::size_t index = 0; index < result.code_region_values.size(); ++index) {
        if (index != 0U) output << ',';
        output << '"' << BytesHex(result.code_region_values[index]) << '"';
    }
    output << "]}";
    return output.str();
}

bool CopyLongArray(JNIEnv* env, jlongArray source, std::vector<jlong>* output) {
    if (source == nullptr || output == nullptr) {
        return false;
    }
    const jsize length = env->GetArrayLength(source);
    if (length < 0 || static_cast<std::size_t>(length) > kMaxMemoryBatchItems) {
        return false;
    }
    output->resize(static_cast<std::size_t>(length));
    if (length != 0) {
        env->GetLongArrayRegion(source, 0, length, output->data());
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            output->clear();
            return false;
        }
    }
    return true;
}

bool CopyIntArray(JNIEnv* env, jintArray source, std::vector<jint>* output) {
    if (source == nullptr || output == nullptr) {
        return false;
    }
    const jsize length = env->GetArrayLength(source);
    if (length < 0 || static_cast<std::size_t>(length) > kMaxMemoryBatchItems) {
        return false;
    }
    output->resize(static_cast<std::size_t>(length));
    if (length != 0) {
        env->GetIntArrayRegion(source, 0, length, output->data());
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            output->clear();
            return false;
        }
    }
    return true;
}

bool CopyJniString(JNIEnv* env, jstring source, std::string* output) {
    if (source == nullptr || output == nullptr) {
        return false;
    }
    const char* characters = env->GetStringUTFChars(source, nullptr);
    if (characters == nullptr) {
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
        }
        return false;
    }
    output->assign(characters);
    env->ReleaseStringUTFChars(source, characters);
    return true;
}

bool CopyStringArray(JNIEnv* env, jobjectArray source, std::vector<std::string>* output) {
    if (source == nullptr || output == nullptr) return false;
    const jsize length = env->GetArrayLength(source);
    if (length < 0 || static_cast<std::size_t>(length) > kMaxMemoryBatchItems) return false;
    output->clear();
    output->reserve(static_cast<std::size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(source, index));
        std::string value;
        const bool copied = CopyJniString(env, item, &value);
        if (item != nullptr) env->DeleteLocalRef(item);
        if (!copied) {
            output->clear();
            return false;
        }
        output->push_back(std::move(value));
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeInspect(
    JNIEnv* env,
    jobject,
    jint pid
) {
    try {
        const std::string json = InspectionJson(InspectTarget(static_cast<int>(pid)));
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = InspectionJson(
            ErrorInspection(static_cast<int>(pid), "NATIVE_ERROR", error.what())
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = InspectionJson(
            ErrorInspection(static_cast<int>(pid), "NATIVE_ERROR", "Unknown native failure")
        );
        return env->NewStringUTF(json.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeReadScalar(
    JNIEnv* env,
    jobject,
    jint pid,
    jlong address,
    jint byte_count
) {
    try {
        const std::size_t requested_bytes = byte_count < 0
            ? 0U
            : static_cast<std::size_t>(byte_count);
        const ScalarRead result = address <= 0
            ? ErrorScalarRead(
                static_cast<int>(pid),
                requested_bytes,
                "INVALID_ADDRESS",
                "Address must be positive"
            )
            : ReadTargetScalar(
                static_cast<int>(pid),
                static_cast<std::uint64_t>(address),
                requested_bytes
            );
        const std::string json = ScalarReadJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = ScalarReadJson(
            ErrorScalarRead(
                static_cast<int>(pid),
                byte_count < 0 ? 0U : static_cast<std::size_t>(byte_count),
                "NATIVE_ERROR",
                error.what()
            )
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = ScalarReadJson(
            ErrorScalarRead(
                static_cast<int>(pid),
                byte_count < 0 ? 0U : static_cast<std::size_t>(byte_count),
                "NATIVE_ERROR",
                "Unknown native failure"
            )
        );
        return env->NewStringUTF(json.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeCompareExchangeScalar(
    JNIEnv* env,
    jobject,
    jint pid,
    jlong address,
    jint byte_count,
    jstring expected_start_time_ticks,
    jlong expected_value_bits,
    jlong desired_value_bits,
    jboolean require_writable_mapping,
    jboolean require_executable_mapping
) {
    try {
        const std::size_t requested_bytes = byte_count < 0
            ? 0U
            : static_cast<std::size_t>(byte_count);
        std::string expected_identity;
        if (expected_start_time_ticks != nullptr) {
            const char* identity_chars = env->GetStringUTFChars(expected_start_time_ticks, nullptr);
            if (identity_chars != nullptr) {
                expected_identity.assign(identity_chars);
                env->ReleaseStringUTFChars(expected_start_time_ticks, identity_chars);
            }
        }
        const ScalarPatch result = address <= 0
            ? ErrorScalarPatch(
                static_cast<int>(pid),
                requested_bytes,
                "INVALID_ADDRESS",
                "Address must be positive"
            )
            : PatchTargetScalar(
                static_cast<int>(pid),
                static_cast<std::uint64_t>(address),
                requested_bytes,
                expected_identity,
                static_cast<std::uint64_t>(expected_value_bits),
                static_cast<std::uint64_t>(desired_value_bits),
                require_writable_mapping == JNI_TRUE,
                require_executable_mapping == JNI_TRUE
            );
        const std::string json = ScalarPatchJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = ScalarPatchJson(
            ErrorScalarPatch(
                static_cast<int>(pid),
                byte_count < 0 ? 0U : static_cast<std::size_t>(byte_count),
                "NATIVE_ERROR",
                error.what()
            )
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = ScalarPatchJson(
            ErrorScalarPatch(
                static_cast<int>(pid),
                byte_count < 0 ? 0U : static_cast<std::size_t>(byte_count),
                "NATIVE_ERROR",
                "Unknown native failure"
            )
        );
        return env->NewStringUTF(json.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeReadPagemapEntry(
    JNIEnv* env,
    jobject,
    jint pid,
    jlong address
) {
    try {
        const PagemapRead result = address <= 0
            ? ErrorPagemapRead(
                static_cast<int>(pid),
                0U,
                "INVALID_ADDRESS",
                "Address must be positive"
            )
            : ReadTargetPagemapEntry(
                static_cast<int>(pid),
                static_cast<std::uint64_t>(address)
            );
        const std::string json = PagemapReadJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = PagemapReadJson(
            ErrorPagemapRead(
                static_cast<int>(pid),
                address > 0 ? static_cast<std::uint64_t>(address) : 0U,
                "NATIVE_ERROR",
                error.what()
            )
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = PagemapReadJson(
            ErrorPagemapRead(
                static_cast<int>(pid),
                address > 0 ? static_cast<std::uint64_t>(address) : 0U,
                "NATIVE_ERROR",
                "Unknown native failure"
            )
        );
        return env->NewStringUTF(json.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeReadMemoryRegions(
    JNIEnv* env,
    jobject,
    jint pid,
    jstring expected_start_time_ticks,
    jstring expected_maps_fingerprint,
    jlongArray addresses,
    jintArray byte_counts,
    jintArray mapping_flags
) {
    try {
        std::string expected_identity;
        std::string expected_fingerprint;
        std::vector<jlong> address_values;
        std::vector<jint> byte_count_values;
        std::vector<jint> flag_values;
        MemoryRegionBatch result;
        if (!CopyJniString(env, expected_start_time_ticks, &expected_identity) ||
            !CopyJniString(env, expected_maps_fingerprint, &expected_fingerprint) ||
            !CopyLongArray(env, addresses, &address_values) ||
            !CopyIntArray(env, byte_counts, &byte_count_values) ||
            !CopyIntArray(env, mapping_flags, &flag_values) ||
            address_values.size() != byte_count_values.size() ||
            address_values.size() != flag_values.size()) {
            const std::size_t count = address_values.size();
            result = ErrorMemoryRegionBatch(
                static_cast<int>(pid),
                count,
                "INVALID_REQUEST",
                "Memory region arrays are null, oversized, or have different lengths"
            );
        } else {
            std::vector<MemoryRegionRequest> requests;
            requests.reserve(address_values.size());
            for (std::size_t index = 0; index < address_values.size(); ++index) {
                requests.push_back(MemoryRegionRequest{
                    .address = address_values[index] > 0
                        ? static_cast<std::uint64_t>(address_values[index])
                        : 0U,
                    .byte_count = byte_count_values[index] > 0
                        ? static_cast<std::size_t>(byte_count_values[index])
                        : 0U,
                    .mapping_flags = static_cast<int>(flag_values[index]),
                });
            }
            result = ReadTargetMemoryRegions(
                static_cast<int>(pid), expected_identity, expected_fingerprint, requests
            );
        }
        const std::string json = MemoryRegionBatchJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = MemoryRegionBatchJson(
            ErrorMemoryRegionBatch(
                static_cast<int>(pid), 0U, "NATIVE_ERROR", error.what()
            )
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = MemoryRegionBatchJson(
            ErrorMemoryRegionBatch(
                static_cast<int>(pid), 0U, "NATIVE_ERROR", "Unknown native failure"
            )
        );
        return env->NewStringUTF(json.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeWriteVerifyU32Batch(
    JNIEnv* env,
    jobject,
    jint pid,
    jstring expected_start_time_ticks,
    jstring expected_maps_fingerprint,
    jstring operation,
    jlongArray addresses,
    jlongArray value_bits,
    jlongArray expected_current_value_bits,
    jintArray mapping_flags
) {
    try {
        std::string expected_identity;
        std::string expected_fingerprint;
        std::string operation_value;
        std::vector<jlong> address_values;
        std::vector<jlong> write_values;
        std::vector<jlong> expected_current_values;
        std::vector<jint> flag_values;
        U32Batch result;
        if (!CopyJniString(env, expected_start_time_ticks, &expected_identity) ||
            !CopyJniString(env, expected_maps_fingerprint, &expected_fingerprint) ||
            !CopyJniString(env, operation, &operation_value) ||
            !CopyLongArray(env, addresses, &address_values) ||
            !CopyLongArray(env, value_bits, &write_values) ||
            !CopyLongArray(env, expected_current_value_bits, &expected_current_values) ||
            !CopyIntArray(env, mapping_flags, &flag_values) ||
            address_values.size() != write_values.size() ||
            address_values.size() != expected_current_values.size() ||
            address_values.size() != flag_values.size()) {
            const std::size_t count = address_values.size();
            result = ErrorU32Batch(
                static_cast<int>(pid),
                count,
                operation_value.empty() ? "ITERATION" : operation_value,
                "INVALID_REQUEST",
                "U32 write arrays are null, oversized, or have different lengths"
            );
        } else {
            std::vector<U32WriteRequest> writes;
            writes.reserve(address_values.size());
            bool invalid_value = false;
            std::size_t invalid_value_index = 0;
            for (std::size_t index = 0; index < address_values.size(); ++index) {
                const std::uint64_t value = static_cast<std::uint64_t>(write_values[index]);
                const std::uint64_t expected_current =
                    static_cast<std::uint64_t>(expected_current_values[index]);
                if ((value >> 32U) != 0U || (expected_current >> 32U) != 0U) {
                    invalid_value = true;
                    invalid_value_index = index;
                    break;
                }
                writes.push_back(U32WriteRequest{
                    .address = address_values[index] > 0
                        ? static_cast<std::uint64_t>(address_values[index])
                        : 0U,
                    .value = static_cast<std::uint32_t>(value),
                    .expected_current_value = static_cast<std::uint32_t>(expected_current),
                    .has_expected_current_value = operation_value == "ROLLBACK",
                    .mapping_flags = static_cast<int>(flag_values[index]),
                });
            }
            if (invalid_value) {
                result = ErrorU32Batch(
                    static_cast<int>(pid),
                    address_values.size(),
                    operation_value,
                    "INVALID_SIZE",
                    "U32 write contains upper value bits",
                    {},
                    0,
                    static_cast<int>(invalid_value_index),
                    address_values[invalid_value_index] > 0
                        ? static_cast<std::uint64_t>(address_values[invalid_value_index])
                        : 0U
                );
            } else {
                result = WriteVerifyTargetU32Batch(
                    static_cast<int>(pid),
                    expected_identity,
                    expected_fingerprint,
                    operation_value,
                    writes
                );
            }
        }
        const std::string json = U32BatchJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = U32BatchJson(
            ErrorU32Batch(
                static_cast<int>(pid), 0U, "ITERATION", "NATIVE_ERROR", error.what()
            )
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = U32BatchJson(
            ErrorU32Batch(
                static_cast<int>(pid),
                0U,
                "ITERATION",
                "NATIVE_ERROR",
                "Unknown native failure"
            )
        );
        return env->NewStringUTF(json.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_dartcv_minix_root_nativeadapter_TargetNativeProbe_nativeRunAntiFlashCycle(
    JNIEnv* env,
    jobject,
    jint pid,
    jstring expected_start_time_ticks,
    jstring expected_maps_fingerprint,
    jlongArray region_addresses,
    jobjectArray original_hex,
    jobjectArray patch_hex,
    jlong bss_address,
    jlongArray write_addresses,
    jlongArray write_value_bits,
    jintArray write_mapping_flags
) {
    try {
        std::string expected_identity;
        std::string expected_fingerprint;
        std::vector<jlong> region_address_values;
        std::vector<std::string> original_values;
        std::vector<std::string> patch_values;
        std::vector<jlong> write_address_values;
        std::vector<jlong> write_values;
        std::vector<jint> flag_values;
        AntiFlashCycle result;
        if (!CopyJniString(env, expected_start_time_ticks, &expected_identity) ||
            !CopyJniString(env, expected_maps_fingerprint, &expected_fingerprint) ||
            !CopyLongArray(env, region_addresses, &region_address_values) ||
            !CopyStringArray(env, original_hex, &original_values) ||
            !CopyStringArray(env, patch_hex, &patch_values) ||
            !CopyLongArray(env, write_addresses, &write_address_values) ||
            !CopyLongArray(env, write_value_bits, &write_values) ||
            !CopyIntArray(env, write_mapping_flags, &flag_values) ||
            region_address_values.size() != original_values.size() ||
            region_address_values.size() != patch_values.size() ||
            write_address_values.size() != write_values.size() ||
            write_address_values.size() != flag_values.size()) {
            result = ErrorAntiFlashCycle(
                static_cast<int>(pid),
                write_address_values.size(),
                "INVALID_REQUEST",
                "Anti-flash cycle arrays are null, oversized, or have different lengths"
            );
        } else {
            std::vector<AntiFlashCycleRegion> regions;
            regions.reserve(region_address_values.size());
            bool invalid_region = false;
            for (std::size_t index = 0; index < region_address_values.size(); ++index) {
                AntiFlashCycleRegion region;
                region.address = region_address_values[index] > 0
                    ? static_cast<std::uint64_t>(region_address_values[index]) : 0U;
                if (!DecodeHexBytes(original_values[index], &region.original_bytes) ||
                    !DecodeHexBytes(patch_values[index], &region.patch_bytes)) {
                    invalid_region = true;
                    break;
                }
                regions.push_back(std::move(region));
            }
            std::vector<U32WriteRequest> writes;
            writes.reserve(write_address_values.size());
            bool invalid_write = false;
            for (std::size_t index = 0; !invalid_region && index < write_address_values.size(); ++index) {
                const std::uint64_t value = static_cast<std::uint64_t>(write_values[index]);
                if ((value >> 32U) != 0U) {
                    invalid_write = true;
                    break;
                }
                writes.push_back(U32WriteRequest{
                    .address = write_address_values[index] > 0
                        ? static_cast<std::uint64_t>(write_address_values[index]) : 0U,
                    .value = static_cast<std::uint32_t>(value),
                    .mapping_flags = static_cast<int>(flag_values[index]),
                });
            }
            if (invalid_region || invalid_write) {
                result = ErrorAntiFlashCycle(
                    static_cast<int>(pid),
                    write_address_values.size(),
                    "INVALID_REQUEST",
                    invalid_region ? "Anti-flash region hex is invalid" :
                        "Anti-flash write contains upper value bits"
                );
            } else {
                result = RunAntiFlashCycle(
                    static_cast<int>(pid),
                    expected_identity,
                    expected_fingerprint,
                    regions,
                    bss_address > 0 ? static_cast<std::uint64_t>(bss_address) : 0U,
                    writes
                );
            }
        }
        const std::string json = AntiFlashCycleJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) {
        const std::string json = AntiFlashCycleJson(
            ErrorAntiFlashCycle(
                static_cast<int>(pid), 0U, "NATIVE_ERROR", error.what()
            )
        );
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        const std::string json = AntiFlashCycleJson(
            ErrorAntiFlashCycle(
                static_cast<int>(pid), 0U, "NATIVE_ERROR", "Unknown native failure"
            )
        );
        return env->NewStringUTF(json.c_str());
    }
}
