package me.dartcv.minix.control

/**
 * Result of resolving a versioned injection profile for the currently pinned target.
 *
 * This state is safe to expose through Binder: it deliberately contains no address,
 * payload, path, shell command, or raw patch value.
 */
enum class ControlInjectionProfileStatus {
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
        fun fromWireValue(value: String): ControlInjectionProfileStatus? =
            entries.firstOrNull { it.name == value }
    }
}

/** Last typed feature-application result. */
enum class ControlInjectionApplyStatus {
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
        fun fromWireValue(value: String): ControlInjectionApplyStatus? =
            entries.firstOrNull { it.name == value }
    }
}

data class ControlInjectionState(
    val profileStatus: ControlInjectionProfileStatus = ControlInjectionProfileStatus.IDLE,
    val profileSummary: String = "Injection profile has not been resolved",
    val profileId: String = "",
    val targetVersion: String = "",
    val requiredAbi: String = "",
    val lastApplyStatus: ControlInjectionApplyStatus = ControlInjectionApplyStatus.IDLE,
    val lastFeature: ControlFeature? = null,
    val message: String = "",
) {
    val isProfileReady: Boolean
        get() = profileStatus == ControlInjectionProfileStatus.READY
}

/** Typed press command retained for the recovered Fly1/Fly2 down/up semantics. */
enum class ControlFlightDirection(val wireId: String) {
    ASCEND("ascend"),
    DESCEND("descend");

    companion object {
        fun fromWireValue(value: String): ControlFlightDirection? =
            entries.firstOrNull { it.wireId == value }
    }
}

data class ControlFlightPressRequest(
    val direction: ControlFlightDirection,
    val pressed: Boolean,
)

data class ControlFlightPressResult(
    val requestedPressed: Boolean,
    val effectivePressed: Boolean? = null,
    val profileId: String = "",
    val status: ControlInjectionApplyStatus,
    val message: String = "",
)

data class ControlFakeFlightRequest(
    val enabled: Boolean,
)

data class ControlFakeFlightResult(
    val requestedEnabled: Boolean,
    val effectiveEnabled: Boolean? = null,
    val workerStarted: Boolean = false,
    val profileId: String = "",
    val status: ControlInjectionApplyStatus,
    val message: String = "",
)

data class ControlPlayerPositionRequest(
    val x: Int,
    val y: Int,
    val z: Int,
)

data class ControlPlayerPositionResult(
    val request: ControlPlayerPositionRequest,
    val effectiveX: Int? = null,
    val effectiveY: Int? = null,
    val effectiveZ: Int? = null,
    val appliedAxisCount: Int = 0,
    val profileId: String = "",
    val status: ControlInjectionApplyStatus,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlInjectionApplyStatus.APPLIED ||
            status == ControlInjectionApplyStatus.ALREADY_APPLIED
}

internal enum class ControlPlayerPositionAxis {
    X,
    Y,
    Z,
}

internal data class ControlPlayerPositionEvidence(
    val axisRecipes: Map<ControlPlayerPositionAxis, ControlResolverAddressRecipe>,
    val byteCount: Int = Int.SIZE_BYTES,
    val rawUnitsPerWorldUnit: Int = 1,
    val mappingRequirement: ControlInjectionMappingRequirement =
        ControlInjectionMappingRequirement.WRITABLE,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val isComplete: Boolean
        get() = axisRecipes.keys == ControlPlayerPositionAxis.entries.toSet() &&
            axisRecipes.values.all(ControlResolverAddressRecipe::isStructurallyValid) &&
            axisRecipes.values.distinct().size == ControlPlayerPositionAxis.entries.size &&
            byteCount == Int.SIZE_BYTES &&
            rawUnitsPerWorldUnit > 0 &&
            mappingRequirement == ControlInjectionMappingRequirement.WRITABLE &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()

    fun encodeWorldCoordinate(value: Int): Long? {
        val rawValue = value.toLong() * rawUnitsPerWorldUnit.toLong()
        if (rawValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return rawValue.toInt().toLong() and 0xffff_ffffL
    }
}

internal enum class ControlInjectionMappingRequirement {
    READABLE,
    WRITABLE,
    EXECUTABLE,
}

internal data class ControlInjectionScalarPatchEvidence(
    val feature: ControlFeature,
    val moduleOffset: Long?,
    val addressRecipe: ControlResolverAddressRecipe? = null,
    val absoluteAddress: Long? = null,
    val byteCount: Int,
    val disabledValueBits: Long?,
    val enabledValueBits: Long?,
    val valueMask: Long? = null,
    val mappingRequirement: ControlInjectionMappingRequirement =
        ControlInjectionMappingRequirement.READABLE,
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
            (mappingRequirement != ControlInjectionMappingRequirement.EXECUTABLE ||
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

internal data class ControlInjectionProfileCandidate(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val packageNames: Set<String>,
    val requiredAbi: String,
    val moduleEvidence: ControlModuleIdentityEvidence,
    val patches: List<ControlInjectionScalarPatchEvidence>,
    val playerPositionEvidence: ControlPlayerPositionEvidence? = null,
    val requiresModuleIdentity: Boolean = false,
) {
    val hasCompleteEvidence: Boolean
        get() = profileId.isNotBlank() &&
            targetVersion.isNotBlank() &&
            packageNames.isNotEmpty() &&
            packageNames.all(ControlProtocol::isValidPackageName) &&
            requiredAbi in ControlInjectionProfileCatalog.supportedAbis &&
            (!requiresModuleIdentity || moduleEvidence.isComplete) &&
            (patches.isNotEmpty() || playerPositionEvidence != null) &&
            patches.map(ControlInjectionScalarPatchEvidence::feature).distinct().size == patches.size &&
            (playerPositionEvidence == null || playerPositionEvidence.isComplete) &&
            (patches.any { patch ->
                patch.isComplete &&
                    (patch.addressRecipe != null || moduleEvidence.isComplete)
            } || playerPositionEvidence?.isComplete == true)
}

internal data class ResolvedControlInjectionPatch(
    val feature: ControlFeature,
    val address: Long? = null,
    val addressRecipe: ControlResolverAddressRecipe? = null,
    val byteCount: Int,
    val disabledValueBits: Long,
    val enabledValueBits: Long,
    val valueMask: Long? = null,
    val mappingRequirement: ControlInjectionMappingRequirement =
        ControlInjectionMappingRequirement.READABLE,
)

internal data class ResolvedControlInjectionProfile(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val requiredAbi: String,
    val moduleName: String,
    val moduleSha256: String,
    val patches: Map<ControlFeature, ResolvedControlInjectionPatch>,
    val playerPosition: ControlPlayerPositionEvidence? = null,
)

internal data class ControlInjectionProfileResolution(
    val state: ControlInjectionState = ControlInjectionState(),
    val profile: ResolvedControlInjectionProfile? = null,
)

internal class ControlInjectionProfileResolver(
    private val candidates: List<ControlInjectionProfileCandidate>,
) {
    fun resolve(
        packageName: String,
        serviceAbi: String,
        modules: List<ControlNativeModuleIdentity>,
    ): ControlInjectionProfileResolution {
        val applicable = candidates.filter { packageName in it.packageNames }
        if (applicable.isEmpty()) {
            return failure(ControlInjectionProfileStatus.NO_PROFILE, message = "No injection profile for package")
        }

        val currentSchema = applicable.filter {
            it.schemaVersion == ControlInjectionProfileCatalog.SCHEMA_VERSION
        }
        if (currentSchema.isEmpty()) {
            return failure(
                ControlInjectionProfileStatus.SCHEMA_UNSUPPORTED,
                applicable.first(),
                "Injection profile schema is not supported",
            )
        }

        val complete = currentSchema.filter(ControlInjectionProfileCandidate::hasCompleteEvidence)
        if (complete.isEmpty()) {
            return failure(
                ControlInjectionProfileStatus.INCOMPLETE_EVIDENCE,
                currentSchema.first(),
                "Module fingerprint, patch offset, or expected scalar evidence is incomplete",
            )
        }

        var fallback: ControlInjectionProfileResolution? = null
        complete.forEach { candidate ->
            if (serviceAbi != candidate.requiredAbi) {
                fallback = fallback ?: failure(
                    ControlInjectionProfileStatus.ABI_MISMATCH,
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
            var module: ControlNativeModuleIdentity? = null
            var moduleName = ""
            var expectedSha256 = ""
            var moduleFailure: ControlInjectionProfileResolution? = null
            if (mustResolveModuleIdentity) {
                if (!candidate.moduleEvidence.isComplete) {
                    moduleFailure = failure(
                        ControlInjectionProfileStatus.INCOMPLETE_EVIDENCE,
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
                            ControlInjectionProfileStatus.MODULE_NOT_FOUND,
                            candidate,
                            "Required injection module was not found",
                        )
                    } else if (candidate.requiresModuleIdentity && namedModules.size != 1) {
                        moduleFailure = failure(
                            ControlInjectionProfileStatus.MODULE_AMBIGUOUS,
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
                                ControlInjectionProfileStatus.FINGERPRINT_UNAVAILABLE,
                                candidate,
                                "Required injection module has no SHA-256 fingerprint",
                            )
                        } else {
                            val matched = fingerprinted.filter { (_, sha256) ->
                                sha256 == expectedSha256
                            }
                            moduleFailure = when {
                                matched.isEmpty() -> failure(
                                    ControlInjectionProfileStatus.FINGERPRINT_MISMATCH,
                                    candidate,
                                    "Required injection module fingerprint does not match",
                                )
                                matched.size != 1 -> failure(
                                    ControlInjectionProfileStatus.MODULE_AMBIGUOUS,
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

            val resolvedPatches = linkedMapOf<ControlFeature, ResolvedControlInjectionPatch>()
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
                            ControlInjectionProfileStatus.PATCH_OUT_OF_RANGE,
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
                        ControlInjectionProfileStatus.PATCH_OUT_OF_RANGE,
                        candidate,
                        "Injection patch omitted both an offset and an address recipe",
                    )
                    continue
                }
                resolvedPatches[patch.feature] = ResolvedControlInjectionPatch(
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
                    failure(ControlInjectionProfileStatus.INCOMPLETE_EVIDENCE, candidate)
                return@forEach
            }

            val profile = ResolvedControlInjectionProfile(
                profileId = candidate.profileId,
                schemaVersion = candidate.schemaVersion,
                targetVersion = candidate.targetVersion,
                requiredAbi = candidate.requiredAbi,
                moduleName = moduleName,
                moduleSha256 = expectedSha256,
                patches = resolvedPatches,
                playerPosition = resolvedPlayerPosition,
            )
            return ControlInjectionProfileResolution(
                state = ControlInjectionState(
                    profileStatus = ControlInjectionProfileStatus.READY,
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

        return fallback ?: failure(ControlInjectionProfileStatus.MODULE_NOT_FOUND)
    }

    private fun failure(
        status: ControlInjectionProfileStatus,
        candidate: ControlInjectionProfileCandidate? = null,
        message: String = "",
    ): ControlInjectionProfileResolution = ControlInjectionProfileResolution(
        state = ControlInjectionState(
            profileStatus = status,
            profileSummary = status.summary(),
            profileId = candidate?.profileId.orEmpty(),
            targetVersion = candidate?.targetVersion.orEmpty(),
            requiredAbi = candidate?.requiredAbi.orEmpty(),
            message = message,
        ),
    )
}

internal data class ControlInjectionWriteRequest(
    val pid: Int,
    val expectedStartTimeTicks: String,
    val profileId: String,
    val feature: ControlFeature,
    val address: Long,
    val byteCount: Int,
    val expectedValueBits: Long,
    val desiredValueBits: Long,
    val mappingRequirement: ControlInjectionMappingRequirement,
)

internal data class ControlInjectionWriteResult(
    val status: ControlInjectionApplyStatus,
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlInjectionApplyStatus.APPLIED ||
            status == ControlInjectionApplyStatus.ALREADY_APPLIED
}

internal fun interface ControlInjectionExecutor {
    fun apply(request: ControlInjectionWriteRequest): ControlInjectionWriteResult
}

internal object NoControlInjectionExecutor : ControlInjectionExecutor {
    override fun apply(request: ControlInjectionWriteRequest): ControlInjectionWriteResult =
        ControlInjectionWriteResult(
            status = ControlInjectionApplyStatus.BACKEND_UNAVAILABLE,
            message = "Typed injection backend is unavailable",
        )
}

internal object ControlInjectionProfileCatalog {
    const val SCHEMA_VERSION = 1
    val supportedAbis: Set<String> = setOf("arm64-v8a")

    private const val EVIDENCE_ARTIFACT =
        "reports/native_tools/evidence/java_feature_selector_map.md"

    val candidates: List<ControlInjectionProfileCandidate> = listOf(
        ControlInjectionProfileCandidate(
            profileId = "miniworld-1.58.2-arm64-injection-direct-v4",
            schemaVersion = SCHEMA_VERSION,
            targetVersion = "1.58.2",
            packageNames = ControlTargetCatalog.entries
                .mapTo(linkedSetOf(), ControlTargetChannel::packageName),
            requiredAbi = "arm64-v8a",
            moduleEvidence = ControlModuleIdentityEvidence(
                moduleName = ControlGameAppArtifact1582.MODULE_NAME,
                expectedSha256 = ControlGameAppArtifact1582.SHA256,
                sourceArtifact = ControlGameAppArtifact1582.SOURCE_ARTIFACT,
                sourceReference =
                    "${ControlGameAppArtifact1582.SOURCE_REFERENCE}; closed typed patches remain version-gated and unresolved selectors stay disabled",
            ),
            patches = listOf(
                unresolvedPatch(
                    ControlFeature.AIM,
                    "40-slot candidate/filter recipe recovered; final GameApp writer, width, values and readback unresolved",
                    "work/aim-direct-recovery-20260817/aim_control_recovery_continued_20260817.md",
                ),
                unresolvedPatch(
                    ControlFeature.DRAW,
                    "Entity/matrix/wire recipe recovered; full projection, field mapping and iteration boundary unresolved",
                    "work/draw-recovery-20260817/production-draw-recipe.md",
                ),
                ControlInjectionScalarPatchEvidence(
                    feature = ControlFeature.FLIGHT,
                    moduleOffset = null,
                    addressRecipe = ControlRecoveredInjectionRecipes.flightCommon10,
                    byteCount = Int.SIZE_BYTES,
                    disabledValueBits = 0L,
                    enabledValueBits = 8L,
                    valueMask = 8L,
                    mappingRequirement = ControlInjectionMappingRequirement.WRITABLE,
                    sourceArtifact =
                        "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
                    sourceReference =
                        "ClientPlayer::getFlying vslot +0x4e8 reads bit 3 at player+0xf0; " +
                            "the standard pointer chain starts at IPlayerControl* global RVA 0x0afde370",
                ),
                ControlInjectionScalarPatchEvidence(
                    feature = ControlFeature.FAKE_FLIGHT,
                    moduleOffset = 0x052e13f8L,
                    byteCount = Int.SIZE_BYTES,
                    disabledValueBits = 0x39449269L,
                    enabledValueBits = 0xd503201fL,
                    mappingRequirement = ControlInjectionMappingRequirement.EXECUTABLE,
                    sourceArtifact = "work/fake-flight-recovery-20260817/README.md",
                    sourceReference =
                        "At RVA 0x052e13f8 the predecessor CBNZ proves w9=0; replacing " +
                            "LDRB w9,[x19,#0x124] with NOP preserves zero and bypasses the " +
                            "movement-state gate. The 0x052e1398 duplicate is excluded because " +
                            "its path proves w9>=1 and NOP would immediately exit via CBNZ.",
                ),
                unresolvedPatch(
                    ControlFeature.HITBOX,
                    "Local-player Locomotion triplet recovered; multiplayer entity set and rollback lifetime unresolved",
                    "work/hitbox-recovery-20260817/production-hitbox-recipe.md",
                ),
            ),
            playerPositionEvidence = ControlPlayerPositionEvidence(
                axisRecipes = mapOf(
                    ControlPlayerPositionAxis.X to ControlRecoveredInjectionRecipes.playerPositionX,
                    ControlPlayerPositionAxis.Y to ControlRecoveredInjectionRecipes.playerPositionY,
                    ControlPlayerPositionAxis.Z to ControlRecoveredInjectionRecipes.playerPositionZ,
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

    val resolver = ControlInjectionProfileResolver(candidates)

    private fun unresolvedPatch(
        feature: ControlFeature,
        sourceReference: String,
        sourceArtifact: String = EVIDENCE_ARTIFACT,
    ): ControlInjectionScalarPatchEvidence = ControlInjectionScalarPatchEvidence(
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

internal object ControlRecoveredInjectionRecipes {
    val flightCommon10 = ControlResolverAddressRecipe(
        initialSeed = ControlGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        intermediateOffsets = listOf(0x1a0L),
        finalOffset = 0xf0L,
        sourceArtifact = "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
        sourceReference =
            "IPlayerControl* = *(loadBias+0x0afde370); player = *(control+0x1a0); " +
                "ClientPlayer::getFlying reads player+0xf0 bit 3",
        seedAnchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = ControlAddressDereferenceMode.POINTER64,
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
    ): ControlResolverAddressRecipe = ControlResolverAddressRecipe(
        initialSeed = ControlGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        intermediateOffsets = listOf(0x1a0L, 0x140L),
        finalOffset = finalOffset,
        sourceArtifact = "work/gameapp-runtime-recovery-20260817/runtime-pointer-recovery.md",
        sourceReference = sourceReference,
        seedAnchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = ControlAddressDereferenceMode.POINTER64,
    )
}

private fun ControlInjectionProfileStatus.summary(): String = when (this) {
    ControlInjectionProfileStatus.IDLE -> "Injection profile has not been resolved"
    ControlInjectionProfileStatus.NO_PROFILE -> "No injection profile matches the target"
    ControlInjectionProfileStatus.SCHEMA_UNSUPPORTED -> "Injection profile schema is unsupported"
    ControlInjectionProfileStatus.INCOMPLETE_EVIDENCE -> "Injection evidence is incomplete"
    ControlInjectionProfileStatus.ABI_MISMATCH -> "Control service ABI does not match"
    ControlInjectionProfileStatus.MODULE_NOT_FOUND -> "Injection module was not found"
    ControlInjectionProfileStatus.MODULE_AMBIGUOUS -> "Injection module identity is ambiguous"
    ControlInjectionProfileStatus.FINGERPRINT_UNAVAILABLE -> "Injection module fingerprint is unavailable"
    ControlInjectionProfileStatus.FINGERPRINT_MISMATCH -> "Injection module fingerprint does not match"
    ControlInjectionProfileStatus.PATCH_OUT_OF_RANGE -> "Injection patch is outside the mapped module"
    ControlInjectionProfileStatus.INVALID_RESPONSE -> "Injection state response is invalid"
    ControlInjectionProfileStatus.READY -> "Injection profile is ready"
}

private fun isInjectionSha256(value: String): Boolean =
    value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }
