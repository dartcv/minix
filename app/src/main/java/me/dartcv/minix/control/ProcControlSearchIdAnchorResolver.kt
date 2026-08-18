package me.dartcv.minix.control

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.StringTokenizer

internal data class RecoveredModuleSpec(
    val moduleName: String,
    val hasSuffixToken: Boolean,
)

/**
 * Mirrors the recovered `strtok(spec, ":")` / `strtok(NULL, ":")` contract.
 * Empty tokens are skipped and only the presence of a second token matters.
 */
internal fun parseRecoveredModuleSpec(value: String): RecoveredModuleSpec? {
    if (
        value.length !in 1..MAX_MODULE_SPEC_CHARS ||
        '\u0000' in value ||
        '\n' in value ||
        '\r' in value
    ) {
        return null
    }
    val tokenizer = StringTokenizer(value, ":")
    if (!tokenizer.hasMoreTokens()) return null
    val moduleName = tokenizer.nextToken()
    if (
        moduleName.length !in 1..MAX_MODULE_NAME_CHARS ||
        '/' in moduleName ||
        '\\' in moduleName ||
        !moduleName.endsWith(".so")
    ) {
        return null
    }
    return RecoveredModuleSpec(
        moduleName = moduleName,
        hasSuffixToken = tokenizer.hasMoreTokens(),
    )
}

internal class ProcControlSearchIdAnchorResolver(
    private val processInspector: TargetProcessInspector,
    private val fingerprintProvider: TargetModuleFingerprintProvider =
        ProcControlModuleFingerprintProvider,
    private val procControl: File = File("/proc"),
) : ControlSearchIdAnchorResolver {
    private var fingerprintCache: FingerprintCacheEntry? = null

    @Synchronized
    override fun invalidateCache() {
        fingerprintCache = null
    }

    @Synchronized
    override fun resolve(pid: Int, profile: ControlSearchIdProfile): ControlSearchIdAnchorResult {
        val result = runCatching { resolveChecked(pid, profile) }
            .getOrElse {
                failure(
                    ControlSearchIdAnchorStatus.UNAVAILABLE,
                    "Module-spec anchor resolution failed closed",
                )
            }
        if (!result.isSuccess) fingerprintCache = null
        return result
    }

    private fun resolveChecked(
        pid: Int,
        profile: ControlSearchIdProfile,
    ): ControlSearchIdAnchorResult {
        if (pid <= 0) {
            return failure(ControlSearchIdAnchorStatus.INVALID_PID, "PID must be positive")
        }
        val moduleName = profile.moduleName.trim()
        val moduleSpec = parseRecoveredModuleSpec(profile.moduleSpec)
        val expectedSha256 = profile.normalizedSha256
        if (
            profile.profileId.isBlank() ||
            moduleSpec == null ||
            moduleSpec.moduleName != moduleName ||
            expectedSha256 == null ||
            profile.anchorKind != ControlModuleAnchorKind.MODULE_SPEC_RESULT
        ) {
            return failure(
                ControlSearchIdAnchorStatus.INVALID_PROFILE,
                "Module-spec anchor profile is incomplete",
            )
        }

        val startTimeBefore = readStartTimeTicks(pid)
            ?.takeIf(String::isNotBlank)
            ?: return failure(
                ControlSearchIdAnchorStatus.TARGET_CHANGED,
                "Target process identity is unavailable",
            )

        val initialMaps = readMaps(pid)
        identityChanged(pid, startTimeBefore)?.let { return it }
        initialMaps.errorMessage?.let { message ->
            return failure(
                ControlSearchIdAnchorStatus.UNAVAILABLE,
                message,
                startTimeBefore,
            )
        }

        val moduleRows = initialMaps.rows.filter { row ->
            row.mappedModulePath(moduleName) != null
        }
        if (moduleRows.isEmpty()) {
            return failure(
                ControlSearchIdAnchorStatus.MODULE_NOT_FOUND,
                "Target module was not present in process maps",
                startTimeBefore,
            )
        }

        val initialLoadRows = offsetZeroLoadRows(initialMaps, moduleName)
        if (initialLoadRows.isEmpty()) {
            return failure(
                ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
                "Target module has no valid offset-zero executable mapping",
                startTimeBefore,
            )
        }

        val candidatePaths = initialLoadRows
            .mapNotNull { row -> row.mappedModulePath(moduleName) }
            .distinct()
        val relevantRows = relevantModuleRows(initialMaps, moduleName)
        val cacheKey = FingerprintCacheKey(
            pid = pid,
            processStartTimeTicks = startTimeBefore,
            expectedSha256 = expectedSha256,
            candidatePaths = candidatePaths,
            relevantRows = relevantRows,
        )
        val cachedFingerprints = fingerprintCache
            ?.takeIf { entry -> entry.key == cacheKey }
            ?.fingerprints
        if (cachedFingerprints == null) fingerprintCache = null
        val fingerprints: Map<String, String?> = cachedFingerprints ?: candidatePaths.associateWith {
                mappedPath ->
            val loadBase = initialLoadRows
                .singleOrNull { row -> row.mappedModulePath(moduleName) == mappedPath }
                ?.start
                ?: 0L
            runCatching { fingerprintProvider.sha256(pid, mappedPath, loadBase) }
                .getOrNull()
                ?.trim()
                ?.lowercase()
                ?.takeIf(::isSha256)
        }
        identityChanged(pid, startTimeBefore)?.let { return it }

        if (fingerprints.values.any { it == null }) {
            return failure(
                ControlSearchIdAnchorStatus.FINGERPRINT_UNAVAILABLE,
                "Every candidate module must have a valid full-file SHA-256",
                startTimeBefore,
            )
        }
        val matchedPaths = fingerprints
            .filterValues { fingerprint -> fingerprint == expectedSha256 }
            .keys
        if (matchedPaths.isEmpty()) {
            return failure(
                ControlSearchIdAnchorStatus.FINGERPRINT_MISMATCH,
                "No offset-zero module mapping matched the profile SHA-256",
                startTimeBefore,
            )
        }
        if (matchedPaths.size != 1) {
            return failure(
                ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS,
                "Multiple mapped modules matched the profile SHA-256",
                startTimeBefore,
            )
        }

        val selectedPath = matchedPaths.single()
        val initialSelection = selectAnchor(
            maps = initialMaps,
            moduleName = moduleName,
            selectedPath = selectedPath,
            hasSuffixToken = moduleSpec.hasSuffixToken,
            requiredControlOffset = profile.recipe.anchorOffset,
        )
        initialSelection.failureStatus?.let { status ->
            return failure(status, initialSelection.message, startTimeBefore)
        }
        val selected = requireNotNull(initialSelection.selection)

        // Hashing the backing file is intentionally performed between two
        // bounded maps snapshots. A reused PID or remap must not leave a stale
        // load bias / anonymous-BSS address in the returned anchor.
        val finalMaps = readMaps(pid)
        identityChanged(pid, startTimeBefore)?.let { return it }
        finalMaps.errorMessage?.let { message ->
            return failure(
                ControlSearchIdAnchorStatus.UNAVAILABLE,
                message,
                startTimeBefore,
            )
        }
        if (offsetZeroLoadRows(finalMaps, moduleName) != initialLoadRows) {
            return failure(
                ControlSearchIdAnchorStatus.TARGET_CHANGED,
                "Target module mapping changed during anchor resolution",
                startTimeBefore,
            )
        }
        if (relevantModuleRows(finalMaps, moduleName) != relevantRows) {
            return failure(
                ControlSearchIdAnchorStatus.TARGET_CHANGED,
                "Target module maps changed during anchor resolution",
                startTimeBefore,
            )
        }
        val finalSelection = selectAnchor(
            maps = finalMaps,
            moduleName = moduleName,
            selectedPath = selectedPath,
            hasSuffixToken = moduleSpec.hasSuffixToken,
            requiredControlOffset = profile.recipe.anchorOffset,
        )
        if (finalSelection.selection != selected) {
            return failure(
                ControlSearchIdAnchorStatus.TARGET_CHANGED,
                "Target anchor range changed during anchor resolution",
                startTimeBefore,
            )
        }

        if (cachedFingerprints == null) {
            fingerprintCache = FingerprintCacheEntry(
                key = cacheKey,
                fingerprints = fingerprints.mapValues { (_, value) -> requireNotNull(value) },
            )
        }

        val anchor = ControlSearchIdAnchor(
            profileId = profile.profileId,
            moduleName = moduleName,
            moduleSpec = profile.moduleSpec,
            moduleSha256 = expectedSha256,
            anchorKind = profile.anchorKind,
            baseAddress = selected.anchorRow.start,
            processStartTimeTicks = startTimeBefore,
        )
        return ControlSearchIdAnchorResult(
            status = ControlSearchIdAnchorStatus.RESOLVED,
            anchor = anchor,
            processStartTimeTicks = startTimeBefore,
        )
    }

    private fun selectAnchor(
        maps: MapsSnapshot,
        moduleName: String,
        selectedPath: String,
        hasSuffixToken: Boolean,
        requiredControlOffset: Long,
    ): AnchorSelectionResult {
        val selectedLoads = offsetZeroLoadRows(maps, moduleName).filter { row ->
            row.mappedModulePath(moduleName) == selectedPath
        }
        if (selectedLoads.isEmpty()) {
            return AnchorSelectionResult(
                failureStatus = ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
                message = "Fingerprint-matched module lost its offset-zero mapping",
            )
        }
        if (selectedLoads.size != 1) {
            return AnchorSelectionResult(
                failureStatus = ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS,
                message = "Fingerprint-matched module has multiple offset-zero mappings",
            )
        }
        val loadRow = selectedLoads.single()
        if (!hasSuffixToken) {
            if (!loadRow.covers(loadRow.start, requiredControlOffset, INT32_BYTES)) {
                return AnchorSelectionResult(
                    failureStatus = ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
                    message = "Offset-zero module mapping does not cover the required anchor seed",
                )
            }
            return AnchorSelectionResult(
                selection = AnchorSelection(loadRow = loadRow, anchorRow = loadRow),
            )
        }

        val expectedAnchor = checkedAdd(
            loadRow.start,
            ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
        ) ?: return AnchorSelectionResult(
            failureStatus = ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
            message = "Anonymous-BSS anchor address overflowed",
        )
        val expectedLogicalEnd = checkedAdd(
            loadRow.start,
            ControlGameAppArtifact1582.LOAD_VIRTUAL_END,
        ) ?: return AnchorSelectionResult(
            failureStatus = ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
            message = "Anonymous-BSS logical end overflowed",
        )
        val anchorRows = maps.rows.filter { row ->
            row.start == expectedAnchor &&
                row.permissions == ANON_BSS_PERMISSIONS &&
                row.fileOffset == 0L &&
                row.mappedPath == ANON_BSS_MARKER
        }
        if (anchorRows.isEmpty()) {
            return AnchorSelectionResult(
                failureStatus = ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
                message = "Expected independent read-write anonymous-BSS mapping was not found",
            )
        }
        if (anchorRows.size != 1) {
            return AnchorSelectionResult(
                failureStatus = ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS,
                message = "Multiple anonymous-BSS mappings matched the module layout",
            )
        }
        val anchorRow = anchorRows.single()
        val mappedPageEnd = checkedAdd(
            loadRow.start,
            ControlGameAppArtifact1582.ANON_BSS_4K_END,
        )
        if (
            mappedPageEnd == null ||
            anchorRow.end != mappedPageEnd ||
            anchorRow.end < expectedLogicalEnd ||
            !anchorRow.covers(anchorRow.start, requiredControlOffset, INT32_BYTES)
        ) {
            return AnchorSelectionResult(
                failureStatus = ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND,
                message = "Anonymous-BSS mapping range does not match the verified ELF layout",
            )
        }
        return AnchorSelectionResult(
            selection = AnchorSelection(loadRow = loadRow, anchorRow = anchorRow),
        )
    }

    private fun offsetZeroLoadRows(
        maps: MapsSnapshot,
        moduleName: String,
    ): List<ProcMapRow> = maps.rows.filter { row ->
        row.fileOffset == 0L &&
            row.permissions == MODULE_LOAD_PERMISSIONS &&
            row.mappedModulePath(moduleName) != null
    }

    private fun relevantModuleRows(
        maps: MapsSnapshot,
        moduleName: String,
    ): List<ProcMapRow> = maps.rows.filter { row ->
        row.mappedModulePath(moduleName) != null || row.mappedPath == ANON_BSS_MARKER
    }

    private fun readMaps(pid: Int): MapsSnapshot {
        val mapsFile = File(procControl, "$pid/maps")
        return runCatching {
            var lineCount = 0
            var byteCount = 0L
            val rows = ArrayList<ProcMapRow>()
            mapsFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lineCount = Math.addExact(lineCount, 1)
                    byteCount = Math.addExact(
                        byteCount,
                        line.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1L,
                    )
                    if (
                        lineCount > MAX_MAP_LINES ||
                        byteCount > MAX_MAP_BYTES ||
                        line.length > MAX_MAP_LINE_CHARS
                    ) {
                        return@runCatching MapsSnapshot(
                            errorMessage = "Process maps exceeded the bounded resolver limits",
                        )
                    }
                    val row = parseProcMapRow(line)
                        ?: return@runCatching MapsSnapshot(
                            errorMessage = "Process maps contained a malformed row",
                        )
                    rows += row
                }
            }
            MapsSnapshot(rows = rows)
        }.getOrElse {
            MapsSnapshot(errorMessage = "Process maps are unreadable")
        }
    }

    private fun identityChanged(
        pid: Int,
        expectedStartTime: String,
    ): ControlSearchIdAnchorResult? {
        val actualStartTime = readStartTimeTicks(pid)
        return if (actualStartTime == expectedStartTime) {
            null
        } else {
            failure(
                ControlSearchIdAnchorStatus.TARGET_CHANGED,
                "Target process identity changed during module-spec resolution",
                actualStartTime.orEmpty(),
            )
        }
    }

    private fun readStartTimeTicks(pid: Int): String? =
        runCatching { processInspector.readStartTimeTicks(pid) }.getOrNull()

    private fun failure(
        status: ControlSearchIdAnchorStatus,
        message: String,
        processStartTimeTicks: String = "",
    ): ControlSearchIdAnchorResult = ControlSearchIdAnchorResult(
        status = status,
        processStartTimeTicks = processStartTimeTicks,
        message = message,
    )

    private data class MapsSnapshot(
        val rows: List<ProcMapRow> = emptyList(),
        val errorMessage: String? = null,
    )

    private data class AnchorSelection(
        val loadRow: ProcMapRow,
        val anchorRow: ProcMapRow,
    )

    private data class AnchorSelectionResult(
        val selection: AnchorSelection? = null,
        val failureStatus: ControlSearchIdAnchorStatus? = null,
        val message: String = "",
    )

    private data class FingerprintCacheKey(
        val pid: Int,
        val processStartTimeTicks: String,
        val expectedSha256: String,
        val candidatePaths: List<String>,
        val relevantRows: List<ProcMapRow>,
    )

    private data class FingerprintCacheEntry(
        val key: FingerprintCacheKey,
        val fingerprints: Map<String, String>,
    )

    private companion object {
        const val MAX_MAP_BYTES = 4L * 1024L * 1024L
        const val MAX_MAP_LINES = 50_000
        const val MAX_MAP_LINE_CHARS = 4_096
        const val MODULE_LOAD_PERMISSIONS = "r-xp"
        const val ANON_BSS_PERMISSIONS = "rw-p"
        const val ANON_BSS_MARKER = "[anon:.bss]"
        const val INT32_BYTES = 4L
    }
}

private data class ProcMapRow(
    val start: Long,
    val end: Long,
    val permissions: String,
    val fileOffset: Long,
    val mappedPath: String?,
) {
    fun mappedModulePath(moduleName: String): String? {
        val path = mappedPath ?: return null
        val pathForName = path.removeSuffix(DELETED_SUFFIX)
        if (!pathForName.startsWith('/')) return null
        if (pathForName.substringAfterLast('/') != moduleName) return null
        return path
    }

    fun covers(base: Long, offset: Long, byteCount: Long): Boolean {
        if (base != start || offset < 0L || byteCount <= 0L) return false
        val address = checkedAdd(base, offset) ?: return false
        val addressEnd = checkedAdd(address, byteCount) ?: return false
        return address >= start && addressEnd <= end
    }
}

private fun parseProcMapRow(line: String): ProcMapRow? {
    val fields = line.trim().split(Regex("\\s+"), limit = MAPS_FIELD_LIMIT)
    if (fields.size < MAPS_PREFIX_FIELD_COUNT) return null
    val range = fields[0].split('-', limit = 2)
    if (range.size != 2) return null
    val start = parsePositiveHex(range[0]) ?: return null
    val end = parsePositiveHex(range[1])?.takeIf { it > start } ?: return null
    val permissions = fields[1].takeIf(::isMapPermissions) ?: return null
    val fileOffset = parseNonNegativeHex(fields[2]) ?: return null
    if (!fields[4].all(Char::isDigit)) return null
    return ProcMapRow(
        start = start,
        end = end,
        permissions = permissions,
        fileOffset = fileOffset,
        mappedPath = fields.getOrNull(5)?.takeIf(String::isNotBlank),
    )
}

private fun parsePositiveHex(value: String): Long? =
    parseNonNegativeHex(value)?.takeIf { it > 0L }

private fun parseNonNegativeHex(value: String): Long? {
    if (value.isEmpty() || value.length > 16 || !value.all(Char::isHexDigit)) return null
    return runCatching { java.lang.Long.parseUnsignedLong(value, 16) }
        .getOrNull()
        ?.takeIf { it >= 0L }
}

private fun isMapPermissions(value: String): Boolean =
    value.length == 4 &&
        value[0] in "r-" &&
        value[1] in "w-" &&
        value[2] in "x-" &&
        value[3] in "ps"

private fun checkedAdd(value: Long, delta: Long): Long? =
    runCatching { Math.addExact(value, delta) }
        .getOrNull()
        ?.takeIf { it > 0L }

private fun isSha256(value: String): Boolean =
    value.length == 64 && value.all(Char::isHexDigit)

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val MAX_MODULE_NAME_CHARS = 192
private const val MAX_MODULE_SPEC_CHARS = 256
private const val MAPS_PREFIX_FIELD_COUNT = 5
private const val MAPS_FIELD_LIMIT = 6
private const val DELETED_SUFFIX = " (deleted)"
