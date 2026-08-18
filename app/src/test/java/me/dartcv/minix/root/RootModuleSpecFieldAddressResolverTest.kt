package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootModuleSpecFieldAddressResolverTest {
    @Test
    fun exactAnchorAddsOnlyTheRecoveredBssRelativeOffset() {
        val anchorResolver = FixedFieldAnchorResolver(
            resolvedAnchor(baseAddress = 0x7000_0000L),
        )
        val resolver = ProcRootModuleSpecFieldAddressResolver(
            anchorResolver,
        )

        val result = resolver.resolve(PID, START_TIME, recipe())

        assertTrue(result.isSuccess)
        assertEquals(0x7001_cc78L, result.address)
        assertEquals(START_TIME, result.processStartTimeTicks)
        assertEquals(
            RootGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
            anchorResolver.profiles.single().recipe.rootOffset,
        )
    }

    @Test
    fun anchorFingerprintFailurePropagatesWithoutProducingAnAddress() {
        val resolver = ProcRootModuleSpecFieldAddressResolver(
            FixedFieldAnchorResolver(
                RootSearchIdAnchorResult(
                    status = RootSearchIdAnchorStatus.FINGERPRINT_MISMATCH,
                    processStartTimeTicks = START_TIME,
                    message = "exact SHA mismatch",
                ),
            ),
        )

        val result = resolver.resolve(PID, START_TIME, recipe())

        assertFalse(result.isSuccess)
        assertEquals(RootAddressRecipeStatus.RESOLVER_FAILED, result.status)
        assertEquals("exact SHA mismatch", result.message)
        assertNull(result.address)
    }

    @Test
    fun anchorIdentityMismatchAndAddressOverflowFailClosed() {
        val changed = ProcRootModuleSpecFieldAddressResolver(
            FixedFieldAnchorResolver(resolvedAnchor(startTime = "changed")),
        ).resolve(PID, START_TIME, recipe())
        assertEquals(RootAddressRecipeStatus.TARGET_CHANGED, changed.status)

        val overflow = ProcRootModuleSpecFieldAddressResolver(
            FixedFieldAnchorResolver(resolvedAnchor(baseAddress = Long.MAX_VALUE)),
        ).resolve(PID, START_TIME, recipe())
        assertEquals(RootAddressRecipeStatus.ADDRESS_OVERFLOW, overflow.status)
        assertNull(overflow.address)
    }

    private fun recipe(): RootModuleSpecFieldAddressRecipe = RootModuleSpecFieldAddressRecipe(
        anchorProfile = PROFILE,
        finalOffset = RootGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
        sourceArtifact = "fixture.json",
        sourceReference = "anchor plus field offset",
    )

    private fun resolvedAnchor(
        baseAddress: Long = 0x7000_0000L,
        startTime: String = START_TIME,
    ): RootSearchIdAnchorResult = RootSearchIdAnchorResult(
        status = RootSearchIdAnchorStatus.RESOLVED,
        anchor = RootSearchIdAnchor(
            profileId = PROFILE.profileId,
            moduleName = PROFILE.moduleName,
            moduleSpec = PROFILE.moduleSpec,
            moduleSha256 = requireNotNull(PROFILE.normalizedSha256),
            anchorKind = PROFILE.anchorKind,
            baseAddress = baseAddress,
            processStartTimeTicks = startTime,
        ),
        processStartTimeTicks = startTime,
    )

    private class FixedFieldAnchorResolver(
        private val result: RootSearchIdAnchorResult,
    ) : RootSearchIdAnchorResolver {
        val profiles = mutableListOf<RootSearchIdProfile>()

        override fun resolve(pid: Int, profile: RootSearchIdProfile): RootSearchIdAnchorResult {
            profiles += profile
            return result
        }
    }

    private companion object {
        const val PID = 77
        const val START_TIME = "start-77"
        val PROFILE = RootRecoveredSearchIdProfiles.miniWorld1582
    }
}
