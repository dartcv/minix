package me.dartcv.minix.control

internal data class ControlModuleSpecFieldAddressRecipe(
    val anchorProfile: ControlSearchIdProfile,
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

internal fun interface ControlModuleSpecFieldAddressResolver {
    fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: ControlModuleSpecFieldAddressRecipe,
    ): ControlAddressRecipeResult

    fun invalidateCache() = Unit
}

internal object NoControlModuleSpecFieldAddressResolver : ControlModuleSpecFieldAddressResolver {
    override fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: ControlModuleSpecFieldAddressRecipe,
    ): ControlAddressRecipeResult = ControlAddressRecipeResult(
        status = ControlAddressRecipeStatus.RESOLVER_FAILED,
        message = "Module-spec field resolver is unavailable",
    )
}

internal class ProcControlModuleSpecFieldAddressResolver(
    private val anchorResolver: ControlSearchIdAnchorResolver,
) : ControlModuleSpecFieldAddressResolver {
    override fun invalidateCache() = anchorResolver.invalidateCache()

    override fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: ControlModuleSpecFieldAddressRecipe,
    ): ControlAddressRecipeResult {
        if (pid <= 0) {
            return failure(ControlAddressRecipeStatus.INVALID_PID, "PID must be positive")
        }
        if (expectedStartTimeTicks.isBlank()) {
            return failure(
                ControlAddressRecipeStatus.TARGET_CHANGED,
                "Pinned process identity is missing",
            )
        }
        if (!recipe.isStructurallyValid) {
            return failure(ControlAddressRecipeStatus.INVALID_RECIPE, "Module-spec recipe is invalid")
        }

        // Ask the shared anchor resolver to prove that the field itself, not
        // merely SearchID's anchor seed, is covered by the selected mapping.
        val boundedAnchorProfile = recipe.anchorProfile.copy(
            recipe = recipe.anchorProfile.recipe.copy(anchorOffset = recipe.finalOffset),
        )
        val anchorResult = runCatching {
            anchorResolver.resolve(pid, boundedAnchorProfile)
        }.getOrElse { error ->
            return failure(
                ControlAddressRecipeStatus.RESOLVER_FAILED,
                error.message ?: "Module-spec anchor resolver failed",
            )
        }
        if (!anchorResult.isSuccess || anchorResult.anchor == null) {
            val status = if (anchorResult.status == ControlSearchIdAnchorStatus.TARGET_CHANGED) {
                ControlAddressRecipeStatus.TARGET_CHANGED
            } else {
                ControlAddressRecipeStatus.RESOLVER_FAILED
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
                ControlAddressRecipeStatus.TARGET_CHANGED,
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
                ControlAddressRecipeStatus.RESOLVER_FAILED,
                "Module-spec anchor metadata does not match the field recipe",
                observedStartTime,
            )
        }
        if (anchor.baseAddress <= 0L) {
            return failure(
                ControlAddressRecipeStatus.ZERO_ADDRESS,
                "Module-spec anchor address is zero or invalid",
                observedStartTime,
            )
        }

        val address = runCatching { Math.addExact(anchor.baseAddress, recipe.finalOffset) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: return failure(
                ControlAddressRecipeStatus.ADDRESS_OVERFLOW,
                "Module-spec field address overflowed",
                observedStartTime,
            )
        return ControlAddressRecipeResult(
            status = ControlAddressRecipeStatus.RESOLVED,
            address = address,
            processStartTimeTicks = observedStartTime,
        )
    }

    private fun failure(
        status: ControlAddressRecipeStatus,
        message: String,
        processStartTimeTicks: String = "",
    ): ControlAddressRecipeResult = ControlAddressRecipeResult(
        status = status,
        processStartTimeTicks = processStartTimeTicks,
        message = message,
    )
}
