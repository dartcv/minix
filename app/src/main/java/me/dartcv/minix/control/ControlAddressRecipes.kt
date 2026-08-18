package me.dartcv.minix.control

internal enum class ControlAddressDereferenceMode {
    LEGACY_MASKED_H,
    POINTER64,
}

internal data class ControlResolverAddressRecipe(
    val initialSeed: Long,
    val intermediateOffsets: List<Long>,
    val finalOffset: Long,
    val sourceArtifact: String,
    val sourceReference: String,
    val seedAnchorProfile: ControlSearchIdProfile? = null,
    val dereferenceMode: ControlAddressDereferenceMode =
        ControlAddressDereferenceMode.LEGACY_MASKED_H,
) {
    val isStructurallyValid: Boolean
        get() = initialSeed in 1L..UINT32_MAX &&
            intermediateOffsets.size <= MAX_RESOLVER_STEPS &&
            intermediateOffsets.all { it in 0L..MAX_RECIPE_OFFSET } &&
            finalOffset in 0L..MAX_RECIPE_OFFSET &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank() &&
            (seedAnchorProfile == null || seedAnchorProfile.isReadyForScan)

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
        const val MAX_RECIPE_OFFSET = 1L shl 32
        const val MAX_RESOLVER_STEPS = 16
    }
}

internal enum class ControlAddressRecipeStatus {
    RESOLVED,
    INVALID_PID,
    INVALID_RECIPE,
    RESOLVER_FAILED,
    ZERO_ADDRESS,
    ADDRESS_OVERFLOW,
    TARGET_CHANGED,
}

internal data class ControlAddressRecipeResult(
    val status: ControlAddressRecipeStatus,
    val address: Long? = null,
    val resolvedValues: List<Long> = emptyList(),
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == ControlAddressRecipeStatus.RESOLVED && address != null
}

internal fun interface ControlAddressRecipeResolver {
    fun resolve(pid: Int, recipe: ControlResolverAddressRecipe): ControlAddressRecipeResult
}

internal object NoControlAddressRecipeResolver : ControlAddressRecipeResolver {
    override fun resolve(pid: Int, recipe: ControlResolverAddressRecipe): ControlAddressRecipeResult =
        ControlAddressRecipeResult(
            status = ControlAddressRecipeStatus.RESOLVER_FAILED,
            message = "Address recipe resolver is unavailable",
        )
}

internal class ControlResolverAddressRecipeEvaluator(
    private val seedResolver: ControlSeedAddressResolver,
    private val anchorResolver: ControlSearchIdAnchorResolver = NoControlSearchIdAnchorResolver,
    private val scalarReader: TargetScalarReader = NoTargetScalarReader,
) : ControlAddressRecipeResolver {
    override fun resolve(pid: Int, recipe: ControlResolverAddressRecipe): ControlAddressRecipeResult {
        if (pid <= 0) return failure(ControlAddressRecipeStatus.INVALID_PID, "PID must be positive")
        if (!recipe.isStructurallyValid) {
            return failure(ControlAddressRecipeStatus.INVALID_RECIPE, "Address recipe is invalid")
        }

        val values = mutableListOf<Long>()
        var expectedStartTime = ""
        var seed = recipe.initialSeed
        recipe.seedAnchorProfile?.let { profile ->
            // Bound the selected module-spec mapping against the actual first
            // resolver offset rather than SearchID's unrelated anchor offset.
            val boundedProfile = profile.copy(
                recipe = profile.recipe.copy(anchorOffset = recipe.initialSeed),
            )
            val anchorResult = runCatching {
                anchorResolver.resolve(pid, boundedProfile)
            }.getOrElse { error ->
                return failure(
                    ControlAddressRecipeStatus.RESOLVER_FAILED,
                    error.message ?: "Seed anchor resolver failed",
                )
            }
            if (!anchorResult.isSuccess || anchorResult.anchor == null) {
                val status = when (anchorResult.status) {
                    ControlSearchIdAnchorStatus.INVALID_PID -> ControlAddressRecipeStatus.INVALID_PID
                    ControlSearchIdAnchorStatus.TARGET_CHANGED -> ControlAddressRecipeStatus.TARGET_CHANGED
                    else -> ControlAddressRecipeStatus.RESOLVER_FAILED
                }
                return failure(
                    status = status,
                    message = "Seed anchor failed: ${anchorResult.message.ifBlank { anchorResult.status.name }}",
                    processStartTimeTicks = anchorResult.processStartTimeTicks,
                )
            }
            val anchor = anchorResult.anchor
            val observedStartTime = anchorResult.processStartTimeTicks
                .ifBlank { anchor.processStartTimeTicks }
            if (
                observedStartTime.isBlank() ||
                anchor.processStartTimeTicks.isBlank() ||
                observedStartTime != anchor.processStartTimeTicks
            ) {
                return failure(
                    ControlAddressRecipeStatus.TARGET_CHANGED,
                    "Seed anchor omitted or changed the target process identity",
                    processStartTimeTicks = observedStartTime,
                )
            }
            if (
                anchor.profileId != profile.profileId ||
                anchor.moduleName != profile.moduleName ||
                anchor.moduleSpec != profile.moduleSpec ||
                anchor.moduleSha256 != profile.normalizedSha256 ||
                anchor.anchorKind != profile.anchorKind
            ) {
                return failure(
                    ControlAddressRecipeStatus.RESOLVER_FAILED,
                    "Seed anchor metadata does not match the address recipe",
                    processStartTimeTicks = observedStartTime,
                )
            }
            seed = checkedAdd(anchor.baseAddress, recipe.initialSeed)
                ?: return failure(
                    ControlAddressRecipeStatus.ADDRESS_OVERFLOW,
                    "Initial anchored resolver seed overflowed",
                    processStartTimeTicks = observedStartTime,
                )
            expectedStartTime = observedStartTime
        }
        repeat(recipe.intermediateOffsets.size + 1) { step ->
            val result = when (recipe.dereferenceMode) {
                ControlAddressDereferenceMode.LEGACY_MASKED_H -> seedResolver.resolve(pid, seed)
                ControlAddressDereferenceMode.POINTER64 -> resolvePointer64(pid, seed)
            }
            if (!result.isSuccess) {
                return failure(
                    status = ControlAddressRecipeStatus.RESOLVER_FAILED,
                    message = "${recipe.dereferenceMode.label} step $step failed: " +
                        result.message.ifBlank { result.status.name },
                    resolvedValues = values,
                    processStartTimeTicks = result.processStartTimeTicks,
                )
            }
            val value = result.resolvedAddress
                ?: return failure(
                    ControlAddressRecipeStatus.RESOLVER_FAILED,
                    "Resolver step $step omitted its value",
                    values,
                    result.processStartTimeTicks,
                )
            if (value == 0L) {
                return failure(
                    ControlAddressRecipeStatus.ZERO_ADDRESS,
                    "${recipe.dereferenceMode.label} step $step returned zero; " +
                        "the runtime object is not initialized",
                    values,
                    result.processStartTimeTicks,
                )
            }
            val startTime = result.processStartTimeTicks
            if (expectedStartTime.isNotBlank() && startTime.isBlank()) {
                return failure(
                    ControlAddressRecipeStatus.TARGET_CHANGED,
                    "Resolver step $step omitted the anchored process identity",
                    values,
                    expectedStartTime,
                )
            }
            if (startTime.isNotBlank()) {
                if (expectedStartTime.isBlank()) {
                    expectedStartTime = startTime
                } else if (startTime != expectedStartTime) {
                    return failure(
                        ControlAddressRecipeStatus.TARGET_CHANGED,
                        "PID identity changed during address resolution",
                        values,
                        startTime,
                    )
                }
            }
            values += value

            if (step < recipe.intermediateOffsets.size) {
                seed = checkedAdd(value, recipe.intermediateOffsets[step])
                    ?: return failure(
                        ControlAddressRecipeStatus.ADDRESS_OVERFLOW,
                        "Resolver seed overflowed after step $step",
                        values,
                        expectedStartTime,
                    )
            }
        }

        val address = checkedAdd(values.last(), recipe.finalOffset)
            ?: return failure(
                ControlAddressRecipeStatus.ADDRESS_OVERFLOW,
                "Final field address overflowed",
                values,
                expectedStartTime,
            )
        if (address == 0L) {
            return failure(
                ControlAddressRecipeStatus.ZERO_ADDRESS,
                "Final field address is zero",
                values,
                expectedStartTime,
            )
        }
        return ControlAddressRecipeResult(
            status = ControlAddressRecipeStatus.RESOLVED,
            address = address,
            resolvedValues = values,
            processStartTimeTicks = expectedStartTime,
        )
    }

    private fun resolvePointer64(pid: Int, address: Long): ControlAddressResolveResult {
        if (address <= 0L) {
            return ControlAddressResolveResult(
                status = ControlAddressResolveStatus.INVALID_SEED,
                seed = address,
                message = "Pointer address must be positive",
            )
        }
        val scalar = scalarReader.readInt64(pid, address)
        val valueBits = scalar.valueBits
        if (!scalar.isSuccess || valueBits == null) {
            return ControlAddressResolveResult(
                status = ControlAddressResolveStatus.SCALAR_READ_FAILED,
                seed = address,
                processStartTimeTicks = scalar.processStartTimeTicks,
                message = scalar.message.ifBlank { "Pointer64 read failed" },
            )
        }
        return ControlAddressResolveResult(
            status = ControlAddressResolveStatus.RESOLVED,
            seed = address,
            resolvedAddress = valueBits,
            processStartTimeTicks = scalar.processStartTimeTicks,
        )
    }

    private fun checkedAdd(value: Long, offset: Long): Long? =
        runCatching { Math.addExact(value, offset) }
            .getOrNull()
            ?.takeIf { it > 0L }

    private fun failure(
        status: ControlAddressRecipeStatus,
        message: String,
        resolvedValues: List<Long> = emptyList(),
        processStartTimeTicks: String = "",
    ): ControlAddressRecipeResult = ControlAddressRecipeResult(
        status = status,
        resolvedValues = resolvedValues.toList(),
        processStartTimeTicks = processStartTimeTicks,
        message = message,
    )
}

internal object JniControlAddressRecipeResolver {
    fun create(anchorResolver: ControlSearchIdAnchorResolver): ControlAddressRecipeResolver =
        ControlResolverAddressRecipeEvaluator(
            seedResolver = JniControlSeedAddressResolver.instance,
            anchorResolver = anchorResolver,
            scalarReader = JniTargetScalarReader,
        )
}

private val ControlAddressDereferenceMode.label: String
    get() = when (this) {
        ControlAddressDereferenceMode.LEGACY_MASKED_H -> "Legacy H resolver"
        ControlAddressDereferenceMode.POINTER64 -> "Pointer64 resolver"
    }

internal object ControlRecoveredAddressRecipes {
    val lifeState = ControlResolverAddressRecipe(
        initialSeed = ControlGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        intermediateOffsets = listOf(0x1a0L, 0x140L, 0x210L),
        finalOffset = 0x38L,
        sourceArtifact = "work/life-state-recovery-20260817/production-life-state-recipe.md",
        sourceReference =
            "Exact-SHA current-version chain: IPlayerControl* -> player+0x140 ClientActor -> " +
                "ClientActor+0x210 AttrHPComponent -> float32 current HP at +0x38",
        seedAnchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = ControlAddressDereferenceMode.POINTER64,
    )

    val dataLongSelector1 = ControlResolverAddressRecipe(
        initialSeed = 0x5b860L,
        intermediateOffsets = emptyList(),
        finalOffset = 0x548L,
        sourceArtifact = "artifacts/getdatalong_current_maps.txt",
        sourceReference =
            "GetDataLong(1): live verification reads one pointer64 from the exact-SHA GameApp " +
                "BSS anchor at +0x5b860, then reads the u64 field at pointer+0x548",
        seedAnchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = ControlAddressDereferenceMode.POINTER64,
    )
}
