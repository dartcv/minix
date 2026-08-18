package me.dartcv.minix.root

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Gated domain model for the recovered head-spin candidate.
 *
 * The supervisor is implemented as an internal, testable controller.  The
 * JNI/native adapter lives in [RootHeadSpinNativeBackend.kt], while
 * RootFeature, Binder/AIDL, and UI exposure remain deliberately closed until
 * the evidence gates in HEADSPIN_MINIX_IMPLEMENTATION_DESIGN.md are closed.
 */
internal enum class RootHeadSpinAxis {
    A,
    B,
}

internal enum class RootHeadSpinStatus {
    IDLE,
    PREFLIGHT,
    STARTING,
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
    INVALID_RESPONSE,
}

internal enum class RootHeadSpinResolveStatus {
    RESOLVED,
    INVALID_REQUEST,
    MODULE_NOT_FOUND,
    MODULE_AMBIGUOUS,
    FINGERPRINT_UNAVAILABLE,
    FINGERPRINT_MISMATCH,
    ADDRESS_FAILED,
    TARGET_CHANGED,
    PROFILE_MISMATCH,
}

internal enum class RootHeadSpinBackendStatus {
    OK,
    ALREADY_APPLIED,
    TARGET_CHANGED,
    PROFILE_MISMATCH,
    READ_FAILED,
    EXPECTED_VALUE_MISMATCH,
    WRITE_FAILED,
    VERIFY_FAILED,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

internal data class RootHeadSpinTargetIdentity(
    val pid: Int,
    val startTimeTicks: String,
    val mapsFingerprint: String,
) {
    init {
        require(pid > 0) { "Head-spin PID must be positive" }
        require(startTimeTicks.isNotBlank()) { "Head-spin start time is required" }
        require(mapsFingerprint.isNotBlank()) { "Head-spin maps fingerprint is required" }
    }
}

internal data class RootHeadSpinStartRequest(
    val identity: RootHeadSpinTargetIdentity,
    val modules: List<RootNativeModuleIdentity>,
    val intervalInput: Int,
)

internal data class RootHeadSpinTarget(
    val identity: RootHeadSpinTargetIdentity,
    val gameAppSha256: String,
    val libClientSha256: String,
    val gameAppLoadBase: Long,
    val codeAddress: Long,
    val axisAddressA: Long,
    val axisAddressB: Long,
) {
    init {
        require(gameAppSha256.length == 64) { "GameApp SHA-256 is malformed" }
        require(libClientSha256.length == 64) { "libClient SHA-256 is malformed" }
        require(gameAppLoadBase > 0L) { "GameApp load base must be positive" }
        require(codeAddress > 0L) { "Head-spin code address must be positive" }
        require(axisAddressA > 0L && axisAddressB > 0L) {
            "Head-spin axis addresses must be positive"
        }
        require(axisAddressA != axisAddressB) { "Head-spin axis addresses must be distinct" }
    }
}

internal data class RootHeadSpinAxisSnapshot(
    val addressA: Long,
    val addressB: Long,
    val originalBitsA: Int,
    val originalBitsB: Int,
    var lastVerifiedBitsA: Int = originalBitsA,
    var lastVerifiedBitsB: Int = originalBitsB,
)

internal data class RootHeadSpinState(
    val status: RootHeadSpinStatus = RootHeadSpinStatus.IDLE,
    val requestedEnabled: Boolean = false,
    val workerRunning: Boolean = false,
    val applied: Boolean = false,
    val profileId: String = "",
    val generation: Long = 0L,
    val targetPid: Int? = null,
    val targetStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val intervalInput: Int = 0,
    val delayMicros: Long = 0L,
    val iterationCount: Long = 0L,
    val successfulPairWriteCount: Long = 0L,
    val snapshotReady: Boolean = false,
    val message: String = "",
)

internal data class RootHeadSpinProfile(
    val profileId: String,
    val libClientSha256: String,
    val gameAppSha256: String,
    val selector: Int,
    val delayScaleMicros: Long,
    val rootSeed: Long,
    val chainOffsets: List<Long>,
    val axisOffsetA: Long,
    val axisOffsetB: Long,
    val patchRva: Long,
    val patchedWord: Int,
    val restoreWord: Int,
    val angleStep: Float,
    val angleModulus: Float,
    val minIntervalInput: Int,
    val maxIntervalInput: Int,
    val defaultIntervalInput: Int,
    val axisRecipes: Map<RootHeadSpinAxis, RootResolverAddressRecipe>,
) {
    init {
        require(profileId.isNotBlank()) { "Head-spin profile ID is required" }
        require(isSha256(libClientSha256) && isSha256(gameAppSha256)) {
            "Head-spin profile SHA-256 is malformed"
        }
        require(selector > 0) { "Head-spin selector must be positive" }
        require(delayScaleMicros > 0L) { "Head-spin delay scale must be positive" }
        require(rootSeed > 0L && chainOffsets.size == 3) {
            "Head-spin address chain is incomplete"
        }
        require(axisOffsetA >= 0L && axisOffsetB >= 0L && axisOffsetA != axisOffsetB) {
            "Head-spin axis offsets are invalid"
        }
        require(patchRva >= 0L && patchedWord != restoreWord) {
            "Head-spin code patch profile is invalid"
        }
        require(angleStep.isFinite() && angleStep > 0f) { "Head-spin angle step is invalid" }
        require(angleModulus.isFinite() && angleModulus > angleStep) {
            "Head-spin angle modulus is invalid"
        }
        require(minIntervalInput > 0 && maxIntervalInput >= minIntervalInput) {
            "Head-spin interval range is invalid"
        }
        require(defaultIntervalInput in minIntervalInput..maxIntervalInput) {
            "Head-spin default interval is outside the safety range"
        }
        require(axisRecipes.keys == RootHeadSpinAxis.entries.toSet()) {
            "Head-spin axis recipes are incomplete"
        }
        require(axisRecipes.values.all(RootResolverAddressRecipe::isStructurallyValid)) {
            "Head-spin axis recipe is malformed"
        }
    }

    fun encodeDelayMicros(intervalInput: Int): Long? {
        if (intervalInput !in minIntervalInput..maxIntervalInput) return null
        return runCatching { Math.multiplyExact(intervalInput.toLong(), delayScaleMicros) }
            .getOrNull()
    }

    private companion object {
        fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

/** Exact static profile recovered from the pinned arm64 samples. */
internal object RootHeadSpinProfileCatalog {
    const val LIB_CLIENT_SHA256 =
        "0e83d59aea5b32e45704068b2e35390bedb431891a56a9a5cccd357e5b17f13b"
    const val GAME_APP_SHA256 =
        "d8efe55c37b061ce41cb3830ec743401138bf40b8bacf251f18f7b026bf0140e"
    const val SELECTOR = 17
    const val CROSSHAIR_SELECTOR = 18
    const val DELAY_GLOBAL_OFFSET = 0x971e04L
    const val WORKER_ENTRY_OFFSET = 0x589698L
    const val WORKER_FLAG_OFFSET = 0x8357e8L
    const val PATCH_RVA = 0x037b220cL
    const val PATCHED_WORD = 0xb9005909L
    const val RESTORE_WORD = 0x72a80beaL

    /**
     * The engine safety default is deliberately non-zero. It is not a claim
     * about the original UI default; that value remains evidence-gated.
     */
    const val SAFE_MIN_INTERVAL_INPUT = 1
    const val SAFE_MAX_INTERVAL_INPUT = 100_000
    const val SAFE_DEFAULT_INTERVAL_INPUT = 1

    /** Modules whose full file identity is required before resolving this gate. */
    val fingerprintModuleNames: Set<String> = setOf(
        RootGameAppArtifact1582.MODULE_NAME,
        "libClient.so",
    )

    val profile = RootHeadSpinProfile(
        profileId = "miniworld-1.58.2-arm64-head-spin-v1-gated",
        libClientSha256 = LIB_CLIENT_SHA256,
        gameAppSha256 = GAME_APP_SHA256,
        selector = SELECTOR,
        delayScaleMicros = 10L,
        rootSeed = 0x000de990L,
        chainOffsets = listOf(0x38L, 0x08L, 0x50L),
        axisOffsetA = 0x54L,
        axisOffsetB = 0x58L,
        patchRva = PATCH_RVA,
        patchedWord = PATCHED_WORD.toInt(),
        restoreWord = RESTORE_WORD.toInt(),
        angleStep = 1.0f,
        angleModulus = 361.0f,
        minIntervalInput = SAFE_MIN_INTERVAL_INPUT,
        maxIntervalInput = SAFE_MAX_INTERVAL_INPUT,
        defaultIntervalInput = SAFE_DEFAULT_INTERVAL_INPUT,
        axisRecipes = mapOf(
            RootHeadSpinAxis.A to axisRecipe(
                finalOffset = 0x54L,
                sourceReference = "H(0x000de990)->+0x38->+0x08->+0x50->+0x54",
            ),
            RootHeadSpinAxis.B to axisRecipe(
                finalOffset = 0x58L,
                sourceReference = "H(0x000de990)->+0x38->+0x08->+0x50->+0x58",
            ),
        ),
    )

    private fun axisRecipe(
        finalOffset: Long,
        sourceReference: String,
    ): RootResolverAddressRecipe = RootResolverAddressRecipe(
        initialSeed = 0x000de990L,
        intermediateOffsets = listOf(0x38L, 0x08L, 0x50L),
        finalOffset = finalOffset,
        sourceArtifact = "work/headspeed-recovery-20260817/HEADSPEED_RECOVERY_REPORT.md",
        sourceReference = sourceReference,
        seedAnchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = RootAddressDereferenceMode.LEGACY_MASKED_H,
    )
}

/**
 * Static UI conversion contract recovered alongside the native worker.
 *
 * This is deliberately separate from [RootHeadSpinProfile]'s internal safety
 * interval range: the original UI stores an inverted progress value, while
 * the supervisor consumes the raw delay input directly.  Keeping the two
 * domains explicit prevents the selector-18 crosshair control from being
 * accidentally wired to selector 17's worker.
 */
internal object RootHeadSpinUiContract {
    const val MIN_PROGRESS = 10
    const val MAX_PROGRESS = 955
    const val MAX_PROGRESS_SCALE = 1_000
    const val CONSTRUCTOR_PROGRESS = 300
    const val CONSTRUCTOR_TEXT = 960
    const val PERSISTED_DISPLAY_DEFAULT = 955
    const val RAW_DEFAULT = 0
    const val CHARACTER_SELECTOR = RootHeadSpinProfileCatalog.SELECTOR
    const val CROSSHAIR_SELECTOR = RootHeadSpinProfileCatalog.CROSSHAIR_SELECTOR

    fun displayToRaw(progress: Int): Int? {
        if (progress !in MIN_PROGRESS..MAX_PROGRESS) return null
        return MAX_PROGRESS_SCALE - progress
    }

    fun rawToDisplay(raw: Int): Int? {
        val display = MAX_PROGRESS_SCALE - raw
        return display.takeIf { it in MIN_PROGRESS..MAX_PROGRESS }
    }
}

internal data class RootHeadSpinTargetResolution(
    val status: RootHeadSpinResolveStatus,
    val target: RootHeadSpinTarget? = null,
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootHeadSpinResolveStatus.RESOLVED && target != null
}

internal fun interface RootHeadSpinTargetResolver {
    fun resolve(
        request: RootHeadSpinStartRequest,
        profile: RootHeadSpinProfile,
    ): RootHeadSpinTargetResolution
}

/**
 * Exact-SHA target resolver. Address resolution is injected so this class is
 * fully unit-testable and does not load JNI on its own.
 */
internal class RootHeadSpinExactShaTargetResolver(
    private val addressRecipeResolver: RootAddressRecipeResolver,
) : RootHeadSpinTargetResolver {
    override fun resolve(
        request: RootHeadSpinStartRequest,
        profile: RootHeadSpinProfile,
    ): RootHeadSpinTargetResolution {
        val delay = profile.encodeDelayMicros(request.intervalInput)
            ?: return failure(
                RootHeadSpinResolveStatus.INVALID_REQUEST,
                "Head-spin interval is outside the gated safety range",
            )
        if (delay <= 0L) {
            return failure(RootHeadSpinResolveStatus.INVALID_REQUEST, "Head-spin delay must be positive")
        }

        val gameApp = when (val lookup = findExactModule(
            request.modules,
            moduleName = RootGameAppArtifact1582.MODULE_NAME,
            expectedSha256 = profile.gameAppSha256,
        )) {
            is ExactModuleLookup.Found -> lookup.module
            is ExactModuleLookup.Failed -> return lookup.resolution
        }
        val libClient = when (val lookup = findExactModule(
            request.modules,
            moduleName = "libClient.so",
            expectedSha256 = profile.libClientSha256,
        )) {
            is ExactModuleLookup.Found -> lookup.module
            is ExactModuleLookup.Failed -> return lookup.resolution
        }

        if (gameApp.loadBase <= 0L || libClient.loadBase <= 0L) {
            return failure(RootHeadSpinResolveStatus.PROFILE_MISMATCH, "Module load base is invalid")
        }
        val codeAddress = runCatching {
            Math.addExact(gameApp.loadBase, profile.patchRva)
        }.getOrNull()?.takeIf { it > 0L }
            ?: return failure(RootHeadSpinResolveStatus.PROFILE_MISMATCH, "Code address overflowed")
        val patchEnd = runCatching {
            Math.addExact(profile.patchRva, Int.SIZE_BYTES.toLong())
        }.getOrNull()
        if (patchEnd == null || patchEnd > gameApp.mappedBytes) {
            return failure(
                RootHeadSpinResolveStatus.PROFILE_MISMATCH,
                "Code patch is outside the exact GameApp mapping",
            )
        }

        val resolved = linkedMapOf<RootHeadSpinAxis, Long>()
        for (axis in RootHeadSpinAxis.entries) {
            val recipe = profile.axisRecipes[axis]
                ?: return failure(RootHeadSpinResolveStatus.PROFILE_MISMATCH, "Axis recipe is missing")
            val result = runCatching {
                addressRecipeResolver.resolve(request.identity.pid, recipe)
            }.getOrElse { error ->
                return failure(
                    RootHeadSpinResolveStatus.ADDRESS_FAILED,
                    error.message ?: "Axis address resolver failed",
                )
            }
            if (result.status == RootAddressRecipeStatus.TARGET_CHANGED) {
                return RootHeadSpinTargetResolution(
                    status = RootHeadSpinResolveStatus.TARGET_CHANGED,
                    processStartTimeTicks = result.processStartTimeTicks,
                    message = result.message,
                )
            }
            if (!result.isSuccess || result.address == null) {
                return failure(
                    RootHeadSpinResolveStatus.ADDRESS_FAILED,
                    result.message.ifBlank { "Axis ${axis.name} address resolution failed" },
                )
            }
            if (result.processStartTimeTicks != request.identity.startTimeTicks) {
                return RootHeadSpinTargetResolution(
                    status = RootHeadSpinResolveStatus.TARGET_CHANGED,
                    processStartTimeTicks = result.processStartTimeTicks,
                    message = "Target start time changed while resolving axis ${axis.name}",
                )
            }
            resolved[axis] = result.address
        }

        val addressA = requireNotNull(resolved[RootHeadSpinAxis.A])
        val addressB = requireNotNull(resolved[RootHeadSpinAxis.B])
        if (addressA == addressB) {
            return failure(RootHeadSpinResolveStatus.ADDRESS_FAILED, "Axis recipes resolved the same address")
        }
        return RootHeadSpinTargetResolution(
            status = RootHeadSpinResolveStatus.RESOLVED,
            target = RootHeadSpinTarget(
                identity = request.identity,
                gameAppSha256 = profile.gameAppSha256,
                libClientSha256 = profile.libClientSha256,
                gameAppLoadBase = gameApp.loadBase,
                codeAddress = codeAddress,
                axisAddressA = addressA,
                axisAddressB = addressB,
            ),
            processStartTimeTicks = request.identity.startTimeTicks,
            message = "Exact-SHA head-spin target resolved",
        )
    }

    private sealed interface ExactModuleLookup {
        data class Found(val module: RootNativeModuleIdentity) : ExactModuleLookup

        data class Failed(val resolution: RootHeadSpinTargetResolution) : ExactModuleLookup
    }

    private fun findExactModule(
        modules: List<RootNativeModuleIdentity>,
        moduleName: String,
        expectedSha256: String,
    ): ExactModuleLookup {
        val candidates = modules.filter { module ->
            module.name == moduleName || module.path.substringAfterLast('/') == moduleName
        }
        if (candidates.isEmpty()) {
            return ExactModuleLookup.Failed(
                failure(
                    RootHeadSpinResolveStatus.MODULE_NOT_FOUND,
                    "Required module is missing: $moduleName",
                ),
            )
        }
        if (candidates.size != 1) {
            return ExactModuleLookup.Failed(
                failure(
                    RootHeadSpinResolveStatus.MODULE_AMBIGUOUS,
                    "Required module is ambiguous: $moduleName",
                ),
            )
        }
        val candidate = candidates.single()
        val actualSha = candidate.sha256?.trim()?.lowercase()
        if (actualSha == null) {
            return ExactModuleLookup.Failed(
                failure(
                    RootHeadSpinResolveStatus.FINGERPRINT_UNAVAILABLE,
                    "Module fingerprint is unavailable: $moduleName",
                ),
            )
        }
        if (actualSha != expectedSha256.lowercase()) {
            return ExactModuleLookup.Failed(
                failure(
                    RootHeadSpinResolveStatus.FINGERPRINT_MISMATCH,
                    "Module fingerprint does not match: $moduleName",
                ),
            )
        }
        return ExactModuleLookup.Found(candidate)
    }

    private fun failure(
        status: RootHeadSpinResolveStatus,
        message: String,
    ): RootHeadSpinTargetResolution = RootHeadSpinTargetResolution(status = status, message = message.take(256))
}

internal data class RootHeadSpinPreflightResult(
    val status: RootHeadSpinBackendStatus,
    val processStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val codeWord: Int? = null,
    val axisBitsA: Int? = null,
    val axisBitsB: Int? = null,
    val message: String = "",
)

internal data class RootHeadSpinCodeWriteResult(
    val status: RootHeadSpinBackendStatus,
    val processStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val beforeWord: Int? = null,
    val afterWord: Int? = null,
    val message: String = "",
)

internal data class RootHeadSpinAxisWriteResult(
    val status: RootHeadSpinBackendStatus,
    val processStartTimeTicks: String = "",
    val mapsFingerprint: String = "",
    val appliedA: Boolean = false,
    val appliedB: Boolean = false,
    val beforeBitsA: Int? = null,
    val afterBitsA: Int? = null,
    val beforeBitsB: Int? = null,
    val afterBitsB: Int? = null,
    val message: String = "",
) {
    val appliedAny: Boolean
        get() = appliedA || appliedB
}

internal interface RootHeadSpinBackend {
    /** All responses must echo the target start time and maps fingerprint. */
    fun readPreflight(target: RootHeadSpinTarget): RootHeadSpinPreflightResult

    fun compareExchangeCodeWord(
        target: RootHeadSpinTarget,
        expectedWord: Int,
        desiredWord: Int,
    ): RootHeadSpinCodeWriteResult

    /** Pair writes must enforce both identity fields before touching memory. */
    fun compareExchangeAxisPair(
        target: RootHeadSpinTarget,
        expectedBitsA: Int,
        desiredBitsA: Int,
        expectedBitsB: Int,
        desiredBitsB: Int,
    ): RootHeadSpinAxisWriteResult
}

internal object NoRootHeadSpinBackend : RootHeadSpinBackend {
    override fun readPreflight(target: RootHeadSpinTarget): RootHeadSpinPreflightResult =
        RootHeadSpinPreflightResult(
            status = RootHeadSpinBackendStatus.UNAVAILABLE,
            processStartTimeTicks = target.identity.startTimeTicks,
            mapsFingerprint = target.identity.mapsFingerprint,
            message = "Head-spin backend is unavailable",
        )

    override fun compareExchangeCodeWord(
        target: RootHeadSpinTarget,
        expectedWord: Int,
        desiredWord: Int,
    ): RootHeadSpinCodeWriteResult = RootHeadSpinCodeWriteResult(
        status = RootHeadSpinBackendStatus.UNAVAILABLE,
        processStartTimeTicks = target.identity.startTimeTicks,
        mapsFingerprint = target.identity.mapsFingerprint,
        message = "Head-spin backend is unavailable",
    )

    override fun compareExchangeAxisPair(
        target: RootHeadSpinTarget,
        expectedBitsA: Int,
        desiredBitsA: Int,
        expectedBitsB: Int,
        desiredBitsB: Int,
    ): RootHeadSpinAxisWriteResult = RootHeadSpinAxisWriteResult(
        status = RootHeadSpinBackendStatus.UNAVAILABLE,
        processStartTimeTicks = target.identity.startTimeTicks,
        mapsFingerprint = target.identity.mapsFingerprint,
        message = "Head-spin backend is unavailable",
    )
}

internal fun interface RootHeadSpinSleeper {
    fun sleepMicros(micros: Long)
}

internal object DefaultRootHeadSpinSleeper : RootHeadSpinSleeper {
    override fun sleepMicros(micros: Long) {
        if (micros <= 0L) {
            Thread.yield()
            return
        }
        val nanos = TimeUnit.MICROSECONDS.toNanos(micros)
        java.util.concurrent.locks.LockSupport.parkNanos(nanos)
    }
}

internal fun interface RootHeadSpinWorkerFactory {
    fun create(name: String, task: Runnable): Thread
}

internal object DefaultRootHeadSpinWorkerFactory : RootHeadSpinWorkerFactory {
    override fun create(name: String, task: Runnable): Thread =
        Thread(task, name).apply { isDaemon = true }
}

/**
 * Single-worker supervisor. The class is deliberately independent of Android
 * and can be tested with fake target resolvers/backends.
 */
internal class RootHeadSpinSupervisor(
    private val targetResolver: RootHeadSpinTargetResolver,
    private val backend: RootHeadSpinBackend,
    private val profile: RootHeadSpinProfile = RootHeadSpinProfileCatalog.profile,
    private val sleeper: RootHeadSpinSleeper = DefaultRootHeadSpinSleeper,
    private val workerFactory: RootHeadSpinWorkerFactory = DefaultRootHeadSpinWorkerFactory,
    private val stopJoinMillis: Long = DEFAULT_STOP_JOIN_MILLIS,
) {
    /** Ownership of the code word after a guarded write attempt. */
    private enum class CodePatchOwnership {
        NOT_APPLIED,
        APPLIED,
        UNCERTAIN,
    }

    private val commandLock = Any()
    private val stateLock = Any()
    private val writeGate = ReentrantLock()
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null
    private var active: ActiveContext? = null
    private var state = RootHeadSpinState(
        profileId = profile.profileId,
        intervalInput = profile.defaultIntervalInput,
        delayMicros = requireNotNull(profile.encodeDelayMicros(profile.defaultIntervalInput)),
    )

    private data class ActiveContext(
        val target: RootHeadSpinTarget,
        val snapshot: RootHeadSpinAxisSnapshot,
        val generation: Long,
        var angle: Float,
        var delayMicros: Long,
        var codePatchOwnership: CodePatchOwnership,
    )

    private data class DisablePlan(
        val worker: Thread?,
        val noOp: Boolean,
    )

    fun stateSnapshot(): RootHeadSpinState = synchronized(stateLock) { state }

    /** Updates the delay input without invoking the recovered JNI setter. */
    fun setIntervalInput(intervalInput: Int): RootHeadSpinState = synchronized(commandLock) {
        val delay = profile.encodeDelayMicros(intervalInput)
        if (delay == null || delay <= 0L) {
            return@synchronized updateState(
                status = RootHeadSpinStatus.INVALID_RESPONSE,
                message = "Head-spin interval is outside the gated safety range",
            )
        }
        synchronized(stateLock) {
            active?.delayMicros = delay
            state = state.copy(
                intervalInput = intervalInput,
                delayMicros = delay,
                message = "Head-spin interval updated",
            )
            state
        }
    }

    /**
     * Starts one supervised worker. A repeated request for the same target and
     * interval is idempotent; a different target must be stopped first.
     */
    fun enable(request: RootHeadSpinStartRequest): RootHeadSpinState = synchronized(commandLock) {
        val existingWorker = worker
        val existing = active
        if (existingWorker?.isAlive == true && existing != null) {
            val sameRequest = existing.target.identity == request.identity &&
                stateSnapshot().intervalInput == request.intervalInput &&
                stateSnapshot().requestedEnabled
            return@synchronized if (sameRequest) {
                updateState(message = "Head-spin worker already running")
            } else {
                updateState(
                    status = RootHeadSpinStatus.PREFLIGHT,
                    message = "Head-spin is already active for another target; stop it first",
                )
            }
        }
        if (existing != null && stateSnapshot().applied) {
            return@synchronized updateState(
                status = RootHeadSpinStatus.ROLLBACK_FAILED,
                requestedEnabled = false,
                workerRunning = false,
                message = "Head-spin rollback is pending before restart",
            )
        }

        val delay = profile.encodeDelayMicros(request.intervalInput)
        if (delay == null || delay <= 0L) {
            return@synchronized updateState(
                status = RootHeadSpinStatus.INVALID_RESPONSE,
                requestedEnabled = false,
                workerRunning = false,
                message = "Head-spin interval is outside the gated safety range",
            )
        }

        synchronized(stateLock) {
            state = state.copy(
                status = RootHeadSpinStatus.PREFLIGHT,
                requestedEnabled = true,
                workerRunning = false,
                applied = false,
                intervalInput = request.intervalInput,
                delayMicros = delay,
                targetPid = request.identity.pid,
                targetStartTimeTicks = request.identity.startTimeTicks,
                mapsFingerprint = request.identity.mapsFingerprint,
                snapshotReady = false,
                message = "Resolving exact-SHA head-spin target",
            )
        }

        val resolution = runCatching {
            targetResolver.resolve(request, profile)
        }.getOrElse { error ->
            return@synchronized failBeforeApply(
                RootHeadSpinStatus.PROFILE_MISMATCH,
                error.message ?: "Head-spin target resolver failed",
            )
        }
        val target = resolution.target
        if (!resolution.isSuccess || target == null) {
            val status = resolution.status.toPublicStatus()
            return@synchronized failBeforeApply(status, resolution.message)
        }

        val preflight = runCatching { backend.readPreflight(target) }.getOrElse { error ->
            return@synchronized failBeforeApply(
                RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                error.message ?: "Head-spin preflight failed",
            )
        }
        if (!preflight.matches(target) || preflight.status != RootHeadSpinBackendStatus.OK) {
            return@synchronized failBeforeApply(
                preflight.status.toPublicStatus(),
                preflight.message.ifBlank { "Head-spin preflight rejected the target" },
            )
        }
        val codeWord = preflight.codeWord
        val bitsA = preflight.axisBitsA
        val bitsB = preflight.axisBitsB
        if (codeWord == null || bitsA == null || bitsB == null) {
            return@synchronized failBeforeApply(
                RootHeadSpinStatus.INVALID_RESPONSE,
                "Head-spin preflight omitted code or axis values",
            )
        }
        if (codeWord != profile.restoreWord) {
            return@synchronized failBeforeApply(
                RootHeadSpinStatus.PROFILE_MISMATCH,
                "GameApp code word is not the verified restore word",
            )
        }
        val angle = Float.fromBits(bitsA)
        if (!angle.isFinite() || !Float.fromBits(bitsB).isFinite()) {
            return@synchronized failBeforeApply(
                RootHeadSpinStatus.PREFLIGHT,
                "Head-spin axis snapshot contains a non-finite float",
            )
        }

        val generation = stateSnapshot().generation + 1L
        val context = ActiveContext(
            target = target,
            snapshot = RootHeadSpinAxisSnapshot(
                addressA = target.axisAddressA,
                addressB = target.axisAddressB,
                originalBitsA = bitsA,
                originalBitsB = bitsB,
            ),
            generation = generation,
            angle = normalizeAngle(angle),
            delayMicros = delay,
            codePatchOwnership = CodePatchOwnership.NOT_APPLIED,
        )
        active = context
        synchronized(stateLock) {
            state = state.copy(
                generation = generation,
                snapshotReady = true,
                message = "Head-spin snapshot captured; patching GameApp code",
            )
        }

        // From this point on the outcome is unknown until the backend returns
        // an identity-bound before/after pair. Exceptions must retain context
        // so disable() can make a guarded restore attempt.
        context.codePatchOwnership = CodePatchOwnership.UNCERTAIN
        val codeWrite = runCatching {
            backend.compareExchangeCodeWord(target, profile.restoreWord, profile.patchedWord)
        }.getOrElse { error ->
            return@synchronized failWithContext(
                RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                error.message ?: "Head-spin code patch failed",
            )
        }
        context.codePatchOwnership = when {
            codeWrite.afterWord == profile.patchedWord -> CodePatchOwnership.APPLIED
            codeWrite.matches(target) &&
                codeWrite.beforeWord == profile.restoreWord &&
                codeWrite.afterWord == profile.restoreWord -> CodePatchOwnership.NOT_APPLIED
            else -> CodePatchOwnership.UNCERTAIN
        }
        if (!codeWrite.matches(target) ||
            codeWrite.status !in setOf(
                RootHeadSpinBackendStatus.OK,
                RootHeadSpinBackendStatus.ALREADY_APPLIED,
            ) || codeWrite.afterWord != profile.patchedWord
        ) {
            val status = codeWrite.status.toPublicStatus()
            val codePatchDefinitelyNotApplied =
                context.codePatchOwnership == CodePatchOwnership.NOT_APPLIED
            return@synchronized failWithContext(
                status,
                codeWrite.message.ifBlank { "Head-spin code patch was not verified" },
                codePatchDefinitelyNotApplied = codePatchDefinitelyNotApplied,
            )
        }

        val thread = runCatching {
            workerFactory.create(
                "minix-head-spin-$generation",
                Runnable { runWorker(generation) },
            )
        }.getOrElse { error ->
            return@synchronized failWithContext(
                RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                error.message ?: "Head-spin worker creation failed",
            )
        }
        stopRequested.set(false)
        worker = thread
        synchronized(stateLock) {
            state = state.copy(
                status = RootHeadSpinStatus.STARTING,
                requestedEnabled = true,
                workerRunning = true,
                applied = true,
                message = "Head-spin worker starting",
            )
        }
        return@synchronized try {
            thread.start()
            stateSnapshot()
        } catch (error: Throwable) {
            worker = null
            stopRequested.set(true)
            failWithContext(
                RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                error.message ?: "Head-spin worker start failed",
            )
        }
    }

    /** Requests stop, joins the worker, then performs guarded rollback. */
    fun disable(): RootHeadSpinState {
        val plan = synchronized(commandLock) {
            stopRequested.set(true)
            synchronized(stateLock) {
                if (active == null && worker == null && !state.applied) {
                    state = state.copy(
                        status = RootHeadSpinStatus.STOPPED,
                        requestedEnabled = false,
                        workerRunning = false,
                        message = "Head-spin stopped",
                    )
                    // A no-op disable must not leave a stale stop request that
                    // would affect the next enable before its worker starts.
                    stopRequested.set(false)
                    DisablePlan(worker = null, noOp = true)
                } else {
                    state = state.copy(
                        status = RootHeadSpinStatus.STOPPING,
                        requestedEnabled = false,
                        message = "Stopping head-spin worker",
                    )
                    DisablePlan(worker = worker, noOp = false)
                }
            }
        }

        if (plan.noOp) return stateSnapshot()

        plan.worker?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.interrupt()
                runCatching { thread.join(stopJoinMillis) }
            }
        }

        return synchronized(commandLock) {
            val stillAlive = worker?.isAlive == true
            if (stillAlive) {
                return@synchronized updateState(
                    status = RootHeadSpinStatus.STOPPING,
                    requestedEnabled = false,
                    workerRunning = true,
                    message = "Head-spin worker did not join before timeout",
                )
            }
            worker = null
            rollbackLocked()
        }
    }

    fun stopAndRollback(): RootHeadSpinState = disable()

    private fun runWorker(generation: Long) {
        var failure: RootHeadSpinStatus? = null
        var failureMessage = ""
        try {
            while (!stopRequested.get()) {
                val cycle = runOneCycle(generation)
                if (!cycle) break
                val delay = synchronized(stateLock) {
                    active?.takeIf { it.generation == generation }?.delayMicros
                } ?: break
                try {
                    sleeper.sleepMicros(delay)
                } catch (_: InterruptedException) {
                    if (!stopRequested.get()) {
                        failure = RootHeadSpinStatus.BACKEND_UNAVAILABLE
                        failureMessage = "Head-spin sleeper was interrupted"
                    }
                    break
                }
            }
        } catch (error: Throwable) {
            if (!stopRequested.get()) {
                failure = RootHeadSpinStatus.BACKEND_UNAVAILABLE
                failureMessage = error.message ?: "Head-spin worker failed"
            }
            stopRequested.set(true)
        } finally {
            synchronized(stateLock) {
                if (failure != null) {
                    state = state.copy(
                        status = failure ?: RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                        requestedEnabled = false,
                        workerRunning = false,
                        applied = true,
                        message = failureMessage.take(256),
                    )
                } else if (state.generation == generation) {
                    state = state.copy(workerRunning = false)
                }
            }
            synchronized(workerLock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private val workerLock = Any()

    private fun runOneCycle(generation: Long): Boolean {
        if (stopRequested.get()) return false
        val context = synchronized(stateLock) {
            active?.takeIf { it.generation == generation }
        } ?: return false

        val nextAngle = nextAngle(context.angle)
        val desiredBits = nextAngle.toRawBits()
        val result = try {
            writeGate.lockInterruptibly()
            try {
                if (stopRequested.get()) return false
                backend.compareExchangeAxisPair(
                    target = context.target,
                    expectedBitsA = context.snapshot.lastVerifiedBitsA,
                    desiredBitsA = desiredBits,
                    expectedBitsB = context.snapshot.lastVerifiedBitsB,
                    desiredBitsB = desiredBits,
                )
            } finally {
                writeGate.unlock()
            }
        } catch (_: InterruptedException) {
            if (!stopRequested.get()) {
                synchronized(stateLock) {
                    state = state.copy(
                        status = RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                        requestedEnabled = false,
                        workerRunning = false,
                        applied = true,
                        message = "Head-spin write was interrupted",
                    )
                }
                stopRequested.set(true)
            }
            return false
        } catch (error: Throwable) {
            synchronized(stateLock) {
                state = state.copy(
                    status = RootHeadSpinStatus.BACKEND_UNAVAILABLE,
                    requestedEnabled = false,
                    workerRunning = false,
                    applied = true,
                    message = (error.message ?: "Head-spin axis write failed").take(256),
                )
            }
            stopRequested.set(true)
            return false
        }

        if (result.appliedA) {
            result.afterBitsA?.let { context.snapshot.lastVerifiedBitsA = it }
        }
        if (result.appliedB) {
            result.afterBitsB?.let { context.snapshot.lastVerifiedBitsB = it }
        }
        if (!result.matches(context.target) ||
            result.status !in setOf(
                RootHeadSpinBackendStatus.OK,
                RootHeadSpinBackendStatus.ALREADY_APPLIED,
            ) ||
            result.afterBitsA != desiredBits ||
            result.afterBitsB != desiredBits
        ) {
            synchronized(stateLock) {
                state = state.copy(
                    status = result.status.toPublicStatus(),
                    requestedEnabled = false,
                    workerRunning = false,
                    applied = true,
                    message = result.message.ifBlank { "Head-spin axis write was not verified" },
                )
            }
            stopRequested.set(true)
            return false
        }

        context.angle = nextAngle
        synchronized(stateLock) {
            state = state.copy(
                status = RootHeadSpinStatus.RUNNING,
                requestedEnabled = true,
                workerRunning = true,
                applied = true,
                iterationCount = state.iterationCount + 1L,
                successfulPairWriteCount = state.successfulPairWriteCount + 1L,
                message = "Head-spin axis pair verified",
            )
        }
        return true
    }

    private fun rollbackLocked(): RootHeadSpinState {
        val context = active ?: return updateState(
            status = RootHeadSpinStatus.STOPPED,
            requestedEnabled = false,
            workerRunning = false,
            applied = false,
            snapshotReady = false,
            message = "Head-spin stopped",
        )

        var axisFailure: RootHeadSpinState? = null
        val snapshot = context.snapshot
        if (snapshot.lastVerifiedBitsA != snapshot.originalBitsA ||
            snapshot.lastVerifiedBitsB != snapshot.originalBitsB
        ) {
            val axisResult = runCatching {
                backend.compareExchangeAxisPair(
                    target = context.target,
                    expectedBitsA = snapshot.lastVerifiedBitsA,
                    desiredBitsA = snapshot.originalBitsA,
                    expectedBitsB = snapshot.lastVerifiedBitsB,
                    desiredBitsB = snapshot.originalBitsB,
                )
            }.getOrElse { error ->
                RootHeadSpinAxisWriteResult(
                    status = RootHeadSpinBackendStatus.UNAVAILABLE,
                    processStartTimeTicks = context.target.identity.startTimeTicks,
                    mapsFingerprint = context.target.identity.mapsFingerprint,
                    message = error.message ?: "Head-spin axis rollback failed",
                )
            }
            if (axisResult.appliedA) axisResult.afterBitsA?.let { snapshot.lastVerifiedBitsA = it }
            if (axisResult.appliedB) axisResult.afterBitsB?.let { snapshot.lastVerifiedBitsB = it }
            if (!axisResult.matches(context.target) ||
                axisResult.status !in setOf(
                    RootHeadSpinBackendStatus.OK,
                    RootHeadSpinBackendStatus.ALREADY_APPLIED,
                ) ||
                axisResult.afterBitsA != snapshot.originalBitsA ||
                axisResult.afterBitsB != snapshot.originalBitsB
            ) {
                axisFailure = updateState(
                    status = axisResult.status.toPublicStatus(rollback = true),
                    requestedEnabled = false,
                    workerRunning = false,
                    applied = true,
                    message = axisResult.message.ifBlank { "Head-spin axis rollback was not verified" },
                )
            }
        }
        if (axisFailure != null) return axisFailure

        if (context.codePatchOwnership != CodePatchOwnership.NOT_APPLIED) {
            val codeResult = runCatching {
                backend.compareExchangeCodeWord(
                    target = context.target,
                    expectedWord = profile.patchedWord,
                    desiredWord = profile.restoreWord,
                )
            }.getOrElse { error ->
                RootHeadSpinCodeWriteResult(
                    status = RootHeadSpinBackendStatus.UNAVAILABLE,
                    processStartTimeTicks = context.target.identity.startTimeTicks,
                    mapsFingerprint = context.target.identity.mapsFingerprint,
                    message = error.message ?: "Head-spin code rollback failed",
                )
            }
            val codeAlreadyRestored = codeResult.matches(context.target) &&
                codeResult.status in setOf(
                    // The pure Kotlin fake uses EXPECTED_VALUE_MISMATCH;
                    // the native guarded batch reports the same compare-
                    // exchange outcome as PROFILE_MISMATCH.
                    RootHeadSpinBackendStatus.EXPECTED_VALUE_MISMATCH,
                    RootHeadSpinBackendStatus.PROFILE_MISMATCH,
                ) &&
                codeResult.afterWord == profile.restoreWord
            if (!codeResult.matches(context.target) ||
                (!codeAlreadyRestored && codeResult.status !in setOf(
                    RootHeadSpinBackendStatus.OK,
                    RootHeadSpinBackendStatus.ALREADY_APPLIED,
                )) ||
                codeResult.afterWord != profile.restoreWord
            ) {
                return updateState(
                    status = codeResult.status.toPublicStatus(rollback = true),
                    requestedEnabled = false,
                    workerRunning = false,
                    applied = true,
                    message = codeResult.message.ifBlank { "Head-spin code rollback was not verified" },
                )
            }
            context.codePatchOwnership = CodePatchOwnership.NOT_APPLIED
        }

        active = null
        stopRequested.set(false)
        return updateState(
            status = RootHeadSpinStatus.STOPPED,
            requestedEnabled = false,
            workerRunning = false,
            applied = false,
            snapshotReady = false,
            message = "Head-spin stopped and rollback verified",
        )
    }

    private fun failBeforeApply(status: RootHeadSpinStatus, message: String): RootHeadSpinState {
        active = null
        stopRequested.set(false)
        return updateState(
            status = status,
            requestedEnabled = false,
            workerRunning = false,
            applied = false,
            snapshotReady = false,
            message = message.ifBlank { status.name }.take(256),
        )
    }

    private fun failWithContext(
        status: RootHeadSpinStatus,
        message: String,
        codePatchDefinitelyNotApplied: Boolean = false,
    ): RootHeadSpinState {
        stopRequested.set(true)
        val context = active
        if (context == null) {
            return failBeforeApply(status, message)
        }
        if (codePatchDefinitelyNotApplied &&
            context.codePatchOwnership == CodePatchOwnership.NOT_APPLIED
        ) {
            // The guarded compare-exchange reported the verified original word
            // on both sides, so there is no native state to roll back. Drop the
            // provisional snapshot instead of forcing a pointless disable.
            active = null
            stopRequested.set(false)
            return updateState(
                status = status,
                requestedEnabled = false,
                workerRunning = false,
                applied = false,
                snapshotReady = false,
                message = message.ifBlank { status.name }.take(256),
            )
        }
        // The caller still owns the context and can invoke disable() to retry
        // guarded rollback. No new worker is allowed while it is retained.
        return updateState(
            status = status,
            requestedEnabled = false,
            workerRunning = false,
            applied = context.codePatchOwnership != CodePatchOwnership.NOT_APPLIED,
            message = message.ifBlank { status.name }.take(256),
        )
    }

    private fun updateState(
        status: RootHeadSpinStatus? = null,
        requestedEnabled: Boolean? = null,
        workerRunning: Boolean? = null,
        applied: Boolean? = null,
        snapshotReady: Boolean? = null,
        message: String? = null,
    ): RootHeadSpinState = synchronized(stateLock) {
        state = state.copy(
            status = status ?: state.status,
            requestedEnabled = requestedEnabled ?: state.requestedEnabled,
            workerRunning = workerRunning ?: state.workerRunning,
            applied = applied ?: state.applied,
            snapshotReady = snapshotReady ?: state.snapshotReady,
            message = message?.take(256) ?: state.message,
        )
        state
    }

    private fun nextAngle(current: Float): Float {
        val candidate = current + profile.angleStep
        val wrapped = candidate % profile.angleModulus
        return if (wrapped >= 0f) wrapped else wrapped + profile.angleModulus
    }

    private fun normalizeAngle(value: Float): Float {
        val wrapped = value % profile.angleModulus
        return if (wrapped >= 0f) wrapped else wrapped + profile.angleModulus
    }

    private fun RootHeadSpinPreflightResult.matches(target: RootHeadSpinTarget): Boolean =
        processStartTimeTicks == target.identity.startTimeTicks &&
            mapsFingerprint == target.identity.mapsFingerprint

    private fun RootHeadSpinCodeWriteResult.matches(target: RootHeadSpinTarget): Boolean =
        processStartTimeTicks == target.identity.startTimeTicks &&
            mapsFingerprint == target.identity.mapsFingerprint

    private fun RootHeadSpinAxisWriteResult.matches(target: RootHeadSpinTarget): Boolean =
        processStartTimeTicks == target.identity.startTimeTicks &&
            mapsFingerprint == target.identity.mapsFingerprint

    private fun RootHeadSpinResolveStatus.toPublicStatus(): RootHeadSpinStatus = when (this) {
        RootHeadSpinResolveStatus.RESOLVED -> RootHeadSpinStatus.PREFLIGHT
        RootHeadSpinResolveStatus.INVALID_REQUEST -> RootHeadSpinStatus.INVALID_RESPONSE
        RootHeadSpinResolveStatus.MODULE_NOT_FOUND,
        RootHeadSpinResolveStatus.MODULE_AMBIGUOUS,
        RootHeadSpinResolveStatus.FINGERPRINT_UNAVAILABLE,
        RootHeadSpinResolveStatus.FINGERPRINT_MISMATCH,
        RootHeadSpinResolveStatus.PROFILE_MISMATCH,
        -> RootHeadSpinStatus.PROFILE_MISMATCH
        RootHeadSpinResolveStatus.ADDRESS_FAILED -> RootHeadSpinStatus.READ_FAILED
        RootHeadSpinResolveStatus.TARGET_CHANGED -> RootHeadSpinStatus.TARGET_CHANGED
    }

    private fun RootHeadSpinBackendStatus.toPublicStatus(rollback: Boolean = false): RootHeadSpinStatus = when (this) {
        RootHeadSpinBackendStatus.OK,
        RootHeadSpinBackendStatus.ALREADY_APPLIED,
        -> if (rollback) RootHeadSpinStatus.STOPPED else RootHeadSpinStatus.RUNNING
        RootHeadSpinBackendStatus.TARGET_CHANGED -> RootHeadSpinStatus.TARGET_CHANGED
        RootHeadSpinBackendStatus.PROFILE_MISMATCH -> RootHeadSpinStatus.PROFILE_MISMATCH
        RootHeadSpinBackendStatus.READ_FAILED -> RootHeadSpinStatus.READ_FAILED
        RootHeadSpinBackendStatus.EXPECTED_VALUE_MISMATCH -> RootHeadSpinStatus.VERIFY_FAILED
        RootHeadSpinBackendStatus.WRITE_FAILED -> RootHeadSpinStatus.WRITE_FAILED
        RootHeadSpinBackendStatus.VERIFY_FAILED -> RootHeadSpinStatus.VERIFY_FAILED
        RootHeadSpinBackendStatus.UNAVAILABLE -> RootHeadSpinStatus.BACKEND_UNAVAILABLE
        RootHeadSpinBackendStatus.INVALID_RESPONSE -> RootHeadSpinStatus.INVALID_RESPONSE
    }

    private companion object {
        const val DEFAULT_STOP_JOIN_MILLIS = 1_500L
    }
}
