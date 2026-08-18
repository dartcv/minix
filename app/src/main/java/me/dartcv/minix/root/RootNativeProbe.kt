package me.dartcv.minix.root

import java.io.File
import java.security.MessageDigest
import me.dartcv.minix.root.nativeadapter.NativeMemoryBatchStatus
import me.dartcv.minix.root.nativeadapter.NativeAntiFlashCycleRegion
import me.dartcv.minix.root.nativeadapter.NativeModuleSummary
import me.dartcv.minix.root.nativeadapter.NativeMemoryRegionRequest
import me.dartcv.minix.root.nativeadapter.NativeProbeResult
import me.dartcv.minix.root.nativeadapter.NativeProbeStatus
import me.dartcv.minix.root.nativeadapter.NativeScalarReadResult
import me.dartcv.minix.root.nativeadapter.NativeScalarPatchStatus
import me.dartcv.minix.root.nativeadapter.NativeU32WriteRequest
import me.dartcv.minix.root.nativeadapter.TargetNativeProbe

enum class RootNativeProbeStatus {
    IDLE,
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
        fun fromWireValue(value: String): RootNativeProbeStatus? =
            entries.firstOrNull { it.name == value }
    }
}

data class RootNativeProbeState(
    val status: RootNativeProbeStatus = RootNativeProbeStatus.IDLE,
    val processStartTimeTicks: String = "",
    val regionCount: Int = 0,
    val moduleCount: Int = 0,
    val memoryReadableModuleCount: Int = 0,
    val memoryReadBytes: Long = 0L,
    val memoryElfHeaderCount: Int = 0,
    val mapsFingerprint: String = "",
    val truncated: Boolean = false,
    val modules: List<String> = emptyList(),
    val message: String = "",
) {
    val isMemoryReady: Boolean
        get() = status == RootNativeProbeStatus.OK &&
            memoryReadableModuleCount > 0 &&
            memoryReadBytes > 0L

    val summary: String
        get() = when {
            status == RootNativeProbeStatus.IDLE -> "尚未执行"
            isMemoryReady -> buildString {
                append("已读回 ")
                append(memoryReadBytes)
                append(" B / ")
                append(memoryReadableModuleCount)
                append(" 个模块")
                append(" · ELF ")
                append(memoryElfHeaderCount)
                append(" · 映射 ")
                append(regionCount)
                if (truncated) append(" · 已截断")
            }
            status == RootNativeProbeStatus.OK ->
                "已解析 $regionCount 个映射，但目标内存读回未就绪"
            message.isNotBlank() -> "${status.displayLabel()}：$message"
            else -> status.displayLabel()
        }
}

internal fun interface TargetMemoryProbe {
    fun inspect(pid: Int): TargetMemoryInspection

    fun inspectGeneration(pid: Int): RootNativeProbeState = inspect(pid).state
}

internal object NoTargetMemoryProbe : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection()
}

internal object JniTargetMemoryProbe : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = buildTargetMemoryInspection(
        pid = pid,
        result = TargetNativeProbe.inspect(pid),
        fingerprintModuleNames = RootReadOnlyFieldProfileCatalog.fingerprintModuleNames +
            RootRecoveredSearchIdProfiles.fingerprintModuleNames +
            RootInjectionProfileCatalog.fingerprintModuleNames +
            RootAntiFlashProfileCatalog.fingerprintModuleNames +
            RootHeadSpinProfileCatalog.fingerprintModuleNames,
        fingerprintProvider = ProcRootModuleFingerprintProvider,
    )

    override fun inspectGeneration(pid: Int): RootNativeProbeState =
        TargetNativeProbe.inspect(pid).toRootState()
}

internal data class TargetMemoryInspection(
    val state: RootNativeProbeState = RootNativeProbeState(),
    val modules: List<RootNativeModuleIdentity> = emptyList(),
)

internal fun interface TargetModuleFingerprintProvider {
    fun sha256(pid: Int, mappedPath: String, loadBase: Long): String?
}

internal object ProcRootModuleFingerprintProvider : TargetModuleFingerprintProvider {
    override fun sha256(pid: Int, mappedPath: String, loadBase: Long): String? {
        return sha256(pid, mappedPath, loadBase, File("/proc"))
    }

    internal fun sha256(
        pid: Int,
        mappedPath: String,
        loadBase: Long,
        procRoot: File,
    ): String? {
        if (
            pid <= 0 ||
            (mappedPath.endsWith(DELETED_MAPPING_SUFFIX) && loadBase <= 0L) ||
            mappedPath.length !in 2..MAX_MAPPED_PATH_CHARS ||
            !mappedPath.startsWith('/') ||
            mappedPath.contains('\u0000') ||
            mappedPath.contains('\n') ||
            mappedPath.contains('\r') ||
            mappedPath.split('/').any { it == ".." }
        ) {
            return null
        }
        if (!mappedPath.endsWith(DELETED_MAPPING_SUFFIX)) {
            return sha256File(File(procRoot, "$pid/root$mappedPath"))
        }

        val mapping = findDeletedMapping(pid, mappedPath, loadBase, procRoot) ?: return null
        return sha256StreamFile(
            file = File(procRoot, "$pid/map_files/${mapping.first.toString(16)}-${mapping.second.toString(16)}"),
            expectedLength = mapping.third,
        )
    }

    private fun findDeletedMapping(
        pid: Int,
        mappedPath: String,
        loadBase: Long,
        procRoot: File,
    ): Triple<Long, Long, Long>? = runCatching {
        val matches = mutableListOf<Triple<Long, Long, Long>>()
        var lineCount = 0
        var byteCount = 0L
        File(procRoot, "$pid/maps").bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val encodedBytes = line.toByteArray(Charsets.UTF_8).size
                lineCount += 1
                byteCount = Math.addExact(byteCount, encodedBytes.toLong() + 1L)
                if (lineCount > MAX_FINGERPRINT_MAP_LINES ||
                    byteCount > MAX_FINGERPRINT_MAP_BYTES ||
                    encodedBytes > MAX_FINGERPRINT_MAP_LINE_BYTES
                ) {
                    return@runCatching null
                }
                val fields = line.trim().split(Regex("\\s+"), limit = 6)
                if (fields.size < 6 || fields[5] != mappedPath) continue
                val range = fields[0].split('-', limit = 2)
                if (range.size != 2) continue
                val start = range[0].toLongOrNull(16)?.takeIf { it > 0L } ?: continue
                val end = range[1].toLongOrNull(16)?.takeIf { it > start } ?: continue
                if (loadBase > 0L && start != loadBase) continue
                if (fields[2].toLongOrNull(16) != 0L) continue
                val mapFile = File(procRoot, "$pid/map_files/${start.toString(16)}-${end.toString(16)}")
                val expectedLength = mapFile.length().takeIf { it in 1..MAX_FINGERPRINT_FILE_BYTES }
                    ?: continue
                matches += Triple(start, end, expectedLength)
            }
        }
        matches.singleOrNull()
    }.getOrNull()

    private const val MAX_MAPPED_PATH_CHARS = 1_024
    private const val DELETED_MAPPING_SUFFIX = " (deleted)"
    private const val MAX_FINGERPRINT_MAP_LINES = 50_000
    private const val MAX_FINGERPRINT_MAP_BYTES = 4L * 1024L * 1024L
    private const val MAX_FINGERPRINT_MAP_LINE_BYTES = 4_096
}

internal fun sha256File(file: File): String? {
    if (!file.isFile) return null
    val expectedLength = file.length()
    if (expectedLength !in 1..MAX_FINGERPRINT_FILE_BYTES) return null

    return sha256StreamFile(file, expectedLength)
}

private fun sha256StreamFile(file: File, expectedLength: Long?): String? {
    if (expectedLength != null && expectedLength !in 1..MAX_FINGERPRINT_FILE_BYTES) return null

    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(FINGERPRINT_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total = Math.addExact(total, count.toLong())
                if (total > MAX_FINGERPRINT_FILE_BYTES) return null
                digest.update(buffer, 0, count)
            }
        }
        if (total !in 1..MAX_FINGERPRINT_FILE_BYTES) return null
        if (expectedLength != null && (total != expectedLength || file.length() != expectedLength)) {
            return null
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()
}

internal fun buildTargetMemoryInspection(
    pid: Int,
    result: NativeProbeResult,
    fingerprintModuleNames: Set<String>,
    fingerprintProvider: TargetModuleFingerprintProvider,
): TargetMemoryInspection {
    val modules = result.modules.map { module ->
        val sha256 = if (
            module.name in fingerprintModuleNames &&
            module.memoryElf &&
            module.loadBase > 0L
        ) {
            fingerprintProvider.sha256(pid, module.path, module.loadBase)
        } else {
            null
        }
        module.toRootIdentity(sha256)
    }
    return TargetMemoryInspection(
        state = result.toRootState(),
        modules = modules,
    )
}

internal data class TargetScalarReadResult(
    val isSuccess: Boolean,
    val processStartTimeTicks: String = "",
    val valueBits: Long? = null,
    val message: String = "",
)

internal interface TargetScalarReader {
    fun readInt32(pid: Int, address: Long): TargetScalarReadResult
    fun readInt64(pid: Int, address: Long): TargetScalarReadResult
}

internal object NoTargetScalarReader : TargetScalarReader {
    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult =
        TargetScalarReadResult(isSuccess = false, message = "Scalar reader is unavailable")

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult =
        TargetScalarReadResult(isSuccess = false, message = "Scalar reader is unavailable")
}

internal object JniTargetScalarReader : TargetScalarReader {
    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult =
        TargetNativeProbe.readInt32(pid, address).toRootScalarRead()

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult =
        TargetNativeProbe.readInt64(pid, address).toRootScalarRead()
}

internal object JniRootInjectionExecutor : RootInjectionExecutor {
    override fun apply(request: RootInjectionWriteRequest): RootInjectionWriteResult {
        val result = when (request.byteCount) {
            Int.SIZE_BYTES -> TargetNativeProbe.compareExchangeInt32(
                pid = request.pid,
                address = request.address,
                expectedStartTimeTicks = request.expectedStartTimeTicks,
                expectedValueBits = request.expectedValueBits,
                desiredValueBits = request.desiredValueBits,
                requireWritableMapping =
                    request.mappingRequirement == RootInjectionMappingRequirement.WRITABLE,
                requireExecutableMapping =
                    request.mappingRequirement == RootInjectionMappingRequirement.EXECUTABLE,
            )
            Long.SIZE_BYTES -> TargetNativeProbe.compareExchangeInt64(
                pid = request.pid,
                address = request.address,
                expectedStartTimeTicks = request.expectedStartTimeTicks,
                expectedValueBits = request.expectedValueBits,
                desiredValueBits = request.desiredValueBits,
                requireWritableMapping =
                    request.mappingRequirement == RootInjectionMappingRequirement.WRITABLE,
                requireExecutableMapping =
                    request.mappingRequirement == RootInjectionMappingRequirement.EXECUTABLE,
            )
            else -> return RootInjectionWriteResult(
                status = RootInjectionApplyStatus.INVALID_RESPONSE,
                message = "Injection profile uses an unsupported scalar width",
            )
        }
        return RootInjectionWriteResult(
            status = when (result.status) {
                NativeScalarPatchStatus.APPLIED -> RootInjectionApplyStatus.APPLIED
                NativeScalarPatchStatus.ALREADY_APPLIED ->
                    RootInjectionApplyStatus.ALREADY_APPLIED
                NativeScalarPatchStatus.TARGET_NOT_RUNNING,
                NativeScalarPatchStatus.TARGET_CHANGED -> RootInjectionApplyStatus.TARGET_CHANGED
                NativeScalarPatchStatus.EXPECTED_VALUE_MISMATCH ->
                    RootInjectionApplyStatus.EXPECTED_VALUE_MISMATCH
                NativeScalarPatchStatus.VERIFY_FAILED -> RootInjectionApplyStatus.VERIFY_FAILED
                NativeScalarPatchStatus.ROLLBACK_FAILED -> RootInjectionApplyStatus.ROLLBACK_FAILED
                NativeScalarPatchStatus.NATIVE_UNAVAILABLE ->
                    RootInjectionApplyStatus.BACKEND_UNAVAILABLE
                NativeScalarPatchStatus.INVALID_RESPONSE ->
                    RootInjectionApplyStatus.INVALID_RESPONSE
                else -> RootInjectionApplyStatus.WRITE_FAILED
            },
            processStartTimeTicks = result.processStartTimeTicks,
            message = result.message,
        )
    }
}

internal object JniRootAntiFlashBackend : RootAntiFlashBackend {
    fun warmUp(): Throwable? = TargetNativeProbe.warmUp()

    override fun runCycle(
        target: RootAntiFlashTarget,
        regions: List<RootAntiFlashCycleRegion>,
        bssRange: RootAntiFlashMemoryRange,
        writes: List<RootAntiFlashMemoryWrite>,
    ): RootAntiFlashCycleResult {
        if (bssRange.byteCount != Int.SIZE_BYTES) {
            return RootAntiFlashCycleResult(
                status = RootAntiFlashBackendStatus.INVALID_RESPONSE,
                message = "Anti-flash BSS preflight must be one u32",
            )
        }
        val nativeWrites = writes.map { write ->
            if (write.bytes.size != Int.SIZE_BYTES || write.address <= 0L ||
                write.address and 0x3L != 0L
            ) {
                return RootAntiFlashCycleResult(
                    status = RootAntiFlashBackendStatus.INVALID_RESPONSE,
                    failureIndex = write.index,
                    message = "Anti-flash cycle write is not one aligned u32",
                )
            }
            val isBssMarker = write.address == bssRange.address
            NativeU32WriteRequest(
                address = write.address,
                valueBits = write.bytes.asU32Words().single(),
                requireWritableMapping = isBssMarker,
                requireExecutableMapping = !isBssMarker,
            )
        }
        val expectedMapsFingerprint = target.pinnedAntiFlashMapsGeneration()
            ?: return RootAntiFlashCycleResult(
            status = RootAntiFlashBackendStatus.TARGET_CHANGED,
            message = "Anti-flash scoped mapping identity is unavailable",
        )
        val result = TargetNativeProbe.runAntiFlashCycle(
            pid = target.pid,
            expectedStartTimeTicks = target.startTimeTicks,
            expectedMapsFingerprint = expectedMapsFingerprint,
            regions = regions.map { region ->
                NativeAntiFlashCycleRegion(
                    address = region.address,
                    originalBytes = region.originalBytes,
                    patchBytes = region.patchBytes,
                )
            },
            bssAddress = bssRange.address,
            writes = nativeWrites,
        )
        return RootAntiFlashCycleResult(
            status = result.status.toAntiFlashStatus(readPhase = false),
            processStartTimeTicks = result.processStartTimeTicks,
            mapsGeneration = result.mapsFingerprint,
            codeRegionValues = result.codeRegionValues,
            bssValue = result.bssValue ?: byteArrayOf(),
            completedWriteCount = result.completedCount,
            failureIndex = result.failedIndex?.plus(1),
            writeAttempted = result.writeAttempted,
            message = result.message,
        )
    }

    override fun readBatch(
        target: RootAntiFlashTarget,
        ranges: List<RootAntiFlashMemoryRange>,
    ): RootAntiFlashReadBatchResult {
        val bssMarkerAddress = target.absoluteAddress(
            RootAntiFlashModule.GAME_APP_BSS,
            RootAntiFlashProfileCatalog.profile.bssMarkerOffset,
        )
        val requests = ranges.map { range ->
            val isBssMarker = range.address == bssMarkerAddress
            NativeMemoryRegionRequest(
                address = range.address,
                byteCount = range.byteCount,
                requireWritableMapping = isBssMarker,
                requireExecutableMapping = !isBssMarker,
            )
        }
        val expectedMapsFingerprint = target.pinnedAntiFlashMapsGeneration()
            ?: return RootAntiFlashReadBatchResult(
            status = RootAntiFlashBackendStatus.TARGET_CHANGED,
            message = "Anti-flash rollback mapping identity is unavailable",
        )
        val result = TargetNativeProbe.readMemoryRegions(
            pid = target.pid,
            expectedStartTimeTicks = target.startTimeTicks,
            expectedMapsFingerprint = expectedMapsFingerprint,
            requests = requests,
        )
        return RootAntiFlashReadBatchResult(
            status = result.status.toAntiFlashStatus(readPhase = true),
            processStartTimeTicks = result.processStartTimeTicks,
            mapsGeneration = if (result.isSuccess) target.mapsGeneration else result.mapsFingerprint,
            values = result.regions.map { it.bytes },
            failureIndex = result.failedIndex?.plus(1),
            message = result.message,
        )
    }

    override fun writeAndVerifyBatch(
        target: RootAntiFlashTarget,
        writes: List<RootAntiFlashMemoryWrite>,
    ): RootAntiFlashWriteBatchResult {
        val bssMarkerAddress = target.absoluteAddress(
            RootAntiFlashModule.GAME_APP_BSS,
            RootAntiFlashProfileCatalog.profile.bssMarkerOffset,
        )
        val nativeWrites = mutableListOf<NativeU32WriteRequest>()
        val nativeToLogicalIndex = mutableListOf<Int>()
        writes.forEach { write ->
            if (write.bytes.isEmpty() || write.bytes.size % Int.SIZE_BYTES != 0 ||
                write.address <= 0L || write.address and 0x3L != 0L
            ) {
                return RootAntiFlashWriteBatchResult(
                    status = RootAntiFlashBackendStatus.INVALID_RESPONSE,
                    failureIndex = write.index,
                    message = "Anti-flash write is not a valid aligned u32 sequence",
                )
            }
            val expectedCurrentWords = write.expectedCurrentBytes?.let { expectedCurrent ->
                if (expectedCurrent.size != write.bytes.size) {
                    return RootAntiFlashWriteBatchResult(
                        status = RootAntiFlashBackendStatus.INVALID_RESPONSE,
                        failureIndex = write.index,
                        message = "Anti-flash conditional write guard has a different width",
                    )
                }
                expectedCurrent.asU32Words()
            }
            write.bytes.asU32Words().forEachIndexed { wordIndex, valueBits ->
                val address = runCatching {
                    Math.addExact(write.address, wordIndex * Int.SIZE_BYTES.toLong())
                }.getOrElse {
                    return RootAntiFlashWriteBatchResult(
                        status = RootAntiFlashBackendStatus.INVALID_RESPONSE,
                        failureIndex = write.index,
                        message = "Anti-flash write address overflowed",
                    )
                }
                val isBssMarker = address == bssMarkerAddress
                nativeWrites += NativeU32WriteRequest(
                    address = address,
                    valueBits = valueBits,
                    expectedCurrentValueBits = expectedCurrentWords?.get(wordIndex),
                    requireWritableMapping = isBssMarker,
                    requireExecutableMapping = !isBssMarker,
                )
                nativeToLogicalIndex += write.index
            }
        }
        if (nativeWrites.any { it.expectedCurrentValueBits == null }) {
            return RootAntiFlashWriteBatchResult(
                status = RootAntiFlashBackendStatus.INVALID_RESPONSE,
                failureIndex = writes.firstOrNull { it.expectedCurrentBytes == null }?.index,
                message = "Anti-flash rollback write is missing its expected current value",
            )
        }
        val expectedMapsFingerprint = target.pinnedAntiFlashMapsGeneration()
            ?: return RootAntiFlashWriteBatchResult(
            status = RootAntiFlashBackendStatus.TARGET_CHANGED,
            message = "Anti-flash write mapping identity is unavailable",
        )
        val result = TargetNativeProbe.rollbackU32Batch(
            pid = target.pid,
            expectedStartTimeTicks = target.startTimeTicks,
            expectedMapsFingerprint = expectedMapsFingerprint,
            writes = nativeWrites,
        )
        val failedLogicalIndex = result.failedIndex
            ?.takeIf { it in nativeToLogicalIndex.indices }
            ?.let(nativeToLogicalIndex::get)
        val completedLogicalWrites = if (result.isSuccess) {
            writes.size
        } else {
            nativeToLogicalIndex
                .take(result.completedCount.coerceIn(0, nativeToLogicalIndex.size))
                .distinct()
                .count { it != failedLogicalIndex }
        }
        return RootAntiFlashWriteBatchResult(
            status = result.status.toAntiFlashStatus(readPhase = false),
            processStartTimeTicks = result.processStartTimeTicks,
            mapsGeneration = if (result.isSuccess) target.mapsGeneration else result.mapsFingerprint,
            completedWriteCount = completedLogicalWrites,
            failureIndex = failedLogicalIndex,
            message = result.message,
        )
    }

    override fun sleepMillis(milliseconds: Long) = Thread.sleep(milliseconds)
}

private fun RootAntiFlashTarget.pinnedAntiFlashMapsGeneration(): String? =
    mapsGeneration.takeIf { generation ->
        generation.length == 16 && generation.all { it in '0'..'9' || it in 'a'..'f' }
    }

private fun NativeMemoryBatchStatus.toAntiFlashStatus(
    readPhase: Boolean,
): RootAntiFlashBackendStatus = when (this) {
    NativeMemoryBatchStatus.OK -> RootAntiFlashBackendStatus.OK
    NativeMemoryBatchStatus.TARGET_NOT_RUNNING,
    NativeMemoryBatchStatus.TARGET_CHANGED -> RootAntiFlashBackendStatus.TARGET_CHANGED
    NativeMemoryBatchStatus.PROFILE_MISMATCH -> RootAntiFlashBackendStatus.PROFILE_MISMATCH
    NativeMemoryBatchStatus.NATIVE_UNAVAILABLE -> RootAntiFlashBackendStatus.UNAVAILABLE
    NativeMemoryBatchStatus.VERIFY_FAILED -> RootAntiFlashBackendStatus.VERIFY_FAILED
    NativeMemoryBatchStatus.WRITE_FAILED,
    NativeMemoryBatchStatus.PARTIAL_WRITE -> RootAntiFlashBackendStatus.WRITE_FAILED
    NativeMemoryBatchStatus.READ_FAILED,
    NativeMemoryBatchStatus.PARTIAL_READ -> if (readPhase) {
        RootAntiFlashBackendStatus.READ_FAILED
    } else {
        RootAntiFlashBackendStatus.VERIFY_FAILED
    }
    NativeMemoryBatchStatus.MEM_OPEN_FAILED,
    NativeMemoryBatchStatus.PROC_UNREADABLE,
    NativeMemoryBatchStatus.MAPS_UNREADABLE,
    NativeMemoryBatchStatus.ADDRESS_NOT_READABLE -> if (readPhase) {
        RootAntiFlashBackendStatus.READ_FAILED
    } else {
        RootAntiFlashBackendStatus.WRITE_FAILED
    }
    else -> RootAntiFlashBackendStatus.INVALID_RESPONSE
}

private fun ByteArray.asU32Words(): List<Long> = buildList(size / Int.SIZE_BYTES) {
    var offset = 0
    while (offset < this@asU32Words.size) {
        var value = 0L
        repeat(Int.SIZE_BYTES) { index ->
            value = value or
                ((this@asU32Words[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        add(value)
        offset += Int.SIZE_BYTES
    }
}

private fun NativeProbeResult.toRootState(): RootNativeProbeState = RootNativeProbeState(
    status = status.toRootStatus(),
    processStartTimeTicks = processStartTimeTicks,
    regionCount = regionCount,
    moduleCount = moduleCount,
    memoryReadableModuleCount = memoryReadableModuleCount,
    memoryReadBytes = memoryReadBytes,
    memoryElfHeaderCount = memoryElfHeaderCount,
    mapsFingerprint = mapsFingerprint,
    truncated = truncated,
    modules = modules
        .asSequence()
        .filter { it.memoryReadable }
        .map { "${it.name} (${it.origin.name})" }
        .distinct()
        .take(RootProtocol.MAX_PROBE_MODULES)
        .toList(),
    message = message,
)

private fun NativeModuleSummary.toRootIdentity(sha256: String?): RootNativeModuleIdentity =
    RootNativeModuleIdentity(
        name = name,
        path = path,
        loadBase = loadBase,
        mappedBytes = mappedBytes,
        memoryElf = memoryElf,
        sha256 = sha256,
    )

private fun NativeScalarReadResult.toRootScalarRead(): TargetScalarReadResult =
    TargetScalarReadResult(
        isSuccess = isSuccess,
        processStartTimeTicks = processStartTimeTicks,
        valueBits = valueBits,
        message = message,
    )

private fun NativeProbeStatus.toRootStatus(): RootNativeProbeStatus =
    RootNativeProbeStatus.fromWireValue(name) ?: RootNativeProbeStatus.NATIVE_ERROR

private fun RootNativeProbeStatus.displayLabel(): String = when (this) {
    RootNativeProbeStatus.IDLE -> "尚未执行"
    RootNativeProbeStatus.OK -> "正常"
    RootNativeProbeStatus.INVALID_PID -> "PID 无效"
    RootNativeProbeStatus.TARGET_NOT_RUNNING -> "目标未运行"
    RootNativeProbeStatus.TARGET_CHANGED -> "目标进程已变化"
    RootNativeProbeStatus.PROC_UNREADABLE -> "进程信息不可读"
    RootNativeProbeStatus.MAPS_UNREADABLE -> "映射表不可读"
    RootNativeProbeStatus.MAPS_EMPTY -> "映射表为空"
    RootNativeProbeStatus.NATIVE_UNAVAILABLE -> "Native 后端不可用"
    RootNativeProbeStatus.INVALID_RESPONSE -> "Native 响应无效"
    RootNativeProbeStatus.NATIVE_ERROR -> "Native 执行异常"
}

private const val MAX_FINGERPRINT_FILE_BYTES = 256L * 1024L * 1024L
private const val FINGERPRINT_BUFFER_BYTES = 64 * 1024
