package me.dartcv.minix.root

/**
 * Result of resolving a versioned injection profile for the currently pinned target.
 *
 * This state is safe to expose through Binder: it deliberately contains no address,
 * payload, path, shell command, or raw patch value.
 */
enum class RootInjectionProfileStatus {
    IDLE,
    NO_PROFILE,
    SCHEMA_UNSUPPORTED,
    INCOMPLETE_EVIDENCE,
    ABI_MISMATCH,
    MODULE_NOT_FOUND,
    MODULE_AMBIGUOUS,
    FINGERPRINT_UNAVAILABLE,
    FINGERPRINT_MISMATCH,
    PATCH_OUT_OF_RANGE,
    INVALID_RESPONSE,
    READY;

    companion object {
        fun fromWireValue(value: String): RootInjectionProfileStatus? =
            entries.firstOrNull { it.name == value }
    }
}

/** Last typed feature-application result. */
enum class RootInjectionApplyStatus {
    IDLE,
    APPLIED,
    ALREADY_APPLIED,
    SESSION_CLOSED,
    TARGET_CHANGED,
    PROFILE_NOT_READY,
    FEATURE_NOT_DEFINED,
    PRECONDITION_READ_FAILED,
    EXPECTED_VALUE_MISMATCH,
    BACKEND_UNAVAILABLE,
    WRITE_FAILED,
    VERIFY_FAILED,
    ROLLBACK_FAILED,
    INVALID_RESPONSE;

    companion object {
        fun fromWireValue(value: String): RootInjectionApplyStatus? =
            entries.firstOrNull { it.name == value }
    }
}

data class RootInjectionState(
    val profileStatus: RootInjectionProfileStatus = RootInjectionProfileStatus.IDLE,
    val profileSummary: String = "Injection profile has not been resolved",
    val profileId: String = "",
    val targetVersion: String = "",
    val requiredAbi: String = "",
    val lastApplyStatus: RootInjectionApplyStatus = RootInjectionApplyStatus.IDLE,
    val lastFeature: RootFeature? = null,
    val message: String = "",
) {
    val isProfileReady: Boolean
        get() = profileStatus == RootInjectionProfileStatus.READY
}

/** Typed press command retained for the recovered Fly1/Fly2 down/up semantics. */
enum class RootFlightDirection(val wireId: String) {
    ASCEND("ascend"),
    DESCEND("descend");

    companion object {
        fun fromWireValue(value: String): RootFlightDirection? =
            entries.firstOrNull { it.wireId == value }
    }
}

data class RootFlightPressRequest(
    val direction: RootFlightDirection,
    val pressed: Boolean,
)

data class RootFlightPressResult(
    val requestedPressed: Boolean,
    val effectivePressed: Boolean? = null,
    val profileId: String = "",
    val status: RootInjectionApplyStatus,
    val message: String = "",
)

data class RootFakeFlightRequest(
    val enabled: Boolean,
)

data class RootFakeFlightResult(
    val requestedEnabled: Boolean,
    val effectiveEnabled: Boolean? = null,
    val workerStarted: Boolean = false,
    val profileId: String = "",
    val status: RootInjectionApplyStatus,
    val message: String = "",
)

data class RootPlayerPositionRequest(
    val x: Int,
    val y: Int,
    val z: Int,
)

data class RootPlayerPositionResult(
    val request: RootPlayerPositionRequest,
    val effectiveX: Int? = null,
    val effectiveY: Int? = null,
    val effectiveZ: Int? = null,
    val appliedAxisCount: Int = 0,
    val profileId: String = "",
    val status: RootInjectionApplyStatus,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootInjectionApplyStatus.APPLIED ||
            status == RootInjectionApplyStatus.ALREADY_APPLIED
}

internal enum class RootPlayerPositionAxis {
    X,
    Y,
    Z,
}

internal data class RootPlayerPositionEvidence(
    val axisRecipes: Map<RootPlayerPositionAxis, RootResolverAddressRecipe>,
    val byteCount: Int = Int.SIZE_BYTES,
    val rawUnitsPerWorldUnit: Int = 1,
    val mappingRequirement: RootInjectionMappingRequirement =
        RootInjectionMappingRequirement.WRITABLE,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val isComplete: Boolean
        get() = axisRecipes.keys == RootPlayerPositionAxis.entries.toSet() &&
            axisRecipes.values.all(RootResolverAddressRecipe::isStructurallyValid) &&
            axisRecipes.values.distinct().size == RootPlayerPositionAxis.entries.size &&
            byteCount == Int.SIZE_BYTES &&
            rawUnitsPerWorldUnit > 0 &&
            mappingRequirement == RootInjectionMappingRequirement.WRITABLE &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()

    fun encodeWorldCoordinate(value: Int): Long? {
        val rawValue = value.toLong() * rawUnitsPerWorldUnit.toLong()
        if (rawValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return rawValue.toInt().toLong() and 0xffff_ffffL
    }
}

internal enum class RootInjectionMappingRequirement {
    READABLE,
    WRITABLE,
    EXECUTABLE,
}

internal data class RootInjectionScalarPatchEvidence(
    val feature: RootFeature,
    val moduleOffset: Long?,
    val addressRecipe: RootResolverAddressRecipe? = null,
    val absoluteAddress: Long? = null,
    val byteCount: Int,
    val disabledValueBits: Long?,
    val enabledValueBits: Long?,
    val valueMask: Long? = null,
    val mappingRequirement: RootInjectionMappingRequirement =
        RootInjectionMappingRequirement.READABLE,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val isComplete: Boolean
        get() = listOf(
            moduleOffset != null,
            addressRecipe != null,
            absoluteAddress != null,
        ).count { it } == 1 &&
            (moduleOffset == null || moduleOffset in 0..MAX_MODULE_OFFSET) &&
            (addressRecipe == null || addressRecipe.isStructurallyValid) &&
            (absoluteAddress == null || absoluteAddress > 0L) &&
            byteCount in SUPPORTED_SCALAR_BYTES &&
            (mappingRequirement != RootInjectionMappingRequirement.EXECUTABLE ||
                byteCount == Int.SIZE_BYTES) &&
            hasValidValueEvidence() &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()

    private fun hasValidValueEvidence(): Boolean {
        if (!disabledValueBits.fits(byteCount) || !enabledValueBits.fits(byteCount)) return false
        val disabled = requireNotNull(disabledValueBits)
        val enabled = requireNotNull(enabledValueBits)
        if (disabled == enabled) return false
        val mask = valueMask ?: return true
        return mask.fits(byteCount) &&
            mask != 0L &&
            (disabled and mask.inv()) == 0L &&
            (enabled and mask.inv()) == 0L
    }

    private fun Long?.fits(bytes: Int): Boolean = when {
        this == null -> false
        bytes == Int.SIZE_BYTES -> this ushr Int.SIZE_BITS == 0L
        bytes == Long.SIZE_BYTES -> true
        else -> false
    }

    private companion object {
        const val MAX_MODULE_OFFSET = 1L shl 40
        val SUPPORTED_SCALAR_BYTES = setOf(Int.SIZE_BYTES, Long.SIZE_BYTES)
    }
}

internal data class RootInjectionProfileCandidate(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val packageNames: Set<String>,
    val requiredAbi: String,
    val moduleEvidence: RootModuleIdentityEvidence,
    val patches: List<RootInjectionScalarPatchEvidence>,
    val playerPositionEvidence: RootPlayerPositionEvidence? = null,
    val requiresModuleIdentity: Boolean = false,
) {
    val hasCompleteEvidence: Boolean
        get() = profileId.isNotBlank() &&
            targetVersion.isNotBlank() &&
            packageNames.isNotEmpty() &&
            packageNames.all(RootProtocol::isValidPackageName) &&
            requiredAbi in RootInjectionProfileCatalog.supportedAbis &&
            (!requiresModuleIdentity || moduleEvidence.isComplete) &&
            (patches.isNotEmpty() || playerPositionEvidence != null) &&
            patches.map(RootInjectionScalarPatchEvidence::feature).distinct().size == patches.size &&
            (playerPositionEvidence == null || playerPositionEvidence.isComplete) &&
            (patches.any { patch ->
                patch.isComplete &&
                    (patch.addressRecipe != null || moduleEvidence.isComplete)
            } || playerPositionEvidence?.isComplete == true)
}

internal data class ResolvedRootInjectionPatch(
    val feature: RootFeature,
    val address: Long? = null,
    val addressRecipe: RootResolverAddressRecipe? = null,
    val byteCount: Int,
    val disabledValueBits: Long,
    val enabledValueBits: Long,
    val valueMask: Long? = null,
    val mappingRequirement: RootInjectionMappingRequirement =
        RootInjectionMappingRequirement.READABLE,
)

internal data class ResolvedRootInjectionProfile(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val requiredAbi: String,
    val moduleName: String,
    val moduleSha256: String,
    val patches: Map<RootFeature, ResolvedRootInjectionPatch>,
    val playerPosition: RootPlayerPositionEvidence? = null,
)

internal data class RootInjectionProfileResolution(
    val state: RootInjectionState = RootInjectionState(),
    val profile: ResolvedRootInjectionProfile? = null,
)

internal class RootInjectionProfileResolver(
    private val candidates: List<RootInjectionProfileCandidate>,
) {
    fun resolve(
        packageName: String,
        serviceAbi: String,
        modules: List<RootNativeModuleIdentity>,
    ): RootInjectionProfileResolution {
        val applicable = candidates.filter { packageName in it.packageNames }
        if (applicable.isEmpty()) {
            return failure(RootInjectionProfileStatus.NO_PROFILE, message = "No injection profile for package")
        }

        val currentSchema = applicable.filter {
            it.schemaVersion == RootInjectionProfileCatalog.SCHEMA_VERSION
        }
        if (currentSchema.isEmpty()) {
            return failure(
                RootInjectionProfileStatus.SCHEMA_UNSUPPORTED,
                applicable.first(),
                "Injection profile schema is not supported",
            )
        }

        val complete = currentSchema.filter(RootInjectionProfileCandidate::hasCompleteEvidence)
        if (complete.isEmpty()) {
            return failure(
                RootInjectionProfileStatus.INCOMPLETE_EVIDENCE,
                currentSchema.first(),
                "Module fingerprint, patch offset, or expected scalar evidence is incomplete",
            )
        }

        var fallback: RootInjectionProfileResolution? = null
        complete.forEach { candidate ->
            if (serviceAbi != candidate.requiredAbi) {
                fallback = fallback ?: failure(
                    RootInjectionProfileStatus.ABI_MISMATCH,
                    candidate,
                    "Control service ABI does not match the injection profile",
                )
                return@forEach
            }

            val independentPatches = candidate.patches.filter { patch ->
                patch.isComplete && patch.moduleOffset == null
            }
            val modulePatches = candidate.patches.filter { patch ->
                patch.isComplete && patch.moduleOffset != null
            }
            val mustResolveModuleIdentity =
                candidate.requiresModuleIdentity || modulePatches.isNotEmpty()
            var module: RootNativeModuleIdentity? = null
            var moduleName = ""
            var expectedSha256 = ""
            var moduleFailure: RootInjectionProfileResolution? = null
            if (mustResolveModuleIdentity) {
                if (!candidate.moduleEvidence.isComplete) {
                    moduleFailure = failure(
                        RootInjectionProfileStatus.INCOMPLETE_EVIDENCE,
                        candidate,
                        "Module-backed injection patch fingerprint evidence is incomplete",
                    )
                } else {
                    moduleName = requireNotNull(candidate.moduleEvidence.moduleName).trim()
                    expectedSha256 = requireNotNull(candidate.moduleEvidence.normalizedSha256)
                    val namedModules = modules.filter { candidateModule ->
                        candidateModule.name == moduleName &&
                            candidateModule.memoryElf &&
                            candidateModule.loadBase > 0L &&
                            candidateModule.mappedBytes > 0L
                    }
                    if (namedModules.isEmpty()) {
                        moduleFailure = failure(
                            RootInjectionProfileStatus.MODULE_NOT_FOUND,
                            candidate,
                            "Required injection module was not found",
                        )
                    } else if (candidate.requiresModuleIdentity && namedModules.size != 1) {
                        moduleFailure = failure(
                            RootInjectionProfileStatus.MODULE_AMBIGUOUS,
                            candidate,
                            "More than one mapped module matched the required injection module name",
                        )
                    } else {
                        val fingerprinted = namedModules.mapNotNull { candidateModule ->
                            candidateModule.sha256
                                ?.trim()
                                ?.lowercase()
                                ?.takeIf(::isInjectionSha256)
                                ?.let { candidateModule to it }
                        }
                        if (fingerprinted.isEmpty()) {
                            moduleFailure = failure(
                                RootInjectionProfileStatus.FINGERPRINT_UNAVAILABLE,
                                candidate,
                                "Required injection module has no SHA-256 fingerprint",
                            )
                        } else {
                            val matched = fingerprinted.filter { (_, sha256) ->
                                sha256 == expectedSha256
                            }
                            moduleFailure = when {
                                matched.isEmpty() -> failure(
                                    RootInjectionProfileStatus.FINGERPRINT_MISMATCH,
                                    candidate,
                                    "Required injection module fingerprint does not match",
                                )
                                matched.size != 1 -> failure(
                                    RootInjectionProfileStatus.MODULE_AMBIGUOUS,
                                    candidate,
                                    "More than one mapped module matched the injection identity",
                                )
                                else -> null
                            }
                            module = matched.singleOrNull()?.first
                        }
                    }
                }
            }
            if (
                moduleFailure != null &&
                (candidate.requiresModuleIdentity || independentPatches.isEmpty())
            ) {
                fallback = fallback ?: moduleFailure
                return@forEach
            }

            val resolvedPatches = linkedMapOf<RootFeature, ResolvedRootInjectionPatch>()
            for (patch in independentPatches + modulePatches.filter { module != null }) {
                val offset = patch.moduleOffset
                val address = if (offset != null) {
                    val matchedModule = requireNotNull(module)
                    val endOffset = runCatching {
                        Math.addExact(offset, patch.byteCount.toLong())
                    }.getOrNull()
                    val absolute = runCatching {
                        Math.addExact(matchedModule.loadBase, offset)
                    }.getOrNull()
                    if (endOffset == null ||
                        endOffset > matchedModule.mappedBytes ||
                        absolute == null ||
                        absolute <= 0L
                    ) {
                        fallback = failure(
                            RootInjectionProfileStatus.PATCH_OUT_OF_RANGE,
                            candidate,
                            "A verified injection patch falls outside the matched module",
                        )
                        continue
                    }
                    absolute
                } else {
                    patch.absoluteAddress
                }
                if (offset == null && patch.addressRecipe == null && patch.absoluteAddress == null) {
                    fallback = failure(
                        RootInjectionProfileStatus.PATCH_OUT_OF_RANGE,
                        candidate,
                        "Injection patch omitted both an offset and an address recipe",
                    )
                    continue
                }
                resolvedPatches[patch.feature] = ResolvedRootInjectionPatch(
                    feature = patch.feature,
                    address = address,
                    addressRecipe = patch.addressRecipe,
                    byteCount = patch.byteCount,
                    disabledValueBits = requireNotNull(patch.disabledValueBits),
                    enabledValueBits = requireNotNull(patch.enabledValueBits),
                    valueMask = patch.valueMask,
                    mappingRequirement = patch.mappingRequirement,
                )
            }
            val resolvedPlayerPosition = candidate.playerPositionEvidence?.takeIf { it.isComplete }
            if (resolvedPatches.isEmpty() && resolvedPlayerPosition == null) {
                fallback = fallback ?: moduleFailure ?:
                    failure(RootInjectionProfileStatus.INCOMPLETE_EVIDENCE, candidate)
                return@forEach
            }

            val profile = ResolvedRootInjectionProfile(
                profileId = candidate.profileId,
                schemaVersion = candidate.schemaVersion,
                targetVersion = candidate.targetVersion,
                requiredAbi = candidate.requiredAbi,
                moduleName = moduleName,
                moduleSha256 = expectedSha256,
                patches = resolvedPatches,
                playerPosition = resolvedPlayerPosition,
            )
            return RootInjectionProfileResolution(
                state = RootInjectionState(
                    profileStatus = RootInjectionProfileStatus.READY,
                    profileSummary = buildString {
                        val typedFeatureCount = resolvedPatches.size +
                            if (profile.playerPosition != null) 1 else 0
                        append(typedFeatureCount)
                        append(" typed injection features verified")
                        if (resolvedPatches.size < candidate.patches.size) {
                            append("; remaining scalar patches are evidence-gated")
                        }
                    },
                    profileId = candidate.profileId,
                    targetVersion = candidate.targetVersion,
                    requiredAbi = candidate.requiredAbi,
                ),
                profile = profile,
            )
        }

        return fallback ?: failure(RootInjectionProfileStatus.MODULE_NOT_FOUND)
    }

    private fun failure(
        status: RootInjectionProfileStatus,
        candidate: RootInjectionProfileCandidate? = null,
        message: String = "",
    ): RootInjectionProfileResolution = RootInjectionProfileResolution(
        state = RootInjectionState(
            profileStatus = status,
            profileSummary = status.summary(),
            profileId = candidate?.profileId.orEmpty(),
            targetVersion = candidate?.targetVersion.orEmpty(),
            requiredAbi = candidate?.requiredAbi.orEmpty(),
            message = message,
        ),
    )
}

internal data class RootInjectionWriteRequest(
    val pid: Int,
    val expectedStartTimeTicks: String,
    val profileId: String,
    val feature: RootFeature,
    val address: Long,
    val byteCount: Int,
    val expectedValueBits: Long,
    val desiredValueBits: Long,
    val mappingRequirement: RootInjectionMappingRequirement,
)

internal data class RootInjectionWriteResult(
    val status: RootInjectionApplyStatus,
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootInjectionApplyStatus.APPLIED ||
            status == RootInjectionApplyStatus.ALREADY_APPLIED
}

internal fun interface RootInjectionExecutor {
    fun apply(request: RootInjectionWriteRequest): RootInjectionWriteResult
}

internal object NoRootInjectionExecutor : RootInjectionExecutor {
    override fun apply(request: RootInjectionWriteRequest): RootInjectionWriteResult =
        RootInjectionWriteResult(
            status = RootInjectionApplyStatus.BACKEND_UNAVAILABLE,
            message = "Typed injection backend is unavailable",
        )
}

internal object RootInjectionProfileCatalog {
    const val SCHEMA_VERSION = 1
    val supportedAbis: Set<String> = setOf("arm64-v8a")

    private const val EVIDENCE_ARTIFACT =
        "reports/native_tools/evidence/java_feature_selector_map.md"

    val candidates: List<RootInjectionProfileCandidate> = listOf(
        RootInjectionProfileCandidate(
            profileId = "miniworld-1.58.2-arm64-injection-direct-v4",
            schemaVersion = SCHEMA_VERSION,
            targetVersion = "1.58.2",
            packageNames = RootTargetCatalog.entries
                .mapTo(linkedSetOf(), RootTargetChannel::packageName),
            requiredAbi = "arm64-v8a",
            moduleEvidence = RootModuleIdentityEvidence(
                moduleName = RootGameAppArtifact1582.MODULE_NAME,
                expectedSha256 = RootGameAppArtifact1582.SHA256,
                sourceArtifact = RootGameAppArtifact1582.SOURCE_ARTIFACT,
                sourceReference =
                    "${RootGameAppArtifact1582.SOURCE_REFERENCE}; closed typed patches remain version-gated and unresolved selectors stay disabled",
            ),
            patches = listOf(
                unresolvedPatch(
                    RootFeature.AIM,
                    "40-slot candidate/filter recipe recovered; final GameApp writer, width, values and readback unresolved",
                    "work/aim-direct-recovery-20260817/aim_control_recovery_continued_20260817.md",
                ),
                unresolvedPatch(
                    RootFeature.DRAW,
                    "Entity/matrix/wire recipe recovered; full projection, field mapping and iteration boundary unresolved",
                    "work/draw-recovery-20260817/production-draw-recipe.md",
                ),
                RootInjectionScalarPatchEvidence(
                    feature = RootFeature.FLIGHT,
                    moduleOffset = null,
                    addressRecipe = RootRecoveredInjectionRecipes.flightCommon10,
                    byteCount = Int.SIZE_BYTES,
                    disabledValueBits = 0L,
                    enabledValueBits = 8L,
                    valueMask = 8L,
                    mappingRequirement = RootInjectionMappingRequirement.WRITABLE,
                    sourceArtifact =
                        "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
                    sourceReference =
                        "ClientPlayer::getFlying vslot +0x4e8 reads bit 3 at player+0xf0; " +
                            "the standard pointer chain starts at IPlayerControl* global RVA 0x0afde370",
                ),
                RootInjectionScalarPatchEvidence(
                    feature = RootFeature.FAKE_FLIGHT,
                    moduleOffset = 0x052e13f8L,
                    byteCount = Int.SIZE_BYTES,
                    disabledValueBits = 0x39449269L,
                    enabledValueBits = 0xd503201fL,
                    mappingRequirement = RootInjectionMappingRequirement.EXECUTABLE,
                    sourceArtifact = "work/fake-flight-recovery-20260817/README.md",
                    sourceReference =
                        "At RVA 0x052e13f8 the predecessor CBNZ proves w9=0; replacing " +
                            "LDRB w9,[x19,#0x124] with NOP preserves zero and bypasses the " +
                            "movement-state gate. The 0x052e1398 duplicate is excluded because " +
                            "its path proves w9>=1 and NOP would immediately exit via CBNZ.",
                ),
                unresolvedPatch(
                    RootFeature.HITBOX,
                    "Local-player Locomotion triplet recovered; multiplayer entity set and rollback lifetime unresolved",
                    "work/hitbox-recovery-20260817/production-hitbox-recipe.md",
                ),
            ),
            playerPositionEvidence = RootPlayerPositionEvidence(
                axisRecipes = mapOf(
                    RootPlayerPositionAxis.X to RootRecoveredInjectionRecipes.playerPositionX,
                    RootPlayerPositionAxis.Y to RootRecoveredInjectionRecipes.playerPositionY,
                    RootPlayerPositionAxis.Z to RootRecoveredInjectionRecipes.playerPositionZ,
                ),
                rawUnitsPerWorldUnit = 100,
                sourceArtifact =
                    "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
                sourceReference =
                    "World::GetPlayerPosition vslot +0x140 resolves PlayerControl -> player -> " +
                        "actor and reads centi-unit int32 X/Y/Z at actor+0x2c8/+0x2cc/+0x2d0",
            ),
            requiresModuleIdentity = true,
        ),
    )

    val fingerprintModuleNames: Set<String> = candidates
        .asSequence()
        .mapNotNull { it.moduleEvidence.moduleName?.trim() }
        .filter(String::isNotEmpty)
        .toSet()

    val resolver = RootInjectionProfileResolver(candidates)

    private fun unresolvedPatch(
        feature: RootFeature,
        sourceReference: String,
        sourceArtifact: String = EVIDENCE_ARTIFACT,
    ): RootInjectionScalarPatchEvidence = RootInjectionScalarPatchEvidence(
        feature = feature,
        moduleOffset = null,
        addressRecipe = null,
        byteCount = Int.SIZE_BYTES,
        disabledValueBits = null,
        enabledValueBits = null,
        sourceArtifact = sourceArtifact,
        sourceReference = sourceReference,
    )
}

internal object RootRecoveredInjectionRecipes {
    val flightCommon10 = RootResolverAddressRecipe(
        initialSeed = RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        intermediateOffsets = listOf(0x1a0L),
        finalOffset = 0xf0L,
        sourceArtifact = "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
        sourceReference =
            "IPlayerControl* = *(loadBias+0x0afde370); player = *(control+0x1a0); " +
                "ClientPlayer::getFlying reads player+0xf0 bit 3",
        seedAnchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = RootAddressDereferenceMode.POINTER64,
    )

    val playerPositionX = playerPositionRecipe(
        finalOffset = 0x2c8L,
        sourceReference = "SetXYZ X: helper selector 1 -> write_u32 at 0x587b4c",
    )

    val playerPositionY = playerPositionRecipe(
        finalOffset = 0x2ccL,
        sourceReference = "SetXYZ Y: helper selector 2 -> write_u32 at 0x587b64",
    )

    val playerPositionZ = playerPositionRecipe(
        finalOffset = 0x2d0L,
        sourceReference = "SetXYZ Z: helper selector 3 -> write_u32 at 0x587b7c",
    )

    private fun playerPositionRecipe(
        finalOffset: Long,
        sourceReference: String,
    ): RootResolverAddressRecipe = RootResolverAddressRecipe(
        initialSeed = RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        intermediateOffsets = listOf(0x1a0L, 0x140L),
        finalOffset = finalOffset,
        sourceArtifact = "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
        sourceReference = sourceReference,
        seedAnchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = RootAddressDereferenceMode.POINTER64,
    )
}

private fun RootInjectionProfileStatus.summary(): String = when (this) {
    RootInjectionProfileStatus.IDLE -> "Injection profile has not been resolved"
    RootInjectionProfileStatus.NO_PROFILE -> "No injection profile matches the target"
    RootInjectionProfileStatus.SCHEMA_UNSUPPORTED -> "Injection profile schema is unsupported"
    RootInjectionProfileStatus.INCOMPLETE_EVIDENCE -> "Injection evidence is incomplete"
    RootInjectionProfileStatus.ABI_MISMATCH -> "Control service ABI does not match"
    RootInjectionProfileStatus.MODULE_NOT_FOUND -> "Injection module was not found"
    RootInjectionProfileStatus.MODULE_AMBIGUOUS -> "Injection module identity is ambiguous"
    RootInjectionProfileStatus.FINGERPRINT_UNAVAILABLE -> "Injection module fingerprint is unavailable"
    RootInjectionProfileStatus.FINGERPRINT_MISMATCH -> "Injection module fingerprint does not match"
    RootInjectionProfileStatus.PATCH_OUT_OF_RANGE -> "Injection patch is outside the mapped module"
    RootInjectionProfileStatus.INVALID_RESPONSE -> "Injection state response is invalid"
    RootInjectionProfileStatus.READY -> "Injection profile is ready"
}

private fun isInjectionSha256(value: String): Boolean =
    value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }
