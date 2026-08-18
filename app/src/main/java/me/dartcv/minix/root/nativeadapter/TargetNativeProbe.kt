package me.dartcv.minix.root.nativeadapter

import androidx.annotation.Keep
import org.json.JSONObject

internal enum class NativeProbeStatus {
    OK,
    INVALID_PID,
    TARGET_NOT_RUNNING,
    TARGET_CHANGED,
    PROC_UNREADABLE,
    MAPS_UNREADABLE,
    MAPS_EMPTY,
    NATIVE_UNAVAILABLE,
    INVALID_RESPONSE,
    NATIVE_ERROR,
    ;

    companion object {
        fun fromWireValue(value: String): NativeProbeStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal enum class NativeScalarReadStatus {
    OK,
    INVALID_PID,
    INVALID_ADDRESS,
    INVALID_SIZE,
    TARGET_NOT_RUNNING,
    TARGET_CHANGED,
    PROC_UNREADABLE,
    MAPS_UNREADABLE,
    ADDRESS_NOT_READABLE,
    READ_FAILED,
    PARTIAL_READ,
    NATIVE_UNAVAILABLE,
    INVALID_RESPONSE,
    NATIVE_ERROR,
    ;

    companion object {
        fun fromWireValue(value: String): NativeScalarReadStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal enum class NativePagemapReadStatus {
    OK,
    INVALID_PID,
    INVALID_ADDRESS,
    TARGET_NOT_RUNNING,
    TARGET_CHANGED,
    PROC_UNREADABLE,
    PAGE_SIZE_UNAVAILABLE,
    OFFSET_OUT_OF_RANGE,
    READ_FAILED,
    PARTIAL_READ,
    PAGE_NOT_PRESENT,
    NATIVE_UNAVAILABLE,
    INVALID_RESPONSE,
    NATIVE_ERROR,
    ;

    companion object {
        fun fromWireValue(value: String): NativePagemapReadStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal enum class NativeScalarPatchStatus {
    APPLIED,
    ALREADY_APPLIED,
    INVALID_PID,
    INVALID_ADDRESS,
    INVALID_SIZE,
    INVALID_IDENTITY,
    TARGET_NOT_RUNNING,
    TARGET_CHANGED,
    PROC_UNREADABLE,
    MAPS_UNREADABLE,
    ADDRESS_NOT_READABLE,
    READ_FAILED,
    PARTIAL_READ,
    EXPECTED_VALUE_MISMATCH,
    WRITE_FAILED,
    PARTIAL_WRITE,
    VERIFY_FAILED,
    ROLLBACK_FAILED,
    NATIVE_UNAVAILABLE,
    INVALID_RESPONSE,
    NATIVE_ERROR,
    ;

    companion object {
        fun fromWireValue(value: String): NativeScalarPatchStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal enum class NativeMemoryBatchStatus {
    OK,
    INVALID_PID,
    INVALID_IDENTITY,
    INVALID_REQUEST,
    INVALID_ADDRESS,
    INVALID_SIZE,
    TARGET_NOT_RUNNING,
    TARGET_CHANGED,
    PROC_UNREADABLE,
    MAPS_UNREADABLE,
    ADDRESS_NOT_READABLE,
    PROFILE_MISMATCH,
    MEM_OPEN_FAILED,
    READ_FAILED,
    PARTIAL_READ,
    WRITE_FAILED,
    PARTIAL_WRITE,
    VERIFY_FAILED,
    NATIVE_UNAVAILABLE,
    INVALID_RESPONSE,
    NATIVE_ERROR,
    ;

    companion object {
        fun fromWireValue(value: String): NativeMemoryBatchStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal enum class NativeU32BatchOperation {
    ITERATION,
    ROLLBACK,
}

internal enum class NativeModuleOrigin {
    APP,
    APEX,
    SYSTEM,
    VENDOR,
    PRODUCT,
    OTHER,
    ;

    companion object {
        fun fromWireValue(value: String): NativeModuleOrigin =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}

internal data class NativeModuleSummary(
    val name: String,
    val path: String,
    val origin: NativeModuleOrigin,
    val regionCount: Int,
    val mappedBytes: Long,
    val readableBytes: Long,
    val executable: Boolean,
    val fileReadable: Boolean,
    val elfFile: Boolean,
    val memoryReadable: Boolean,
    val memoryReadBytes: Int,
    val memoryElf: Boolean,
    val loadBase: Long,
)

internal data class NativeProbeResult(
    val status: NativeProbeStatus,
    val pid: Int,
    val processStartTimeTicks: String = "",
    val regionCount: Int = 0,
    val moduleCount: Int = 0,
    val readableBytes: Long = 0L,
    val readableFileCount: Int = 0,
    val elfHeaderCount: Int = 0,
    val memoryReadableModuleCount: Int = 0,
    val memoryReadBytes: Long = 0L,
    val memoryElfHeaderCount: Int = 0,
    val mapsFingerprint: String = "",
    val truncated: Boolean = false,
    val modules: List<NativeModuleSummary> = emptyList(),
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativeProbeStatus.OK
}

internal data class NativeScalarReadResult(
    val status: NativeScalarReadStatus,
    val pid: Int,
    val byteCount: Int,
    val processStartTimeTicks: String = "",
    val valueBits: Long? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativeScalarReadStatus.OK && valueBits != null

    val int32Value: Int?
        get() = valueBits?.takeIf { byteCount == Int.SIZE_BYTES }?.toInt()

    val int64Value: Long?
        get() = valueBits?.takeIf { byteCount == Long.SIZE_BYTES }

    val float32Value: Float?
        get() = int32Value?.let(Float::fromBits)
}

internal data class NativePagemapReadResult(
    val status: NativePagemapReadStatus,
    val pid: Int,
    val address: Long,
    val pageSize: Long = 0L,
    val processStartTimeTicks: String = "",
    val entryBits: Long? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativePagemapReadStatus.OK && entryBits != null

    val isPresent: Boolean
        get() = entryBits?.let { bits -> bits ushr 63 == 1L } == true

    val pageFrameNumber: Long?
        get() = entryBits
            ?.takeIf { isPresent }
            ?.and(PAGEMAP_PFN_MASK)

    private companion object {
        const val PAGEMAP_PFN_MASK = (1L shl 55) - 1L
    }
}

internal data class NativeScalarPatchResult(
    val status: NativeScalarPatchStatus,
    val pid: Int,
    val byteCount: Int,
    val processStartTimeTicks: String = "",
    val beforeValueBits: Long? = null,
    val afterValueBits: Long? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativeScalarPatchStatus.APPLIED ||
            status == NativeScalarPatchStatus.ALREADY_APPLIED
}

internal data class NativeMemoryRegionRequest(
    val address: Long,
    val byteCount: Int,
    val requireWritableMapping: Boolean = false,
    val requireExecutableMapping: Boolean = false,
)

internal data class NativeMemoryRegionRead(
    val address: Long,
    val bytes: ByteArray,
)

internal data class NativeMemoryRegionBatchResult(
    val status: NativeMemoryBatchStatus,
    val pid: Int,
    val processStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val requestedCount: Int = 0,
    val completedCount: Int = 0,
    val failedIndex: Int? = null,
    val failedAddress: Long? = null,
    val regions: List<NativeMemoryRegionRead> = emptyList(),
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativeMemoryBatchStatus.OK &&
            completedCount == requestedCount &&
            regions.size == requestedCount
}

internal data class NativeU32WriteRequest(
    val address: Long,
    val valueBits: Long,
    /** Required for rollback: the u32 that must still be present immediately before the write. */
    val expectedCurrentValueBits: Long? = null,
    val requireWritableMapping: Boolean = false,
    val requireExecutableMapping: Boolean = false,
)

internal data class NativeU32BatchResult(
    val status: NativeMemoryBatchStatus,
    val operation: NativeU32BatchOperation,
    val pid: Int,
    val processStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val requestedCount: Int = 0,
    val completedCount: Int = 0,
    val failedIndex: Int? = null,
    val failedAddress: Long? = null,
    val expectedValueBits: Long? = null,
    val observedValueBits: Long? = null,
    val guardValueBits: Long? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativeMemoryBatchStatus.OK && completedCount == requestedCount
}

internal data class NativeAntiFlashCycleRegion(
    val address: Long,
    val originalBytes: ByteArray,
    val patchBytes: ByteArray,
)

internal data class NativeAntiFlashCycleResult(
    val status: NativeMemoryBatchStatus,
    val pid: Int,
    val processStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val codeRegionValues: List<ByteArray> = emptyList(),
    val bssValue: ByteArray? = null,
    val requestedCount: Int = 0,
    val completedCount: Int = 0,
    val failedIndex: Int? = null,
    val failedAddress: Long? = null,
    val writeAttempted: Boolean = false,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == NativeMemoryBatchStatus.OK &&
            completedCount == requestedCount && writeAttempted
}

@Keep
internal object TargetNativeProbe {
    private const val LIBRARY_NAME = "minix_target_probe"
    private const val MAX_RESPONSE_CHARS = 256 * 1024
    private const val MAX_SCALAR_RESPONSE_CHARS = 4 * 1024
    private const val MAX_PAGEMAP_RESPONSE_CHARS = 4 * 1024
    private const val MAX_PATCH_RESPONSE_CHARS = 4 * 1024
    private const val MAX_MEMORY_BATCH_RESPONSE_CHARS = 768 * 1024
    private const val MAX_MESSAGE_CHARS = 256
    private const val MAX_MEMORY_BATCH_ITEMS = 64
    private const val MAX_MEMORY_REGION_BYTES = 64 * 1024
    private const val MAX_MEMORY_BATCH_BYTES = 256 * 1024
    private const val ANTI_FLASH_CODE_REGION_COUNT = 6
    private const val ANTI_FLASH_WRITE_COUNT = 17
    private const val MAPPING_FLAG_WRITABLE = 1
    private const val MAPPING_FLAG_EXECUTABLE = 2

    private val loadFailure: Throwable? by lazy {
        runCatching { System.loadLibrary(LIBRARY_NAME) }.exceptionOrNull()
    }

    fun warmUp(): Throwable? = loadFailure

    fun inspect(pid: Int): NativeProbeResult {
        if (pid <= 0) {
            return failure(NativeProbeStatus.INVALID_PID, pid, "PID must be positive")
        }

        loadFailure?.let { error ->
            return failure(
                status = NativeProbeStatus.NATIVE_UNAVAILABLE,
                pid = pid,
                message = error.message.orEmpty(),
            )
        }

        val payload = runCatching { nativeInspect(pid) }.getOrElse { error ->
            return failure(
                status = NativeProbeStatus.NATIVE_ERROR,
                pid = pid,
                message = error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_RESPONSE_CHARS) {
            return failure(NativeProbeStatus.INVALID_RESPONSE, pid, "Native response exceeded the limit")
        }

        return runCatching { decodeNativeProbePayload(payload, pid) }.getOrElse { error ->
            failure(
                status = NativeProbeStatus.INVALID_RESPONSE,
                pid = pid,
                message = error.message.orEmpty(),
            )
        }
    }

    fun readInt32(pid: Int, address: Long): NativeScalarReadResult =
        readScalar(pid, address, Int.SIZE_BYTES)

    fun readInt64(pid: Int, address: Long): NativeScalarReadResult =
        readScalar(pid, address, Long.SIZE_BYTES)

    fun readPagemapEntry(pid: Int, address: Long): NativePagemapReadResult {
        if (pid <= 0) {
            return pagemapFailure(
                NativePagemapReadStatus.INVALID_PID,
                pid,
                address,
                "PID must be positive",
            )
        }
        if (address <= 0L) {
            return pagemapFailure(
                NativePagemapReadStatus.INVALID_ADDRESS,
                pid,
                address,
                "Address must be positive",
            )
        }

        loadFailure?.let { error ->
            return pagemapFailure(
                status = NativePagemapReadStatus.NATIVE_UNAVAILABLE,
                pid = pid,
                address = address,
                message = error.message.orEmpty(),
            )
        }

        val payload = runCatching { nativeReadPagemapEntry(pid, address) }.getOrElse { error ->
            return pagemapFailure(
                status = NativePagemapReadStatus.NATIVE_ERROR,
                pid = pid,
                address = address,
                message = error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_PAGEMAP_RESPONSE_CHARS) {
            return pagemapFailure(
                NativePagemapReadStatus.INVALID_RESPONSE,
                pid,
                address,
                "Native pagemap response exceeded the limit",
            )
        }

        return runCatching {
            decodeNativePagemapReadPayload(payload, pid, address)
        }.getOrElse { error ->
            pagemapFailure(
                NativePagemapReadStatus.INVALID_RESPONSE,
                pid,
                address,
                error.message.orEmpty(),
            )
        }
    }

    fun compareExchangeInt32(
        pid: Int,
        address: Long,
        expectedStartTimeTicks: String,
        expectedValueBits: Long,
        desiredValueBits: Long,
        requireWritableMapping: Boolean = false,
        requireExecutableMapping: Boolean = false,
    ): NativeScalarPatchResult = compareExchangeScalar(
        pid = pid,
        address = address,
        byteCount = Int.SIZE_BYTES,
        expectedStartTimeTicks = expectedStartTimeTicks,
        expectedValueBits = expectedValueBits,
        desiredValueBits = desiredValueBits,
        requireWritableMapping = requireWritableMapping,
        requireExecutableMapping = requireExecutableMapping,
    )

    fun compareExchangeInt64(
        pid: Int,
        address: Long,
        expectedStartTimeTicks: String,
        expectedValueBits: Long,
        desiredValueBits: Long,
        requireWritableMapping: Boolean = false,
        requireExecutableMapping: Boolean = false,
    ): NativeScalarPatchResult = compareExchangeScalar(
        pid = pid,
        address = address,
        byteCount = Long.SIZE_BYTES,
        expectedStartTimeTicks = expectedStartTimeTicks,
        expectedValueBits = expectedValueBits,
        desiredValueBits = desiredValueBits,
        requireWritableMapping = requireWritableMapping,
        requireExecutableMapping = requireExecutableMapping,
    )

    /** Reads all requested regions through one pinned /proc/<pid>/mem descriptor. */
    fun readMemoryRegions(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        requests: List<NativeMemoryRegionRequest>,
    ): NativeMemoryRegionBatchResult {
        validateMemoryBatchIdentity(pid, expectedStartTimeTicks, expectedMapsFingerprint)?.let { return it }
        validateMemoryRegionRequests(pid, requests)?.let { return it }

        loadFailure?.let { error ->
            return memoryRegionBatchFailure(
                status = NativeMemoryBatchStatus.NATIVE_UNAVAILABLE,
                pid = pid,
                requestedCount = requests.size,
                message = error.message.orEmpty(),
            )
        }
        val payload = runCatching {
            nativeReadMemoryRegions(
                pid = pid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedMapsFingerprint = expectedMapsFingerprint,
                addresses = LongArray(requests.size) { requests[it].address },
                byteCounts = IntArray(requests.size) { requests[it].byteCount },
                mappingFlags = IntArray(requests.size) { requests[it].mappingFlags() },
            )
        }.getOrElse { error ->
            return memoryRegionBatchFailure(
                status = NativeMemoryBatchStatus.NATIVE_ERROR,
                pid = pid,
                requestedCount = requests.size,
                message = error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_MEMORY_BATCH_RESPONSE_CHARS) {
            return memoryRegionBatchFailure(
                status = NativeMemoryBatchStatus.INVALID_RESPONSE,
                pid = pid,
                requestedCount = requests.size,
                message = "Native memory batch response exceeded the limit",
            )
        }
        return runCatching {
            decodeNativeMemoryRegionBatchPayload(
                payload = payload,
                expectedPid = pid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedMapsFingerprint = expectedMapsFingerprint,
                expectedRequests = requests,
            )
        }.getOrElse { error ->
            memoryRegionBatchFailure(
                status = NativeMemoryBatchStatus.INVALID_RESPONSE,
                pid = pid,
                requestedCount = requests.size,
                message = error.message.orEmpty(),
            )
        }
    }

    /** Applies one ordered anti-flash iteration and verifies every u32 immediately. */
    fun runU32Iteration(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        writes: List<NativeU32WriteRequest>,
    ): NativeU32BatchResult = writeVerifyU32Batch(
        pid = pid,
        expectedStartTimeTicks = expectedStartTimeTicks,
        expectedMapsFingerprint = expectedMapsFingerprint,
        operation = NativeU32BatchOperation.ITERATION,
        writes = writes,
    )

    /** Restores an ordered u32 snapshot using the same pinned, verified batch path. */
    fun rollbackU32Batch(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        writes: List<NativeU32WriteRequest>,
    ): NativeU32BatchResult = writeVerifyU32Batch(
        pid = pid,
        expectedStartTimeTicks = expectedStartTimeTicks,
        expectedMapsFingerprint = expectedMapsFingerprint,
        operation = NativeU32BatchOperation.ROLLBACK,
        writes = writes,
    )

    /** Preflights six code regions and applies one 17-write cycle through one O_RDWR fd. */
    fun runAntiFlashCycle(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        regions: List<NativeAntiFlashCycleRegion>,
        bssAddress: Long,
        writes: List<NativeU32WriteRequest>,
    ): NativeAntiFlashCycleResult {
        validateAntiFlashCycle(
            pid,
            expectedStartTimeTicks,
            expectedMapsFingerprint,
            regions,
            bssAddress,
            writes,
        )?.let { return it }

        loadFailure?.let { error ->
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.NATIVE_UNAVAILABLE,
                pid,
                writes.size,
                error.message.orEmpty(),
            )
        }
        val payload = runCatching {
            nativeRunAntiFlashCycle(
                pid = pid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedMapsFingerprint = expectedMapsFingerprint,
                regionAddresses = LongArray(regions.size) { regions[it].address },
                originalHex = Array(regions.size) { regions[it].originalBytes.toLowerHex() },
                patchHex = Array(regions.size) { regions[it].patchBytes.toLowerHex() },
                bssAddress = bssAddress,
                writeAddresses = LongArray(writes.size) { writes[it].address },
                writeValueBits = LongArray(writes.size) { writes[it].valueBits },
                writeMappingFlags = IntArray(writes.size) { writes[it].mappingFlags() },
            )
        }.getOrElse { error ->
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.NATIVE_ERROR,
                pid,
                writes.size,
                error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_MEMORY_BATCH_RESPONSE_CHARS) {
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.INVALID_RESPONSE,
                pid,
                writes.size,
                "Native anti-flash cycle response exceeded the limit",
            )
        }
        return runCatching {
            decodeNativeAntiFlashCyclePayload(
                payload = payload,
                expectedPid = pid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedMapsFingerprint = expectedMapsFingerprint,
                expectedRegions = regions,
                expectedBssAddress = bssAddress,
                expectedWrites = writes,
            )
        }.getOrElse { error ->
            antiFlashCycleFailure(
                NativeMemoryBatchStatus.INVALID_RESPONSE,
                pid,
                writes.size,
                error.message.orEmpty(),
            )
        }
    }

    private fun writeVerifyU32Batch(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        operation: NativeU32BatchOperation,
        writes: List<NativeU32WriteRequest>,
    ): NativeU32BatchResult {
        validateU32BatchIdentity(
            pid,
            expectedStartTimeTicks,
            expectedMapsFingerprint,
            operation,
        )?.let { return it }
        validateU32WriteRequests(pid, operation, writes)?.let { return it }

        loadFailure?.let { error ->
            return u32BatchFailure(
                status = NativeMemoryBatchStatus.NATIVE_UNAVAILABLE,
                operation = operation,
                pid = pid,
                requestedCount = writes.size,
                message = error.message.orEmpty(),
            )
        }
        val payload = runCatching {
            nativeWriteVerifyU32Batch(
                pid = pid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedMapsFingerprint = expectedMapsFingerprint,
                operation = operation.name,
                addresses = LongArray(writes.size) { writes[it].address },
                valueBits = LongArray(writes.size) { writes[it].valueBits },
                expectedCurrentValueBits = LongArray(writes.size) {
                    writes[it].expectedCurrentValueBits ?: 0L
                },
                mappingFlags = IntArray(writes.size) { writes[it].mappingFlags() },
            )
        }.getOrElse { error ->
            return u32BatchFailure(
                status = NativeMemoryBatchStatus.NATIVE_ERROR,
                operation = operation,
                pid = pid,
                requestedCount = writes.size,
                message = error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_MEMORY_BATCH_RESPONSE_CHARS) {
            return u32BatchFailure(
                status = NativeMemoryBatchStatus.INVALID_RESPONSE,
                operation = operation,
                pid = pid,
                requestedCount = writes.size,
                message = "Native u32 batch response exceeded the limit",
            )
        }
        return runCatching {
            decodeNativeU32BatchPayload(
                payload = payload,
                expectedPid = pid,
                expectedStartTimeTicks = expectedStartTimeTicks,
                expectedMapsFingerprint = expectedMapsFingerprint,
                expectedOperation = operation,
                expectedWrites = writes,
            )
        }.getOrElse { error ->
            u32BatchFailure(
                status = NativeMemoryBatchStatus.INVALID_RESPONSE,
                operation = operation,
                pid = pid,
                requestedCount = writes.size,
                message = error.message.orEmpty(),
            )
        }
    }

    private fun readScalar(pid: Int, address: Long, byteCount: Int): NativeScalarReadResult {
        if (pid <= 0) {
            return scalarFailure(NativeScalarReadStatus.INVALID_PID, pid, byteCount, "PID must be positive")
        }
        if (address <= 0L) {
            return scalarFailure(
                NativeScalarReadStatus.INVALID_ADDRESS,
                pid,
                byteCount,
                "Address must be positive",
            )
        }

        loadFailure?.let { error ->
            return scalarFailure(
                status = NativeScalarReadStatus.NATIVE_UNAVAILABLE,
                pid = pid,
                byteCount = byteCount,
                message = error.message.orEmpty(),
            )
        }

        val payload = runCatching { nativeReadScalar(pid, address, byteCount) }.getOrElse { error ->
            return scalarFailure(
                status = NativeScalarReadStatus.NATIVE_ERROR,
                pid = pid,
                byteCount = byteCount,
                message = error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_SCALAR_RESPONSE_CHARS) {
            return scalarFailure(
                NativeScalarReadStatus.INVALID_RESPONSE,
                pid,
                byteCount,
                "Native scalar response exceeded the limit",
            )
        }

        return runCatching {
            decodeNativeScalarReadPayload(payload, pid, byteCount)
        }.getOrElse { error ->
            scalarFailure(
                status = NativeScalarReadStatus.INVALID_RESPONSE,
                pid = pid,
                byteCount = byteCount,
                message = error.message.orEmpty(),
            )
        }
    }

    private fun compareExchangeScalar(
        pid: Int,
        address: Long,
        byteCount: Int,
        expectedStartTimeTicks: String,
        expectedValueBits: Long,
        desiredValueBits: Long,
        requireWritableMapping: Boolean,
        requireExecutableMapping: Boolean,
    ): NativeScalarPatchResult {
        if (pid <= 0) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_PID,
                pid,
                byteCount,
                "PID must be positive",
            )
        }
        if (address <= 0L) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_ADDRESS,
                pid,
                byteCount,
                "Address must be positive",
            )
        }
        if (requireExecutableMapping && address and 0x3L != 0L) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_ADDRESS,
                pid,
                byteCount,
                "Typed executable patch address must be 4-byte aligned",
            )
        }
        if (byteCount != Int.SIZE_BYTES && byteCount != Long.SIZE_BYTES) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_SIZE,
                pid,
                byteCount,
                "Only 4-byte and 8-byte typed patches are supported",
            )
        }
        if (expectedStartTimeTicks.isEmpty() ||
            expectedStartTimeTicks.length > 32 ||
            !expectedStartTimeTicks.all(Char::isDigit)
        ) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_IDENTITY,
                pid,
                byteCount,
                "Pinned process identity is invalid",
            )
        }
        if (byteCount == Int.SIZE_BYTES &&
            (expectedValueBits ushr Int.SIZE_BITS != 0L || desiredValueBits ushr Int.SIZE_BITS != 0L)
        ) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_SIZE,
                pid,
                byteCount,
                "32-bit patch values must not contain upper bits",
            )
        }

        loadFailure?.let { error ->
            return patchFailure(
                NativeScalarPatchStatus.NATIVE_UNAVAILABLE,
                pid,
                byteCount,
                error.message.orEmpty(),
            )
        }
        val payload = runCatching {
            nativeCompareExchangeScalar(
                pid,
                address,
                byteCount,
                expectedStartTimeTicks,
                expectedValueBits,
                desiredValueBits,
                requireWritableMapping,
                requireExecutableMapping,
            )
        }.getOrElse { error ->
            return patchFailure(
                NativeScalarPatchStatus.NATIVE_ERROR,
                pid,
                byteCount,
                error.message.orEmpty(),
            )
        }
        if (payload.length > MAX_PATCH_RESPONSE_CHARS) {
            return patchFailure(
                NativeScalarPatchStatus.INVALID_RESPONSE,
                pid,
                byteCount,
                "Native patch response exceeded the limit",
            )
        }
        return runCatching {
            decodeNativeScalarPatchPayload(
                payload = payload,
                expectedPid = pid,
                expectedByteCount = byteCount,
                expectedStartTimeTicks = expectedStartTimeTicks,
            )
        }.getOrElse { error ->
            patchFailure(
                NativeScalarPatchStatus.INVALID_RESPONSE,
                pid,
                byteCount,
                error.message.orEmpty(),
            )
        }
    }

    private fun failure(
        status: NativeProbeStatus,
        pid: Int,
        message: String,
    ): NativeProbeResult = NativeProbeResult(
        status = status,
        pid = pid,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun scalarFailure(
        status: NativeScalarReadStatus,
        pid: Int,
        byteCount: Int,
        message: String,
    ): NativeScalarReadResult = NativeScalarReadResult(
        status = status,
        pid = pid,
        byteCount = byteCount,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun pagemapFailure(
        status: NativePagemapReadStatus,
        pid: Int,
        address: Long,
        message: String,
    ): NativePagemapReadResult = NativePagemapReadResult(
        status = status,
        pid = pid,
        address = address,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun patchFailure(
        status: NativeScalarPatchStatus,
        pid: Int,
        byteCount: Int,
        message: String,
    ): NativeScalarPatchResult = NativeScalarPatchResult(
        status = status,
        pid = pid,
        byteCount = byteCount,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun validateMemoryBatchIdentity(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
    ): NativeMemoryRegionBatchResult? {
        if (pid <= 0) {
            return memoryRegionBatchFailure(
                NativeMemoryBatchStatus.INVALID_PID,
                pid,
                0,
                "PID must be positive",
            )
        }
        if (!expectedStartTimeTicks.isValidProcessIdentity()) {
            return memoryRegionBatchFailure(
                NativeMemoryBatchStatus.INVALID_IDENTITY,
                pid,
                0,
                "Pinned process identity is invalid",
            )
        }
        if (!expectedMapsFingerprint.isValidMapsFingerprint()) {
            return memoryRegionBatchFailure(
                NativeMemoryBatchStatus.INVALID_IDENTITY,
                pid,
                0,
                "Pinned maps fingerprint is invalid",
            )
        }
        return null
    }

    private fun validateMemoryRegionRequests(
        pid: Int,
        requests: List<NativeMemoryRegionRequest>,
    ): NativeMemoryRegionBatchResult? {
        if (requests.isEmpty() || requests.size > MAX_MEMORY_BATCH_ITEMS) {
            return memoryRegionBatchFailure(
                NativeMemoryBatchStatus.INVALID_REQUEST,
                pid,
                requests.size,
                "Memory region count must be between 1 and $MAX_MEMORY_BATCH_ITEMS",
            )
        }
        var totalBytes = 0
        requests.forEachIndexed { index, request ->
            if (request.byteCount !in 1..MAX_MEMORY_REGION_BYTES) {
                return memoryRegionBatchFailure(
                    NativeMemoryBatchStatus.INVALID_SIZE,
                    pid,
                    requests.size,
                    "Memory region $index has an invalid size",
                    failedIndex = index,
                    failedAddress = request.address,
                )
            }
            if (request.address <= 0L || request.address > Long.MAX_VALUE - (request.byteCount - 1L)) {
                return memoryRegionBatchFailure(
                    NativeMemoryBatchStatus.INVALID_ADDRESS,
                    pid,
                    requests.size,
                    "Memory region $index has an invalid address range",
                    failedIndex = index,
                    failedAddress = request.address.takeIf { it > 0L },
                )
            }
            totalBytes += request.byteCount
            if (totalBytes > MAX_MEMORY_BATCH_BYTES) {
                return memoryRegionBatchFailure(
                    NativeMemoryBatchStatus.INVALID_SIZE,
                    pid,
                    requests.size,
                    "Memory batch exceeds $MAX_MEMORY_BATCH_BYTES bytes",
                    failedIndex = index,
                    failedAddress = request.address,
                )
            }
        }
        return null
    }

    private fun validateU32BatchIdentity(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        operation: NativeU32BatchOperation,
    ): NativeU32BatchResult? {
        if (pid <= 0) {
            return u32BatchFailure(
                NativeMemoryBatchStatus.INVALID_PID,
                operation,
                pid,
                0,
                "PID must be positive",
            )
        }
        if (!expectedStartTimeTicks.isValidProcessIdentity()) {
            return u32BatchFailure(
                NativeMemoryBatchStatus.INVALID_IDENTITY,
                operation,
                pid,
                0,
                "Pinned process identity is invalid",
            )
        }
        if (!expectedMapsFingerprint.isValidMapsFingerprint()) {
            return u32BatchFailure(
                NativeMemoryBatchStatus.INVALID_IDENTITY,
                operation,
                pid,
                0,
                "Pinned maps fingerprint is invalid",
            )
        }
        return null
    }

    private fun validateU32WriteRequests(
        pid: Int,
        operation: NativeU32BatchOperation,
        writes: List<NativeU32WriteRequest>,
    ): NativeU32BatchResult? {
        if (writes.isEmpty() || writes.size > MAX_MEMORY_BATCH_ITEMS) {
            return u32BatchFailure(
                NativeMemoryBatchStatus.INVALID_REQUEST,
                operation,
                pid,
                writes.size,
                "U32 write count must be between 1 and $MAX_MEMORY_BATCH_ITEMS",
            )
        }
        writes.forEachIndexed { index, write ->
            if (write.address <= 0L || write.address and 0x3L != 0L) {
                return u32BatchFailure(
                    NativeMemoryBatchStatus.INVALID_ADDRESS,
                    operation,
                    pid,
                    writes.size,
                    "U32 write $index must have a positive 4-byte aligned address",
                    failedIndex = index,
                    failedAddress = write.address.takeIf { it > 0L },
                    expectedValueBits = write.valueBits,
                )
            }
            if (write.valueBits ushr Int.SIZE_BITS != 0L) {
                return u32BatchFailure(
                    NativeMemoryBatchStatus.INVALID_SIZE,
                    operation,
                    pid,
                    writes.size,
                    "U32 write $index contains upper value bits",
                    failedIndex = index,
                    failedAddress = write.address,
                )
            }
            val expectedCurrent = write.expectedCurrentValueBits
            if (operation == NativeU32BatchOperation.ROLLBACK && expectedCurrent == null) {
                return u32BatchFailure(
                    NativeMemoryBatchStatus.INVALID_REQUEST,
                    operation,
                    pid,
                    writes.size,
                    "Rollback u32 write $index is missing its expected current value",
                    failedIndex = index,
                    failedAddress = write.address,
                )
            }
            if (expectedCurrent != null && expectedCurrent ushr Int.SIZE_BITS != 0L) {
                return u32BatchFailure(
                    NativeMemoryBatchStatus.INVALID_SIZE,
                    operation,
                    pid,
                    writes.size,
                    "Rollback u32 guard $index contains upper value bits",
                    failedIndex = index,
                    failedAddress = write.address,
                )
            }
        }
        return null
    }

    private fun validateAntiFlashCycle(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        regions: List<NativeAntiFlashCycleRegion>,
        bssAddress: Long,
        writes: List<NativeU32WriteRequest>,
    ): NativeAntiFlashCycleResult? {
        if (pid <= 0 || !expectedStartTimeTicks.isValidProcessIdentity() ||
            !expectedMapsFingerprint.isValidMapsFingerprint()
        ) {
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.INVALID_IDENTITY,
                pid,
                writes.size,
                "Pinned anti-flash target identity is invalid",
            )
        }
        if (regions.size != ANTI_FLASH_CODE_REGION_COUNT || writes.size != ANTI_FLASH_WRITE_COUNT) {
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.INVALID_REQUEST,
                pid,
                writes.size,
                "Anti-flash cycle requires 6 regions and 17 writes",
            )
        }
        regions.forEachIndexed { index, region ->
            val byteCount = region.originalBytes.size
            if (byteCount !in 1..MAX_MEMORY_REGION_BYTES ||
                byteCount != region.patchBytes.size ||
                region.address <= 0L || region.address > Long.MAX_VALUE - (byteCount - 1L)
            ) {
                return antiFlashCycleFailure(
                    NativeMemoryBatchStatus.INVALID_REQUEST,
                    pid,
                    writes.size,
                    "Anti-flash region $index is invalid",
                    failedIndex = antiFlashRegionWriteIndex(index),
                    failedAddress = region.address.takeIf { it > 0L },
                )
            }
        }
        if (bssAddress <= 0L || bssAddress and 0x3L != 0L) {
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.INVALID_ADDRESS,
                pid,
                writes.size,
                "Anti-flash BSS address must be positive and 4-byte aligned",
                failedIndex = ANTI_FLASH_WRITE_COUNT - 1,
                failedAddress = bssAddress.takeIf { it > 0L },
            )
        }
        validateU32WriteRequests(
            pid,
            NativeU32BatchOperation.ITERATION,
            writes,
        )?.let { failure ->
            return antiFlashCycleFailure(
                failure.status,
                pid,
                writes.size,
                failure.message,
                failure.failedIndex,
                failure.failedAddress,
            )
        }
        if (writes.last().address != bssAddress ||
            writes.dropLast(1).any { !it.requireExecutableMapping } ||
            !writes.last().requireWritableMapping
        ) {
            return antiFlashCycleFailure(
                NativeMemoryBatchStatus.INVALID_REQUEST,
                pid,
                writes.size,
                "Anti-flash write mapping requirements are invalid",
            )
        }
        return null
    }

    private fun memoryRegionBatchFailure(
        status: NativeMemoryBatchStatus,
        pid: Int,
        requestedCount: Int,
        message: String,
        failedIndex: Int? = null,
        failedAddress: Long? = null,
    ): NativeMemoryRegionBatchResult = NativeMemoryRegionBatchResult(
        status = status,
        pid = pid,
        requestedCount = requestedCount,
        failedIndex = failedIndex,
        failedAddress = failedAddress,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun u32BatchFailure(
        status: NativeMemoryBatchStatus,
        operation: NativeU32BatchOperation,
        pid: Int,
        requestedCount: Int,
        message: String,
        failedIndex: Int? = null,
        failedAddress: Long? = null,
        expectedValueBits: Long? = null,
    ): NativeU32BatchResult = NativeU32BatchResult(
        status = status,
        operation = operation,
        pid = pid,
        requestedCount = requestedCount,
        failedIndex = failedIndex,
        failedAddress = failedAddress,
        expectedValueBits = expectedValueBits,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun antiFlashCycleFailure(
        status: NativeMemoryBatchStatus,
        pid: Int,
        requestedCount: Int,
        message: String,
        failedIndex: Int? = null,
        failedAddress: Long? = null,
    ): NativeAntiFlashCycleResult = NativeAntiFlashCycleResult(
        status = status,
        pid = pid,
        requestedCount = requestedCount,
        failedIndex = failedIndex,
        failedAddress = failedAddress,
        message = message.take(MAX_MESSAGE_CHARS),
    )

    private fun NativeMemoryRegionRequest.mappingFlags(): Int =
        (if (requireWritableMapping) MAPPING_FLAG_WRITABLE else 0) or
            (if (requireExecutableMapping) MAPPING_FLAG_EXECUTABLE else 0)

    private fun NativeU32WriteRequest.mappingFlags(): Int =
        (if (requireWritableMapping) MAPPING_FLAG_WRITABLE else 0) or
            (if (requireExecutableMapping) MAPPING_FLAG_EXECUTABLE else 0)

    private fun String.isValidProcessIdentity(): Boolean =
        isNotEmpty() && length <= 32 && all(Char::isDigit)

    private fun String.isValidMapsFingerprint(): Boolean =
        length == 16 && all { it in '0'..'9' || it in 'a'..'f' }

    private external fun nativeInspect(pid: Int): String
    private external fun nativeReadScalar(pid: Int, address: Long, byteCount: Int): String
    private external fun nativeReadPagemapEntry(pid: Int, address: Long): String
    private external fun nativeCompareExchangeScalar(
        pid: Int,
        address: Long,
        byteCount: Int,
        expectedStartTimeTicks: String,
        expectedValueBits: Long,
        desiredValueBits: Long,
        requireWritableMapping: Boolean,
        requireExecutableMapping: Boolean,
    ): String
    private external fun nativeReadMemoryRegions(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        addresses: LongArray,
        byteCounts: IntArray,
        mappingFlags: IntArray,
    ): String
    private external fun nativeWriteVerifyU32Batch(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        operation: String,
        addresses: LongArray,
        valueBits: LongArray,
        expectedCurrentValueBits: LongArray,
        mappingFlags: IntArray,
    ): String

    private external fun nativeRunAntiFlashCycle(
        pid: Int,
        expectedStartTimeTicks: String,
        expectedMapsFingerprint: String,
        regionAddresses: LongArray,
        originalHex: Array<String>,
        patchHex: Array<String>,
        bssAddress: Long,
        writeAddresses: LongArray,
        writeValueBits: LongArray,
        writeMappingFlags: IntArray,
    ): String
}

internal fun decodeNativeProbePayload(
    payload: String,
    expectedPid: Int,
): NativeProbeResult {
    val json = JSONObject(payload)
    val status = NativeProbeStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native status")
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native response PID mismatch" }

    val moduleArray = json.optJSONArray("modules")
    val modules = buildList {
        val count = minOf(moduleArray?.length() ?: 0, MAX_DECODED_MODULES)
        repeat(count) { index ->
            val module = moduleArray!!.getJSONObject(index)
            add(
                NativeModuleSummary(
                    name = module.getString("name").take(192),
                    path = module.optString("path").take(MAX_DECODED_MODULE_PATH_CHARS),
                    origin = NativeModuleOrigin.fromWireValue(module.optString("origin")),
                    regionCount = module.nonNegativeInt("regionCount"),
                    mappedBytes = module.nonNegativeLong("mappedBytes"),
                    readableBytes = module.nonNegativeLong("readableBytes"),
                    executable = module.optBoolean("executable"),
                    fileReadable = module.optBoolean("fileReadable"),
                    elfFile = module.optBoolean("elfFile"),
                    memoryReadable = module.optBoolean("memoryReadable"),
                    memoryReadBytes = module.nonNegativeInt("memoryReadBytes"),
                    memoryElf = module.optBoolean("memoryElf"),
                    loadBase = parsePositiveAddressHex(module.optString("loadBaseHex")),
                ),
            )
        }
    }

    return NativeProbeResult(
        status = status,
        pid = responsePid,
        processStartTimeTicks = json.optString("processStartTimeTicks").take(32),
        regionCount = json.nonNegativeInt("regionCount"),
        moduleCount = json.nonNegativeInt("moduleCount"),
        readableBytes = json.nonNegativeLong("readableBytes"),
        readableFileCount = json.nonNegativeInt("readableFileCount"),
        elfHeaderCount = json.nonNegativeInt("elfHeaderCount"),
        memoryReadableModuleCount = json.nonNegativeInt("memoryReadableModuleCount"),
        memoryReadBytes = json.nonNegativeLong("memoryReadBytes"),
        memoryElfHeaderCount = json.nonNegativeInt("memoryElfHeaderCount"),
        mapsFingerprint = json.optString("mapsFingerprint").take(32),
        truncated = json.optBoolean("truncated"),
        modules = modules,
        message = json.optString("message").take(MAX_DECODED_MESSAGE_CHARS),
    )
}

private const val MAX_DECODED_MODULES = 128
private const val MAX_DECODED_MODULE_PATH_CHARS = 1_024
private const val MAX_DECODED_MESSAGE_CHARS = 256

private fun JSONObject.nonNegativeInt(key: String): Int =
    optInt(key, 0).coerceAtLeast(0)

private fun JSONObject.nonNegativeLong(key: String): Long =
    optLong(key, 0L).coerceAtLeast(0L)

private fun parsePositiveAddressHex(value: String): Long {
    if (value.isEmpty() || value.length > 16 || !value.all(Char::isHexDigit)) return 0L
    return runCatching { java.lang.Long.parseUnsignedLong(value, 16) }
        .getOrDefault(0L)
        .takeIf { it > 0L }
        ?: 0L
}

internal fun decodeNativeScalarReadPayload(
    payload: String,
    expectedPid: Int,
    expectedByteCount: Int,
): NativeScalarReadResult {
    val json = JSONObject(payload)
    val status = NativeScalarReadStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native scalar status")
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native scalar response PID mismatch" }
    val byteCount = json.optInt("byteCount", -1)
    check(byteCount == expectedByteCount) { "Native scalar response size mismatch" }

    val startTime = json.optString("processStartTimeTicks").take(32)
    val valueBits = if (status == NativeScalarReadStatus.OK) {
        check(startTime.isNotBlank()) { "Native scalar response omitted process identity" }
        val valueHex = json.getString("valueHex")
        check(valueHex.length == byteCount * 2 && valueHex.all(Char::isHexDigit)) {
            "Native scalar response value is malformed"
        }
        java.lang.Long.parseUnsignedLong(valueHex, 16)
    } else {
        null
    }

    return NativeScalarReadResult(
        status = status,
        pid = responsePid,
        byteCount = byteCount,
        processStartTimeTicks = startTime,
        valueBits = valueBits,
        message = json.optString("message").take(256),
    )
}

internal fun decodeNativePagemapReadPayload(
    payload: String,
    expectedPid: Int,
    expectedAddress: Long,
): NativePagemapReadResult {
    val json = JSONObject(payload)
    val status = NativePagemapReadStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native pagemap status")
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native pagemap response PID mismatch" }
    val address = parseUnsignedLongHex(json.getString("addressHex"))
    check(address == expectedAddress) { "Native pagemap response address mismatch" }
    val pageSize = json.optLong("pageSize", 0L)
    check(pageSize >= 0L) { "Native pagemap page size is invalid" }

    val startTime = json.optString("processStartTimeTicks").take(32)
    val entryBits = if (status == NativePagemapReadStatus.OK) {
        check(startTime.isNotBlank()) { "Native pagemap response omitted process identity" }
        check(pageSize > 0L) { "Native pagemap response omitted page size" }
        parseUnsignedLongHex(json.getString("entryHex"))
    } else {
        null
    }

    return NativePagemapReadResult(
        status = status,
        pid = responsePid,
        address = address,
        pageSize = pageSize,
        processStartTimeTicks = startTime,
        entryBits = entryBits,
        message = json.optString("message").take(256),
    )
}

internal fun decodeNativeScalarPatchPayload(
    payload: String,
    expectedPid: Int,
    expectedByteCount: Int,
    expectedStartTimeTicks: String,
): NativeScalarPatchResult {
    val json = JSONObject(payload)
    val status = NativeScalarPatchStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native scalar patch status")
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native scalar patch PID mismatch" }
    val byteCount = json.optInt("byteCount", -1)
    check(byteCount == expectedByteCount) { "Native scalar patch size mismatch" }
    val startTime = json.optString("processStartTimeTicks").take(32)
    if (startTime.isNotBlank()) {
        check(startTime == expectedStartTimeTicks) { "Native scalar patch identity mismatch" }
    }
    if (status == NativeScalarPatchStatus.APPLIED ||
        status == NativeScalarPatchStatus.ALREADY_APPLIED
    ) {
        check(startTime.isNotBlank()) { "Native scalar patch omitted process identity" }
    }

    val beforeBits = json.optString("beforeValueHex")
        .takeIf(String::isNotEmpty)
        ?.let { parseScalarHex(it, byteCount) }
    val afterBits = json.optString("afterValueHex")
        .takeIf(String::isNotEmpty)
        ?.let { parseScalarHex(it, byteCount) }
    if (status == NativeScalarPatchStatus.APPLIED ||
        status == NativeScalarPatchStatus.ALREADY_APPLIED
    ) {
        check(beforeBits != null && afterBits != null) {
            "Native scalar patch omitted compare-exchange values"
        }
    }
    return NativeScalarPatchResult(
        status = status,
        pid = responsePid,
        byteCount = byteCount,
        processStartTimeTicks = startTime,
        beforeValueBits = beforeBits,
        afterValueBits = afterBits,
        message = json.optString("message").take(MAX_DECODED_MESSAGE_CHARS),
    )
}

internal fun decodeNativeMemoryRegionBatchPayload(
    payload: String,
    expectedPid: Int,
    expectedStartTimeTicks: String,
    expectedMapsFingerprint: String,
    expectedRequests: List<NativeMemoryRegionRequest>,
): NativeMemoryRegionBatchResult {
    val json = JSONObject(payload)
    val status = NativeMemoryBatchStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native memory batch status")
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native memory batch PID mismatch" }
    val requestedCount = json.optInt("requestedCount", -1)
    check(requestedCount == expectedRequests.size) { "Native memory batch count mismatch" }
    val completedCount = json.optInt("completedCount", -1)
    check(completedCount in 0..requestedCount) { "Native memory batch completed count is invalid" }
    val startTime = json.optString("processStartTimeTicks").take(32)
    if (startTime.isNotEmpty()) {
        check(startTime == expectedStartTimeTicks) { "Native memory batch identity mismatch" }
    }
    val mapsFingerprint = json.optString("mapsFingerprint")
    if (mapsFingerprint.isNotEmpty()) {
        check(mapsFingerprint.isCanonicalMapsFingerprint()) {
            "Native memory batch maps fingerprint is malformed"
        }
    }

    val failedIndex = json.optInt("failedIndex", -1).takeIf { it >= 0 }
    failedIndex?.let { check(it < requestedCount) { "Native memory batch failure index is invalid" } }
    val failedAddress = json.optString("failedAddressHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parsePositiveUnsignedAddressHex)
    if (failedIndex != null && failedAddress != null) {
        check(failedAddress == expectedRequests[failedIndex].address) {
            "Native memory batch failure address mismatch"
        }
    }

    val regions = if (status == NativeMemoryBatchStatus.OK) {
        check(startTime.isNotEmpty()) { "Native memory batch omitted process identity" }
        check(mapsFingerprint == expectedMapsFingerprint) {
            "Native memory batch maps fingerprint mismatch"
        }
        check(completedCount == requestedCount) { "Native memory batch success is incomplete" }
        val array = json.getJSONArray("regions")
        check(array.length() == requestedCount) { "Native memory batch region count mismatch" }
        buildList(requestedCount) {
            repeat(requestedCount) { index ->
                val item = array.getJSONObject(index)
                val request = expectedRequests[index]
                val address = parsePositiveUnsignedAddressHex(item.getString("addressHex"))
                check(address == request.address) { "Native memory region address mismatch" }
                val byteCount = item.optInt("byteCount", -1)
                check(byteCount == request.byteCount) { "Native memory region size mismatch" }
                val bytes = decodeHexBytes(item.getString("bytesHex"), byteCount)
                add(NativeMemoryRegionRead(address = address, bytes = bytes))
            }
        }
    } else {
        emptyList()
    }

    return NativeMemoryRegionBatchResult(
        status = status,
        pid = responsePid,
        processStartTimeTicks = startTime,
        mapsFingerprint = mapsFingerprint,
        requestedCount = requestedCount,
        completedCount = completedCount,
        failedIndex = failedIndex,
        failedAddress = failedAddress,
        regions = regions,
        message = json.optString("message").take(MAX_DECODED_MESSAGE_CHARS),
    )
}

internal fun decodeNativeU32BatchPayload(
    payload: String,
    expectedPid: Int,
    expectedStartTimeTicks: String,
    expectedMapsFingerprint: String,
    expectedOperation: NativeU32BatchOperation,
    expectedWrites: List<NativeU32WriteRequest>,
): NativeU32BatchResult {
    val json = JSONObject(payload)
    val status = NativeMemoryBatchStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native u32 batch status")
    val operation = runCatching {
        NativeU32BatchOperation.valueOf(json.getString("operation"))
    }.getOrElse { error("Unknown native u32 batch operation") }
    check(operation == expectedOperation) { "Native u32 batch operation mismatch" }
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native u32 batch PID mismatch" }
    val requestedCount = json.optInt("requestedCount", -1)
    check(requestedCount == expectedWrites.size) { "Native u32 batch count mismatch" }
    val completedCount = json.optInt("completedCount", -1)
    check(completedCount in 0..requestedCount) { "Native u32 batch completed count is invalid" }
    val startTime = json.optString("processStartTimeTicks").take(32)
    if (startTime.isNotEmpty()) {
        check(startTime == expectedStartTimeTicks) { "Native u32 batch identity mismatch" }
    }
    val mapsFingerprint = json.optString("mapsFingerprint")
    if (mapsFingerprint.isNotEmpty()) {
        check(mapsFingerprint.isCanonicalMapsFingerprint()) {
            "Native u32 batch maps fingerprint is malformed"
        }
    }

    val failedIndex = json.optInt("failedIndex", -1).takeIf { it >= 0 }
    failedIndex?.let { check(it < requestedCount) { "Native u32 batch failure index is invalid" } }
    val failedAddress = json.optString("failedAddressHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parsePositiveUnsignedAddressHex)
    if (failedIndex != null && failedAddress != null) {
        check(failedAddress == expectedWrites[failedIndex].address) {
            "Native u32 batch failure address mismatch"
        }
    }
    val expectedValueBits = json.optString("expectedValueHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parseU32Hex)
    val observedValueBits = json.optString("observedValueHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parseU32Hex)
    val guardValueBits = json.optString("guardValueHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parseU32Hex)
    if (failedIndex != null && expectedValueBits != null) {
        check(expectedValueBits == expectedWrites[failedIndex].valueBits) {
            "Native u32 batch expected value mismatch"
        }
    }
    if (failedIndex != null && guardValueBits != null) {
        check(guardValueBits == expectedWrites[failedIndex].expectedCurrentValueBits) {
            "Native u32 batch guard value mismatch"
        }
    }
    if (status == NativeMemoryBatchStatus.OK) {
        check(startTime.isNotEmpty()) { "Native u32 batch omitted process identity" }
        check(mapsFingerprint == expectedMapsFingerprint) {
            "Native u32 batch maps fingerprint mismatch"
        }
        check(completedCount == requestedCount) { "Native u32 batch success is incomplete" }
        check(failedIndex == null && failedAddress == null) {
            "Native u32 batch success includes a failure location"
        }
    }

    return NativeU32BatchResult(
        status = status,
        operation = operation,
        pid = responsePid,
        processStartTimeTicks = startTime,
        mapsFingerprint = mapsFingerprint,
        requestedCount = requestedCount,
        completedCount = completedCount,
        failedIndex = failedIndex,
        failedAddress = failedAddress,
        expectedValueBits = expectedValueBits,
        observedValueBits = observedValueBits,
        guardValueBits = guardValueBits,
        message = json.optString("message").take(MAX_DECODED_MESSAGE_CHARS),
    )
}

internal fun decodeNativeAntiFlashCyclePayload(
    payload: String,
    expectedPid: Int,
    expectedStartTimeTicks: String,
    expectedMapsFingerprint: String,
    expectedRegions: List<NativeAntiFlashCycleRegion>,
    expectedBssAddress: Long,
    expectedWrites: List<NativeU32WriteRequest>,
): NativeAntiFlashCycleResult {
    val json = JSONObject(payload)
    val status = NativeMemoryBatchStatus.fromWireValue(json.getString("status"))
        ?: error("Unknown native anti-flash cycle status")
    val responsePid = json.optInt("pid", expectedPid)
    check(responsePid == expectedPid) { "Native anti-flash cycle PID mismatch" }
    val startTime = json.optString("processStartTimeTicks").take(32)
    if (startTime.isNotEmpty()) {
        check(startTime == expectedStartTimeTicks) { "Native anti-flash cycle identity mismatch" }
    }
    val mapsFingerprint = json.optString("mapsFingerprint")
    if (mapsFingerprint.isNotEmpty()) {
        check(mapsFingerprint.isCanonicalMapsFingerprint()) {
            "Native anti-flash cycle maps fingerprint is malformed"
        }
    }
    val requestedCount = json.optInt("requestedCount", -1)
    check(requestedCount == expectedWrites.size) { "Native anti-flash cycle write count mismatch" }
    val completedCount = json.optInt("completedCount", -1)
    check(completedCount in 0..requestedCount) {
        "Native anti-flash cycle completed count is invalid"
    }
    val failedIndex = json.optInt("failedIndex", -1).takeIf { it >= 0 }
    failedIndex?.let { check(it < requestedCount) { "Native anti-flash cycle failure index is invalid" } }
    val failedAddress = json.optString("failedAddressHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parsePositiveUnsignedAddressHex)
    if (failedIndex != null && failedAddress != null) {
        check(failedAddress == expectedWrites[failedIndex].address) {
            "Native anti-flash cycle failure address mismatch"
        }
    }
    val writeAttempted = json.optBoolean("writeAttempted", false)
    val regionArray = json.optJSONArray("codeRegionValues")
    val requiresFullPreflight = status == NativeMemoryBatchStatus.OK ||
        status == NativeMemoryBatchStatus.PROFILE_MISMATCH || writeAttempted
    val regionCount = regionArray?.length() ?: 0
    check(
        if (requiresFullPreflight) regionCount == expectedRegions.size
        else regionCount in 0..expectedRegions.size
    ) {
        "Native anti-flash cycle preflight region count mismatch"
    }
    val codeRegionValues = if (regionArray != null) {
        buildList(regionCount) {
            repeat(regionCount) { index ->
                add(decodeHexBytes(regionArray.getString(index), expectedRegions[index].originalBytes.size))
            }
        }
    } else {
        emptyList()
    }
    val bssValue = json.optString("bssValueHex")
        .takeIf(String::isNotEmpty)
        ?.let { decodeHexBytes(it, Int.SIZE_BYTES) }
    val bssAddress = json.optString("bssAddressHex")
        .takeIf(String::isNotEmpty)
        ?.let(::parsePositiveUnsignedAddressHex)
    bssAddress?.let {
        check(it == expectedBssAddress) { "Native anti-flash cycle BSS address mismatch" }
    }

    if (status == NativeMemoryBatchStatus.OK) {
        check(startTime.isNotEmpty()) { "Native anti-flash cycle omitted process identity" }
        check(mapsFingerprint == expectedMapsFingerprint) {
            "Native anti-flash cycle maps fingerprint mismatch"
        }
        check(codeRegionValues.size == expectedRegions.size && bssValue != null) {
            "Native anti-flash cycle omitted preflight values"
        }
        check(writeAttempted && completedCount == requestedCount) {
            "Native anti-flash cycle success is incomplete"
        }
        check(failedIndex == null && failedAddress == null) {
            "Native anti-flash cycle success includes a failure location"
        }
    }
    if (status == NativeMemoryBatchStatus.PROFILE_MISMATCH) {
        check(!writeAttempted && completedCount == 0) {
            "Native anti-flash profile mismatch attempted writes"
        }
    }

    return NativeAntiFlashCycleResult(
        status = status,
        pid = responsePid,
        processStartTimeTicks = startTime,
        mapsFingerprint = mapsFingerprint,
        codeRegionValues = codeRegionValues,
        bssValue = bssValue,
        requestedCount = requestedCount,
        completedCount = completedCount,
        failedIndex = failedIndex,
        failedAddress = failedAddress,
        writeAttempted = writeAttempted,
        message = json.optString("message").take(MAX_DECODED_MESSAGE_CHARS),
    )
}

private fun String.isCanonicalMapsFingerprint(): Boolean =
    length == 16 && all { it in '0'..'9' || it in 'a'..'f' }

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    this@toLowerHex.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
}

private fun antiFlashRegionWriteIndex(regionIndex: Int): Int =
    if (regionIndex == 0) 0 else 1 + (regionIndex - 1) * 3

private fun parseUnsignedLongHex(value: String): Long {
    check(value.length == 16 && value.all(Char::isHexDigit)) {
        "Native unsigned value is malformed"
    }
    return java.lang.Long.parseUnsignedLong(value, 16)
}

private fun parseScalarHex(value: String, byteCount: Int): Long {
    check(value.length == byteCount * 2 && value.all(Char::isHexDigit)) {
        "Native scalar patch value is malformed"
    }
    return java.lang.Long.parseUnsignedLong(value, 16)
}

private fun parsePositiveUnsignedAddressHex(value: String): Long {
    val address = parseUnsignedLongHex(value)
    check(address > 0L) { "Native address must be positive" }
    return address
}

private fun parseU32Hex(value: String): Long {
    check(value.length == 8 && value.all(Char::isHexDigit)) {
        "Native u32 value is malformed"
    }
    return java.lang.Long.parseUnsignedLong(value, 16)
}

private fun decodeHexBytes(value: String, byteCount: Int): ByteArray {
    check(value.length == byteCount * 2 && value.all(Char::isHexDigit)) {
        "Native byte region is malformed"
    }
    return ByteArray(byteCount) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
