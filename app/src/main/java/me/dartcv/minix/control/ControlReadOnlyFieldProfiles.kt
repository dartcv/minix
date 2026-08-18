package me.dartcv.minix.control

internal enum class ControlReadOnlyFieldId(
    val wireId: String,
    val label: String,
    val sourceMethod: String,
    val selector: Int,
) {
    LIFE_STATE("get_data_2", "生命/存活状态", "GetData", 2),
    KILL_COUNT("get_data_3", "击杀数", "GetData", 3),
    DATA_LONG_SELECTOR_1("get_data_long_1", "GetDataLong(1)", "GetDataLong", 1),
}

internal data class ControlModuleIdentityEvidence(
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

internal data class ControlModuleOffsetEvidence(
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

internal sealed interface ControlReadOnlyFieldDefinition<T> {
    val id: ControlReadOnlyFieldId
    val offsetEvidence: ControlModuleOffsetEvidence
    val addressRecipe: ControlResolverAddressRecipe?
    val moduleSpecAddressRecipe: ControlModuleSpecFieldAddressRecipe?
    val byteCount: Int

    fun decode(valueBits: Long): T
}

internal enum class ControlInt32SourceEncoding {
    INT32,
    FLOAT32_TRUNCATE_TOWARD_ZERO,
}

internal data class ControlInt32FieldDefinition(
    override val id: ControlReadOnlyFieldId,
    override val offsetEvidence: ControlModuleOffsetEvidence,
    override val addressRecipe: ControlResolverAddressRecipe? = null,
    override val moduleSpecAddressRecipe: ControlModuleSpecFieldAddressRecipe? = null,
    val sourceEncoding: ControlInt32SourceEncoding = ControlInt32SourceEncoding.INT32,
) : ControlReadOnlyFieldDefinition<Int> {
    override val byteCount: Int = Int.SIZE_BYTES

    override fun decode(valueBits: Long): Int = when (sourceEncoding) {
        ControlInt32SourceEncoding.INT32 -> valueBits.toInt()
        ControlInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO ->
            Float.fromBits(valueBits.toInt()).toInt()
    }
}

internal data class ControlInt64FieldDefinition(
    override val id: ControlReadOnlyFieldId,
    override val offsetEvidence: ControlModuleOffsetEvidence,
    override val addressRecipe: ControlResolverAddressRecipe? = null,
    override val moduleSpecAddressRecipe: ControlModuleSpecFieldAddressRecipe? = null,
) : ControlReadOnlyFieldDefinition<Long> {
    override val byteCount: Int = Long.SIZE_BYTES

    override fun decode(valueBits: Long): Long = valueBits
}

internal data class ControlReadOnlyFieldProfileCandidate(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val packageNames: Set<String>,
    val moduleEvidence: ControlModuleIdentityEvidence,
    val fields: List<ControlReadOnlyFieldDefinition<*>>,
) {
    val isEvidenceComplete: Boolean
        get() = profileId.isNotBlank() &&
            schemaVersion > 0 &&
            targetVersion.isNotBlank() &&
            packageNames.isNotEmpty() &&
            packageNames.all(ControlProtocol::isValidPackageName) &&
            moduleEvidence.isComplete &&
            fields.isNotEmpty() &&
            fields.map(ControlReadOnlyFieldDefinition<*>::id).distinct().size == fields.size &&
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

internal data class ControlNativeModuleIdentity(
    val name: String,
    val path: String,
    val loadBase: Long,
    val mappedBytes: Long,
    val memoryElf: Boolean,
    val sha256: String?,
)

internal data class ResolvedControlReadOnlyField(
    val definition: ControlReadOnlyFieldDefinition<*>,
    val address: Long? = null,
)

internal data class ResolvedControlReadOnlyFieldProfile(
    val profileId: String,
    val schemaVersion: Int,
    val targetVersion: String,
    val moduleName: String,
    val moduleSha256: String,
    val fields: Map<ControlReadOnlyFieldId, ResolvedControlReadOnlyField>,
)

enum class ControlReadOnlyFieldProfileStatus {
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
        fun fromWireValue(value: String): ControlReadOnlyFieldProfileStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal data class ControlReadOnlyFieldProfileState(
    val status: ControlReadOnlyFieldProfileStatus = ControlReadOnlyFieldProfileStatus.IDLE,
    val profileId: String = "",
    val targetVersion: String = "",
    val fieldIds: Set<ControlReadOnlyFieldId> = emptySet(),
    val message: String = "",
) {
    val isReady: Boolean
        get() = status == ControlReadOnlyFieldProfileStatus.READY && fieldIds.isNotEmpty()

    val summary: String
        get() = when (status) {
            ControlReadOnlyFieldProfileStatus.IDLE -> "尚未解析"
            ControlReadOnlyFieldProfileStatus.NO_PROFILE -> "没有适用档案"
            ControlReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE -> "静态证据未闭合"
            ControlReadOnlyFieldProfileStatus.MODULE_NOT_FOUND -> "目标模块未就绪"
            ControlReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS -> "目标模块身份不唯一"
            ControlReadOnlyFieldProfileStatus.FINGERPRINT_UNAVAILABLE -> "模块指纹不可用"
            ControlReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH -> "模块版本不匹配"
            ControlReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE -> "字段偏移越界"
            ControlReadOnlyFieldProfileStatus.READY -> "${fieldIds.size} 个字段已验证"
        }
}

internal data class ControlReadOnlyFieldProfileResolution(
    val state: ControlReadOnlyFieldProfileState = ControlReadOnlyFieldProfileState(),
    val profile: ResolvedControlReadOnlyFieldProfile? = null,
)

enum class ControlReadOnlyFieldReadStatus {
    IDLE,
    OK,
    SESSION_CLOSED,
    TARGET_CHANGED,
    PROFILE_NOT_READY,
    FEATURE_DISABLED,
    FIELD_NOT_AVAILABLE,
    READ_FAILED;

    companion object {
        fun fromWireValue(value: String): ControlReadOnlyFieldReadStatus? =
            entries.firstOrNull { it.name == value }
    }
}

internal sealed interface ControlReadOnlyFieldValue {
    val id: ControlReadOnlyFieldId
}

internal data class ControlInt32FieldValue(
    override val id: ControlReadOnlyFieldId,
    val value: Int,
) : ControlReadOnlyFieldValue

internal data class ControlInt64FieldValue(
    override val id: ControlReadOnlyFieldId,
    val value: Long,
) : ControlReadOnlyFieldValue

internal data class ControlReadOnlyFieldReadResult(
    val status: ControlReadOnlyFieldReadStatus,
    val value: ControlReadOnlyFieldValue? = null,
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlReadOnlyFieldReadStatus.OK && value != null
}

internal data class ControlReadOnlyFieldBatch(
    val profileState: ControlReadOnlyFieldProfileState,
    val lifeState: ControlReadOnlyFieldReadResult,
    val killCount: ControlReadOnlyFieldReadResult,
    val dataLongSelector1: ControlReadOnlyFieldReadResult,
)

internal class ControlReadOnlyFieldProfileResolver(
    private val candidates: List<ControlReadOnlyFieldProfileCandidate>,
) {
    fun resolve(
        packageName: String,
        modules: List<ControlNativeModuleIdentity>,
    ): ControlReadOnlyFieldProfileResolution {
        val applicable = candidates.filter { packageName in it.packageNames }
        if (applicable.isEmpty()) {
            return failure(ControlReadOnlyFieldProfileStatus.NO_PROFILE, message = "No profile for package")
        }

        val complete = applicable.filter(ControlReadOnlyFieldProfileCandidate::isEvidenceComplete)
        if (complete.isEmpty()) {
            val candidate = applicable.first()
            return failure(
                status = ControlReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                candidate = candidate,
                message = "Module identity or field offset evidence is incomplete",
            )
        }

        var fallback: ControlReadOnlyFieldProfileResolution? = null
        complete.forEach { candidate ->
            val moduleName = requireNotNull(candidate.moduleEvidence.moduleName).trim()
            val expectedSha256 = requireNotNull(candidate.moduleEvidence.normalizedSha256)
            val namedModules = modules.filter { module -> module.name == moduleName }
            if (namedModules.isEmpty()) {
                fallback = fallback ?: failure(
                    ControlReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
                    candidate,
                    "Required module was not found",
                )
                return@forEach
            }
            if (namedModules.size != 1) {
                fallback = fallback ?: failure(
                    ControlReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS,
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
                    ControlReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
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
                    ControlReadOnlyFieldProfileStatus.FINGERPRINT_UNAVAILABLE,
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
                    ControlReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH,
                    candidate,
                    "Required module fingerprint does not match",
                )
                return@forEach
            }
            if (matchedModules.size != 1) {
                fallback = fallback ?: failure(
                    ControlReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS,
                    candidate,
                    "Required module fingerprint is not unique",
                )
                return@forEach
            }
            val matchedModule = matchedModules.single()

            val resolvedFields = linkedMapOf<ControlReadOnlyFieldId, ResolvedControlReadOnlyField>()
            for (definition in candidate.fields) {
                if (!definition.offsetEvidence.isComplete) {
                    if (
                        definition.addressRecipe?.isStructurallyValid == true ||
                        definition.moduleSpecAddressRecipe?.isStructurallyValid == true
                    ) {
                        resolvedFields[definition.id] = ResolvedControlReadOnlyField(definition)
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
                        ControlReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE,
                        candidate,
                        "A verified field offset falls outside the matched module",
                    )
                    resolvedFields.clear()
                    break
                }
                resolvedFields[definition.id] = ResolvedControlReadOnlyField(definition, address)
            }
            if (resolvedFields.size != candidate.fields.size) return@forEach

            val profile = ResolvedControlReadOnlyFieldProfile(
                profileId = candidate.profileId,
                schemaVersion = candidate.schemaVersion,
                targetVersion = candidate.targetVersion,
                moduleName = moduleName,
                moduleSha256 = expectedSha256,
                fields = resolvedFields,
            )
            return ControlReadOnlyFieldProfileResolution(
                state = ControlReadOnlyFieldProfileState(
                    status = ControlReadOnlyFieldProfileStatus.READY,
                    profileId = candidate.profileId,
                    targetVersion = candidate.targetVersion,
                    fieldIds = resolvedFields.keys,
                ),
                profile = profile,
            )
        }

        return fallback ?: failure(ControlReadOnlyFieldProfileStatus.MODULE_NOT_FOUND)
    }

    private fun failure(
        status: ControlReadOnlyFieldProfileStatus,
        candidate: ControlReadOnlyFieldProfileCandidate? = null,
        message: String = "",
    ): ControlReadOnlyFieldProfileResolution = ControlReadOnlyFieldProfileResolution(
        state = ControlReadOnlyFieldProfileState(
            status = status,
            profileId = candidate?.profileId.orEmpty(),
            targetVersion = candidate?.targetVersion.orEmpty(),
            message = message,
        ),
    )
}

internal object ControlReadOnlyFieldProfileCatalog {
    private const val EVIDENCE_ARTIFACT =
        "reports/native_tools/evidence/libclient_getdata_fields.md"

    val candidates: List<ControlReadOnlyFieldProfileCandidate> = listOf(
        ControlReadOnlyFieldProfileCandidate(
            profileId = "miniworld-1.58.2-arm64-draft-v2",
            schemaVersion = 2,
            targetVersion = "1.58.2",
            packageNames = ControlTargetCatalog.entries
                .mapTo(linkedSetOf(), ControlTargetChannel::packageName),
            moduleEvidence = ControlModuleIdentityEvidence(
                moduleName = ControlGameAppArtifact1582.MODULE_NAME,
                expectedSha256 = ControlGameAppArtifact1582.SHA256,
                sourceArtifact = ControlGameAppArtifact1582.SOURCE_ARTIFACT,
                sourceReference =
                    "${ControlGameAppArtifact1582.SOURCE_REFERENCE}; live maps and recovered field values remain to be validated",
            ),
            fields = listOf(
                ControlInt32FieldDefinition(
                    id = ControlReadOnlyFieldId.LIFE_STATE,
                    offsetEvidence = unresolvedOffset(
                        "GetData selector 2 reads the current-version AttrHPComponent float32 " +
                            "current-HP field and truncates it toward zero to signed int32",
                    ),
                    addressRecipe = ControlRecoveredAddressRecipes.lifeState,
                    sourceEncoding = ControlInt32SourceEncoding.FLOAT32_TRUNCATE_TOWARD_ZERO,
                ),
                ControlInt32FieldDefinition(
                    id = ControlReadOnlyFieldId.KILL_COUNT,
                    offsetEvidence = unresolvedOffset(
                        "GetData selector 3 is read_u32(resolve_module_spec(\"liblibGameApp.so:bss\") " +
                            "+ 0x1cc78); module identity is pinned and live field validation remains",
                    ),
                    moduleSpecAddressRecipe = ControlModuleSpecFieldAddressRecipe(
                        anchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582,
                        finalOffset = ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
                        sourceArtifact = ControlGameAppArtifact1582.SOURCE_ARTIFACT,
                        sourceReference =
                            "Verified anonymous-BSS anchor plus recovered GetData selector 3 offset",
                    ),
                ),
                ControlInt64FieldDefinition(
                    id = ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = unresolvedOffset(
                        "GetDataLong selector 1 is read_u64(*(GameApp BSS anchor+0x5b860)+0x548); " +
                            "the exact module identity is pinned and the pointer64 path was stable on device",
                    ),
                    addressRecipe = ControlRecoveredAddressRecipes.dataLongSelector1,
                ),
            ),
        ),
    )

    val fingerprintModuleNames: Set<String> = candidates
        .asSequence()
        .mapNotNull { it.moduleEvidence.moduleName?.trim() }
        .filter(::isModuleName)
        .toSet()

    val resolver: ControlReadOnlyFieldProfileResolver = ControlReadOnlyFieldProfileResolver(candidates)

    private fun unresolvedOffset(reference: String): ControlModuleOffsetEvidence =
        ControlModuleOffsetEvidence(
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
