package me.dartcv.minix.root

internal enum class RootAddressDereferenceMode {
    LEGACY_MASKED_H,
    POINTER64,
}

internal data class RootResolverAddressRecipe(
    val initialSeed: Long,
    val intermediateOffsets: List<Long>,
    val finalOffset: Long,
    val sourceArtifact: String,
    val sourceReference: String,
    val seedAnchorProfile: RootSearchIdProfile? = null,
    val dereferenceMode: RootAddressDereferenceMode =
        RootAddressDereferenceMode.LEGACY_MASKED_H,
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

internal enum class RootAddressRecipeStatus {
    RESOLVED,
    INVALID_PID,
    INVALID_RECIPE,
    RESOLVER_FAILED,
    ZERO_ADDRESS,
    ADDRESS_OVERFLOW,
    TARGET_CHANGED,
}

internal data class RootAddressRecipeResult(
    val status: RootAddressRecipeStatus,
    val address: Long? = null,
    val resolvedValues: List<Long> = emptyList(),
    val processStartTimeTicks: String = "",
    val message: String = "",
) {
    val isSuccess: Boolean
        get() = status == RootAddressRecipeStatus.RESOLVED && address != null
}

internal fun interface RootAddressRecipeResolver {
    fun resolve(pid: Int, recipe: RootResolverAddressRecipe): RootAddressRecipeResult
}

internal object NoRootAddressRecipeResolver : RootAddressRecipeResolver {
    override fun resolve(pid: Int, recipe: RootResolverAddressRecipe): RootAddressRecipeResult =
        RootAddressRecipeResult(
            status = RootAddressRecipeStatus.RESOLVER_FAILED,
            message = "Address recipe resolver is unavailable",
        )
}

internal class RootResolverAddressRecipeEvaluator(
    private val seedResolver: RootSeedAddressResolver,
    private val anchorResolver: RootSearchIdAnchorResolver = NoRootSearchIdAnchorResolver,
    private val scalarReader: TargetScalarReader = NoTargetScalarReader,
) : RootAddressRecipeResolver {
    override fun resolve(pid: Int, recipe: RootResolverAddressRecipe): RootAddressRecipeResult {
        if (pid <= 0) return failure(RootAddressRecipeStatus.INVALID_PID, "PID must be positive")
        if (!recipe.isStructurallyValid) {
            return failure(RootAddressRecipeStatus.INVALID_RECIPE, "Address recipe is invalid")
        }

        val values = mutableListOf<Long>()
        var expectedStartTime = ""
        var seed = recipe.initialSeed
        recipe.seedAnchorProfile?.let { profile ->
            // Bound the selected module-spec mapping against the actual first
            // resolver offset rather than SearchID's unrelated root offset.
            val boundedProfile = profile.copy(
                recipe = profile.recipe.copy(rootOffset = recipe.initialSeed),
            )
            val anchorResult = runCatching {
                anchorResolver.resolve(pid, boundedProfile)
            }.getOrElse { error ->
                return failure(
                    RootAddressRecipeStatus.RESOLVER_FAILED,
                    error.message ?: "Seed anchor resolver failed",
                )
            }
            if (!anchorResult.isSuccess || anchorResult.anchor == null) {
                val status = when (anchorResult.status) {
                    RootSearchIdAnchorStatus.INVALID_PID -> RootAddressRecipeStatus.INVALID_PID
                    RootSearchIdAnchorStatus.TARGET_CHANGED -> RootAddressRecipeStatus.TARGET_CHANGED
                    else -> RootAddressRecipeStatus.RESOLVER_FAILED
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
                    RootAddressRecipeStatus.TARGET_CHANGED,
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
                    RootAddressRecipeStatus.RESOLVER_FAILED,
                    "Seed anchor metadata does not match the address recipe",
                    processStartTimeTicks = observedStartTime,
                )
            }
            seed = checkedAdd(anchor.baseAddress, recipe.initialSeed)
                ?: return failure(
                    RootAddressRecipeStatus.ADDRESS_OVERFLOW,
                    "Initial anchored resolver seed overflowed",
                    processStartTimeTicks = observedStartTime,
                )
            expectedStartTime = observedStartTime
        }
        repeat(recipe.intermediateOffsets.size + 1) { step ->
            val result = when (recipe.dereferenceMode) {
                RootAddressDereferenceMode.LEGACY_MASKED_H -> seedResolver.resolve(pid, seed)
                RootAddressDereferenceMode.POINTER64 -> resolvePointer64(pid, seed)
            }
            if (!result.isSuccess) {
                return failure(
                    status = RootAddressRecipeStatus.RESOLVER_FAILED,
                    message = "${recipe.dereferenceMode.label} step $step failed: " +
                        result.message.ifBlank { result.status.name },
                    resolvedValues = values,
                    processStartTimeTicks = result.processStartTimeTicks,
                )
            }
            val value = result.resolvedAddress
                ?: return failure(
                    RootAddressRecipeStatus.RESOLVER_FAILED,
                    "Resolver step $step omitted its value",
                    values,
                    result.processStartTimeTicks,
                )
            if (value == 0L) {
                return failure(
                    RootAddressRecipeStatus.ZERO_ADDRESS,
                    "${recipe.dereferenceMode.label} step $step returned zero; " +
                        "the runtime object is not initialized",
                    values,
                    result.processStartTimeTicks,
                )
            }
            val startTime = result.processStartTimeTicks
            if (expectedStartTime.isNotBlank() && startTime.isBlank()) {
                return failure(
                    RootAddressRecipeStatus.TARGET_CHANGED,
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
                        RootAddressRecipeStatus.TARGET_CHANGED,
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
                        RootAddressRecipeStatus.ADDRESS_OVERFLOW,
                        "Resolver seed overflowed after step $step",
                        values,
                        expectedStartTime,
                    )
            }
        }

        val address = checkedAdd(values.last(), recipe.finalOffset)
            ?: return failure(
                RootAddressRecipeStatus.ADDRESS_OVERFLOW,
                "Final field address overflowed",
                values,
                expectedStartTime,
            )
        if (address == 0L) {
            return failure(
                RootAddressRecipeStatus.ZERO_ADDRESS,
                "Final field address is zero",
                values,
                expectedStartTime,
            )
        }
        return RootAddressRecipeResult(
            status = RootAddressRecipeStatus.RESOLVED,
            address = address,
            resolvedValues = values,
            processStartTimeTicks = expectedStartTime,
        )
    }

    private fun resolvePointer64(pid: Int, address: Long): RootAddressResolveResult {
        if (address <= 0L) {
            return RootAddressResolveResult(
                status = RootAddressResolveStatus.INVALID_SEED,
                seed = address,
                message = "Pointer address must be positive",
            )
        }
        val scalar = scalarReader.readInt64(pid, address)
        val valueBits = scalar.valueBits
        if (!scalar.isSuccess || valueBits == null) {
            return RootAddressResolveResult(
                status = RootAddressResolveStatus.SCALAR_READ_FAILED,
                seed = address,
                processStartTimeTicks = scalar.processStartTimeTicks,
                message = scalar.message.ifBlank { "Pointer64 read failed" },
            )
        }
        return RootAddressResolveResult(
            status = RootAddressResolveStatus.RESOLVED,
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
        status: RootAddressRecipeStatus,
        message: String,
        resolvedValues: List<Long> = emptyList(),
        processStartTimeTicks: String = "",
    ): RootAddressRecipeResult = RootAddressRecipeResult(
        status = status,
        resolvedValues = resolvedValues.toList(),
        processStartTimeTicks = processStartTimeTicks,
        message = message,
    )
}

internal object JniRootAddressRecipeResolver {
    fun create(anchorResolver: RootSearchIdAnchorResolver): RootAddressRecipeResolver =
        RootResolverAddressRecipeEvaluator(
            seedResolver = JniRootSeedAddressResolver.instance,
            anchorResolver = anchorResolver,
            scalarReader = JniTargetScalarReader,
        )
}

private val RootAddressDereferenceMode.label: String
    get() = when (this) {
        RootAddressDereferenceMode.LEGACY_MASKED_H -> "Legacy H resolver"
        RootAddressDereferenceMode.POINTER64 -> "Pointer64 resolver"
    }

internal object RootRecoveredAddressRecipes {
    val lifeState = RootResolverAddressRecipe(
        initialSeed = RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
        intermediateOffsets = listOf(0x1a0L, 0x140L, 0x210L),
        finalOffset = 0x38L,
        sourceArtifact = "work/life-state-recovery-20260817/production-life-state-recipe.md",
        sourceReference =
            "Exact-SHA current-version chain: IPlayerControl* -> player+0x140 ClientActor -> " +
                "ClientActor+0x210 AttrHPComponent -> float32 current HP at +0x38",
        seedAnchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = RootAddressDereferenceMode.POINTER64,
    )

    val dataLongSelector1 = RootResolverAddressRecipe(
        initialSeed = 0x5b860L,
        intermediateOffsets = emptyList(),
        finalOffset = 0x548L,
        sourceArtifact = "artifacts/getdatalong_current_maps.txt",
        sourceReference =
            "GetDataLong(1): live verification reads one pointer64 from the exact-SHA GameApp " +
                "BSS anchor at +0x5b860, then reads the u64 field at pointer+0x548",
        seedAnchorProfile = RootRecoveredSearchIdProfiles.miniWorld1582,
        dereferenceMode = RootAddressDereferenceMode.POINTER64,
    )
}
