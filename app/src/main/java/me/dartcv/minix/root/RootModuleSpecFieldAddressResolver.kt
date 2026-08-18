package me.dartcv.minix.root

internal data class RootModuleSpecFieldAddressRecipe(
    val anchorProfile: RootSearchIdProfile,
    val finalOffset: Long,
    val sourceArtifact: String,
    val sourceReference: String,
) {
    val isStructurallyValid: Boolean
        get() = anchorProfile.isReadyForScan &&
            finalOffset in 0L..MAX_FIELD_OFFSET &&
            sourceArtifact.isNotBlank() &&
            sourceReference.isNotBlank()

    private companion object {
        const val MAX_FIELD_OFFSET = 0xffff_ffffL
    }
}

internal fun interface RootModuleSpecFieldAddressResolver {
    fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: RootModuleSpecFieldAddressRecipe,
    ): RootAddressRecipeResult

    fun invalidateCache() = Unit
}

internal object NoRootModuleSpecFieldAddressResolver : RootModuleSpecFieldAddressResolver {
    override fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: RootModuleSpecFieldAddressRecipe,
    ): RootAddressRecipeResult = RootAddressRecipeResult(
        status = RootAddressRecipeStatus.RESOLVER_FAILED,
        message = "Module-spec field resolver is unavailable",
    )
}

internal class ProcRootModuleSpecFieldAddressResolver(
    private val anchorResolver: RootSearchIdAnchorResolver,
) : RootModuleSpecFieldAddressResolver {
    override fun invalidateCache() = anchorResolver.invalidateCache()

    override fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: RootModuleSpecFieldAddressRecipe,
    ): RootAddressRecipeResult {
        if (pid <= 0) {
            return failure(RootAddressRecipeStatus.INVALID_PID, "PID must be positive")
        }
        if (expectedStartTimeTicks.isBlank()) {
            return failure(
                RootAddressRecipeStatus.TARGET_CHANGED,
                "Pinned process identity is missing",
            )
        }
        if (!recipe.isStructurallyValid) {
            return failure(RootAddressRecipeStatus.INVALID_RECIPE, "Module-spec recipe is invalid")
        }

        // Ask the shared anchor resolver to prove that the field itself, not
        // merely SearchID's root seed, is covered by the selected mapping.
        val boundedAnchorProfile = recipe.anchorProfile.copy(
            recipe = recipe.anchorProfile.recipe.copy(rootOffset = recipe.finalOffset),
        )
        val anchorResult = runCatching {
            anchorResolver.resolve(pid, boundedAnchorProfile)
        }.getOrElse { error ->
            return failure(
                RootAddressRecipeStatus.RESOLVER_FAILED,
                error.message ?: "Module-spec anchor resolver failed",
            )
        }
        if (!anchorResult.isSuccess || anchorResult.anchor == null) {
            val status = if (anchorResult.status == RootSearchIdAnchorStatus.TARGET_CHANGED) {
                RootAddressRecipeStatus.TARGET_CHANGED
            } else {
                RootAddressRecipeStatus.RESOLVER_FAILED
            }
            return failure(
                status = status,
                message = anchorResult.message.ifBlank { anchorResult.status.name },
                processStartTimeTicks = anchorResult.processStartTimeTicks,
            )
        }

        val anchor = anchorResult.anchor
        val observedStartTime = anchorResult.processStartTimeTicks
        if (
            observedStartTime.isBlank() ||
            anchor.processStartTimeTicks.isBlank() ||
            observedStartTime != anchor.processStartTimeTicks ||
            observedStartTime != expectedStartTimeTicks
        ) {
            return failure(
                RootAddressRecipeStatus.TARGET_CHANGED,
                "Module-spec anchor does not belong to the pinned target process",
                observedStartTime.ifBlank { anchor.processStartTimeTicks },
            )
        }
        if (
            anchor.profileId != recipe.anchorProfile.profileId ||
            anchor.moduleName != recipe.anchorProfile.moduleName ||
            anchor.moduleSpec != recipe.anchorProfile.moduleSpec ||
            anchor.moduleSha256 != recipe.anchorProfile.normalizedSha256 ||
            anchor.anchorKind != recipe.anchorProfile.anchorKind
        ) {
            return failure(
                RootAddressRecipeStatus.RESOLVER_FAILED,
                "Module-spec anchor metadata does not match the field recipe",
                observedStartTime,
            )
        }
        if (anchor.baseAddress <= 0L) {
            return failure(
                RootAddressRecipeStatus.ZERO_ADDRESS,
                "Module-spec anchor address is zero or invalid",
                observedStartTime,
            )
        }

        val address = runCatching { Math.addExact(anchor.baseAddress, recipe.finalOffset) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: return failure(
                RootAddressRecipeStatus.ADDRESS_OVERFLOW,
                "Module-spec field address overflowed",
                observedStartTime,
            )
        return RootAddressRecipeResult(
            status = RootAddressRecipeStatus.RESOLVED,
            address = address,
            processStartTimeTicks = observedStartTime,
        )
    }

    private fun failure(
        status: RootAddressRecipeStatus,
        message: String,
        processStartTimeTicks: String = "",
    ): RootAddressRecipeResult = RootAddressRecipeResult(
        status = status,
        processStartTimeTicks = processStartTimeTicks,
        message = message,
    )
}
