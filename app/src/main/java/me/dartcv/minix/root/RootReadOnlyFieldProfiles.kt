package me.dartcv.minix.root

internal enum class RootReadOnlyFieldId(
    val wireId: String,
    val label: String,
    val sourceMethod: String,
    val selector: Int,
) {
    LIFE_STATE("get_data_2", "生命/存活状态", "GetData", 2),
    KILL_COUNT("get_data_3", "击杀数", "GetData", 3),
    DATA_LONG_SELECTOR_1("get_data_long_1", "GetDataLong(1)", "GetDataLong", 1),
}

internal data class RootModuleIdentityEvidence(
    val moduleName: String?,
    val expectedSha256: String?,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val normalizedSha256: String?
        get() = expectedSha256?.trim()?.lowercase()?.takeIf(::isSha256)

    val isComplete: Boolean
        get() = moduleName?.trim()?.takeIf(::isModuleName) != null &&
            normalizedSha256 != null &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()
}

internal data class RootModuleOffsetEvidence(
    val moduleOffset: Long?,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val isComplete: Boolean
        get() = moduleOffset != null &&
            moduleOffset in 0..MAX_MODULE_OFFSET &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()

    private companion object {
        const val MAX_MODULE_OFFSET = 1L shl 40
    }
}

internal sealed interface RootReadOnlyFieldDefinition<T> {
    val id: RootReadOnlyFieldId
    val offsetEvidence: RootModuleOffsetEvidence
    val addressRecipe: RootResolverAddressRecipe?
    val moduleSpecAddressRecipe: RootModuleSpecFieldAddressRecipe?
    val byteCount: Int

    fun decode(valueBits: Long): T
}

internal enum class RootInt32SourceEncoding {
    INT32,
    FLOAT32_TRUNCATE_TOWARD_ZERO,
}

internal data class RootInt32FieldDefinition(
    override val id: RootReadOnlyFieldId,
    override val offsetEvidence: RootModuleOffsetEvidence,
    override val addressRecipe: RootResolverAddressRecipe? = null,
    override val moduleSpecAddressRecipe: RootModuleSpecFieldAddressRecipe? = null,
    val sourceEncoding: RootInt32SourceEncoding = RootInt32SourceEncoding.INT32,
) : RootReadOnlyFieldDefinition<Int> {
    override val byteCount: Int = Int.SIZE_BYTES

    override fun decode(valueBits: Long): Int = when (sourceEncoding) {
        RootInt32SourceEncoding.INT32 -> valueBits.toInt()
        RootInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO ->
            Float.fromBits(valueBits.toInt()).toInt()
    }
}

internal data class RootInt64FieldDefinition(
    override val id: RootReadOnlyFieldId,
    override val offsetEvidence: RootModuleOffsetEvidence,
    override val addressRecipe: RootResolverAddressRecipe? = null,
    override val moduleSpecAddressRecipe: RootModuleSpecFieldAddressRecipe? = null,
) : RootReadOnlyFieldDefinition<Long> {
    override val byteCount: Int = Long.SIZE_BYTES

    override fun decode(valueBits: Long): Long = valueBits
}

internal data class RootReadOnlyFieldProfileCandidate(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val packageNames: Set<String>,
    val moduleEvidence: RootModuleIdentityEvidence,
    val fields: List<RootReadOnlyFieldDefinition<*>>,
) {
    val isEvidenceComplete: Boolean
        get() = profileId.isNotBlank() &&
            schemaVersion > 0 &&
            targetVersion.isNotBlank() &&
            packageNames.isNotEmpty() &&
            packageNames.all(RootProtocol::isValidPackageName) &&
            moduleEvidence.isComplete &&
            fields.isNotEmpty() &&
            fields.map(RootReadOnlyFieldDefinition<*>::id).distinct().size == fields.size &&
            fields.all { definition ->
                listOf(
                    definition.offsetEvidence.isComplete,
                    definition.addressRecipe?.isStructurallyValid == true,
                    definition.moduleSpecAddressRecipe?.let { recipe ->
                        recipe.isStructurallyValid &&
                            recipe.anchorProfile.moduleName.trim() ==
                            moduleEvidence.moduleName?.trim() &&
                            recipe.anchorProfile.normalizedSha256 ==
                            moduleEvidence.normalizedSha256 &&
                            recipe.anchorProfile.targetVersion == targetVersion
                    } == true,
                ).count { it } == 1
            }
}

internal data class RootNativeModuleIdentity(
    val name: String,
    val path: String,
    val loadBase: Long,
    val mappedBytes: Long,
    val memoryElf: Boolean,
    val sha256: String?,
)

internal data class ResolvedRootReadOnlyField(
    val definition: RootReadOnlyFieldDefinition<*>,
    val address: Long? = null,
)

internal data class ResolvedRootReadOnlyFieldProfile(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val moduleName: String,
    val moduleSha256: String,
    val fields: Map<RootReadOnlyFieldId, ResolvedRootReadOnlyField>,
)

enum class RootReadOnlyFieldProfileStatus {
    IDLE,
    NO_PROFILE,
    INCOMPLETE_EVIDENCE,
    MODULE_NOT_FOUND,
    MODULE_AMBIGUOUS,
    FINGERPRINT_UNAVAILABLE,
    FINGERPRINT_MISMATCH,
    OFFSET_OUT_OF_RANGE,
    READY;

    companion object {
        fun fromWireValue(value: String): RootReadOnlyFieldProfileStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal data class RootReadOnlyFieldProfileState(
    val status: RootReadOnlyFieldProfileStatus = RootReadOnlyFieldProfileStatus.IDLE,
    val profileId: String = "",
    val targetVersion: String = "",
    val fieldIds: Set<RootReadOnlyFieldId> = emptySet(),
    val message: String = "",
) {
    val isReady: Boolean
        get() = status == RootReadOnlyFieldProfileStatus.READY && fieldIds.isNotEmpty()

    val summary: String
        get() = when (status) {
            RootReadOnlyFieldProfileStatus.IDLE -> "尚未解析"
            RootReadOnlyFieldProfileStatus.NO_PROFILE -> "没有适用档案"
            RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE -> "静态证据未闭合"
            RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND -> "目标模块未就绪"
            RootReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS -> "目标模块身份不唯一"
            RootReadOnlyFieldProfileStatus.FINGERPRINT_UNAVAILABLE -> "模块指纹不可用"
            RootReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH -> "模块版本不匹配"
            RootReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE -> "字段偏移越界"
            RootReadOnlyFieldProfileStatus.READY -> "${fieldIds.size} 个字段已验证"
        }
}

internal data class RootReadOnlyFieldProfileResolution(
    val state: RootReadOnlyFieldProfileState = RootReadOnlyFieldProfileState(),
    val profile: ResolvedRootReadOnlyFieldProfile? = null,
)

enum class RootReadOnlyFieldReadStatus {
    IDLE,
    OK,
    SESSION_CLOSED,
    TARGET_CHANGED,
    PROFILE_NOT_READY,
    FEATURE_DISABLED,
    FIELD_NOT_AVAILABLE,
    READ_FAILED;

    companion object {
        fun fromWireValue(value: String): RootReadOnlyFieldReadStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal sealed interface RootReadOnlyFieldValue {
    val id: RootReadOnlyFieldId
}

internal data class RootInt32FieldValue(
    override val id: RootReadOnlyFieldId,
    val value: Int,
) : RootReadOnlyFieldValue

internal data class RootInt64FieldValue(
    override val id: RootReadOnlyFieldId,
    val value: Long,
) : RootReadOnlyFieldValue

internal data class RootReadOnlyFieldReadResult(
    val status: RootReadOnlyFieldReadStatus,
    val value: RootReadOnlyFieldValue? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootReadOnlyFieldReadStatus.OK && value != null
}

internal data class RootReadOnlyFieldBatch(
    val profileState: RootReadOnlyFieldProfileState,
    val lifeState: RootReadOnlyFieldReadResult,
    val killCount: RootReadOnlyFieldReadResult,
    val dataLongSelector1: RootReadOnlyFieldReadResult,
)

internal class RootReadOnlyFieldProfileResolver(
    private val candidates: List<RootReadOnlyFieldProfileCandidate>,
) {
    fun resolve(
        packageName: String,
        modules: List<RootNativeModuleIdentity>,
    ): RootReadOnlyFieldProfileResolution {
        val applicable = candidates.filter { packageName in it.packageNames }
        if (applicable.isEmpty()) {
            return failure(RootReadOnlyFieldProfileStatus.NO_PROFILE, message = "No profile for package")
        }

        val complete = applicable.filter(RootReadOnlyFieldProfileCandidate::isEvidenceComplete)
        if (complete.isEmpty()) {
            val candidate = applicable.first()
            return failure(
                status = RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                candidate = candidate,
                message = "Module identity or field offset evidence is incomplete",
            )
        }

        var fallback: RootReadOnlyFieldProfileResolution? = null
        complete.forEach { candidate ->
            val moduleName = requireNotNull(candidate.moduleEvidence.moduleName).trim()
            val expectedSha256 = requireNotNull(candidate.moduleEvidence.normalizedSha256)
            val namedModules = modules.filter { module -> module.name == moduleName }
            if (namedModules.isEmpty()) {
                fallback = fallback ?: failure(
                    RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
                    candidate,
                    "Required module was not found",
                )
                return@forEach
            }
            if (namedModules.size != 1) {
                fallback = fallback ?: failure(
                    RootReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS,
                    candidate,
                    "Required module name is not unique",
                )
                return@forEach
            }

            val eligibleModules = namedModules.filter { module ->
                module.memoryElf &&
                    module.loadBase > 0L &&
                    module.mappedBytes > 0L
            }
            if (eligibleModules.size != 1) {
                fallback = fallback ?: failure(
                    RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
                    candidate,
                    "Required module has no valid ELF mapping",
                )
                return@forEach
            }

            val fingerprintedModules = eligibleModules.mapNotNull { module ->
                module.sha256?.trim()?.lowercase()?.takeIf(::isSha256)?.let { module to it }
            }
            if (fingerprintedModules.isEmpty()) {
                fallback = fallback ?: failure(
                    RootReadOnlyFieldProfileStatus.FINGERPRINT_UNAVAILABLE,
                    candidate,
                    "Required module has no SHA-256 fingerprint",
                )
                return@forEach
            }

            val matchedModules = fingerprintedModules
                .filter { (_, fingerprint) -> fingerprint == expectedSha256 }
                .map { (module, _) -> module }
            if (matchedModules.isEmpty()) {
                fallback = fallback ?: failure(
                    RootReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH,
                    candidate,
                    "Required module fingerprint does not match",
                )
                return@forEach
            }
            if (matchedModules.size != 1) {
                fallback = fallback ?: failure(
                    RootReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS,
                    candidate,
                    "Required module fingerprint is not unique",
                )
                return@forEach
            }
            val matchedModule = matchedModules.single()

            val resolvedFields = linkedMapOf<RootReadOnlyFieldId, ResolvedRootReadOnlyField>()
            for (definition in candidate.fields) {
                if (!definition.offsetEvidence.isComplete) {
                    if (
                        definition.addressRecipe?.isStructurallyValid == true ||
                        definition.moduleSpecAddressRecipe?.isStructurallyValid == true
                    ) {
                        resolvedFields[definition.id] = ResolvedRootReadOnlyField(definition)
                        continue
                    }
                    resolvedFields.clear()
                    break
                }
                val offset = requireNotNull(definition.offsetEvidence.moduleOffset)
                val endOffset = runCatching { Math.addExact(offset, definition.byteCount.toLong()) }
                    .getOrNull()
                val address = runCatching { Math.addExact(matchedModule.loadBase, offset) }
                    .getOrNull()
                if (
                    endOffset == null ||
                    endOffset > matchedModule.mappedBytes ||
                    address == null ||
                    address <= 0L
                ) {
                    fallback = failure(
                        RootReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE,
                        candidate,
                        "A verified field offset falls outside the matched module",
                    )
                    resolvedFields.clear()
                    break
                }
                resolvedFields[definition.id] = ResolvedRootReadOnlyField(definition, address)
            }
            if (resolvedFields.size != candidate.fields.size) return@forEach

            val profile = ResolvedRootReadOnlyFieldProfile(
                profileId = candidate.profileId,
                schemaVersion = candidate.schemaVersion,
                targetVersion = candidate.targetVersion,
                moduleName = moduleName,
                moduleSha256 = expectedSha256,
                fields = resolvedFields,
            )
            return RootReadOnlyFieldProfileResolution(
                state = RootReadOnlyFieldProfileState(
                    status = RootReadOnlyFieldProfileStatus.READY,
                    profileId = candidate.profileId,
                    targetVersion = candidate.targetVersion,
                    fieldIds = resolvedFields.keys,
                ),
                profile = profile,
            )
        }

        return fallback ?: failure(RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND)
    }

    private fun failure(
        status: RootReadOnlyFieldProfileStatus,
        candidate: RootReadOnlyFieldProfileCandidate? = null,
        message: String = "",
    ): RootReadOnlyFieldProfileResolution = RootReadOnlyFieldProfileResolution(
        state = RootReadOnlyFieldProfileState(
            status = status,
            profileId = candidate?.profileId.orEmpty(),
            targetVersion = candidate?.targetVersion.orEmpty(),
            message = message,
        ),
    )
}

internal object RootReadOnlyFieldProfileCatalog {
    private const val EVIDENCE_ARTIFACT =
        "reports/native_tools/evidence/libclient_getdata_fields.md"

    val candidates: List<RootReadOnlyFieldProfileCandidate> = listOf(
        RootReadOnlyFieldProfileCandidate(
            profileId = "miniworld-1.58.2-arm64-draft-v2",
            schemaVersion = 2,
            targetVersion = "1.58.2",
            packageNames = RootTargetCatalog.entries
                .mapTo(linkedSetOf(), RootTargetChannel::packageName),
            moduleEvidence = RootModuleIdentityEvidence(
                moduleName = RootGameAppArtifact1582.MODULE_NAME,
                expectedSha256 = RootGameAppArtifact1582.SHA256,
                sourceArtifact = RootGameAppArtifact1582.SOURCE_ARTIFACT,
                sourceReference =
                    "${RootGameAppArtifact1582.SOURCE_REFERENCE}; live maps and recovered field values remain to be validated",
            ),
            fields = listOf(
                RootInt32FieldDefinition(
                    id = RootReadOnlyFieldId.LIFE_STATE,
                    offsetEvidence = unresolvedOffset(
                        "GetData selector 2 reads the current-version AttrHPComponent float32 " +
                            "current-HP field and truncates it toward zero to signed int32",
                    ),
                    addressRecipe = RootRecoveredAddressRecipes.lifeState,
                    sourceEncoding = RootInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO,
                ),
                RootInt32FieldDefinition(
                    id = RootReadOnlyFieldId.KILL_COUNT,
                    offsetEvidence = unresolvedOffset(
                        "GetData selector 3 is read_u32(resolve_module_spec(\"liblibGameApp.so:bss\") " +
                            "+ 0x1cc78); module identity is pinned and live field validation remains",
                    ),
                    moduleSpecAddressRecipe = RootModuleSpecFieldAddressRecipe(
                        anchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
                        finalOffset = RootGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
                        sourceArtifact = RootGameAppArtifact1582.SOURCE_ARTIFACT,
                        sourceReference =
                            "Verified anonymous-BSS anchor plus recovered GetData selector 3 offset",
                    ),
                ),
                RootInt64FieldDefinition(
                    id = RootReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = unresolvedOffset(
                        "GetDataLong selector 1 is read_u64(*(GameApp BSS anchor+0x5b860)+0x548); " +
                            "the exact module identity is pinned and the pointer64 path was stable on device",
                    ),
                    addressRecipe = RootRecoveredAddressRecipes.dataLongSelector1,
                ),
            ),
        ),
    )

    val fingerprintModuleNames: Set<String> = candidates
        .asSequence()
        .mapNotNull { it.moduleEvidence.moduleName?.trim() }
        .filter(::isModuleName)
        .toSet()

    val resolver: RootReadOnlyFieldProfileResolver = RootReadOnlyFieldProfileResolver(candidates)

    private fun unresolvedOffset(reference: String): RootModuleOffsetEvidence =
        RootModuleOffsetEvidence(
            moduleOffset = null,
            sourceArtifact = EVIDENCE_ARTIFACT,
            sourceReference = reference,
        )
}

private fun isModuleName(value: String): Boolean =
    value.length in 1..192 &&
        '/' !in value &&
        '\\' !in value &&
        value.endsWith(".so")

private fun isSha256(value: String): Boolean =
    value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }
