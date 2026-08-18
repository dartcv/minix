package me.dartcv.minix.control

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Public state exposed through the existing Binder snapshot. */
enum class ControlAntiFlashStatus {
    IDLE,
    STARTING,
    WAITING_FOR_TARGET,
    PREFLIGHT_FAILED,
    RUNNING,
    STOPPING,
    STOPPED,
    TARGET_CHANGED,
    PROFILE_MISMATCH,
    READ_FAILED,
    WRITE_FAILED,
    VERIFY_FAILED,
    ROLLBACK_FAILED,
    BACKEND_UNAVAILABLE,
}

data class ControlAntiFlashState(
    val status: ControlAntiFlashStatus = ControlAntiFlashStatus.IDLE,
    val requestedEnabled: Boolean = false,
    val workerRunning: Boolean = false,
    val applied: Boolean = false,
    val iterationCount: Long = 0L,
    val successfulWriteCount: Long = 0L,
    val lastFailureIndex: Int? = null,
    val targetPid: Int? = null,
    val targetStartTimeTicks: String = "",
    val profileId: String = "",
    val message: String = "",
)

internal enum class ControlAntiFlashModule {
    GAME_APP,
    TPRT,
    GAME_APP_BSS,
}

internal data class ControlAntiFlashModuleProfile(
    val module: ControlAntiFlashModule,
    val name: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val gnuBuildId: String,
)

internal data class ControlAntiFlashCodeRegion(
    val module: ControlAntiFlashModule,
    val rva: Long,
    val originalBytes: ByteArray,
    val patchBytes: ByteArray,
)

internal data class ControlAntiFlashWordWrite(
    val index: Int,
    val module: ControlAntiFlashModule,
    val offset: Long,
    val valueBits: Long,
) {
    val bytes: ByteArray
        get() = littleEndianInt32(valueBits)
}

internal data class ControlAntiFlashProfile(
    val profileId: String,
    val targetVersion: String,
    val requiredAbi: String,
    val gameApp: ControlAntiFlashModuleProfile,
    val tprt: ControlAntiFlashModuleProfile,
    val codeRegions: List<ControlAntiFlashCodeRegion>,
    val writes: List<ControlAntiFlashWordWrite>,
    val bssMarkerOffset: Long,
    val iterationDelayMillis: Long,
) {
    val isComplete: Boolean
        get() = profileId.isNotBlank() &&
            targetVersion.isNotBlank() &&
            requiredAbi == "arm64-v8a" &&
            gameApp.isComplete(ControlAntiFlashModule.GAME_APP) &&
            tprt.isComplete(ControlAntiFlashModule.TPRT) &&
            codeRegions.size == EXPECTED_CODE_REGION_COUNT &&
            codeRegions.all { region ->
                region.module != ControlAntiFlashModule.GAME_APP_BSS &&
                    region.rva >= 0L &&
                    region.originalBytes.isNotEmpty() &&
                    region.originalBytes.size == region.patchBytes.size
            } &&
            writes.map(ControlAntiFlashWordWrite::index) == (1..EXPECTED_WRITE_COUNT).toList() &&
            writes.all { it.offset >= 0L && it.valueBits ushr Int.SIZE_BITS == 0L } &&
            writes.lastOrNull()?.let { write ->
                write.module == ControlAntiFlashModule.GAME_APP_BSS &&
                    write.offset == bssMarkerOffset &&
                    write.valueBits == BSS_MARKER_VALUE
            } == true &&
            bssMarkerOffset == 0x80L &&
            iterationDelayMillis == 22L

    private fun ControlAntiFlashModuleProfile.isComplete(
        expectedModule: ControlAntiFlashModule,
    ): Boolean = module == expectedModule &&
        name.endsWith(".so") &&
        sha256.length == 64 &&
        sha256.all(Char::isLowerHexDigit) &&
        fileSizeBytes > 0L &&
        gnuBuildId.length == 40 &&
        gnuBuildId.all(Char::isLowerHexDigit)

    private companion object {
        const val EXPECTED_CODE_REGION_COUNT = 6
        const val EXPECTED_WRITE_COUNT = 17
        const val BSS_MARKER_VALUE = 0x0024_75f6L
    }
}

/** Exact static profile recovered from the pinned 1.58.2 arm64 libraries. */
internal object ControlAntiFlashProfileCatalog {
    private val gameApp = ControlAntiFlashModuleProfile(
        module = ControlAntiFlashModule.GAME_APP,
        name = "liblibGameApp.so",
        sha256 = "d8efe55c37b061ce41cb3830ec743401138bf40b8bacf251f18f7b026bf0140e",
        fileSizeBytes = 176_533_320L,
        gnuBuildId = "662a450a7331aff319cbc4b3897c2124f8fd61f9",
    )
    private val tprt = ControlAntiFlashModuleProfile(
        module = ControlAntiFlashModule.TPRT,
        name = "libtprt.so",
        sha256 = "056726e1419d68284deafa21b64e55a919adacd7a8171c9e1d5fb1998cd95cc8",
        fileSizeBytes = 1_883_784L,
        gnuBuildId = "e9e9ba3856e5c644983d3886abf9a4ddb253e77c",
    )

    val profile = ControlAntiFlashProfile(
        profileId = "miniworld-1.58.2-arm64-antiflash-v1",
        targetVersion = "1.58.2",
        requiredAbi = "arm64-v8a",
        gameApp = gameApp,
        tprt = tprt,
        codeRegions = listOf(
            region(ControlAntiFlashModule.GAME_APP, 0x0925_3ce8L, "766b3b94", "1f2003d5"),
            region(
                ControlAntiFlashModule.TPRT,
                0x0014_c288L,
                "ff8301d1fd7b02a9f71b00f9",
                "80078052f1df0094feffff17",
            ),
            region(
                ControlAntiFlashModule.TPRT,
                0x0016_ce78L,
                "ff4303d1fd7b0aa9f6570ba9",
                "80078052f55c0094feffff17",
            ),
            region(
                ControlAntiFlashModule.TPRT,
                0x0013_c5fcL,
                "ffc301d1fd7b04a9f52b00f9",
                "80078052141f0194feffff17",
            ),
            region(
                ControlAntiFlashModule.TPRT,
                0x0013_cc74L,
                "a80400f000f16f39c0035fd6",
                "80078052761d0194feffff17",
            ),
            region(
                ControlAntiFlashModule.TPRT,
                0x000f_c270L,
                "ff8300d1fd7b01a9fd430091",
                "80078052f71f0294feffff17",
            ),
        ),
        writes = listOf(
            word(1, ControlAntiFlashModule.GAME_APP, 0x0925_3ce8L, 0xd503_201fL),
            word(2, ControlAntiFlashModule.TPRT, 0x0014_c288L, 0x5280_0780L),
            word(3, ControlAntiFlashModule.TPRT, 0x0014_c28cL, 0x9400_dff1L),
            word(4, ControlAntiFlashModule.TPRT, 0x0014_c290L, 0x17ff_fffeL),
            word(5, ControlAntiFlashModule.TPRT, 0x0016_ce78L, 0x5280_0780L),
            word(6, ControlAntiFlashModule.TPRT, 0x0016_ce7cL, 0x9400_5cf5L),
            word(7, ControlAntiFlashModule.TPRT, 0x0016_ce80L, 0x17ff_fffeL),
            word(8, ControlAntiFlashModule.TPRT, 0x0013_c5fcL, 0x5280_0780L),
            word(9, ControlAntiFlashModule.TPRT, 0x0013_c600L, 0x9401_1f14L),
            word(10, ControlAntiFlashModule.TPRT, 0x0013_c604L, 0x17ff_fffeL),
            word(11, ControlAntiFlashModule.TPRT, 0x0013_cc74L, 0x5280_0780L),
            word(12, ControlAntiFlashModule.TPRT, 0x0013_cc78L, 0x9401_1d76L),
            word(13, ControlAntiFlashModule.TPRT, 0x0013_cc7cL, 0x17ff_fffeL),
            word(14, ControlAntiFlashModule.TPRT, 0x000f_c270L, 0x5280_0780L),
            word(15, ControlAntiFlashModule.TPRT, 0x000f_c274L, 0x9402_1ff7L),
            word(16, ControlAntiFlashModule.TPRT, 0x000f_c278L, 0x17ff_fffeL),
            word(17, ControlAntiFlashModule.GAME_APP_BSS, 0x80L, 0x0024_75f6L),
        ),
        bssMarkerOffset = 0x80L,
        iterationDelayMillis = 22L,
    )

    val fingerprintModuleNames: Set<String> = setOf(gameApp.name, tprt.name)

    init {
        check(profile.isComplete) { "Bundled anti-flash profile is incomplete" }
    }

    private fun region(
        module: ControlAntiFlashModule,
        rva: Long,
        originalHex: String,
        patchHex: String,
    ) = ControlAntiFlashCodeRegion(module, rva, decodeAntiFlashHex(originalHex), decodeAntiFlashHex(patchHex))

    private fun word(
        index: Int,
        module: ControlAntiFlashModule,
        offset: Long,
        valueBits: Long,
    ) = ControlAntiFlashWordWrite(index, module, offset, valueBits)
}

internal data class ControlAntiFlashStartRequest(
    val pid: Int,
    val startTimeTicks: String,
    val mapsGeneration: String,
    val modules: List<ControlNativeModuleIdentity>,
)

internal data class ControlAntiFlashInstalledProfile(
    val gameAppPath: String,
    val tprtPath: String,
)

internal data class ControlAntiFlashInstalledProfileResult(
    val profile: ControlAntiFlashInstalledProfile? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = profile != null
}

internal fun verifyControlAntiFlashInstalledProfile(
    nativeLibraryDir: String,
    profile: ControlAntiFlashProfile = ControlAntiFlashProfileCatalog.profile,
): ControlAntiFlashInstalledProfileResult {
    val directory = nativeLibraryDir.trim().takeIf(String::isNotEmpty)?.let(::File)
        ?: return ControlAntiFlashInstalledProfileResult(message = "Target native library directory is missing")
    val gameApp = File(directory, profile.gameApp.name)
    val tprt = File(directory, profile.tprt.name)
    val checks = listOf(gameApp to profile.gameApp, tprt to profile.tprt)
    checks.forEach { (file, expected) ->
        if (!file.isFile || file.length() != expected.fileSizeBytes) {
            return ControlAntiFlashInstalledProfileResult(
                message = "${expected.name} size does not match the anti-flash profile",
            )
        }
        val sha256 = antiFlashSha256(file, expected.fileSizeBytes)
        if (sha256 != expected.sha256) {
            return ControlAntiFlashInstalledProfileResult(
                message = "${expected.name} fingerprint does not match the anti-flash profile",
            )
        }
    }
    return ControlAntiFlashInstalledProfileResult(
        profile = ControlAntiFlashInstalledProfile(
            gameAppPath = gameApp.absolutePath,
            tprtPath = tprt.absolutePath,
        ),
        message = "Installed anti-flash modules verified",
    )
}

/** Resolves only the two pinned modules and BSS mapping needed to start anti-flash. */
internal class ProcControlAntiFlashLaunchRequestResolver(
    private val processInspector: TargetProcessInspector,
    private val procControl: File = File("/proc"),
    private val profile: ControlAntiFlashProfile = ControlAntiFlashProfileCatalog.profile,
) {
    fun resolve(
        pid: Int,
        installedProfile: ControlAntiFlashInstalledProfile,
    ): ControlAntiFlashStartRequest? = runCatching {
        val startTimeTicks = processInspector.readStartTimeTicks(pid)
            ?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        val maps = readMaps(pid) ?: return@runCatching null
        if (processInspector.readStartTimeTicks(pid) != startTimeTicks) return@runCatching null

        val gameAppLoad = selectLoad(
            rows = maps.rows,
            expectedPath = installedProfile.gameAppPath,
            expectedName = profile.gameApp.name,
        ) ?: return@runCatching null
        val tprtLoad = selectLoad(
            rows = maps.rows,
            expectedPath = installedProfile.tprtPath,
            expectedName = profile.tprt.name,
        ) ?: return@runCatching null
        val bssStart = checkedAntiFlashAdd(
            gameAppLoad.start,
            ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
        ) ?: return@runCatching null
        val bssEnd = checkedAntiFlashAdd(
            gameAppLoad.start,
            ControlGameAppArtifact1582.ANON_BSS_4K_END,
        ) ?: return@runCatching null
        if (maps.rows.count { row ->
                row.start == bssStart && row.end == bssEnd && row.permissions == "rw-p" &&
                    row.fileOffset == 0L && row.path == "[anon:.bss]"
            } != 1
        ) {
            return@runCatching null
        }

        ControlAntiFlashStartRequest(
            pid = pid,
            startTimeTicks = startTimeTicks,
            mapsGeneration = maps.fingerprint,
            modules = listOf(
                gameAppLoad.toModuleIdentity(maps.rows, profile.gameApp),
                tprtLoad.toModuleIdentity(maps.rows, profile.tprt),
            ),
        )
    }.getOrNull()

    private fun selectLoad(
        rows: List<AntiFlashMapRow>,
        expectedPath: String,
        expectedName: String,
    ): AntiFlashMapRow? = rows.filter { row ->
        val path = row.path?.removeSuffix(" (deleted)")
        row.fileOffset == 0L && row.permissions == "r-xp" && path == expectedPath &&
            path.substringAfterLast('/') == expectedName
    }.singleOrNull()

    private fun AntiFlashMapRow.toModuleIdentity(
        rows: List<AntiFlashMapRow>,
        expected: ControlAntiFlashModuleProfile,
    ): ControlNativeModuleIdentity {
        val normalizedPath = requireNotNull(path).removeSuffix(" (deleted)")
        val mappedBytes = rows.asSequence()
            .filter { row -> row.path?.removeSuffix(" (deleted)") == normalizedPath }
            .fold(0L) { total, row -> Math.addExact(total, row.end - row.start) }
        return ControlNativeModuleIdentity(
            name = expected.name,
            path = normalizedPath,
            loadBase = start,
            mappedBytes = mappedBytes,
            memoryElf = true,
            sha256 = expected.sha256,
        )
    }

    private fun readMaps(pid: Int): AntiFlashMapsSnapshot? = runCatching {
        var count = 0
        var bytes = 0L
        var fingerprint = ANTI_FLASH_FNV_OFFSET_BASIS
        val rows = ArrayList<AntiFlashMapRow>()
        File(procControl, "$pid/maps").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val encoded = line.toByteArray(StandardCharsets.UTF_8)
                count += 1
                bytes = Math.addExact(bytes, encoded.size.toLong() + 1L)
                if (count > MAX_MAP_LINES || bytes > MAX_MAP_BYTES || encoded.size > MAX_MAP_LINE_CHARS) {
                    return@runCatching null
                }
                encoded.forEach { value ->
                    fingerprint = (fingerprint xor (value.toLong() and 0xffL)) * ANTI_FLASH_FNV_PRIME
                }
                fingerprint = (fingerprint xor '\n'.code.toLong()) * ANTI_FLASH_FNV_PRIME
                rows += parseAntiFlashMapRow(line) ?: return@runCatching null
            }
        }
        AntiFlashMapsSnapshot(
            rows = rows,
            fingerprint = java.lang.Long.toUnsignedString(fingerprint, 16).padStart(16, '0'),
        )
    }.getOrNull()

    private companion object {
        const val MAX_MAP_LINES = 50_000
        const val MAX_MAP_BYTES = 4L * 1024L * 1024L
        const val MAX_MAP_LINE_CHARS = 4_096
    }
}

/** A scoped, early-launch target that exists as soon as tprt is mapped. */
internal data class ControlAntiFlashTprtBootstrapTarget(
    val pid: Int,
    val startTimeTicks: String,
    val mapsGeneration: String,
    val tprtLoadBias: Long,
    val tprtPath: String,
    val scopedMappings: List<ControlAntiFlashScopedMapping>,
) {
    internal fun asBackendTarget(): ControlAntiFlashTarget = ControlAntiFlashTarget(
        pid = pid,
        startTimeTicks = startTimeTicks,
        mapsGeneration = mapsGeneration,
        gameAppLoadBias = 0L,
        tprtLoadBias = tprtLoadBias,
        gameAppBssAnchor = 0L,
        gameAppPath = "",
        tprtPath = tprtPath,
        scopedMappings = scopedMappings,
    )
}

internal data class ControlAntiFlashTprtBootstrapResolution(
    val status: ControlAntiFlashResolveStatus,
    val target: ControlAntiFlashTprtBootstrapTarget? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlAntiFlashResolveStatus.RESOLVED && target != null
}

/**
 * Resolves only tprt and fingerprints only the RX mapping that covers its five
 * anti-flash regions. Later GameApp mappings therefore do not invalidate this
 * early-launch target.
 */
internal class ProcControlAntiFlashTprtBootstrapResolver(
    private val processInspector: TargetProcessInspector,
    private val procControl: File = File("/proc"),
    private val profile: ControlAntiFlashProfile = ControlAntiFlashProfileCatalog.profile,
) {
    fun resolve(
        pid: Int,
        installedProfile: ControlAntiFlashInstalledProfile,
    ): ControlAntiFlashTprtBootstrapResolution = runCatching {
        if (pid <= 0 || installedProfile.tprtPath.isBlank()) {
            return@runCatching failure(
                ControlAntiFlashResolveStatus.INVALID_REQUEST,
                "tprt bootstrap request is incomplete",
            )
        }
        val startTimeTicks = processInspector.readStartTimeTicks(pid)
            ?.takeIf(String::isNotBlank)
            ?: return@runCatching failure(
                ControlAntiFlashResolveStatus.TARGET_CHANGED,
                "Target identity is unavailable before tprt bootstrap",
            )
        val maps = readMaps(pid) ?: return@runCatching failure(
            ControlAntiFlashResolveStatus.MAPS_UNAVAILABLE,
            "Target maps are unavailable before tprt bootstrap",
        )
        if (processInspector.readStartTimeTicks(pid) != startTimeTicks) {
            return@runCatching failure(
                ControlAntiFlashResolveStatus.TARGET_CHANGED,
                "Target identity changed while resolving tprt bootstrap",
            )
        }

        val expectedPath = installedProfile.tprtPath.removeSuffix(" (deleted)")
        val loads = maps.rows.filter { row ->
            val path = row.path?.removeSuffix(" (deleted)")
            row.fileOffset == 0L && row.permissions == "r-xp" && path == expectedPath &&
                path.substringAfterLast('/') == profile.tprt.name
        }
        if (loads.isEmpty()) {
            return@runCatching failure(
                ControlAntiFlashResolveStatus.MODULE_NOT_FOUND,
                "tprt offset-zero RX mapping is not ready",
            )
        }
        if (loads.size != 1) {
            return@runCatching failure(
                ControlAntiFlashResolveStatus.MODULE_AMBIGUOUS,
                "tprt offset-zero RX mapping is ambiguous",
            )
        }
        val load = loads.single()
        val ranges = tprtRegions().map { region ->
            val address = checkedAntiFlashAdd(load.start, region.rva)
                ?: return@runCatching failure(
                    ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE,
                    "tprt bootstrap region address overflowed",
                )
            if (!load.covers(address, region.originalBytes.size)) {
                return@runCatching failure(
                    ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE,
                    "tprt bootstrap region falls outside the RX mapping",
                )
            }
            ControlAntiFlashMappingRange(
                address = address,
                byteCount = region.originalBytes.size,
                requireWritable = false,
                requireExecutable = true,
            )
        }
        val scopedMappings = maps.rows.map(AntiFlashMapRow::toScopedMapping)
        val provisional = ControlAntiFlashTprtBootstrapTarget(
            pid = pid,
            startTimeTicks = startTimeTicks,
            mapsGeneration = "0000000000000000",
            tprtLoadBias = load.start,
            tprtPath = expectedPath,
            scopedMappings = scopedMappings,
        )
        val generation = provisional.asBackendTarget().scopedMapsGeneration(ranges)
            ?: return@runCatching failure(
                ControlAntiFlashResolveStatus.MODULE_AMBIGUOUS,
                "tprt bootstrap scoped mapping is absent or ambiguous",
            )
        ControlAntiFlashTprtBootstrapResolution(
            status = ControlAntiFlashResolveStatus.RESOLVED,
            target = provisional.copy(mapsGeneration = generation),
            message = "tprt bootstrap target resolved",
        )
    }.getOrElse {
        failure(
            ControlAntiFlashResolveStatus.MAPS_UNAVAILABLE,
            "tprt bootstrap resolution failed closed",
        )
    }

    private fun tprtRegions(): List<ControlAntiFlashCodeRegion> =
        profile.codeRegions.filter { it.module == ControlAntiFlashModule.TPRT }

    private fun readMaps(pid: Int): AntiFlashMapsSnapshot? = runCatching {
        var count = 0
        var bytes = 0L
        var fingerprint = ANTI_FLASH_FNV_OFFSET_BASIS
        val rows = ArrayList<AntiFlashMapRow>()
        File(procControl, "$pid/maps").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val encoded = line.toByteArray(StandardCharsets.UTF_8)
                count += 1
                bytes = Math.addExact(bytes, encoded.size.toLong() + 1L)
                if (count > MAX_MAP_LINES || bytes > MAX_MAP_BYTES || encoded.size > MAX_MAP_LINE_CHARS) {
                    return@runCatching null
                }
                encoded.forEach { value ->
                    fingerprint = (fingerprint xor (value.toLong() and 0xffL)) * ANTI_FLASH_FNV_PRIME
                }
                fingerprint = (fingerprint xor '\n'.code.toLong()) * ANTI_FLASH_FNV_PRIME
                rows += parseAntiFlashMapRow(line) ?: return@runCatching null
            }
        }
        AntiFlashMapsSnapshot(
            rows = rows,
            fingerprint = java.lang.Long.toUnsignedString(fingerprint, 16).padStart(16, '0'),
        )
    }.getOrNull()

    private fun failure(
        status: ControlAntiFlashResolveStatus,
        message: String,
    ) = ControlAntiFlashTprtBootstrapResolution(status = status, message = message.take(256))

    private companion object {
        const val MAX_MAP_LINES = 50_000
        const val MAX_MAP_BYTES = 4L * 1024L * 1024L
        const val MAX_MAP_LINE_CHARS = 4_096
    }
}

internal data class ControlAntiFlashTprtBootstrapState(
    val status: ControlAntiFlashStatus = ControlAntiFlashStatus.IDLE,
    val applied: Boolean = false,
    val handedOff: Boolean = false,
    val iterationCount: Long = 0L,
    val successfulWriteCount: Long = 0L,
    val lastFailureIndex: Int? = null,
    val targetPid: Int? = null,
    val targetStartTimeTicks: String = "",
    val message: String = "",
) {
    val isPatched: Boolean
        get() = status == ControlAntiFlashStatus.RUNNING && applied && !handedOff
}

/**
 * Applies the 15 tprt words before GameApp exists. Each forward write is a
 * guarded conditional batch built from an immediately preceding read. The
 * caller may repeat [ensurePatched] until the full supervisor has completed
 * its first 17-write cycle, then call [handoff].
 */
internal class ControlAntiFlashTprtBootstrap(
    private val backend: ControlAntiFlashBackend = NoControlAntiFlashBackend,
    private val profile: ControlAntiFlashProfile = ControlAntiFlashProfileCatalog.profile,
) {
    private val regions = profile.codeRegions.filter { it.module == ControlAntiFlashModule.TPRT }
    private val writes = profile.writes.filter { it.module == ControlAntiFlashModule.TPRT }
    private var target: ControlAntiFlashTprtBootstrapTarget? = null
    private var state = ControlAntiFlashTprtBootstrapState()

    init {
        require(regions.size == 5 && writes.size == 15) { "tprt bootstrap profile shape is invalid" }
    }

    /** Runs one guarded 15-word tprt patch iteration. */
    @Synchronized
    fun ensurePatched(candidate: ControlAntiFlashTprtBootstrapTarget): ControlAntiFlashTprtBootstrapState {
        val active = target
        if (active != null && !active.sameIdentityAndMapping(candidate)) {
            state = state.copy(
                status = ControlAntiFlashStatus.TARGET_CHANGED,
                lastFailureIndex = null,
                message = "tprt bootstrap target changed before handoff",
            )
            return state
        }
        if (active == null) {
            target = candidate
            state = ControlAntiFlashTprtBootstrapState(
                status = ControlAntiFlashStatus.STARTING,
                targetPid = candidate.pid,
                targetStartTimeTicks = candidate.startTimeTicks,
                message = "Preflighting tprt bootstrap regions",
            )
        }
        val bound = requireNotNull(target)
        val backendTarget = bound.asBackendTarget()
        val ranges = regions.map { region ->
            ControlAntiFlashMemoryRange(
                address = requireNotNull(backendTarget.absoluteAddress(ControlAntiFlashModule.TPRT, region.rva)),
                byteCount = region.originalBytes.size,
            )
        }
        val read = runCatching { backend.readBatch(backendTarget, ranges) }.getOrElse { error ->
            return failBeforeForwardWrite(
                ControlAntiFlashStatus.BACKEND_UNAVAILABLE,
                null,
                error.message.orEmpty(),
            )
        }
        if (!read.matches(backendTarget)) {
            val status = when {
                read.status != ControlAntiFlashBackendStatus.OK -> read.status.toPublicStatus(readPhase = true)
                else -> ControlAntiFlashStatus.TARGET_CHANGED
            }
            return failBeforeForwardWrite(
                status,
                read.failureIndex,
                read.message.ifBlank { "tprt bootstrap preflight identity changed" },
            )
        }
        val shapeValid = read.values.size == regions.size && read.values.indices.all { index ->
            read.values[index].size == regions[index].originalBytes.size
        }
        if (!shapeValid) {
            return failBeforeForwardWrite(
                ControlAntiFlashStatus.PREFLIGHT_FAILED,
                read.failureIndex,
                "tprt bootstrap preflight returned an invalid region shape",
            )
        }
        val profileMatches = read.values.indices.all { index ->
            val current = read.values[index]
            current.contentEquals(regions[index].originalBytes) ||
                current.contentEquals(regions[index].patchBytes)
        }
        if (!profileMatches) {
            return failBeforeForwardWrite(
                ControlAntiFlashStatus.PROFILE_MISMATCH,
                firstMismatchedWriteIndex(read.values),
                "tprt bootstrap code region has an unknown value",
            )
        }
        val guardedWrites = writes.map { write ->
            val current = currentBytesForWrite(write, read.values)
            ControlAntiFlashMemoryWrite(
                index = write.index,
                address = requireNotNull(backendTarget.absoluteAddress(ControlAntiFlashModule.TPRT, write.offset)),
                bytes = write.bytes,
                expectedCurrentBytes = current,
            )
        }
        val result = runCatching {
            backend.writeAndVerifyBatch(backendTarget, guardedWrites)
        }.getOrElse { error ->
            state = state.copy(
                status = ControlAntiFlashStatus.BACKEND_UNAVAILABLE,
                applied = true,
                handedOff = false,
                message = error.message.orEmpty().ifBlank {
                    "tprt bootstrap backend failed after the guarded write began"
                }.take(256),
            )
            return state
        }
        val identityMatches = result.processStartTimeTicks == bound.startTimeTicks &&
            result.mapsGeneration == bound.mapsGeneration
        if (result.status != ControlAntiFlashBackendStatus.OK || !identityMatches ||
            result.completedWriteCount != guardedWrites.size || result.failureIndex != null
        ) {
            val status = when {
                result.status != ControlAntiFlashBackendStatus.OK -> result.status.toPublicStatus()
                !identityMatches -> ControlAntiFlashStatus.TARGET_CHANGED
                else -> ControlAntiFlashStatus.WRITE_FAILED
            }
            state = state.copy(
                status = status,
                applied = true,
                handedOff = false,
                successfulWriteCount = state.successfulWriteCount + result.completedWriteCount,
                lastFailureIndex = result.failureIndex,
                message = result.message.ifBlank {
                    "tprt bootstrap guarded write did not complete"
                }.take(256),
            )
            return state
        }
        state = state.copy(
            status = ControlAntiFlashStatus.RUNNING,
            applied = true,
            handedOff = false,
            iterationCount = state.iterationCount + 1L,
            successfulWriteCount = state.successfulWriteCount + guardedWrites.size,
            lastFailureIndex = null,
            message = "15 tprt bootstrap writes verified",
        )
        return state
    }

    /**
     * Releases ownership without restoring tprt. Call only after the complete
     * supervisor has successfully applied and now owns rollback responsibility.
     */
    @Synchronized
    fun handoff(request: ControlAntiFlashStartRequest): ControlAntiFlashTprtBootstrapState {
        val active = target ?: return state
        val module = request.modules.filter { it.name == profile.tprt.name }.singleOrNull()
        val matches = state.applied && request.pid == active.pid &&
            request.startTimeTicks == active.startTimeTicks && module != null && module.memoryElf &&
            module.loadBase == active.tprtLoadBias &&
            module.path.removeSuffix(" (deleted)") == active.tprtPath &&
            module.sha256?.trim()?.lowercase() == profile.tprt.sha256
        if (!matches) {
            state = state.copy(
                status = ControlAntiFlashStatus.TARGET_CHANGED,
                handedOff = false,
                message = "Complete anti-flash target does not match the tprt bootstrap owner",
            )
            return state
        }
        target = null
        state = state.copy(
            status = ControlAntiFlashStatus.STOPPED,
            applied = false,
            handedOff = true,
            lastFailureIndex = null,
            message = "tprt bootstrap handed off to the complete supervisor",
        )
        return state
    }

    /** Restores all 15 tprt words while this bootstrap still owns them. */
    @Synchronized
    fun rollback(): ControlAntiFlashTprtBootstrapState {
        val active = target
        if (active == null || !state.applied) {
            target = null
            state = state.copy(
                status = ControlAntiFlashStatus.STOPPED,
                applied = false,
                message = if (state.handedOff) {
                    "tprt bootstrap rollback is owned by the complete supervisor"
                } else {
                    "tprt bootstrap stopped"
                },
            )
            return state
        }
        val backendTarget = active.asBackendTarget()
        val ranges = writes.map { write ->
            ControlAntiFlashMemoryRange(
                address = requireNotNull(backendTarget.absoluteAddress(ControlAntiFlashModule.TPRT, write.offset)),
                byteCount = Int.SIZE_BYTES,
            )
        }
        val read = runCatching { backend.readBatch(backendTarget, ranges) }.getOrElse { error ->
            return rollbackFailure(null, error.message.orEmpty())
        }
        if (read.status == ControlAntiFlashBackendStatus.TARGET_CHANGED &&
            read.processStartTimeTicks != active.startTimeTicks
        ) {
            return releaseExitedTarget("tprt bootstrap target identity no longer exists")
        }
        if (!read.matches(backendTarget) || read.values.size != ranges.size ||
            read.values.any { it.size != Int.SIZE_BYTES }
        ) {
            return rollbackFailure(
                read.failureIndex,
                read.message.ifBlank { "tprt bootstrap rollback preflight failed" },
            )
        }
        val rollbackWrites = writes.mapIndexed { index, write ->
            val current = read.values[index]
            val original = originalBytesForWrite(write)
            if (!current.contentEquals(original) && !current.contentEquals(write.bytes) &&
                !current.isBytewiseBlendOf(original, write.bytes)
            ) {
                return rollbackFailure(write.index, "tprt bootstrap rollback word has an unknown value")
            }
            ControlAntiFlashMemoryWrite(
                index = write.index,
                address = ranges[index].address,
                bytes = original,
                expectedCurrentBytes = current.copyOf(),
            )
        }
        val result = runCatching {
            backend.writeAndVerifyBatch(backendTarget, rollbackWrites)
        }.getOrElse { error ->
            return rollbackFailure(null, error.message.orEmpty())
        }
        if (result.status == ControlAntiFlashBackendStatus.TARGET_CHANGED &&
            result.processStartTimeTicks != active.startTimeTicks
        ) {
            return releaseExitedTarget("tprt bootstrap target exited during rollback")
        }
        if (!result.matches(backendTarget) || result.status != ControlAntiFlashBackendStatus.OK ||
            result.completedWriteCount != rollbackWrites.size || result.failureIndex != null
        ) {
            return rollbackFailure(
                result.failureIndex,
                result.message.ifBlank { "tprt bootstrap rollback write failed" },
            )
        }
        target = null
        state = state.copy(
            status = ControlAntiFlashStatus.STOPPED,
            applied = false,
            handedOff = false,
            lastFailureIndex = null,
            message = "15 tprt bootstrap writes rolled back",
        )
        return state
    }

    @Synchronized
    fun stateSnapshot(): ControlAntiFlashTprtBootstrapState = state

    private fun failBeforeForwardWrite(
        status: ControlAntiFlashStatus,
        failureIndex: Int?,
        message: String,
    ): ControlAntiFlashTprtBootstrapState {
        if (!state.applied && status == ControlAntiFlashStatus.TARGET_CHANGED) target = null
        state = state.copy(
            status = status,
            lastFailureIndex = failureIndex,
            message = message.take(256),
        )
        return state
    }

    private fun rollbackFailure(
        failureIndex: Int?,
        message: String,
    ): ControlAntiFlashTprtBootstrapState {
        state = state.copy(
            status = ControlAntiFlashStatus.ROLLBACK_FAILED,
            applied = true,
            handedOff = false,
            lastFailureIndex = failureIndex,
            message = message.ifBlank { "tprt bootstrap rollback failed" }.take(256),
        )
        return state
    }

    private fun releaseExitedTarget(message: String): ControlAntiFlashTprtBootstrapState {
        target = null
        state = state.copy(
            status = ControlAntiFlashStatus.STOPPED,
            applied = false,
            handedOff = false,
            lastFailureIndex = null,
            message = message.take(256),
        )
        return state
    }

    private fun firstMismatchedWriteIndex(values: List<ByteArray>): Int? {
        val regionIndex = values.indices.firstOrNull { index ->
            val current = values[index]
            !current.contentEquals(regions[index].originalBytes) &&
                !current.contentEquals(regions[index].patchBytes)
        } ?: return null
        return writes.firstOrNull { write ->
            write.offset >= regions[regionIndex].rva &&
                write.offset < regions[regionIndex].rva + regions[regionIndex].originalBytes.size
        }?.index
    }

    private fun currentBytesForWrite(
        write: ControlAntiFlashWordWrite,
        currentRegions: List<ByteArray>,
    ): ByteArray {
        val regionIndex = regions.indices.single { index ->
            val region = regions[index]
            write.offset >= region.rva &&
                write.offset + Int.SIZE_BYTES <= region.rva + region.originalBytes.size
        }
        val region = regions[regionIndex]
        val offset = (write.offset - region.rva).toInt()
        return currentRegions[regionIndex].copyOfRange(offset, offset + Int.SIZE_BYTES)
    }

    private fun originalBytesForWrite(write: ControlAntiFlashWordWrite): ByteArray {
        val region = regions.single { candidate ->
            write.offset >= candidate.rva &&
                write.offset + Int.SIZE_BYTES <= candidate.rva + candidate.originalBytes.size
        }
        val offset = (write.offset - region.rva).toInt()
        return region.originalBytes.copyOfRange(offset, offset + Int.SIZE_BYTES)
    }

    private fun ControlAntiFlashTprtBootstrapTarget.sameIdentityAndMapping(
        other: ControlAntiFlashTprtBootstrapTarget,
    ): Boolean = pid == other.pid && startTimeTicks == other.startTimeTicks &&
        mapsGeneration == other.mapsGeneration && tprtLoadBias == other.tprtLoadBias &&
        tprtPath == other.tprtPath
}

internal data class ControlAntiFlashTarget(
    val pid: Int,
    val startTimeTicks: String,
    val mapsGeneration: String,
    val gameAppLoadBias: Long,
    val tprtLoadBias: Long,
    val gameAppBssAnchor: Long,
    val gameAppPath: String,
    val tprtPath: String,
    val scopedMappings: List<ControlAntiFlashScopedMapping> = emptyList(),
) {
    fun absoluteAddress(module: ControlAntiFlashModule, offset: Long): Long? {
        val base = when (module) {
            ControlAntiFlashModule.GAME_APP -> gameAppLoadBias
            ControlAntiFlashModule.TPRT -> tprtLoadBias
            ControlAntiFlashModule.GAME_APP_BSS -> gameAppBssAnchor
        }
        return runCatching { Math.addExact(base, offset) }.getOrNull()?.takeIf { it > 0L }
    }

}

internal data class ControlAntiFlashScopedMapping(
    val start: Long,
    val end: Long,
    val permissions: String,
    val fileOffset: Long,
    val device: String,
    val inode: String,
    val path: String,
) {
    fun covers(address: Long, byteCount: Int): Boolean {
        if (address < start || byteCount <= 0) return false
        val limit = runCatching { Math.addExact(address, byteCount.toLong()) }.getOrNull()
            ?: return false
        return limit <= end
    }
}

internal data class ControlAntiFlashMappingRange(
    val address: Long,
    val byteCount: Int,
    val requireWritable: Boolean,
    val requireExecutable: Boolean,
)

internal fun ControlAntiFlashTarget.scopedMapsGeneration(
    ranges: List<ControlAntiFlashMappingRange>,
): String? {
    val selected = linkedSetOf<Int>()
    ranges.forEach { range ->
        val matches = scopedMappings.indices.filter { index ->
            val mapping = scopedMappings[index]
            mapping.permissions.length == 4 && mapping.permissions[0] == 'r' &&
                (!range.requireWritable || mapping.permissions[1] == 'w') &&
                (!range.requireExecutable || mapping.permissions[2] == 'x') &&
                mapping.covers(range.address, range.byteCount)
        }
        if (matches.size != 1) return null
        selected += matches.single()
    }
    var fingerprint = ANTI_FLASH_FNV_OFFSET_BASIS
    selected.sorted().forEach { index ->
        val mapping = scopedMappings[index]
        val canonical = buildString {
            append(mapping.start.toString(16).padStart(16, '0'))
            append('-')
            append(mapping.end.toString(16).padStart(16, '0'))
            append(' ')
            append(mapping.permissions)
            append(' ')
            append(mapping.fileOffset.toString(16).padStart(16, '0'))
            append(' ')
            append(mapping.device)
            append(' ')
            append(mapping.inode)
            append(' ')
            append(mapping.path)
        }
        canonical.toByteArray(StandardCharsets.UTF_8).forEach { value ->
            fingerprint = (fingerprint xor (value.toLong() and 0xffL)) * ANTI_FLASH_FNV_PRIME
        }
        fingerprint = (fingerprint xor '\n'.code.toLong()) * ANTI_FLASH_FNV_PRIME
    }
    return java.lang.Long.toUnsignedString(fingerprint, 16).padStart(16, '0')
}

internal enum class ControlAntiFlashResolveStatus {
    RESOLVED,
    INVALID_REQUEST,
    TARGET_CHANGED,
    MAPS_UNAVAILABLE,
    MODULE_NOT_FOUND,
    MODULE_AMBIGUOUS,
    FINGERPRINT_MISMATCH,
    ADDRESS_OUT_OF_RANGE,
}

internal data class ControlAntiFlashTargetResolution(
    val status: ControlAntiFlashResolveStatus,
    val target: ControlAntiFlashTarget? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlAntiFlashResolveStatus.RESOLVED && target != null
}

internal fun interface ControlAntiFlashTargetResolver {
    fun resolve(
        request: ControlAntiFlashStartRequest,
        profile: ControlAntiFlashProfile,
    ): ControlAntiFlashTargetResolution
}

/** Binds only the mappings that cover the anti-flash code and BSS addresses. */
internal class ProcControlAntiFlashTargetResolver(
    private val processInspector: TargetProcessInspector,
    private val procControl: File = File("/proc"),
) : ControlAntiFlashTargetResolver {
    override fun resolve(
        request: ControlAntiFlashStartRequest,
        profile: ControlAntiFlashProfile,
    ): ControlAntiFlashTargetResolution = runCatching { resolveChecked(request, profile) }
        .getOrElse {
            failure(ControlAntiFlashResolveStatus.MAPS_UNAVAILABLE, "Anti-flash target resolution failed closed")
        }

    private fun resolveChecked(
        request: ControlAntiFlashStartRequest,
        profile: ControlAntiFlashProfile,
    ): ControlAntiFlashTargetResolution {
        if (!profile.isComplete || request.pid <= 0 || request.startTimeTicks.isBlank() ||
            request.mapsGeneration.isBlank()
        ) {
            return failure(ControlAntiFlashResolveStatus.INVALID_REQUEST, "Anti-flash start request is incomplete")
        }
        if (processInspector.readStartTimeTicks(request.pid) != request.startTimeTicks) {
            return failure(ControlAntiFlashResolveStatus.TARGET_CHANGED, "Target identity changed before anti-flash resolution")
        }

        val gameApp = selectModule(request.modules, profile.gameApp)
            ?: return moduleFailure(request.modules, profile.gameApp)
        val tprt = selectModule(request.modules, profile.tprt)
            ?: return moduleFailure(request.modules, profile.tprt)
        val initialMaps = readMaps(request.pid)
            ?: return failure(ControlAntiFlashResolveStatus.MAPS_UNAVAILABLE, "Target maps are unavailable")
        if (processInspector.readStartTimeTicks(request.pid) != request.startTimeTicks) {
            return failure(ControlAntiFlashResolveStatus.TARGET_CHANGED, "Target identity changed while reading maps")
        }

        val gameAppLoad = selectLoad(initialMaps.rows, gameApp, profile.gameApp)
            ?: return failure(ControlAntiFlashResolveStatus.MODULE_NOT_FOUND, "GameApp offset-zero RX mapping is not unique")
        val tprtLoad = selectLoad(initialMaps.rows, tprt, profile.tprt)
            ?: return failure(ControlAntiFlashResolveStatus.MODULE_NOT_FOUND, "tprt offset-zero RX mapping is not unique")
        if (!regionsFit(gameAppLoad, tprtLoad, profile)) {
            return failure(ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE, "Anti-flash code region falls outside RX mappings")
        }

        val bssStart = checkedAntiFlashAdd(
            gameAppLoad.start,
            ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
        ) ?: return failure(ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE, "GameApp BSS address overflowed")
        val bssEnd = checkedAntiFlashAdd(
            gameAppLoad.start,
            ControlGameAppArtifact1582.ANON_BSS_4K_END,
        ) ?: return failure(ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE, "GameApp BSS range overflowed")
        val bssRows = initialMaps.rows.filter { row ->
            row.start == bssStart && row.end == bssEnd && row.permissions == "rw-p" &&
                row.fileOffset == 0L && row.path == "[anon:.bss]"
        }
        if (bssRows.isEmpty()) {
            return failure(ControlAntiFlashResolveStatus.MODULE_NOT_FOUND, "Verified GameApp anonymous BSS mapping is not ready")
        }
        if (bssRows.size != 1 || !bssRows.single().covers(bssStart + profile.bssMarkerOffset, 4)) {
            return failure(ControlAntiFlashResolveStatus.MODULE_AMBIGUOUS, "Verified GameApp anonymous BSS mapping is absent or ambiguous")
        }
        val mappingRanges = buildAntiFlashMappingRanges(
            gameAppLoad = gameAppLoad.start,
            tprtLoad = tprtLoad.start,
            bssStart = bssStart,
            profile = profile,
        ) ?: return failure(
            ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE,
            "Anti-flash scoped mapping range overflowed",
        )
        val initialScopedGeneration = scopedMapsFingerprint(
            rows = initialMaps.rows,
            ranges = mappingRanges,
        ) ?: return failure(
            ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE,
            "Anti-flash scoped mappings are absent or ambiguous",
        )

        val finalMaps = readMaps(request.pid)
            ?: return failure(ControlAntiFlashResolveStatus.MAPS_UNAVAILABLE, "Target maps became unavailable")
        val finalScopedGeneration = scopedMapsFingerprint(
            rows = finalMaps.rows,
            ranges = mappingRanges,
        )
        if (processInspector.readStartTimeTicks(request.pid) != request.startTimeTicks ||
            finalScopedGeneration == null || finalScopedGeneration != initialScopedGeneration
        ) {
            return failure(
                ControlAntiFlashResolveStatus.TARGET_CHANGED,
                "Target scoped mappings changed during anti-flash resolution",
            )
        }
        return ControlAntiFlashTargetResolution(
            status = ControlAntiFlashResolveStatus.RESOLVED,
            target = ControlAntiFlashTarget(
                pid = request.pid,
                startTimeTicks = request.startTimeTicks,
                mapsGeneration = finalScopedGeneration,
                gameAppLoadBias = gameAppLoad.start,
                tprtLoadBias = tprtLoad.start,
                gameAppBssAnchor = bssStart,
                gameAppPath = requireNotNull(gameAppLoad.path),
                tprtPath = requireNotNull(tprtLoad.path),
                scopedMappings = finalMaps.rows.map { row ->
                    ControlAntiFlashScopedMapping(
                        start = row.start,
                        end = row.end,
                        permissions = row.permissions,
                        fileOffset = row.fileOffset,
                        device = row.device,
                        inode = row.inode,
                        path = row.path.orEmpty(),
                    )
                },
            ),
        )
    }

    private fun selectModule(
        modules: List<ControlNativeModuleIdentity>,
        expected: ControlAntiFlashModuleProfile,
    ): ControlNativeModuleIdentity? = modules.filter { module ->
        module.name == expected.name && module.memoryElf && module.loadBase > 0L &&
            module.mappedBytes > 0L && module.sha256?.trim()?.lowercase() == expected.sha256
    }.singleOrNull()

    private fun moduleFailure(
        modules: List<ControlNativeModuleIdentity>,
        expected: ControlAntiFlashModuleProfile,
    ): ControlAntiFlashTargetResolution {
        val named = modules.filter { it.name == expected.name }
        return when {
            named.isEmpty() -> failure(ControlAntiFlashResolveStatus.MODULE_NOT_FOUND, "${expected.name} was not inspected")
            named.size != 1 -> failure(ControlAntiFlashResolveStatus.MODULE_AMBIGUOUS, "${expected.name} identity is ambiguous")
            else -> failure(ControlAntiFlashResolveStatus.FINGERPRINT_MISMATCH, "${expected.name} fingerprint does not match")
        }
    }

    private fun selectLoad(
        maps: List<AntiFlashMapRow>,
        module: ControlNativeModuleIdentity,
        expected: ControlAntiFlashModuleProfile,
    ): AntiFlashMapRow? = maps.filter { row ->
        val rowPath = row.path?.removeSuffix(" (deleted)")
        val modulePath = module.path.removeSuffix(" (deleted)")
        row.start == module.loadBase && row.fileOffset == 0L && row.permissions == "r-xp" &&
            rowPath?.substringAfterLast('/') == expected.name && rowPath == modulePath
    }.singleOrNull()

    private fun regionsFit(
        gameApp: AntiFlashMapRow,
        tprt: AntiFlashMapRow,
        profile: ControlAntiFlashProfile,
    ): Boolean = profile.codeRegions.all { region ->
        val row = if (region.module == ControlAntiFlashModule.GAME_APP) gameApp else tprt
        val address = checkedAntiFlashAdd(row.start, region.rva) ?: return@all false
        row.covers(address, region.patchBytes.size)
    }

    private fun buildAntiFlashMappingRanges(
        gameAppLoad: Long,
        tprtLoad: Long,
        bssStart: Long,
        profile: ControlAntiFlashProfile,
    ): List<ControlAntiFlashMappingRange>? {
        val ranges = ArrayList<ControlAntiFlashMappingRange>(
            profile.codeRegions.size + 1 + profile.writes.size,
        )
        profile.codeRegions.forEach { region ->
            val base = if (region.module == ControlAntiFlashModule.GAME_APP) gameAppLoad else tprtLoad
            val address = checkedAntiFlashAdd(base, region.rva) ?: return null
            ranges += ControlAntiFlashMappingRange(
                address = address,
                byteCount = region.originalBytes.size,
                requireWritable = false,
                requireExecutable = true,
            )
        }
        val bssAddress = checkedAntiFlashAdd(bssStart, profile.bssMarkerOffset) ?: return null
        ranges += ControlAntiFlashMappingRange(
            address = bssAddress,
            byteCount = Int.SIZE_BYTES,
            requireWritable = true,
            requireExecutable = false,
        )
        profile.writes.forEach { write ->
            val base = when (write.module) {
                ControlAntiFlashModule.GAME_APP -> gameAppLoad
                ControlAntiFlashModule.TPRT -> tprtLoad
                ControlAntiFlashModule.GAME_APP_BSS -> bssStart
            }
            val address = checkedAntiFlashAdd(base, write.offset) ?: return null
            val isBssMarker = write.module == ControlAntiFlashModule.GAME_APP_BSS
            ranges += ControlAntiFlashMappingRange(
                address = address,
                byteCount = Int.SIZE_BYTES,
                requireWritable = isBssMarker,
                requireExecutable = !isBssMarker,
            )
        }
        return ranges
    }

    private fun scopedMapsFingerprint(
        rows: List<AntiFlashMapRow>,
        ranges: List<ControlAntiFlashMappingRange>,
    ): String? = ControlAntiFlashTarget(
        pid = 1,
        startTimeTicks = "1",
        mapsGeneration = "0000000000000000",
        gameAppLoadBias = 1,
        tprtLoadBias = 1,
        gameAppBssAnchor = 1,
        gameAppPath = "/",
        tprtPath = "/",
        scopedMappings = rows.map { row ->
            ControlAntiFlashScopedMapping(
                start = row.start,
                end = row.end,
                permissions = row.permissions,
                fileOffset = row.fileOffset,
                device = row.device,
                inode = row.inode,
                path = row.path.orEmpty(),
            )
        },
    ).scopedMapsGeneration(ranges)

    private fun readMaps(pid: Int): AntiFlashMapsSnapshot? = runCatching {
        var count = 0
        var bytes = 0L
        var fingerprint = ANTI_FLASH_FNV_OFFSET_BASIS
        val result = ArrayList<AntiFlashMapRow>()
        File(procControl, "$pid/maps").bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val encoded = line.toByteArray(StandardCharsets.UTF_8)
                count += 1
                bytes = Math.addExact(bytes, encoded.size.toLong() + 1L)
                if (count > MAX_MAP_LINES || bytes > MAX_MAP_BYTES || encoded.size > MAX_MAP_LINE_CHARS) {
                    return@runCatching null
                }
                encoded.forEach { value ->
                    fingerprint = (fingerprint xor (value.toLong() and 0xffL)) * ANTI_FLASH_FNV_PRIME
                }
                fingerprint = (fingerprint xor '\n'.code.toLong()) * ANTI_FLASH_FNV_PRIME
                result += parseAntiFlashMapRow(line) ?: return@runCatching null
            }
        }
        AntiFlashMapsSnapshot(
            rows = result,
            fingerprint = java.lang.Long.toUnsignedString(fingerprint, 16).padStart(16, '0'),
        )
    }.getOrNull()

    private fun failure(
        status: ControlAntiFlashResolveStatus,
        message: String,
    ) = ControlAntiFlashTargetResolution(status = status, message = message.take(256))

    private companion object {
        const val MAX_MAP_LINES = 50_000
        const val MAX_MAP_BYTES = 4L * 1024L * 1024L
        const val MAX_MAP_LINE_CHARS = 4_096
    }
}

internal enum class ControlAntiFlashBackendStatus {
    OK,
    TARGET_CHANGED,
    PROFILE_MISMATCH,
    READ_FAILED,
    WRITE_FAILED,
    VERIFY_FAILED,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

internal data class ControlAntiFlashMemoryRange(
    val address: Long,
    val byteCount: Int,
)

internal data class ControlAntiFlashMemoryWrite(
    val index: Int,
    val address: Long,
    val bytes: ByteArray,
    val expectedCurrentBytes: ByteArray? = null,
)

internal data class ControlAntiFlashCycleRegion(
    val address: Long,
    val originalBytes: ByteArray,
    val patchBytes: ByteArray,
)

internal data class ControlAntiFlashCycleResult(
    val status: ControlAntiFlashBackendStatus,
    val processStartTimeTicks: String = "",
    val mapsGeneration: String = "",
    val codeRegionValues: List<ByteArray> = emptyList(),
    val bssValue: ByteArray = byteArrayOf(),
    val completedWriteCount: Int = 0,
    val failureIndex: Int? = null,
    val writeAttempted: Boolean = false,
    val message: String = "",
)

internal data class ControlAntiFlashReadBatchResult(
    val status: ControlAntiFlashBackendStatus,
    val processStartTimeTicks: String = "",
    val mapsGeneration: String = "",
    val values: List<ByteArray> = emptyList(),
    val failureIndex: Int? = null,
    val message: String = "",
)

internal data class ControlAntiFlashWriteBatchResult(
    val status: ControlAntiFlashBackendStatus,
    val processStartTimeTicks: String = "",
    val mapsGeneration: String = "",
    val completedWriteCount: Int = 0,
    val failureIndex: Int? = null,
    val message: String = "",
)

/** Batch backend must read back each write before counting it completed. */
internal interface ControlAntiFlashBackend {
    fun runCycle(
        target: ControlAntiFlashTarget,
        regions: List<ControlAntiFlashCycleRegion>,
        bssRange: ControlAntiFlashMemoryRange,
        writes: List<ControlAntiFlashMemoryWrite>,
    ): ControlAntiFlashCycleResult

    fun readBatch(
        target: ControlAntiFlashTarget,
        ranges: List<ControlAntiFlashMemoryRange>,
    ): ControlAntiFlashReadBatchResult

    fun writeAndVerifyBatch(
        target: ControlAntiFlashTarget,
        writes: List<ControlAntiFlashMemoryWrite>,
    ): ControlAntiFlashWriteBatchResult

    fun sleepMillis(milliseconds: Long)
}

internal object NoControlAntiFlashBackend : ControlAntiFlashBackend {
    override fun runCycle(
        target: ControlAntiFlashTarget,
        regions: List<ControlAntiFlashCycleRegion>,
        bssRange: ControlAntiFlashMemoryRange,
        writes: List<ControlAntiFlashMemoryWrite>,
    ) = ControlAntiFlashCycleResult(
        status = ControlAntiFlashBackendStatus.UNAVAILABLE,
        message = "Anti-flash backend is unavailable",
    )

    override fun readBatch(
        target: ControlAntiFlashTarget,
        ranges: List<ControlAntiFlashMemoryRange>,
    ) = ControlAntiFlashReadBatchResult(
        status = ControlAntiFlashBackendStatus.UNAVAILABLE,
        message = "Anti-flash backend is unavailable",
    )

    override fun writeAndVerifyBatch(
        target: ControlAntiFlashTarget,
        writes: List<ControlAntiFlashMemoryWrite>,
    ) = ControlAntiFlashWriteBatchResult(
        status = ControlAntiFlashBackendStatus.UNAVAILABLE,
        message = "Anti-flash backend is unavailable",
    )

    override fun sleepMillis(milliseconds: Long) = Unit
}

/**
 * Deterministic single-cycle supervisor. The service owns scheduling; each
 * successful [runOneCycle] includes the profile's 22 ms delay.
 */
internal class ControlAntiFlashSupervisor(
    private val targetResolver: ControlAntiFlashTargetResolver,
    private val backend: ControlAntiFlashBackend = NoControlAntiFlashBackend,
    private val profile: ControlAntiFlashProfile = ControlAntiFlashProfileCatalog.profile,
) {
    private var target: ControlAntiFlashTarget? = null
    private var bssOriginal: ByteArray? = null
    private var state = ControlAntiFlashState(profileId = profile.profileId)

    @Synchronized
    fun start(request: ControlAntiFlashStartRequest): ControlAntiFlashState {
        if (state.workerRunning) return state
        if (state.applied) {
            state = state.copy(
                status = ControlAntiFlashStatus.ROLLBACK_FAILED,
                requestedEnabled = false,
                message = "Pending anti-flash changes must be rolled back before restart",
            )
            return state
        }
        state = state.copy(
            status = ControlAntiFlashStatus.STARTING,
            requestedEnabled = true,
            workerRunning = false,
            applied = false,
            lastFailureIndex = null,
            targetPid = request.pid.takeIf { it > 0 },
            targetStartTimeTicks = request.startTimeTicks,
            profileId = profile.profileId,
            message = "Resolving anti-flash target",
        )
        val resolution = targetResolver.resolve(request, profile)
        val resolved = resolution.target
        if (!resolution.isSuccess || resolved == null) {
            target = null
            state = state.copy(
                status = resolution.status.toPublicStatus(),
                requestedEnabled = false,
                workerRunning = false,
                message = resolution.message,
            )
            return state
        }
        target = resolved
        bssOriginal = null
        state = state.copy(
            status = ControlAntiFlashStatus.WAITING_FOR_TARGET,
            workerRunning = true,
            targetPid = resolved.pid,
            targetStartTimeTicks = resolved.startTimeTicks,
            message = "Anti-flash target resolved; preflight pending",
        )
        return state
    }

    @Synchronized
    fun runOneCycle(): ControlAntiFlashState {
        val active = target ?: return state
        if (!state.requestedEnabled || !state.workerRunning) return state

        val cycleRegions = profile.codeRegions.map { region ->
            ControlAntiFlashCycleRegion(
                address = requireNotNull(active.absoluteAddress(region.module, region.rva)),
                originalBytes = region.originalBytes,
                patchBytes = region.patchBytes,
            )
        }
        val bssRange = ControlAntiFlashMemoryRange(
            address = requireNotNull(active.absoluteAddress(ControlAntiFlashModule.GAME_APP_BSS, profile.bssMarkerOffset)),
            byteCount = Int.SIZE_BYTES,
        )

        val writes = profile.writes.map { write ->
            ControlAntiFlashMemoryWrite(
                index = write.index,
                address = requireNotNull(active.absoluteAddress(write.module, write.offset)),
                bytes = write.bytes,
            )
        }
        val result = runCatching {
            backend.runCycle(active, cycleRegions, bssRange, writes)
        }.getOrElse { error ->
            return failWithRollbackContext(
                ControlAntiFlashStatus.BACKEND_UNAVAILABLE,
                null,
                error.message.orEmpty(),
            )
        }
        val processIdentityMatches = result.processStartTimeTicks.isNotBlank() &&
            result.processStartTimeTicks == active.startTimeTicks
        val identityPresent = processIdentityMatches && result.mapsGeneration.isNotBlank()
        val identityMatches = identityPresent &&
            result.mapsGeneration == active.mapsGeneration
        val preflightShapeValid = result.codeRegionValues.size == cycleRegions.size &&
            result.codeRegionValues.indices.all { index ->
                result.codeRegionValues[index].size == cycleRegions[index].originalBytes.size
            } && result.bssValue.size == Int.SIZE_BYTES
        if (preflightShapeValid && bssOriginal == null) bssOriginal = result.bssValue.copyOf()
        val codeProfileMatches = preflightShapeValid &&
            result.codeRegionValues.indices.all { index ->
                val current = result.codeRegionValues[index]
                current.contentEquals(cycleRegions[index].originalBytes) ||
                    current.contentEquals(cycleRegions[index].patchBytes)
            }
        if (result.status != ControlAntiFlashBackendStatus.OK || !identityMatches ||
            !preflightShapeValid || !codeProfileMatches ||
            result.completedWriteCount != writes.size || result.failureIndex != null ||
            !result.writeAttempted
        ) {
            return failAndMaybeRollback(
                result = result,
                expectedWriteCount = writes.size,
                processIdentityMatches = processIdentityMatches,
                identityPresent = identityPresent,
                identityMatches = identityMatches,
                preflightShapeValid = preflightShapeValid,
                codeProfileMatches = codeProfileMatches,
            )
        }
        state = state.copy(
            status = ControlAntiFlashStatus.RUNNING,
            applied = true,
            iterationCount = state.iterationCount + 1L,
            successfulWriteCount = state.successfulWriteCount + writes.size,
            lastFailureIndex = null,
            message = "17 anti-flash writes verified",
        )
        runCatching { backend.sleepMillis(profile.iterationDelayMillis) }.onFailure { error ->
            return failWithRollbackContext(
                ControlAntiFlashStatus.BACKEND_UNAVAILABLE,
                null,
                error.message.orEmpty(),
            )
        }
        return state
    }

    @Synchronized
    fun requestStop(message: String = "Stopping anti-flash worker"): ControlAntiFlashState {
        if (!state.workerRunning) return state
        state = state.copy(
            status = ControlAntiFlashStatus.STOPPING,
            requestedEnabled = false,
            message = message.take(256),
        )
        return state
    }

    @Synchronized
    fun stopAndRollback(): ControlAntiFlashState {
        if (state.status == ControlAntiFlashStatus.STOPPED && !state.applied && target == null) {
            return state
        }
        val active = target
        val priorStatus = state.status
        val priorMessage = state.message
        state = state.copy(
            status = ControlAntiFlashStatus.STOPPING,
            requestedEnabled = false,
            workerRunning = false,
            message = "Stopping anti-flash and checking rollback",
        )
        if (!state.applied) {
            clearActiveTarget()
            state = state.copy(status = ControlAntiFlashStatus.STOPPED, applied = false, message = "Anti-flash stopped")
            return state
        }
        if (active == null) return rollbackFailure("Anti-flash rollback context is unavailable")

        val ranges = profile.writes.map { write ->
            ControlAntiFlashMemoryRange(
                address = requireNotNull(active.absoluteAddress(write.module, write.offset)),
                byteCount = Int.SIZE_BYTES,
            )
        }
        val read = runCatching { backend.readBatch(active, ranges) }.getOrElse { error ->
            return rollbackFailure(error.message.orEmpty())
        }
        if (!read.matches(active) || read.status != ControlAntiFlashBackendStatus.OK || read.values.size != ranges.size) {
            return rollbackFailure(read.message.ifBlank { "Rollback preflight failed" }, read.failureIndex)
        }
        if (read.values.any { it.size != Int.SIZE_BYTES }) {
            return rollbackFailure("Rollback preflight returned an invalid u32 width")
        }
        val rollbackWrites = mutableListOf<ControlAntiFlashMemoryWrite>()
        profile.writes.forEachIndexed { index, write ->
            val current = read.values[index]
            val original = originalBytesFor(write)
                ?: return rollbackFailure("Rollback original value is unavailable", write.index)
            val patch = write.bytes
            when {
                current.contentEquals(original) || current.isBytewiseBlendOf(original, patch) ->
                    rollbackWrites += ControlAntiFlashMemoryWrite(
                    index = write.index,
                    address = ranges[index].address,
                    bytes = original.copyOf(),
                    expectedCurrentBytes = current.copyOf(),
                )
                else -> return rollbackFailure("Rollback word contains an unknown value", write.index)
            }
        }
        val result = runCatching { backend.writeAndVerifyBatch(active, rollbackWrites) }.getOrElse { error ->
            return rollbackFailure(error.message.orEmpty())
        }
        if (!result.matches(active) || result.status != ControlAntiFlashBackendStatus.OK ||
            result.completedWriteCount != rollbackWrites.size || result.failureIndex != null
        ) {
            return rollbackFailure(
                result.message.ifBlank { "Rollback write or read-back failed" },
                result.failureIndex,
            )
        }
        clearActiveTarget()
        state = state.copy(
            status = ControlAntiFlashStatus.STOPPED,
            applied = false,
            lastFailureIndex = null,
            message = if (priorStatus in ROLLBACK_TRIGGER_STATUSES) {
                "Anti-flash failure was rolled back: ${priorMessage.ifBlank { priorStatus.name }}"
            } else {
                "Anti-flash stopped and rollback verified"
            },
        )
        return state
    }

    @Synchronized
    fun stateSnapshot(): ControlAntiFlashState = state

    private fun failAndInvalidate(
        status: ControlAntiFlashStatus,
        failureIndex: Int?,
        message: String,
    ): ControlAntiFlashState {
        clearActiveTarget()
        state = state.copy(
            status = status,
            requestedEnabled = false,
            workerRunning = false,
            lastFailureIndex = failureIndex,
            message = message.take(256),
        )
        return state
    }

    private fun failAndMaybeRollback(
        result: ControlAntiFlashCycleResult,
        expectedWriteCount: Int,
        processIdentityMatches: Boolean,
        identityPresent: Boolean,
        identityMatches: Boolean,
        preflightShapeValid: Boolean,
        codeProfileMatches: Boolean,
    ): ControlAntiFlashState {
        val status = when {
            result.status == ControlAntiFlashBackendStatus.TARGET_CHANGED ||
                identityPresent && !identityMatches -> ControlAntiFlashStatus.TARGET_CHANGED
            result.status == ControlAntiFlashBackendStatus.PROFILE_MISMATCH ||
                preflightShapeValid && !codeProfileMatches ->
                ControlAntiFlashStatus.PROFILE_MISMATCH
            result.status != ControlAntiFlashBackendStatus.OK -> result.status.toPublicStatus()
            !identityPresent || !preflightShapeValid -> ControlAntiFlashStatus.PREFLIGHT_FAILED
            result.status == ControlAntiFlashBackendStatus.OK &&
                result.completedWriteCount != expectedWriteCount -> ControlAntiFlashStatus.WRITE_FAILED
            else -> result.status.toPublicStatus()
        }
        if (status == ControlAntiFlashStatus.TARGET_CHANGED) {
            if (!processIdentityMatches) {
                state = state.copy(applied = false)
                return failAndInvalidate(
                    status,
                    result.failureIndex,
                    result.message.ifBlank { "Anti-flash target identity changed" },
                )
            }
            val rollbackRequired =
                state.applied || result.writeAttempted || result.completedWriteCount > 0
            if (!rollbackRequired) {
                state = state.copy(applied = false)
                return failAndInvalidate(
                    status,
                    result.failureIndex,
                    result.message.ifBlank { "Anti-flash target identity changed" },
                )
            }
            val currentTarget = target
            if (currentTarget != null && result.mapsGeneration.isNotBlank()) {
                target = currentTarget.copy(mapsGeneration = result.mapsGeneration)
            }
        }
        if (!state.applied && !result.writeAttempted && result.completedWriteCount == 0) {
            return failAndInvalidate(
                status,
                result.failureIndex,
                result.message.ifBlank { "Anti-flash cycle failed before writing" },
            )
        }
        state = state.copy(
            status = status,
            requestedEnabled = false,
            workerRunning = false,
            applied = true,
            successfulWriteCount = state.successfulWriteCount + result.completedWriteCount,
            lastFailureIndex = result.failureIndex,
            message = result.message.ifBlank { "Anti-flash cycle failed; rollback is required" }.take(256),
        )
        return state
    }

    private fun failWithRollbackContext(
        status: ControlAntiFlashStatus,
        failureIndex: Int?,
        message: String,
    ): ControlAntiFlashState {
        state = state.copy(
            status = status,
            requestedEnabled = false,
            workerRunning = false,
            applied = true,
            lastFailureIndex = failureIndex,
            message = message.ifBlank { "Anti-flash backend failed; rollback is required" }.take(256),
        )
        return state
    }

    private fun rollbackFailure(
        message: String,
        failureIndex: Int? = null,
    ): ControlAntiFlashState {
        state = state.copy(
            status = ControlAntiFlashStatus.ROLLBACK_FAILED,
            requestedEnabled = false,
            workerRunning = false,
            applied = true,
            lastFailureIndex = failureIndex,
            message = message.take(256),
        )
        return state
    }

    private fun clearActiveTarget() {
        target = null
        bssOriginal = null
    }

    private fun originalBytesFor(write: ControlAntiFlashWordWrite): ByteArray? {
        if (write.module == ControlAntiFlashModule.GAME_APP_BSS) return bssOriginal?.copyOf()
        val matches = profile.codeRegions.mapNotNull { region ->
            if (region.module != write.module || write.offset < region.rva) return@mapNotNull null
            val offset = write.offset - region.rva
            if (offset > Int.MAX_VALUE || offset + Int.SIZE_BYTES > region.originalBytes.size) {
                return@mapNotNull null
            }
            region.originalBytes.copyOfRange(offset.toInt(), offset.toInt() + Int.SIZE_BYTES)
        }
        return matches.singleOrNull()
    }

    private companion object {
        val ROLLBACK_TRIGGER_STATUSES = setOf(
            ControlAntiFlashStatus.TARGET_CHANGED,
            ControlAntiFlashStatus.PREFLIGHT_FAILED,
            ControlAntiFlashStatus.READ_FAILED,
            ControlAntiFlashStatus.WRITE_FAILED,
            ControlAntiFlashStatus.VERIFY_FAILED,
            ControlAntiFlashStatus.BACKEND_UNAVAILABLE,
        )
    }
}

private data class AntiFlashMapsSnapshot(
    val rows: List<AntiFlashMapRow>,
    val fingerprint: String,
)

private data class AntiFlashMapRow(
    val start: Long,
    val end: Long,
    val permissions: String,
    val fileOffset: Long,
    val device: String,
    val inode: String,
    val path: String?,
) {
    fun covers(address: Long, byteCount: Int): Boolean {
        if (address < start || byteCount <= 0) return false
        val limit = checkedAntiFlashAdd(address, byteCount.toLong()) ?: return false
        return limit <= end
    }

}

private fun AntiFlashMapRow.toScopedMapping(): ControlAntiFlashScopedMapping =
    ControlAntiFlashScopedMapping(
        start = start,
        end = end,
        permissions = permissions,
        fileOffset = fileOffset,
        device = device,
        inode = inode,
        path = path.orEmpty(),
    )

private fun parseAntiFlashMapRow(line: String): AntiFlashMapRow? {
    val fields = line.trim().split(Regex("\\s+"), limit = 6)
    if (fields.size < 5) return null
    val range = fields[0].split('-', limit = 2)
    if (range.size != 2) return null
    val start = parseAntiFlashHexLong(range[0])?.takeIf { it > 0L } ?: return null
    val end = parseAntiFlashHexLong(range[1])?.takeIf { it > start } ?: return null
    val permissions = fields[1].takeIf { value ->
        value.length == 4 && value[0] in "r-" && value[1] in "w-" &&
            value[2] in "x-" && value[3] in "ps"
    } ?: return null
    val fileOffset = parseAntiFlashHexLong(fields[2]) ?: return null
    val device = fields[3].takeIf(::isValidAntiFlashMapDevice) ?: return null
    val inode = fields[4].takeIf(::isValidAntiFlashMapInode) ?: return null
    return AntiFlashMapRow(start, end, permissions, fileOffset, device, inode, fields.getOrNull(5))
}

private fun isValidAntiFlashMapDevice(value: String): Boolean {
    val parts = value.split(':', limit = 2)
    return parts.size == 2 && parts.all { part ->
        part.isNotEmpty() && part.length <= 16 && part.all(Char::isHexDigit)
    }
}

private fun isValidAntiFlashMapInode(value: String): Boolean =
    value.isNotEmpty() && value.length <= 20 && value.all { it in '0'..'9' }

private fun parseAntiFlashHexLong(value: String): Long? {
    if (value.isEmpty() || value.length > 16 || !value.all(Char::isHexDigit)) return null
    return runCatching { java.lang.Long.parseUnsignedLong(value, 16) }.getOrNull()?.takeIf { it >= 0L }
}

private fun decodeAntiFlashHex(value: String): ByteArray {
    require(value.length % 2 == 0 && value.all(Char::isHexDigit))
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun antiFlashSha256(file: File, expectedLength: Long): String? {
    if (expectedLength <= 0L || file.length() != expectedLength) return null
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total = Math.addExact(total, count.toLong())
                if (total > expectedLength) return null
                digest.update(buffer, 0, count)
            }
        }
        if (total != expectedLength || file.length() != expectedLength) return null
        digest.digest().joinToString(separator = "") { value ->
            "%02x".format(value.toInt() and 0xff)
        }
    }.getOrNull()
}

private fun littleEndianInt32(value: Long): ByteArray {
    require(value ushr Int.SIZE_BITS == 0L)
    return ByteArray(Int.SIZE_BYTES) { index -> ((value ushr (index * 8)) and 0xffL).toByte() }
}

private fun ControlAntiFlashReadBatchResult.matches(target: ControlAntiFlashTarget): Boolean =
    status == ControlAntiFlashBackendStatus.OK &&
        processStartTimeTicks == target.startTimeTicks && mapsGeneration == target.mapsGeneration

private fun ControlAntiFlashWriteBatchResult.matches(target: ControlAntiFlashTarget): Boolean =
    processStartTimeTicks == target.startTimeTicks && mapsGeneration == target.mapsGeneration

private fun ControlAntiFlashResolveStatus.toPublicStatus(): ControlAntiFlashStatus = when (this) {
    ControlAntiFlashResolveStatus.RESOLVED -> ControlAntiFlashStatus.WAITING_FOR_TARGET
    ControlAntiFlashResolveStatus.TARGET_CHANGED -> ControlAntiFlashStatus.TARGET_CHANGED
    ControlAntiFlashResolveStatus.FINGERPRINT_MISMATCH,
    ControlAntiFlashResolveStatus.MODULE_AMBIGUOUS,
    ControlAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE -> ControlAntiFlashStatus.PROFILE_MISMATCH
    else -> ControlAntiFlashStatus.WAITING_FOR_TARGET
}

private fun ControlAntiFlashBackendStatus.toPublicStatus(readPhase: Boolean = false): ControlAntiFlashStatus = when (this) {
    ControlAntiFlashBackendStatus.OK -> if (readPhase) ControlAntiFlashStatus.READ_FAILED else ControlAntiFlashStatus.WRITE_FAILED
    ControlAntiFlashBackendStatus.TARGET_CHANGED -> ControlAntiFlashStatus.TARGET_CHANGED
    ControlAntiFlashBackendStatus.PROFILE_MISMATCH -> ControlAntiFlashStatus.PROFILE_MISMATCH
    ControlAntiFlashBackendStatus.READ_FAILED -> ControlAntiFlashStatus.READ_FAILED
    ControlAntiFlashBackendStatus.WRITE_FAILED -> ControlAntiFlashStatus.WRITE_FAILED
    ControlAntiFlashBackendStatus.VERIFY_FAILED -> ControlAntiFlashStatus.VERIFY_FAILED
    ControlAntiFlashBackendStatus.UNAVAILABLE -> ControlAntiFlashStatus.BACKEND_UNAVAILABLE
    ControlAntiFlashBackendStatus.INVALID_RESPONSE -> ControlAntiFlashStatus.PREFLIGHT_FAILED
}

private fun checkedAntiFlashAdd(value: Long, delta: Long): Long? =
    runCatching { Math.addExact(value, delta) }.getOrNull()?.takeIf { it > 0L }

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

private fun ByteArray.isBytewiseBlendOf(first: ByteArray, second: ByteArray): Boolean =
    size == first.size && size == second.size && indices.all { index ->
        this[index] == first[index] || this[index] == second[index]
    }

private const val ANTI_FLASH_FNV_OFFSET_BASIS = -3_750_763_034_362_895_579L
private const val ANTI_FLASH_FNV_PRIME = 1_099_511_628_211L
