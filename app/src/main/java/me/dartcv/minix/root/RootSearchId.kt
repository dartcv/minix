package me.dartcv.minix.root

internal enum class RootModuleAnchorKind {
    MODULE_SPEC_RESULT,
}

internal data class RootSearchIdRecipe(
    val rootOffset: Long,
    val chainOffsets: List<Long>,
    val slotCount: Int,
    val slotStride: Long,
    val markerOffset: Long,
    val markerBits: Long,
    val idOffset: Long,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val isStructurallyValid: Boolean
        get() = rootOffset in 0L..MAX_OFFSET &&
            chainOffsets.size in 1..MAX_CHAIN_STEPS &&
            chainOffsets.all { it in 0L..MAX_OFFSET } &&
            slotCount in 1..MAX_SLOT_COUNT &&
            slotStride in 1L..MAX_OFFSET &&
            markerOffset in 0L..MAX_OFFSET &&
            markerBits in 0L..UINT32_MAX &&
            idOffset in 0L..MAX_OFFSET &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()

    private companion object {
        const val MAX_CHAIN_STEPS = 16
        const val MAX_SLOT_COUNT = 256
        const val MAX_OFFSET = 0xffff_ffffL
        const val UINT32_MAX = 0xffff_ffffL
    }
}

internal data class RootSearchIdProfile(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val moduleName: String,
    val moduleSpec: String,
    val expectedSha256: String?,
    val anchorKind: RootModuleAnchorKind,
    val recipe: RootSearchIdRecipe,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val normalizedSha256: String?
        get() = expectedSha256
            ?.trim()
            ?.lowercase()
            ?.takeIf(::isSearchIdSha256)

    val isReadyForScan: Boolean
        get() = profileId.isNotBlank() &&
            schemaVersion > 0 &&
            targetVersion.isNotBlank() &&
            isSearchIdModuleName(moduleName.trim()) &&
            isSearchIdModuleSpec(moduleSpec, moduleName.trim()) &&
            normalizedSha256 != null &&
            recipe.isStructurallyValid &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()
}

internal enum class RootSearchIdAnchorStatus {
    RESOLVED,
    INVALID_PID,
    INVALID_PROFILE,
    UNAVAILABLE,
    MODULE_NOT_FOUND,
    MODULE_AMBIGUOUS,
    FINGERPRINT_UNAVAILABLE,
    FINGERPRINT_MISMATCH,
    ANCHOR_NOT_FOUND,
    TARGET_CHANGED,
}

internal data class RootSearchIdAnchor(
    val profileId: String,
    val moduleName: String,
    val moduleSpec: String,
    val moduleSha256: String,
    val anchorKind: RootModuleAnchorKind,
    val baseAddress: Long,
    val processStartTimeTicks: String,
)

internal data class RootSearchIdAnchorResult(
    val status: RootSearchIdAnchorStatus,
    val anchor: RootSearchIdAnchor? = null,
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootSearchIdAnchorStatus.RESOLVED && anchor != null
}

internal fun interface RootSearchIdAnchorResolver {
    fun resolve(pid: Int, profile: RootSearchIdProfile): RootSearchIdAnchorResult

    fun invalidateCache() = Unit
}

internal object NoRootSearchIdAnchorResolver : RootSearchIdAnchorResolver {
    override fun resolve(pid: Int, profile: RootSearchIdProfile): RootSearchIdAnchorResult =
        RootSearchIdAnchorResult(
            status = RootSearchIdAnchorStatus.UNAVAILABLE,
            message = "Module-spec anchor resolver is unavailable",
        )
}

enum class RootSearchIdStatus {
    MATCH,
    NOT_FOUND,
    INVALID,

    ;

    companion object {
        fun fromWireValue(value: String): RootSearchIdStatus? =
            entries.firstOrNull { it.name == value }
    }
}

enum class RootSearchIdInvalidReason {
    INVALID_PID,
    INVALID_PROFILE,
    ANCHOR_UNAVAILABLE,
    MODULE_NOT_FOUND,
    MODULE_AMBIGUOUS,
    FINGERPRINT_UNAVAILABLE,
    FINGERPRINT_MISMATCH,
    ANCHOR_NOT_FOUND,
    ADDRESS_OVERFLOW,
    RESOLVER_FAILED,
    READ_FAILED,
    TARGET_CHANGED,
    INVALID_RESPONSE,

    ;

    companion object {
        fun fromWireValue(value: String): RootSearchIdInvalidReason? =
            entries.firstOrNull { it.name == value }
    }
}

data class RootSearchIdResult(
    val status: RootSearchIdStatus,
    val requestedId: Long,
    val slotIndex: Int? = null,
    val processStartTimeTicks: String = "",
    val invalidReason: RootSearchIdInvalidReason? = null,
    val message: String = "",
) {
    val isMatch: Boolean
        get() = status == RootSearchIdStatus.MATCH && slotIndex != null
}

internal class RootSearchIdScanner(
    private val anchorResolver: RootSearchIdAnchorResolver,
    private val seedResolver: RootSeedAddressResolver,
    private val scalarReader: TargetScalarReader,
) {
    fun invalidateCache() = anchorResolver.invalidateCache()

    fun scan(
        pid: Int,
        expectedStartTimeTicks: String,
        profile: RootSearchIdProfile,
        requestedId: Long,
    ): RootSearchIdResult {
        if (pid <= 0) {
            return invalid(requestedId, RootSearchIdInvalidReason.INVALID_PID, "PID must be positive")
        }
        if (expectedStartTimeTicks.isBlank()) {
            return invalid(
                requestedId,
                RootSearchIdInvalidReason.TARGET_CHANGED,
                "Pinned process identity is missing",
            )
        }
        if (!profile.isReadyForScan) {
            return invalid(
                requestedId,
                RootSearchIdInvalidReason.INVALID_PROFILE,
                "Search profile is incomplete",
            )
        }

        val anchorResult = anchorResolver.resolve(pid, profile)
        if (!anchorResult.isSuccess) {
            return invalid(
                requestedId = requestedId,
                reason = anchorResult.status.toInvalidReason(),
                message = anchorResult.message.ifBlank { anchorResult.status.name },
                processStartTimeTicks = anchorResult.processStartTimeTicks,
            )
        }
        val anchor = requireNotNull(anchorResult.anchor)
        if (
            anchorResult.processStartTimeTicks.isNotBlank() &&
            anchorResult.processStartTimeTicks != anchor.processStartTimeTicks
        ) {
            return invalid(
                requestedId,
                RootSearchIdInvalidReason.TARGET_CHANGED,
                "Target process identity changed during module-spec resolution",
                anchorResult.processStartTimeTicks,
            )
        }
        if (anchor.processStartTimeTicks != expectedStartTimeTicks) {
            return invalid(
                requestedId,
                RootSearchIdInvalidReason.TARGET_CHANGED,
                "Module-spec anchor does not belong to the pinned target process",
                anchor.processStartTimeTicks,
            )
        }
        val anchorValidation = validateAnchor(profile, anchor)
        if (anchorValidation != null) return anchorValidation.copy(requestedId = requestedId)

        val expectedStartTime = expectedStartTimeTicks
        val rootSeed = checkedAdd(anchor.baseAddress, profile.recipe.rootOffset)
            ?: return invalid(
                requestedId,
                RootSearchIdInvalidReason.ADDRESS_OVERFLOW,
                "Root module-spec seed overflowed",
                expectedStartTime,
            )
        val rootStep = resolveRequired(pid, rootSeed, expectedStartTime, requestedId)
        var tableAddress = when (rootStep) {
            is ScanStep.Success -> rootStep.value
            is ScanStep.Failure -> return rootStep.result
        }
        for (offset in profile.recipe.chainOffsets) {
            val seed = checkedAdd(tableAddress, offset)
                ?: return invalid(
                    requestedId,
                    RootSearchIdInvalidReason.ADDRESS_OVERFLOW,
                    "Search chain seed overflowed",
                    expectedStartTime,
                )
            val chainStep = resolveRequired(pid, seed, expectedStartTime, requestedId)
            tableAddress = when (chainStep) {
                is ScanStep.Success -> chainStep.value
                is ScanStep.Failure -> return chainStep.result
            }
        }

        repeat(profile.recipe.slotCount) { index ->
            val slotDelta = checkedMultiply(index.toLong(), profile.recipe.slotStride)
                ?: return invalid(
                    requestedId,
                    RootSearchIdInvalidReason.ADDRESS_OVERFLOW,
                    "Search slot stride overflowed",
                    expectedStartTime,
                )
            val slotSeed = checkedAdd(tableAddress, slotDelta)
                ?: return invalid(
                    requestedId,
                    RootSearchIdInvalidReason.ADDRESS_OVERFLOW,
                    "Search slot address overflowed",
                    expectedStartTime,
                )
            val candidateResult = resolveCandidate(pid, slotSeed, expectedStartTime, requestedId)
            val candidateAddress = when (candidateResult) {
                is ScanStep.Success -> candidateResult.value
                is ScanStep.Failure -> return candidateResult.result
            }
            if (candidateAddress == 0L) return@repeat

            val markerAddress = checkedAdd(candidateAddress, profile.recipe.markerOffset)
                ?: return invalid(
                    requestedId,
                    RootSearchIdInvalidReason.ADDRESS_OVERFLOW,
                    "Marker address overflowed",
                    expectedStartTime,
                )
            val markerStep = readInt32Bits(
                pid = pid,
                address = markerAddress,
                expectedStartTime = expectedStartTime,
                requestedId = requestedId,
            )
            val markerBits = when (markerStep) {
                is ScanStep.Success -> markerStep.value
                is ScanStep.Failure -> return markerStep.result
            }
            if ((markerBits and UINT32_MAX) != profile.recipe.markerBits) return@repeat

            val idAddress = checkedAdd(candidateAddress, profile.recipe.idOffset)
                ?: return invalid(
                    requestedId,
                    RootSearchIdInvalidReason.ADDRESS_OVERFLOW,
                    "Candidate ID address overflowed",
                    expectedStartTime,
                )
            val candidateIdStep = readInt32Bits(
                pid = pid,
                address = idAddress,
                expectedStartTime = expectedStartTime,
                requestedId = requestedId,
            )
            val candidateId = when (candidateIdStep) {
                is ScanStep.Success -> candidateIdStep.value.toInt().toLong()
                is ScanStep.Failure -> return candidateIdStep.result
            }
            if (candidateId == requestedId) {
                return RootSearchIdResult(
                    status = RootSearchIdStatus.MATCH,
                    requestedId = requestedId,
                    slotIndex = index,
                    processStartTimeTicks = expectedStartTime,
                )
            }
        }

        return RootSearchIdResult(
            status = RootSearchIdStatus.NOT_FOUND,
            requestedId = requestedId,
            processStartTimeTicks = expectedStartTime,
        )
    }

    private fun validateAnchor(
        profile: RootSearchIdProfile,
        anchor: RootSearchIdAnchor,
    ): RootSearchIdResult? {
        val expectedSha256 = requireNotNull(profile.normalizedSha256)
        val actualSha256 = anchor.moduleSha256.trim().lowercase().takeIf(::isSearchIdSha256)
        return when {
            anchor.profileId != profile.profileId ||
                anchor.moduleName != profile.moduleName.trim() ||
                anchor.moduleSpec != profile.moduleSpec ||
                anchor.anchorKind != profile.anchorKind -> invalid(
                requestedId = 0L,
                reason = RootSearchIdInvalidReason.INVALID_PROFILE,
                message = "Resolved module-spec anchor does not match the search profile",
                processStartTimeTicks = anchor.processStartTimeTicks,
            )
            actualSha256 == null -> invalid(
                requestedId = 0L,
                reason = RootSearchIdInvalidReason.FINGERPRINT_UNAVAILABLE,
                message = "Resolved module has no valid SHA-256 fingerprint",
                processStartTimeTicks = anchor.processStartTimeTicks,
            )
            actualSha256 != expectedSha256 -> invalid(
                requestedId = 0L,
                reason = RootSearchIdInvalidReason.FINGERPRINT_MISMATCH,
                message = "Resolved module fingerprint does not match",
                processStartTimeTicks = anchor.processStartTimeTicks,
            )
            anchor.baseAddress <= 0L -> invalid(
                requestedId = 0L,
                reason = RootSearchIdInvalidReason.ANCHOR_NOT_FOUND,
                message = "Resolved module-spec anchor is invalid",
                processStartTimeTicks = anchor.processStartTimeTicks,
            )
            anchor.processStartTimeTicks.isBlank() -> invalid(
                requestedId = 0L,
                reason = RootSearchIdInvalidReason.TARGET_CHANGED,
                message = "Resolved module-spec anchor omitted process identity",
            )
            else -> null
        }
    }

    private fun resolveRequired(
        pid: Int,
        seed: Long,
        expectedStartTime: String,
        requestedId: Long,
    ): ScanStep<Long> {
        val result = seedResolver.resolve(pid, seed)
        val value = result.resolvedAddress
        if (!result.isSuccess || value == null) {
            identityChangeFailure(
                result.processStartTimeTicks,
                expectedStartTime,
                requestedId,
            )?.let { return ScanStep.Failure(it) }
            return ScanStep.Failure(
                invalid(
                    requestedId,
                    RootSearchIdInvalidReason.RESOLVER_FAILED,
                    result.message.ifBlank { result.status.name },
                    expectedStartTime,
                ),
            )
        }
        identityFailure(result.processStartTimeTicks, expectedStartTime, requestedId)?.let {
            return ScanStep.Failure(it)
        }
        if (result.seed != seed || value !in 0L..RESOLVER_VALUE_MASK) {
            return ScanStep.Failure(
                invalid(
                    requestedId,
                    RootSearchIdInvalidReason.RESOLVER_FAILED,
                    "Resolver result violated the recovered H contract",
                    expectedStartTime,
                ),
            )
        }
        if (value == 0L) {
            return ScanStep.Failure(
                invalid(
                    requestedId,
                    RootSearchIdInvalidReason.RESOLVER_FAILED,
                    "Search root chain resolved to zero",
                    expectedStartTime,
                ),
            )
        }
        return ScanStep.Success(value)
    }

    private fun resolveCandidate(
        pid: Int,
        seed: Long,
        expectedStartTime: String,
        requestedId: Long,
    ): ScanStep<Long> {
        val result = seedResolver.resolve(pid, seed)
        val value = result.resolvedAddress
        if (!result.isSuccess || value == null) {
            identityChangeFailure(
                result.processStartTimeTicks,
                expectedStartTime,
                requestedId,
            )?.let { return ScanStep.Failure(it) }
            // The source H folds transport failures into zero. MINIX keeps the
            // failure typed so a partial scan cannot become a false NOT_FOUND.
            return ScanStep.Failure(
                invalid(
                    requestedId,
                    RootSearchIdInvalidReason.RESOLVER_FAILED,
                    result.message.ifBlank { result.status.name },
                    expectedStartTime,
                ),
            )
        }
        identityFailure(result.processStartTimeTicks, expectedStartTime, requestedId)?.let {
            return ScanStep.Failure(it)
        }
        if (result.seed != seed || value !in 0L..RESOLVER_VALUE_MASK) {
            return ScanStep.Failure(
                invalid(
                    requestedId,
                    RootSearchIdInvalidReason.RESOLVER_FAILED,
                    "Resolver result violated the recovered H contract",
                    expectedStartTime,
                ),
            )
        }
        return ScanStep.Success(value)
    }

    private fun readInt32Bits(
        pid: Int,
        address: Long,
        expectedStartTime: String,
        requestedId: Long,
    ): ScanStep<Long> {
        val result = scalarReader.readInt32(pid, address)
        val valueBits = result.valueBits
        if (!result.isSuccess || valueBits == null) {
            identityChangeFailure(
                result.processStartTimeTicks,
                expectedStartTime,
                requestedId,
            )?.let { return ScanStep.Failure(it) }
            return ScanStep.Failure(
                invalid(
                    requestedId,
                    RootSearchIdInvalidReason.READ_FAILED,
                    result.message.ifBlank { "Search scalar read failed" },
                    expectedStartTime,
                ),
            )
        }
        identityFailure(result.processStartTimeTicks, expectedStartTime, requestedId)?.let {
            return ScanStep.Failure(it)
        }
        return ScanStep.Success(valueBits)
    }

    private fun identityFailure(
        actualStartTime: String,
        expectedStartTime: String,
        requestedId: Long,
    ): RootSearchIdResult? = when {
        actualStartTime.isBlank() -> invalid(
            requestedId,
            RootSearchIdInvalidReason.READ_FAILED,
            "Search read omitted process identity",
            expectedStartTime,
        )
        actualStartTime != expectedStartTime -> invalid(
            requestedId,
            RootSearchIdInvalidReason.TARGET_CHANGED,
            "Target process identity changed during search",
            actualStartTime,
        )
        else -> null
    }

    private fun identityChangeFailure(
        actualStartTime: String,
        expectedStartTime: String,
        requestedId: Long,
    ): RootSearchIdResult? = actualStartTime
        .takeIf(String::isNotBlank)
        ?.takeIf { it != expectedStartTime }
        ?.let { changedStartTime ->
            invalid(
                requestedId,
                RootSearchIdInvalidReason.TARGET_CHANGED,
                "Target process identity changed during search",
                changedStartTime,
            )
        }

    private fun checkedAdd(value: Long, offset: Long): Long? =
        runCatching { Math.addExact(value, offset) }
            .getOrNull()
            ?.takeIf { it > 0L }

    private fun checkedMultiply(value: Long, multiplier: Long): Long? =
        runCatching { Math.multiplyExact(value, multiplier) }
            .getOrNull()
            ?.takeIf { it >= 0L }

    private fun invalid(
        requestedId: Long,
        reason: RootSearchIdInvalidReason,
        message: String,
        processStartTimeTicks: String = "",
    ): RootSearchIdResult = RootSearchIdResult(
        status = RootSearchIdStatus.INVALID,
        requestedId = requestedId,
        processStartTimeTicks = processStartTimeTicks,
        invalidReason = reason,
        message = message,
    )

    private sealed interface ScanStep<out T> {
        data class Success<T>(val value: T) : ScanStep<T>
        data class Failure(val result: RootSearchIdResult) : ScanStep<Nothing>
    }

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
        const val RESOLVER_VALUE_MASK = 0x00ff_ffffL
    }
}

internal object RootRecoveredSearchIdProfiles {
    val miniWorld1582 = RootSearchIdProfile(
        profileId = "miniworld-1.58.2-arm64-search-id-v2",
        schemaVersion = 2,
        targetVersion = "1.58.2",
        moduleName = RootGameAppArtifact1582.MODULE_NAME,
        moduleSpec = "${RootGameAppArtifact1582.MODULE_NAME}:bss",
        expectedSha256 = RootGameAppArtifact1582.SHA256,
        anchorKind = RootModuleAnchorKind.MODULE_SPEC_RESULT,
        recipe = RootSearchIdRecipe(
            rootOffset = 0x18760L,
            chainOffsets = listOf(0x88L, 0xd8L),
            slotCount = 40,
            slotStride = 8L,
            markerOffset = 0x488L,
            markerBits = 0x42c80000L,
            idOffset = 0L,
            sourceArtifact = "reports/native_tools/evidence/searchid_address_chain.md",
            sourceReference = "Module-spec/maps anchor resolver chain and fixed 40-slot scan",
        ),
        sourceArtifact = "reports/native_tools/evidence/libclient_resolver_4e96ec.md",
        sourceReference =
            "Fingerprint-pinned offset-zero load bias plus verified 4 KiB anonymous-BSS layout",
    )

    // Compatibility alias for callers compiled while the profile was still
    // evidence-gated. The aliased value is now the production profile.
    val miniWorld1582Draft: RootSearchIdProfile = miniWorld1582

    val fingerprintModuleNames: Set<String> = setOf(miniWorld1582.moduleName)
}

private fun RootSearchIdAnchorStatus.toInvalidReason(): RootSearchIdInvalidReason = when (this) {
    RootSearchIdAnchorStatus.RESOLVED -> RootSearchIdInvalidReason.INVALID_PROFILE
    RootSearchIdAnchorStatus.INVALID_PID -> RootSearchIdInvalidReason.INVALID_PID
    RootSearchIdAnchorStatus.INVALID_PROFILE -> RootSearchIdInvalidReason.INVALID_PROFILE
    RootSearchIdAnchorStatus.UNAVAILABLE -> RootSearchIdInvalidReason.ANCHOR_UNAVAILABLE
    RootSearchIdAnchorStatus.MODULE_NOT_FOUND -> RootSearchIdInvalidReason.MODULE_NOT_FOUND
    RootSearchIdAnchorStatus.MODULE_AMBIGUOUS -> RootSearchIdInvalidReason.MODULE_AMBIGUOUS
    RootSearchIdAnchorStatus.FINGERPRINT_UNAVAILABLE ->
        RootSearchIdInvalidReason.FINGERPRINT_UNAVAILABLE
    RootSearchIdAnchorStatus.FINGERPRINT_MISMATCH ->
        RootSearchIdInvalidReason.FINGERPRINT_MISMATCH
    RootSearchIdAnchorStatus.ANCHOR_NOT_FOUND -> RootSearchIdInvalidReason.ANCHOR_NOT_FOUND
    RootSearchIdAnchorStatus.TARGET_CHANGED -> RootSearchIdInvalidReason.TARGET_CHANGED
}

private fun isSearchIdModuleName(value: String): Boolean =
    value.length in 1..192 &&
        '/' !in value &&
        '\\' !in value &&
        value.endsWith(".so")

private fun isSearchIdModuleSpec(value: String, moduleName: String): Boolean =
    parseRecoveredModuleSpec(value)?.moduleName == moduleName

private fun isSearchIdSha256(value: String): Boolean =
    value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }
