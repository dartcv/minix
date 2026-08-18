package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlModuleSpecFieldAddressResolverTest {
    @Test
    fun exactAnchorAddsOnlyTheRecoveredBssRelativeOffset() {
        val anchorResolver = FixedFieldAnchorResolver(
            resolvedAnchor(baseAddress = 0x7000_0000L),
        )
        val resolver = ProcControlModuleSpecFieldAddressResolver(
            anchorResolver,
        )

        val result = resolver.resolve(PID, START_TIME, recipe())

        assertTrue(result.isSuccess)
        assertEquals(0x7001_cc78L, result.address)
        assertEquals(START_TIME, result.processStartTimeTicks)
        assertEquals(
            ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
            anchorResolver.profiles.single().recipe.anchorOffset,
        )
    }

    @Test
    fun anchorFingerprintFailurePropagatesWithoutProducingAnAddress() {
        val resolver = ProcControlModuleSpecFieldAddressResolver(
            FixedFieldAnchorResolver(
                ControlSearchIdAnchorResult(
                    status = ControlSearchIdAnchorStatus.FINGERPRINT_MISMATCH,
                    processStartTimeTicks = START_TIME,
                    message = "exact SHA mismatch",
                ),
            ),
        )

        val result = resolver.resolve(PID, START_TIME, recipe())

        assertFalse(result.isSuccess)
        assertEquals(ControlAddressRecipeStatus.RESOLVER_FAILED, result.status)
        assertEquals("exact SHA mismatch", result.message)
        assertNull(result.address)
    }

    @Test
    fun anchorIdentityMismatchAndAddressOverflowFailClosed() {
        val changed = ProcControlModuleSpecFieldAddressResolver(
            FixedFieldAnchorResolver(resolvedAnchor(startTime = "changed")),
        ).resolve(PID, START_TIME, recipe())
        assertEquals(ControlAddressRecipeStatus.TARGET_CHANGED, changed.status)

        val overflow = ProcControlModuleSpecFieldAddressResolver(
            FixedFieldAnchorResolver(resolvedAnchor(baseAddress = Long.MAX_VALUE)),
        ).resolve(PID, START_TIME, recipe())
        assertEquals(ControlAddressRecipeStatus.ADDRESS_OVERFLOW, overflow.status)
        assertNull(overflow.address)
    }

    private fun recipe(): ControlModuleSpecFieldAddressRecipe = ControlModuleSpecFieldAddressRecipe(
        anchorProfile = PROFILE,
        finalOffset = ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
        sourceArtifact = "fixture.json",
        sourceReference = "anchor plus field offset",
    )

    private fun resolvedAnchor(
        baseAddress: Long = 0x7000_0000L,
        startTime: String = START_TIME,
    ): ControlSearchIdAnchorResult = ControlSearchIdAnchorResult(
        status = ControlSearchIdAnchorStatus.RESOLVED,
        anchor = ControlSearchIdAnchor(
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
        private val result: ControlSearchIdAnchorResult,
    ) : ControlSearchIdAnchorResolver {
        val profiles = mutableListOf<ControlSearchIdProfile>()

        override fun resolve(pid: Int, profile: ControlSearchIdProfile): ControlSearchIdAnchorResult {
            profiles += profile
            return result
        }
    }

    private companion object {
        const val PID = 77
        const val START_TIME = "start-77"
        val PROFILE = ControlRecoveredSearchIdProfiles.miniWorld1582
    }
}
