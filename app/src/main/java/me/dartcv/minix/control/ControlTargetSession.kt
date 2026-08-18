package me.dartcv.minix.control

import java.io.File
import java.nio.charset.StandardCharsets

internal interface TargetProcessInspector {
    fun findPid(packageName: String): Int?
    fun readProcessName(pid: Int): String?
    fun readStartTimeTicks(pid: Int): String?
    fun readEffectiveUid(pid: Int): Int?
    fun readBrief(pid: Int): TargetProcessBrief?
    fun isAlive(pid: Int): Boolean
}

internal data class TargetProcessBrief(
    val name: String?,
    val state: String?,
    val threads: Int?,
    val vmRss: String?,
    val readableMapLines: Int?,
    val effectiveUid: Int?,
)

internal class ProcTargetProcessInspector(
    private val procControl: File = File("/proc"),
) : TargetProcessInspector {
    override fun findPid(packageName: String): Int? {
        if (!ControlProtocol.isValidPackageName(packageName)) return null

        var fallbackPid: Int? = null
        procControl.listFiles()
            .orEmpty()
            .asSequence()
            .mapNotNull { directory -> directory.name.toIntOrNull() }
            .sortedDescending()
            .forEach { pid ->
                val processName = readProcessName(pid) ?: return@forEach
                if (processName == packageName) return pid
                if (fallbackPid == null && ControlProtocol.processMatchesPackage(processName, packageName)) {
                    fallbackPid = pid
                }
            }
        return fallbackPid
    }

    override fun readProcessName(pid: Int): String? {
        if (pid <= 0) return null
        return runCatching {
            File(procControl, "$pid/cmdline").inputStream().use { input ->
                val buffer = ByteArray(MAX_PROCESS_NAME_BYTES)
                val count = input.read(buffer)
                if (count <= 0) return@use null
                String(buffer, 0, count, StandardCharsets.UTF_8)
                    .substringBefore('\u0000')
                    .trim()
                    .takeIf(String::isNotEmpty)
            }
        }.getOrNull()
    }

    override fun readStartTimeTicks(pid: Int): String? {
        if (pid <= 0) return null
        return runCatching {
            val statLine = File(procControl, "$pid/stat").bufferedReader().use { reader ->
                reader.readLine()
            } ?: return@runCatching null
            if (statLine.length > MAX_STAT_LINE_BYTES) return@runCatching null

            val commandEnd = statLine.lastIndexOf(')')
            if (commandEnd < 0 || commandEnd + 1 >= statLine.length) return@runCatching null
            val fields = java.util.StringTokenizer(statLine.substring(commandEnd + 1))
            var value = ""
            repeat(START_TIME_TOKEN_INDEX + 1) {
                if (!fields.hasMoreTokens()) return@runCatching null
                value = fields.nextToken()
            }
            value.takeIf { token -> token.isNotEmpty() && token.all(Char::isDigit) }
        }.getOrNull()
    }

    override fun readEffectiveUid(pid: Int): Int? {
        if (pid <= 0) return null
        return runCatching {
            val uidRows = File(procControl, "$pid/status").bufferedReader().useLines { lines ->
                lines
                    .take(MAX_STATUS_LINES)
                    .filter { line -> line.substringBefore(':', missingDelimiterValue = "") == STATUS_UID }
                    .map { line -> line.substringAfter(':', missingDelimiterValue = "") }
                    .toList()
            }
            if (uidRows.size != 1) return@runCatching null
            parseEffectiveUid(uidRows.single())
        }.getOrNull()
    }

    override fun readBrief(pid: Int): TargetProcessBrief? {
        if (pid <= 0) return null
        val statusValues = readStatusValues(pid)
        val mapCount = runCatching {
            File(procControl, "$pid/maps").bufferedReader().useLines { lines ->
                lines.take(MAX_MAP_LINES).count()
            }
        }.getOrNull()
        if (statusValues.isEmpty() && mapCount == null) return null
        return TargetProcessBrief(
            name = statusValues[STATUS_NAME],
            state = statusValues[STATUS_STATE],
            threads = statusValues[STATUS_THREADS]?.toIntOrNull(),
            vmRss = statusValues[STATUS_VM_RSS],
            readableMapLines = mapCount,
            effectiveUid = statusValues[STATUS_UID]?.let(::parseEffectiveUid),
        )
    }

    override fun isAlive(pid: Int): Boolean =
        pid > 0 && File(procControl, pid.toString()).isDirectory && readProcessName(pid) != null

    private fun readStatusValues(pid: Int): Map<String, String> {
        return runCatching {
            val values = mutableMapOf<String, String>()
            File(procControl, "$pid/status").bufferedReader().useLines { lines ->
                lines.take(MAX_STATUS_LINES).forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) return@forEach
                    val key = line.substring(0, separator)
                    if (key in STATUS_KEYS) {
                        values[key] = line.substring(separator + 1).trim().take(MAX_STATUS_VALUE_LENGTH)
                    }
                }
            }
            values
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val MAX_PROCESS_NAME_BYTES = 512
        const val MAX_STAT_LINE_BYTES = 4_096
        const val START_TIME_TOKEN_INDEX = 19
        const val MAX_STATUS_LINES = 256
        const val MAX_STATUS_VALUE_LENGTH = 128
        const val MAX_MAP_LINES = 50_000
        const val STATUS_NAME = "Name"
        const val STATUS_STATE = "State"
        const val STATUS_THREADS = "Threads"
        const val STATUS_VM_RSS = "VmRSS"
        const val STATUS_UID = "Uid"
        val STATUS_KEYS = setOf(STATUS_NAME, STATUS_STATE, STATUS_THREADS, STATUS_VM_RSS, STATUS_UID)

        fun parseEffectiveUid(value: String): Int? {
            val fields = value.trim().split(Regex("\\s+"))
            if (fields.size != PROC_UID_FIELD_COUNT) return null
            val parsed = fields.map { field ->
                if (field.isEmpty() || !field.all(Char::isDigit)) return null
                field.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                    ?: return null
            }
            return parsed[EFFECTIVE_UID_FIELD_INDEX]
        }

        const val PROC_UID_FIELD_COUNT = 4
        const val EFFECTIVE_UID_FIELD_INDEX = 1
    }
}

internal class ControlTargetSession(
    private val inspector: TargetProcessInspector,
    private val memoryProbe: TargetMemoryProbe = NoTargetMemoryProbe,
    private val scalarReader: TargetScalarReader = NoTargetScalarReader,
    private val fieldProfileResolver: ControlReadOnlyFieldProfileResolver =
        ControlReadOnlyFieldProfileCatalog.resolver,
    private val addressRecipeResolver: ControlAddressRecipeResolver = NoControlAddressRecipeResolver,
    private val moduleSpecFieldAddressResolver: ControlModuleSpecFieldAddressResolver =
        NoControlModuleSpecFieldAddressResolver,
    private val searchIdScanner: ControlSearchIdScanner? = null,
    private val searchIdProfile: ControlSearchIdProfile =
        ControlRecoveredSearchIdProfiles.miniWorld1582Draft,
    private val serviceAbi: String = "",
    private val injectionProfileResolver: ControlInjectionProfileResolver =
        ControlInjectionProfileCatalog.resolver,
    private val injectionExecutor: ControlInjectionExecutor = NoControlInjectionExecutor,
    private val expectedTargetUid: Int? = null,
    private val packageIdentityVerifier: TargetPackageIdentityVerifier =
        AcceptAnyTargetPackageIdentity,
) {
    private var packageName: String = ""
    private var pid: Int? = null
    private var startTimeTicks: String? = null
    private var targetUid: Int? = null
    private var mapsFingerprint: String? = null
    private var summary: String = "未打开目标会话"
    private val featureStates = defaultControlFeatureStates().toMutableMap()
    private var nativeProbeState = ControlNativeProbeState()
    private var nativeModules: List<ControlNativeModuleIdentity> = emptyList()
    private var fieldProfileResolution = ControlReadOnlyFieldProfileResolution()
    private var injectionProfileResolution = ControlInjectionProfileResolution()

    private data class PreparedPositionAxis(
        val axis: ControlPlayerPositionAxis,
        val address: Long,
        val originalBits: Long,
        val desiredBits: Long,
    )

    private data class TargetVerification(
        val isValid: Boolean,
        val message: String = "",
    )

    init {
        require(expectedTargetUid == null || expectedTargetUid >= 0) {
            "Expected target UID must be non-negative"
        }
    }

    @Synchronized
    fun findTargetPid(value: String): Int? {
        val normalized = ControlProtocol.normalizePackageName(value)
        if (!ControlProtocol.isValidPackageName(normalized)) return null
        if (!packageIdentityVerifier.verify(normalized).isValid) return null
        return inspector.findPid(normalized)
    }

    @Synchronized
    fun readProcessName(value: Int): String? = inspector.readProcessName(value)

    @Synchronized
    fun verifyTargetProcess(value: Int, targetPackage: String): Boolean {
        val normalized = ControlProtocol.normalizePackageName(targetPackage)
        return inspectTargetIdentity(value, normalized).isValid
    }

    @Synchronized
    fun openOrRefresh(value: String): Boolean {
        val normalized = ControlProtocol.normalizePackageName(value)
        if (!ControlProtocol.isValidPackageName(normalized)) {
            close("目标包名格式无效")
            return false
        }

        val packageIdentity = packageIdentityVerifier.verify(normalized)
        if (!packageIdentity.isValid) {
            invalidateOpen(normalized, packageIdentity.message)
            return false
        }

        val resolvedPid = inspector.findPid(normalized)
        val processName = resolvedPid?.let(inspector::readProcessName)
        val resolvedStartTime = resolvedPid?.let(inspector::readStartTimeTicks)
        val resolvedUid = resolvedPid?.let(inspector::readEffectiveUid)
        if (
            resolvedPid == null ||
            processName == null ||
            resolvedStartTime == null ||
            !ControlProtocol.processMatchesPackage(processName, normalized)
        ) {
            invalidateResolverCaches()
            packageName = normalized
            pid = null
            startTimeTicks = null
            targetUid = null
            mapsFingerprint = null
            summary = "目标进程未运行：$normalized"
            resetFeatures()
            resetNativeProbe()
            return false
        }
        if (resolvedUid == null) {
            invalidateOpen(normalized, "目标进程 UID 不可读取：$normalized")
            return false
        }
        if (expectedTargetUid != null && resolvedUid != expectedTargetUid) {
            invalidateOpen(
                normalized,
                "目标进程 UID 不匹配：$resolvedUid，服务 UID $expectedTargetUid",
            )
            return false
        }

        val targetChanged = packageName != normalized ||
            pid != resolvedPid ||
            startTimeTicks != resolvedStartTime ||
            targetUid != resolvedUid
        if (targetChanged) invalidateResolverCaches()
        packageName = normalized
        pid = resolvedPid
        startTimeTicks = resolvedStartTime
        targetUid = resolvedUid
        if (targetChanged) {
            resetFeatures()
            mapsFingerprint = null
        }
        applyMemoryInspection(
            inspection = memoryProbe.inspect(resolvedPid),
            preserveFeatureStates = !targetChanged,
        )
        val postProbeVerification = inspectTargetIdentity(resolvedPid, normalized)
        if (!postProbeVerification.isValid) {
            invalidateTarget(postProbeVerification.message)
            return false
        }
        if (nativeProbeState.processStartTimeTicks.isNotBlank() &&
            nativeProbeState.processStartTimeTicks != resolvedStartTime
        ) {
            invalidateTarget("目标进程在探测期间已变化：$normalized")
            return false
        }
        disableUnsupportedFeatures()
        summary = buildSummary(processName)
        return true
    }

    @Synchronized
    fun close(message: String = "目标会话已关闭") {
        packageName = ""
        pid = null
        startTimeTicks = null
        targetUid = null
        summary = message
        resetFeatures()
        resetNativeProbe()
        invalidateResolverCaches()
    }

    @Synchronized
    fun setFeatureEnabled(feature: ControlFeature, enabled: Boolean): Boolean {
        val activePid = pid ?: return false
        val verification = inspectTargetIdentity(activePid, packageName)
        if (!verification.isValid) {
            invalidateTarget(verification.message)
            return false
        }
        if (!refreshMemoryGeneration(activePid, "feature change")) return false
        if (feature == ControlFeature.PLAYER_TELEPORT) {
            recordInjectionApply(
                ControlInjectionApplyStatus.FEATURE_NOT_DEFINED,
                feature,
                "Player teleport is an explicit three-axis action, not a boolean feature",
            )
            return false
        }
        if (feature != ControlFeature.READABLE_DATA) {
            return applyInjectionFeature(activePid, feature, enabled)
        }
        if (feature !in supportedFeatures()) return false
        if (enabled) {
            if (!nativeProbeState.isMemoryReady) {
                featureStates[feature] = false
                summary = buildSummary(inspector.readProcessName(activePid).orEmpty())
                return false
            }
            if (!fieldProfileResolution.state.isReady) {
                featureStates[feature] = false
                summary = buildSummary(inspector.readProcessName(activePid).orEmpty())
                return false
            }
        }
        featureStates[feature] = enabled
        val processName = inspector.readProcessName(activePid).orEmpty()
        summary = buildSummary(processName)
        return true
    }

    @Synchronized
    fun setPlayerPosition(request: ControlPlayerPositionRequest): ControlPlayerPositionResult {
        val activePid = pid ?: return positionFailure(
            request,
            ControlInjectionApplyStatus.SESSION_CLOSED,
            "Target session is not open",
        )
        val verification = inspectTargetIdentity(activePid, packageName)
        if (!verification.isValid) {
            val message = verification.message
            invalidateTargetForInjection(ControlFeature.PLAYER_TELEPORT, message)
            return ControlPlayerPositionResult(
                request = request,
                status = ControlInjectionApplyStatus.TARGET_CHANGED,
                message = message,
            )
        }
        if (!refreshMemoryGeneration(activePid, "player-position write")) {
            return ControlPlayerPositionResult(
                request = request,
                status = ControlInjectionApplyStatus.TARGET_CHANGED,
                message = summary,
            )
        }

        val resolution = injectionProfileResolution
        val profile = resolution.profile
        if (!resolution.state.isProfileReady || profile == null) {
            return positionFailure(
                request,
                ControlInjectionApplyStatus.PROFILE_NOT_READY,
                resolution.state.message.ifBlank { resolution.state.profileSummary },
            )
        }
        val positionEvidence = profile.playerPosition ?: return positionFailure(
            request,
            ControlInjectionApplyStatus.FEATURE_NOT_DEFINED,
            "Player-position recipes are not defined by the active injection profile",
        )
        val pinnedStartTime = startTimeTicks
        if (pinnedStartTime.isNullOrBlank()) {
            val message = "Pinned process identity is missing before player-position write"
            invalidateTargetForInjection(ControlFeature.PLAYER_TELEPORT, message)
            return ControlPlayerPositionResult(
                request = request,
                profileId = profile.profileId,
                status = ControlInjectionApplyStatus.TARGET_CHANGED,
                message = message,
            )
        }

        val requestedWorldValues = mapOf(
            ControlPlayerPositionAxis.X to request.x,
            ControlPlayerPositionAxis.Y to request.y,
            ControlPlayerPositionAxis.Z to request.z,
        )
        val requestedValues = linkedMapOf<ControlPlayerPositionAxis, Long>()
        for (axis in ControlPlayerPositionAxis.entries) {
            val worldValue = requireNotNull(requestedWorldValues[axis])
            val rawValue = positionEvidence.encodeWorldCoordinate(worldValue)
                ?: return positionFailure(
                    request,
                    ControlInjectionApplyStatus.INVALID_RESPONSE,
                    "Player-position axis ${axis.name} exceeds the int32 raw range at scale " +
                        positionEvidence.rawUnitsPerWorldUnit,
                    profile.profileId,
                )
            requestedValues[axis] = rawValue
        }
        val prepared = mutableListOf<PreparedPositionAxis>()
        for (axis in ControlPlayerPositionAxis.entries) {
            val recipe = positionEvidence.axisRecipes[axis] ?: return positionFailure(
                request,
                ControlInjectionApplyStatus.FEATURE_NOT_DEFINED,
                "Player-position recipe is missing for axis ${axis.name}",
                profile.profileId,
            )
            val addressResult = runCatching {
                addressRecipeResolver.resolve(activePid, recipe)
            }.getOrElse { error ->
                return positionFailure(
                    request,
                    ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                    error.message ?: "Player-position address resolution failed",
                    profile.profileId,
                )
            }
            if (addressResult.processStartTimeTicks.isNotBlank() &&
                addressResult.processStartTimeTicks != pinnedStartTime
            ) {
                val message = "Target identity changed while resolving player-position axis ${axis.name}"
                invalidateTargetForInjection(ControlFeature.PLAYER_TELEPORT, message)
                return ControlPlayerPositionResult(
                    request = request,
                    profileId = profile.profileId,
                    status = ControlInjectionApplyStatus.TARGET_CHANGED,
                    message = message,
                )
            }
            val address = addressResult.address
            if (!addressResult.isSuccess || address == null ||
                addressResult.processStartTimeTicks.isBlank()
            ) {
                return positionFailure(
                    request,
                    ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                    addressResult.message.ifBlank {
                        "Player-position address recipe failed for axis ${axis.name}"
                    },
                    profile.profileId,
                )
            }
            if (prepared.any { it.address == address }) {
                return positionFailure(
                    request,
                    ControlInjectionApplyStatus.INVALID_RESPONSE,
                    "Player-position recipes resolved duplicate axis addresses",
                    profile.profileId,
                )
            }

            val preflight = readInjectionScalarSafely(
                activePid,
                positionEvidence.byteCount,
                address,
            )
            if (preflight.processStartTimeTicks.isNotBlank() &&
                preflight.processStartTimeTicks != pinnedStartTime
            ) {
                val message = "Target identity changed during player-position preflight"
                invalidateTargetForInjection(ControlFeature.PLAYER_TELEPORT, message)
                return ControlPlayerPositionResult(
                    request = request,
                    profileId = profile.profileId,
                    status = ControlInjectionApplyStatus.TARGET_CHANGED,
                    message = message,
                )
            }
            if (!preflight.isSuccess || preflight.valueBits == null ||
                preflight.processStartTimeTicks.isBlank()
            ) {
                return positionFailure(
                    request,
                    ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                    preflight.message.ifBlank {
                        "Player-position preflight read failed for axis ${axis.name}"
                    },
                    profile.profileId,
                )
            }
            prepared += PreparedPositionAxis(
                axis = axis,
                address = address,
                originalBits = normalizePatchBits(
                    preflight.valueBits,
                    positionEvidence.byteCount,
                ),
                desiredBits = requireNotNull(requestedValues[axis]),
            )
        }

        val pending = prepared.filter { it.originalBits != it.desiredBits }
        if (pending.isEmpty()) {
            val message = "Player position already matches the requested coordinates"
            recordInjectionApply(
                ControlInjectionApplyStatus.ALREADY_APPLIED,
                ControlFeature.PLAYER_TELEPORT,
                message,
            )
            return ControlPlayerPositionResult(
                request = request,
                effectiveX = request.x,
                effectiveY = request.y,
                effectiveZ = request.z,
                profileId = profile.profileId,
                status = ControlInjectionApplyStatus.ALREADY_APPLIED,
                message = message,
            )
        }

        val written = mutableListOf<PreparedPositionAxis>()
        for (axisWrite in pending) {
            val writeResult = runCatching {
                injectionExecutor.apply(
                    ControlInjectionWriteRequest(
                        pid = activePid,
                        expectedStartTimeTicks = pinnedStartTime,
                        profileId = profile.profileId,
                        feature = ControlFeature.PLAYER_TELEPORT,
                        address = axisWrite.address,
                        byteCount = positionEvidence.byteCount,
                        expectedValueBits = axisWrite.originalBits,
                        desiredValueBits = axisWrite.desiredBits,
                        mappingRequirement = positionEvidence.mappingRequirement,
                    ),
                )
            }.getOrElse { error ->
                ControlInjectionWriteResult(
                    status = ControlInjectionApplyStatus.WRITE_FAILED,
                    message = error.message ?: "Player-position backend failed",
                )
            }
            if (writeResult.status == ControlInjectionApplyStatus.APPLIED) {
                written += axisWrite
            }
            if (writeResult.status == ControlInjectionApplyStatus.TARGET_CHANGED ||
                writeResult.processStartTimeTicks.isNotBlank() &&
                writeResult.processStartTimeTicks != pinnedStartTime
            ) {
                val message = writeResult.message.ifBlank {
                    "Target identity changed during player-position write"
                }
                invalidateTargetForInjection(ControlFeature.PLAYER_TELEPORT, message)
                return ControlPlayerPositionResult(
                    request = request,
                    appliedAxisCount = written.size,
                    profileId = profile.profileId,
                    status = ControlInjectionApplyStatus.TARGET_CHANGED,
                    message = message,
                )
            }
            if (!writeResult.isSuccess || writeResult.processStartTimeTicks.isBlank()) {
                val rollbackOk = rollbackPlayerPosition(
                    activePid,
                    pinnedStartTime,
                    profile.profileId,
                    positionEvidence,
                    written,
                )
                val status = if (rollbackOk) {
                    if (writeResult.isSuccess) {
                        ControlInjectionApplyStatus.INVALID_RESPONSE
                    } else {
                        writeResult.status
                    }
                } else {
                    ControlInjectionApplyStatus.ROLLBACK_FAILED
                }
                return positionFailure(
                    request,
                    status,
                    writeResult.message.ifBlank { "Player-position write failed" },
                    profile.profileId,
                    written.size,
                )
            }

            val verification = readInjectionScalarSafely(
                activePid,
                positionEvidence.byteCount,
                axisWrite.address,
            )
            val verificationMatches = verification.isSuccess &&
                verification.processStartTimeTicks == pinnedStartTime &&
                verification.valueBits != null &&
                normalizePatchBits(verification.valueBits, positionEvidence.byteCount) ==
                axisWrite.desiredBits
            if (!verificationMatches) {
                val rollbackOk = rollbackPlayerPosition(
                    activePid,
                    pinnedStartTime,
                    profile.profileId,
                    positionEvidence,
                    written,
                )
                return positionFailure(
                    request,
                    if (rollbackOk) {
                        ControlInjectionApplyStatus.VERIFY_FAILED
                    } else {
                        ControlInjectionApplyStatus.ROLLBACK_FAILED
                    },
                    verification.message.ifBlank {
                        "Player-position verification failed for axis ${axisWrite.axis.name}"
                    },
                    profile.profileId,
                    written.size,
                )
            }
        }

        val status = if (written.isEmpty()) {
            ControlInjectionApplyStatus.ALREADY_APPLIED
        } else {
            ControlInjectionApplyStatus.APPLIED
        }
        val message = "Player position applied: X=${request.x}, Y=${request.y}, Z=${request.z}"
        recordInjectionApply(status, ControlFeature.PLAYER_TELEPORT, message)
        summary = buildSummary(inspector.readProcessName(activePid).orEmpty())
        return ControlPlayerPositionResult(
            request = request,
            effectiveX = request.x,
            effectiveY = request.y,
            effectiveZ = request.z,
            appliedAxisCount = written.size,
            profileId = profile.profileId,
            status = status,
            message = message,
        )
    }

    @Synchronized
    fun isFeatureEnabled(feature: ControlFeature): Boolean = featureStates[feature] == true

    @Synchronized
    fun enabledFeatureIds(): Array<String> = featureStates
        .filterValues { it }
        .keys
        .map(ControlFeature::wireId)
        .toTypedArray()

    @Synchronized
    fun supportedFeatureIds(): Array<String> = supportedFeatures()
        .map(ControlFeature::wireId)
        .toTypedArray()

    @Synchronized
    fun fieldProfileState(): ControlReadOnlyFieldProfileState = fieldProfileResolution.state

    @Synchronized
    fun injectionState(): ControlInjectionState = injectionProfileResolution.state

    @Synchronized
    fun antiFlashStartRequest(): ControlAntiFlashStartRequest? {
        val activePid = pid ?: return null
        val activeStartTime = startTimeTicks?.takeIf(String::isNotBlank) ?: return null
        val activeMapsGeneration = mapsFingerprint?.takeIf(String::isNotBlank) ?: return null
        if (!nativeProbeState.isMemoryReady ||
            nativeProbeState.processStartTimeTicks != activeStartTime ||
            nativeProbeState.mapsFingerprint != activeMapsGeneration
        ) {
            return null
        }
        return ControlAntiFlashStartRequest(
            pid = activePid,
            startTimeTicks = activeStartTime,
            mapsGeneration = activeMapsGeneration,
            modules = nativeModules.toList(),
        )
    }

    /**
     * Returns a single-use head-spin request bound to the current process
     * generation.  The returned module identities are the private, full-SHA
     * inspection results; callers must not cache this request across a maps
     * refresh or target restart.
     */
    @Synchronized
    fun headSpinStartRequest(intervalInput: Int): ControlHeadSpinStartRequest? {
        val activePid = pid ?: return null
        val activeStartTime = startTimeTicks?.takeIf(String::isNotBlank) ?: return null
        val activeMapsGeneration = mapsFingerprint?.takeIf(String::isNotBlank) ?: return null
        if (!nativeProbeState.isMemoryReady ||
            nativeProbeState.processStartTimeTicks != activeStartTime ||
            nativeProbeState.mapsFingerprint != activeMapsGeneration
        ) {
            return null
        }
        return ControlHeadSpinStartRequest(
            identity = ControlHeadSpinTargetIdentity(
                pid = activePid,
                startTimeTicks = activeStartTime,
                mapsFingerprint = activeMapsGeneration,
            ),
            modules = nativeModules.toList(),
            intervalInput = intervalInput,
        )
    }

    @Synchronized
    fun isAntiFlashProfileReady(): Boolean {
        val request = antiFlashStartRequest() ?: return false
        val profile = ControlAntiFlashProfileCatalog.profile
        if (serviceAbi != profile.requiredAbi) return false
        return listOf(profile.gameApp, profile.tprt).all { expected ->
            val moduleName = expected.name
            val expectedSha256 = expected.sha256
            val matches = request.modules.filter { it.name == moduleName }
            matches.size == 1 && matches.single().let { module ->
                module.memoryElf &&
                    module.loadBase > 0L &&
                    module.mappedBytes > 0L &&
                    module.sha256?.lowercase() == expectedSha256
            }
        }
    }

    @Synchronized
    fun readVisibleFields(): ControlReadOnlyFieldBatch {
        val activePid = pid
        if (activePid != null && !refreshMemoryGeneration(activePid, "visible-field read")) {
            val failure = fieldReadFailure(
                ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                summary,
            )
            return ControlReadOnlyFieldBatch(fieldProfileResolution.state, failure, failure, failure)
        }
        val profileState = fieldProfileResolution.state
        if (!profileState.isReady) {
            val failure = fieldReadFailure(
                ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                profileState.message.ifBlank { profileState.summary },
            )
            return ControlReadOnlyFieldBatch(profileState, failure, failure, failure)
        }
        if (featureStates[ControlFeature.READABLE_DATA] != true) {
            val failure = fieldReadFailure(
                ControlReadOnlyFieldReadStatus.FEATURE_DISABLED,
                "Readable data feature is disabled",
            )
            return ControlReadOnlyFieldBatch(profileState, failure, failure, failure)
        }
        return ControlReadOnlyFieldBatch(
            profileState = profileState,
            lifeState = readField(ControlReadOnlyFieldId.LIFE_STATE),
            killCount = readField(ControlReadOnlyFieldId.KILL_COUNT),
            dataLongSelector1 = readField(ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1),
        )
    }

    @Synchronized
    fun readField(fieldId: ControlReadOnlyFieldId): ControlReadOnlyFieldReadResult {
        val activePid = pid ?: return fieldReadFailure(
            ControlReadOnlyFieldReadStatus.SESSION_CLOSED,
            "Target session is closed",
        )
        val pinnedStartTime = startTimeTicks
        if (pinnedStartTime.isNullOrBlank()) {
            invalidateTarget("Pinned process identity is missing before field read")
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                "Pinned process identity is missing",
            )
        }
        if (!verifyTargetProcess(activePid, packageName)) {
            invalidateTarget("目标进程已变化或退出：$packageName")
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                "Target process identity changed",
            )
        }
        if (!refreshMemoryGeneration(activePid, "field read")) {
            return fieldReadFailure(ControlReadOnlyFieldReadStatus.TARGET_CHANGED, summary)
        }
        if (!fieldProfileResolution.state.isReady) {
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                fieldProfileResolution.state.message.ifBlank {
                    fieldProfileResolution.state.summary
                },
            )
        }
        if (featureStates[ControlFeature.READABLE_DATA] != true) {
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.FEATURE_DISABLED,
                "Readable data feature is disabled",
            )
        }

        val resolvedField = fieldProfileResolution.profile
            ?.fields
            ?.get(fieldId)
            ?: return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.FIELD_NOT_AVAILABLE,
                "Field is not defined by the active profile",
            )
        val address = resolvedField.address ?: run {
            val addressRecipe = resolvedField.definition.addressRecipe
            val moduleSpecRecipe = resolvedField.definition.moduleSpecAddressRecipe
            if (addressRecipe == null && moduleSpecRecipe == null) {
                return fieldReadFailure(
                    ControlReadOnlyFieldReadStatus.FIELD_NOT_AVAILABLE,
                    "Field has no resolved address or address recipe",
                )
            }
            val addressAttempt = runCatching {
                if (addressRecipe != null) {
                    addressRecipeResolver.resolve(activePid, addressRecipe)
                } else {
                    moduleSpecFieldAddressResolver.resolve(
                        pid = activePid,
                        expectedStartTimeTicks = pinnedStartTime,
                        recipe = requireNotNull(moduleSpecRecipe),
                    )
                }
            }
            if (inspector.readStartTimeTicks(activePid) != pinnedStartTime) {
                invalidateTarget("Target process changed during field address resolution: $packageName")
                return fieldReadFailure(
                    ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                    "Target process identity changed during address resolution",
                )
            }
            val addressResult = addressAttempt.getOrElse { error ->
                return fieldReadFailure(
                    ControlReadOnlyFieldReadStatus.READ_FAILED,
                    error.message ?: "Address resolver failed",
                )
            }
            if (
                addressResult.status == ControlAddressRecipeStatus.TARGET_CHANGED ||
                addressResult.processStartTimeTicks.isNotBlank() &&
                addressResult.processStartTimeTicks != pinnedStartTime
            ) {
                invalidateTarget("目标进程在地址解析期间已变化：$packageName")
                return fieldReadFailure(
                    ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                    "Target process identity changed during address resolution",
                )
            }
            if (!addressResult.isSuccess || addressResult.address == null) {
                return fieldReadFailure(
                    ControlReadOnlyFieldReadStatus.READ_FAILED,
                    addressResult.message.ifBlank { addressResult.status.name },
                )
            }
            if (addressResult.processStartTimeTicks.isBlank()) {
                return fieldReadFailure(
                    ControlReadOnlyFieldReadStatus.READ_FAILED,
                    "Address resolver omitted process identity",
                )
            }
            addressResult.address
        }
        if (inspector.readStartTimeTicks(activePid) != pinnedStartTime) {
            invalidateTarget("Target process changed after field address resolution: $packageName")
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                "Target process identity changed after address resolution",
            )
        }
        val scalarAttempt = runCatching {
            when (resolvedField.definition) {
                is ControlInt32FieldDefinition -> scalarReader.readInt32(activePid, address)
                is ControlInt64FieldDefinition -> scalarReader.readInt64(activePid, address)
            }
        }
        if (inspector.readStartTimeTicks(activePid) != pinnedStartTime) {
            invalidateTarget("Target process changed after field read: $packageName")
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                "Target process identity changed after field read",
            )
        }
        val scalarResult = scalarAttempt.getOrElse { error ->
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.READ_FAILED,
                error.message ?: "Scalar read failed",
            )
        }
        if (
            scalarResult.processStartTimeTicks.isNotBlank() &&
            scalarResult.processStartTimeTicks != pinnedStartTime
        ) {
            invalidateTarget("目标进程在字段读取期间已变化：$packageName")
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.TARGET_CHANGED,
                "Target process identity changed during field read",
            )
        }
        if (scalarResult.isSuccess && scalarResult.processStartTimeTicks.isBlank()) {
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.READ_FAILED,
                "Scalar read omitted process identity",
            )
        }
        val valueBits = scalarResult.valueBits
        if (!scalarResult.isSuccess || valueBits == null) {
            return fieldReadFailure(
                ControlReadOnlyFieldReadStatus.READ_FAILED,
                scalarResult.message.ifBlank { "Scalar read failed" },
            )
        }

        val value = when (val definition = resolvedField.definition) {
            is ControlInt32FieldDefinition -> ControlInt32FieldValue(
                id = definition.id,
                value = definition.decode(valueBits),
            )
            is ControlInt64FieldDefinition -> ControlInt64FieldValue(
                id = definition.id,
                value = definition.decode(valueBits),
            )
        }
        return ControlReadOnlyFieldReadResult(
            status = ControlReadOnlyFieldReadStatus.OK,
            value = value,
        )
    }

    @Synchronized
    fun searchId(requestedId: Long): ControlSearchIdResult {
        val activePid = pid ?: return searchIdFailure(
            requestedId,
            ControlSearchIdInvalidReason.INVALID_PID,
            "Target session is closed",
        )
        val pinnedStartTime = startTimeTicks ?: return searchIdFailure(
            requestedId,
            ControlSearchIdInvalidReason.TARGET_CHANGED,
            "Pinned process identity is missing",
        )
        if (!verifyTargetProcess(activePid, packageName)) {
            invalidateTarget("目标进程已变化或退出：$packageName")
            return searchIdFailure(
                requestedId,
                ControlSearchIdInvalidReason.TARGET_CHANGED,
                "Target process identity changed before searchID",
            )
        }
        if (!refreshMemoryGeneration(activePid, "searchID")) {
            return searchIdFailure(
                requestedId,
                ControlSearchIdInvalidReason.TARGET_CHANGED,
                summary,
            )
        }
        val expectedStartTime = startTimeTicks ?: pinnedStartTime
        val scanner = searchIdScanner ?: return searchIdFailure(
            requestedId,
            ControlSearchIdInvalidReason.ANCHOR_UNAVAILABLE,
            "searchID scanner is unavailable",
            expectedStartTime,
        )
        val result = scanner.scan(
            pid = activePid,
            expectedStartTimeTicks = expectedStartTime,
            profile = searchIdProfile,
            requestedId = requestedId,
        )
        if (result.invalidReason == ControlSearchIdInvalidReason.TARGET_CHANGED) {
            invalidateTarget("目标进程在 searchID 期间已变化：$packageName")
        }
        return result
    }

    @Synchronized
    fun snapshot(): ControlTargetSnapshot = ControlTargetSnapshot(
        packageName = packageName,
        pid = pid,
        startTimeTicks = startTimeTicks,
        targetUid = targetUid,
        summary = summary,
        features = featureStates.toMap(),
        supportedFeatures = supportedFeatures(),
        nativeProbe = nativeProbeState,
        injection = injectionProfileResolution.state,
    )

    @Synchronized
    fun refreshSummary(): String {
        val activePid = pid ?: return summary
        val verification = inspectTargetIdentity(activePid, packageName)
        if (!verification.isValid) {
            invalidateTarget(verification.message)
            return summary
        }
        return summary
    }

    private fun buildSummary(processName: String): String {
        val activePid = pid ?: return summary
        val enabledCount = featureStates.count { it.value }
        val brief = inspector.readBrief(activePid)
        val details = buildList {
            add("PID $activePid")
            targetUid?.let { add("UID $it") }
            brief?.name?.takeIf(String::isNotBlank)?.let { add("名称 $it") }
            brief?.state?.takeIf(String::isNotBlank)?.let { add("状态 $it") }
            brief?.threads?.let { add("线程 $it") }
            brief?.readableMapLines?.let { add("映射 $it") }
            brief?.vmRss?.takeIf(String::isNotBlank)?.let { add("RSS $it") }
            if (size == 1 && processName.isNotBlank()) add(processName)
            if (nativeProbeState.status != ControlNativeProbeStatus.IDLE) {
                add("Native ${nativeProbeState.summary}")
                add("Injection ${injectionProfileResolution.state.profileSummary}")
                add("字段档案 ${fieldProfileResolution.state.summary}")
            }
            add("已启用 $enabledCount 项")
        }
        return "$packageName · ${details.joinToString(" · ")}"
    }

    private fun resetFeatures() {
        ControlFeature.entries.forEach { featureStates[it] = false }
    }

    private fun supportedFeatures(): Set<ControlFeature> = buildSet {
        if (isAntiFlashProfileReady()) {
            add(ControlFeature.ANTI_FLASH)
        }
        if (nativeProbeState.isMemoryReady && fieldProfileResolution.state.isReady) {
            add(ControlFeature.READABLE_DATA)
        }
        if (nativeProbeState.isMemoryReady && injectionProfileResolution.state.isProfileReady) {
            addAll(injectionProfileResolution.profile?.patches?.keys.orEmpty())
            if (injectionProfileResolution.profile?.playerPosition != null) {
                add(ControlFeature.PLAYER_TELEPORT)
            }
        }
    }

    private fun rollbackPlayerPosition(
        activePid: Int,
        pinnedStartTime: String,
        profileId: String,
        evidence: ControlPlayerPositionEvidence,
        written: List<PreparedPositionAxis>,
    ): Boolean {
        var allRolledBack = true
        for (axisWrite in written.asReversed()) {
            val rollback = runCatching {
                injectionExecutor.apply(
                    ControlInjectionWriteRequest(
                        pid = activePid,
                        expectedStartTimeTicks = pinnedStartTime,
                        profileId = profileId,
                        feature = ControlFeature.PLAYER_TELEPORT,
                        address = axisWrite.address,
                        byteCount = evidence.byteCount,
                        expectedValueBits = axisWrite.desiredBits,
                        desiredValueBits = axisWrite.originalBits,
                        mappingRequirement = evidence.mappingRequirement,
                    ),
                )
            }.getOrNull()
            if (rollback == null || !rollback.isSuccess ||
                rollback.processStartTimeTicks != pinnedStartTime
            ) {
                allRolledBack = false
                if (rollback?.status == ControlInjectionApplyStatus.TARGET_CHANGED) break
                continue
            }
            val verification = readInjectionScalarSafely(
                activePid,
                evidence.byteCount,
                axisWrite.address,
            )
            if (!verification.isSuccess ||
                verification.processStartTimeTicks != pinnedStartTime ||
                verification.valueBits == null ||
                normalizePatchBits(verification.valueBits, evidence.byteCount) != axisWrite.originalBits
            ) {
                allRolledBack = false
            }
        }
        return allRolledBack
    }

    private fun positionFailure(
        request: ControlPlayerPositionRequest,
        status: ControlInjectionApplyStatus,
        message: String,
        profileId: String = injectionProfileResolution.state.profileId,
        appliedAxisCount: Int = 0,
    ): ControlPlayerPositionResult {
        recordInjectionApply(status, ControlFeature.PLAYER_TELEPORT, message)
        return ControlPlayerPositionResult(
            request = request,
            appliedAxisCount = appliedAxisCount,
            profileId = profileId,
            status = status,
            message = message.take(256),
        )
    }

    private fun disableUnsupportedFeatures() {
        val supported = supportedFeatures()
        ControlFeature.entries.forEach { feature ->
            if (feature !in supported) featureStates[feature] = false
        }
    }

    private fun applyInjectionFeature(
        activePid: Int,
        feature: ControlFeature,
        enabled: Boolean,
    ): Boolean {
        val resolution = injectionProfileResolution
        val profile = resolution.profile
        if (!resolution.state.isProfileReady || profile == null) {
            recordInjectionApply(
                ControlInjectionApplyStatus.PROFILE_NOT_READY,
                feature,
                resolution.state.message.ifBlank { resolution.state.profileSummary },
            )
            return false
        }
        val patch = profile.patches[feature]
        if (patch == null) {
            recordInjectionApply(
                ControlInjectionApplyStatus.FEATURE_NOT_DEFINED,
                feature,
                "Feature is not defined by the active injection profile",
            )
            return false
        }
        val pinnedStartTime = startTimeTicks
        if (pinnedStartTime.isNullOrBlank()) {
            invalidateTargetForInjection(
                feature,
                "Pinned process identity is missing before injection",
            )
            return false
        }

        val patchAddress = patch.address ?: run {
            val recipe = patch.addressRecipe
            if (recipe == null) {
                recordInjectionApply(
                    ControlInjectionApplyStatus.INVALID_RESPONSE,
                    feature,
                    "Injection profile omitted both a direct address and address recipe",
                )
                return false
            }
            val addressResult = runCatching {
                addressRecipeResolver.resolve(activePid, recipe)
            }.getOrElse { error ->
                recordInjectionApply(
                    ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                    feature,
                    error.message ?: "Injection address resolver failed",
                )
                return false
            }
            if (addressResult.processStartTimeTicks.isNotBlank() &&
                addressResult.processStartTimeTicks != pinnedStartTime
            ) {
                invalidateTargetForInjection(
                    feature,
                    "Target process identity changed during injection address resolution",
                )
                return false
            }
            if (!addressResult.isSuccess || addressResult.address == null ||
                addressResult.processStartTimeTicks.isBlank()
            ) {
                recordInjectionApply(
                    ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                    feature,
                    addressResult.message.ifBlank { "Injection address recipe resolution failed" },
                )
                return false
            }
            addressResult.address
        }
        val preflight = readInjectionScalarSafely(activePid, patch.byteCount, patchAddress)
        if (preflight.processStartTimeTicks.isNotBlank() &&
            preflight.processStartTimeTicks != pinnedStartTime
        ) {
            invalidateTargetForInjection(
                feature,
                "Target process identity changed during injection preflight",
            )
            return false
        }
        if (!preflight.isSuccess || preflight.valueBits == null) {
            recordInjectionApply(
                ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                feature,
                preflight.message.ifBlank { "Injection precondition scalar read failed" },
            )
            return false
        }
        if (preflight.processStartTimeTicks.isBlank()) {
            recordInjectionApply(
                ControlInjectionApplyStatus.INVALID_RESPONSE,
                feature,
                "Injection precondition read omitted process identity",
            )
            return false
        }

        val currentBits = normalizePatchBits(preflight.valueBits, patch.byteCount)
        val targetValueBits = if (enabled) patch.enabledValueBits else patch.disabledValueBits
        val valueMask = patch.valueMask?.let { normalizePatchBits(it, patch.byteCount) }
        val alreadyApplied = if (valueMask == null) {
            currentBits == targetValueBits
        } else {
            (currentBits and valueMask) == (targetValueBits and valueMask)
        }
        if (alreadyApplied) {
            featureStates[feature] = enabled
            recordInjectionApply(
                ControlInjectionApplyStatus.ALREADY_APPLIED,
                feature,
                "Target scalar already has the requested typed value",
            )
            summary = buildSummary(inspector.readProcessName(activePid).orEmpty())
            return true
        }
        val expectedBits: Long
        val desiredBits: Long
        if (valueMask == null) {
            val expectedValueBits = if (enabled) patch.disabledValueBits else patch.enabledValueBits
            if (currentBits != expectedValueBits) {
                recordInjectionApply(
                    ControlInjectionApplyStatus.EXPECTED_VALUE_MISMATCH,
                    feature,
                    "Target scalar did not match the profile precondition",
                )
                return false
            }
            expectedBits = expectedValueBits
            desiredBits = targetValueBits
        } else {
            expectedBits = currentBits
            desiredBits = normalizePatchBits(
                (currentBits and valueMask.inv()) or (targetValueBits and valueMask),
                patch.byteCount,
            )
        }
        if (desiredBits == currentBits) {
            recordInjectionApply(
                ControlInjectionApplyStatus.ALREADY_APPLIED,
                feature,
                "Target scalar already has the requested masked value",
            )
            featureStates[feature] = enabled
            summary = buildSummary(inspector.readProcessName(activePid).orEmpty())
            return true
        }

        val writeRequest = ControlInjectionWriteRequest(
            pid = activePid,
            expectedStartTimeTicks = pinnedStartTime,
            profileId = profile.profileId,
            feature = feature,
            address = patchAddress,
            byteCount = patch.byteCount,
            expectedValueBits = expectedBits,
            desiredValueBits = desiredBits,
            mappingRequirement = patch.mappingRequirement,
        )
        val writeResult = runCatching { injectionExecutor.apply(writeRequest) }
            .getOrElse { error ->
                ControlInjectionWriteResult(
                    status = ControlInjectionApplyStatus.WRITE_FAILED,
                    message = error.message ?: "Typed injection backend failed",
                )
            }
        if (writeResult.status == ControlInjectionApplyStatus.TARGET_CHANGED ||
            writeResult.processStartTimeTicks.isNotBlank() &&
            writeResult.processStartTimeTicks != pinnedStartTime
        ) {
            invalidateTargetForInjection(
                feature,
                writeResult.message.ifBlank { "Target process identity changed during injection" },
            )
            return false
        }
        if (!writeResult.isSuccess) {
            recordInjectionApply(
                writeResult.status,
                feature,
                writeResult.message.ifBlank { writeResult.status.name },
            )
            return false
        }
        if (writeResult.processStartTimeTicks.isBlank()) {
            recordInjectionApply(
                ControlInjectionApplyStatus.INVALID_RESPONSE,
                feature,
                "Injection backend omitted process identity",
            )
            return false
        }

        val verification = readInjectionScalarSafely(activePid, patch.byteCount, patchAddress)
        if (verification.processStartTimeTicks.isNotBlank() &&
            verification.processStartTimeTicks != pinnedStartTime
        ) {
            invalidateTargetForInjection(
                feature,
                "Target process identity changed during injection verification",
            )
            return false
        }
        if (!verification.isSuccess ||
            verification.processStartTimeTicks.isBlank() ||
            verification.valueBits == null ||
            normalizePatchBits(verification.valueBits, patch.byteCount) != desiredBits
        ) {
            recordInjectionApply(
                ControlInjectionApplyStatus.VERIFY_FAILED,
                feature,
                verification.message.ifBlank { "Typed injection verification failed" },
            )
            return false
        }

        featureStates[feature] = enabled
        recordInjectionApply(writeResult.status, feature, writeResult.message)
        summary = buildSummary(inspector.readProcessName(activePid).orEmpty())
        return true
    }

    private fun readInjectionScalar(
        activePid: Int,
        byteCount: Int,
        address: Long,
    ): TargetScalarReadResult = when (byteCount) {
        Int.SIZE_BYTES -> scalarReader.readInt32(activePid, address)
        Long.SIZE_BYTES -> scalarReader.readInt64(activePid, address)
        else -> TargetScalarReadResult(
            isSuccess = false,
            message = "Injection profile uses an unsupported scalar width",
        )
    }

    private fun readInjectionScalarSafely(
        activePid: Int,
        byteCount: Int,
        address: Long,
    ): TargetScalarReadResult = runCatching {
        readInjectionScalar(activePid, byteCount, address)
    }.getOrElse { error ->
        TargetScalarReadResult(
            isSuccess = false,
            message = error.message ?: "Typed injection scalar read failed",
        )
    }

    private fun normalizePatchBits(value: Long, byteCount: Int): Long =
        if (byteCount == Int.SIZE_BYTES) value and 0xffff_ffffL else value

    private fun recordInjectionApply(
        status: ControlInjectionApplyStatus,
        feature: ControlFeature,
        message: String,
    ) {
        injectionProfileResolution = injectionProfileResolution.copy(
            state = injectionProfileResolution.state.copy(
                lastApplyStatus = status,
                lastFeature = feature,
                message = message.take(256),
            ),
        )
    }

    private fun invalidateTargetForInjection(
        feature: ControlFeature,
        message: String,
    ) {
        invalidateTarget(message)
        injectionProfileResolution = ControlInjectionProfileResolution(
            state = ControlInjectionState(
                lastApplyStatus = ControlInjectionApplyStatus.TARGET_CHANGED,
                lastFeature = feature,
                message = message.take(256),
            ),
        )
    }

    private fun resetNativeProbe() {
        nativeProbeState = ControlNativeProbeState()
        nativeModules = emptyList()
        mapsFingerprint = null
        fieldProfileResolution = ControlReadOnlyFieldProfileResolution()
        injectionProfileResolution = ControlInjectionProfileResolution()
    }

    private fun applyMemoryInspection(
        inspection: TargetMemoryInspection,
        preserveFeatureStates: Boolean = false,
    ) {
        val enabledFeatures = if (preserveFeatureStates) {
            featureStates.filterValues { it }.keys.toSet()
        } else {
            emptySet()
        }
        val observedFingerprint = inspection.state.mapsFingerprint.takeIf(String::isNotBlank)
        val mapsChanged = mapsFingerprint != null && observedFingerprint != mapsFingerprint
        if (mapsChanged) {
            resetFeatures()
            invalidateResolverCaches()
        }
        mapsFingerprint = observedFingerprint
        nativeProbeState = inspection.state
        nativeModules = if (inspection.state.isMemoryReady) {
            inspection.modules.toList()
        } else {
            emptyList()
        }
        fieldProfileResolution = if (inspection.state.isMemoryReady) {
            fieldProfileResolver.resolve(packageName, inspection.modules)
        } else {
            ControlReadOnlyFieldProfileResolution()
        }
        injectionProfileResolution = if (inspection.state.isMemoryReady) {
            injectionProfileResolver.resolve(packageName, serviceAbi, inspection.modules)
        } else {
            ControlInjectionProfileResolution()
        }
        disableUnsupportedFeatures()
        if (enabledFeatures.isNotEmpty()) {
            val supported = supportedFeatures()
            enabledFeatures.forEach { feature ->
                if (feature in supported) featureStates[feature] = true
            }
        }
    }

    private fun refreshMemoryGeneration(activePid: Int, operation: String): Boolean {
        val state = runCatching { memoryProbe.inspectGeneration(activePid) }
            .getOrElse { error ->
                invalidateTarget(
                    error.message ?: "Target memory generation check failed during $operation",
                )
                return false
            }
        val observedStartTime = state.processStartTimeTicks
        if (observedStartTime.isBlank() || observedStartTime != startTimeTicks) {
            invalidateTarget("Target process changed during $operation: $packageName")
            return false
        }
        val observedFingerprint = state.mapsFingerprint.takeIf(String::isNotBlank)
        if (observedFingerprint == null) {
            invalidateTarget("Target maps fingerprint is missing during $operation: $packageName")
            return false
        }
        if (mapsFingerprint != null && observedFingerprint != mapsFingerprint) {
            val inspection = runCatching { memoryProbe.inspect(activePid) }
                .getOrElse { error ->
                    invalidateTarget(
                        error.message ?: "Target inspection refresh failed during $operation",
                    )
                    return false
                }
            if (
                inspection.state.processStartTimeTicks != startTimeTicks
            ) {
                invalidateTarget("Target changed while refreshing maps during $operation: $packageName")
                return false
            }
            val refreshedFingerprint = inspection.state.mapsFingerprint.takeIf(String::isNotBlank)
            if (refreshedFingerprint == null) {
                invalidateTarget("Refreshed maps fingerprint is missing during $operation: $packageName")
                return false
            }
            applyMemoryInspection(
                inspection = inspection,
                preserveFeatureStates = true,
            )
            summary = if (refreshedFingerprint == observedFingerprint) {
                "Target maps refreshed during $operation: $packageName"
            } else {
                "Target maps advanced during $operation; latest snapshot applied: $packageName"
            }
            return true
        }
        if (mapsFingerprint == null) mapsFingerprint = observedFingerprint
        return true
    }

    private fun invalidateResolverCaches() {
        moduleSpecFieldAddressResolver.invalidateCache()
        searchIdScanner?.invalidateCache()
    }

    private fun invalidateTarget(message: String) {
        pid = null
        startTimeTicks = null
        targetUid = null
        summary = message
        resetFeatures()
        resetNativeProbe()
        invalidateResolverCaches()
    }

    private fun invalidateOpen(normalizedPackage: String, message: String) {
        packageName = normalizedPackage
        pid = null
        startTimeTicks = null
        targetUid = null
        summary = message
        resetFeatures()
        resetNativeProbe()
        invalidateResolverCaches()
    }

    private fun inspectTargetIdentity(value: Int, normalizedPackage: String): TargetVerification {
        if (!ControlProtocol.isValidPackageName(normalizedPackage)) {
            return TargetVerification(false, "目标包名格式无效")
        }
        val packageIdentity = packageIdentityVerifier.verify(normalizedPackage)
        if (!packageIdentity.isValid) {
            return TargetVerification(false, packageIdentity.message)
        }
        if (!inspector.isAlive(value)) {
            return TargetVerification(false, "目标进程已退出：$normalizedPackage")
        }
        val processName = inspector.readProcessName(value)
            ?: return TargetVerification(false, "目标进程名称不可读取：$normalizedPackage")
        if (!ControlProtocol.processMatchesPackage(processName, normalizedPackage)) {
            return TargetVerification(false, "目标进程包名已变化：$normalizedPackage")
        }
        val actualUid = inspector.readEffectiveUid(value)
            ?: return TargetVerification(false, "目标进程 UID 不可读取：$normalizedPackage")
        if (expectedTargetUid != null && actualUid != expectedTargetUid) {
            return TargetVerification(
                false,
                "目标进程 UID 不匹配：$actualUid，服务 UID $expectedTargetUid",
            )
        }
        val pinnedUid = if (value == pid && normalizedPackage == packageName) targetUid else null
        if (pinnedUid != null && actualUid != pinnedUid) {
            return TargetVerification(false, "目标进程 UID 已变化：$actualUid，固定 UID $pinnedUid")
        }
        val actualStartTime = inspector.readStartTimeTicks(value)
            ?: return TargetVerification(false, "目标进程启动标识不可读取：$normalizedPackage")
        val expectedStartTime = if (value == pid && normalizedPackage == packageName) {
            startTimeTicks
        } else {
            null
        }
        if (expectedStartTime != null && actualStartTime != expectedStartTime) {
            return TargetVerification(false, "目标进程启动标识已变化：$normalizedPackage")
        }
        return TargetVerification(true)
    }

    private fun fieldReadFailure(
        status: ControlReadOnlyFieldReadStatus,
        message: String,
    ): ControlReadOnlyFieldReadResult = ControlReadOnlyFieldReadResult(
        status = status,
        message = message,
    )

    private fun searchIdFailure(
        requestedId: Long,
        reason: ControlSearchIdInvalidReason,
        message: String,
        processStartTimeTicks: String = "",
    ): ControlSearchIdResult = ControlSearchIdResult(
        status = ControlSearchIdStatus.INVALID,
        requestedId = requestedId,
        processStartTimeTicks = processStartTimeTicks,
        invalidReason = reason,
        message = message,
    )
}
