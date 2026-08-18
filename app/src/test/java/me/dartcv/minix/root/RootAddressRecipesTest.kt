package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAddressRecipesTest {
    @Test
    fun lifeStateRecipeFollowsCurrentPlayerActorAndHpComponentPointers() {
        val baseAddress = 0x7348_49c000L
        val globalAddress = baseAddress + RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET
        val legacyResolver = RecordingSeedResolver(values = mutableListOf())
        val pointerReader = RecordingPointerReader(
            values = mapOf(
                globalAddress to 0x1000L,
                0x11a0L to 0x2000L,
                0x2140L to 0x3000L,
                0x3210L to 0x4000L,
            ),
        )
        val anchorResolver = RecordingRecipeAnchorResolver(baseAddress = baseAddress)
        val evaluator = RootResolverAddressRecipeEvaluator(
            legacyResolver,
            anchorResolver,
            pointerReader,
        )

        val result = evaluator.resolve(pid = 77, recipe = RootRecoveredAddressRecipes.lifeState)

        assertTrue(result.isSuccess)
        assertEquals(0x4038L, result.address)
        assertTrue(legacyResolver.seeds.isEmpty())
        assertEquals(listOf(globalAddress, 0x11a0L, 0x2140L, 0x3210L), pointerReader.addresses)
        assertEquals(
            RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
            anchorResolver.profiles.single().recipe.rootOffset,
        )
        assertEquals(
            RootAddressDereferenceMode.POINTER64,
            RootRecoveredAddressRecipes.lifeState.dereferenceMode,
        )
        assertEquals(listOf(0x1000L, 0x2000L, 0x3000L, 0x4000L), result.resolvedValues)
    }

    @Test
    fun dataLongRecipeUsesOnePointer64ReadAndTailOffset() {
        val baseAddress = 0x7348_49c000L
        val globalAddress = baseAddress + 0x5b860L
        val pointerReader = RecordingPointerReader(
            values = mapOf(globalAddress to 0x72a0_60bde0L),
        )
        val legacyResolver = RecordingSeedResolver(values = mutableListOf())
        val anchorResolver = RecordingRecipeAnchorResolver(baseAddress = baseAddress)
        val evaluator = RootResolverAddressRecipeEvaluator(
            legacyResolver,
            anchorResolver,
            pointerReader,
        )

        val result = evaluator.resolve(
            pid = 77,
            recipe = RootRecoveredAddressRecipes.dataLongSelector1,
        )

        assertTrue(result.isSuccess)
        assertEquals(0x72a0_60c328L, result.address)
        assertTrue(legacyResolver.seeds.isEmpty())
        assertEquals(listOf(globalAddress), pointerReader.addresses)
        assertEquals(0x5b860L, anchorResolver.profiles.single().recipe.rootOffset)
        assertEquals(
            RootAddressDereferenceMode.POINTER64,
            RootRecoveredAddressRecipes.dataLongSelector1.dereferenceMode,
        )
    }

    @Test
    fun recipeStopsWhenTheResolverReturnsZero() {
        val baseAddress = 0x7348_49c000L
        val globalAddress = baseAddress + RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET
        val evaluator = RootResolverAddressRecipeEvaluator(
            seedResolver = RecordingSeedResolver(values = mutableListOf()),
            anchorResolver = RecordingRecipeAnchorResolver(baseAddress = baseAddress),
            scalarReader = RecordingPointerReader(values = mapOf(globalAddress to 0L)),
        )

        val result = evaluator.resolve(pid = 77, recipe = RootRecoveredAddressRecipes.lifeState)

        assertFalse(result.isSuccess)
        assertEquals(RootAddressRecipeStatus.ZERO_ADDRESS, result.status)
    }

    @Test
    fun recipeRejectsPidIdentityChangesBetweenResolverSteps() {
        val baseAddress = 0x7348_49c000L
        val globalAddress = baseAddress + RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET
        val legacyResolver = RecordingSeedResolver(values = mutableListOf())
        val pointerReader = RecordingPointerReader(
            values = mapOf(
                globalAddress to 0x1000L,
                0x11a0L to 0x2000L,
                0x2140L to 0x3000L,
                0x3210L to 0x4000L,
            ),
            startTimes = mutableListOf("one", "one", "two", "two"),
        )
        val evaluator = RootResolverAddressRecipeEvaluator(
            seedResolver = legacyResolver,
            anchorResolver = RecordingRecipeAnchorResolver(
                baseAddress = baseAddress,
                startTime = "one",
            ),
            scalarReader = pointerReader,
        )

        val result = evaluator.resolve(pid = 77, recipe = RootRecoveredAddressRecipes.lifeState)

        assertFalse(result.isSuccess)
        assertEquals(RootAddressRecipeStatus.TARGET_CHANGED, result.status)
        assertEquals(listOf(0x1000L, 0x2000L), result.resolvedValues)
    }

    @Test
    fun recipeStopsBeforeRemoteReadsWhenTheAnchorChanges() {
        val resolver = RecordingSeedResolver(values = mutableListOf(0x1000L))
        val evaluator = RootResolverAddressRecipeEvaluator(
            resolver,
            RecordingRecipeAnchorResolver(status = RootSearchIdAnchorStatus.TARGET_CHANGED),
        )

        val result = evaluator.resolve(pid = 77, recipe = RootRecoveredAddressRecipes.lifeState)

        assertEquals(RootAddressRecipeStatus.TARGET_CHANGED, result.status)
        assertTrue(resolver.seeds.isEmpty())
    }

    @Test
    fun recipeRejectsAnOverflowingAnchorPlusInitialOffset() {
        val resolver = RecordingSeedResolver(values = mutableListOf(0x1000L))
        val evaluator = RootResolverAddressRecipeEvaluator(
            resolver,
            RecordingRecipeAnchorResolver(baseAddress = Long.MAX_VALUE),
        )

        val result = evaluator.resolve(pid = 77, recipe = RootRecoveredAddressRecipes.lifeState)

        assertEquals(RootAddressRecipeStatus.ADDRESS_OVERFLOW, result.status)
        assertTrue(resolver.seeds.isEmpty())
    }

    @Test
    fun pointer64RecipeFollowsPlayerControlPlayerAndActorPointers() {
        val baseAddress = 0x7348_49c000L
        val globalAddress = baseAddress + RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET
        val scalarReader = RecordingPointerReader(
            values = mapOf(
                globalAddress to 0x1000L,
                0x11a0L to 0x2000L,
                0x2140L to 0x3000L,
            ),
        )
        val legacyResolver = RecordingSeedResolver(values = mutableListOf())
        val evaluator = RootResolverAddressRecipeEvaluator(
            seedResolver = legacyResolver,
            anchorResolver = RecordingRecipeAnchorResolver(baseAddress = baseAddress),
            scalarReader = scalarReader,
        )

        val result = evaluator.resolve(
            pid = 77,
            recipe = RootRecoveredInjectionRecipes.playerPositionX,
        )

        assertTrue(result.isSuccess)
        assertEquals(0x32c8L, result.address)
        assertEquals(listOf(globalAddress, 0x11a0L, 0x2140L), scalarReader.addresses)
        assertEquals(listOf(0x1000L, 0x2000L, 0x3000L), result.resolvedValues)
        assertTrue(legacyResolver.seeds.isEmpty())
    }
}

private class RecordingRecipeAnchorResolver(
    private val baseAddress: Long = 0x7348_49c000L,
    private val startTime: String = "start-77",
    private val status: RootSearchIdAnchorStatus = RootSearchIdAnchorStatus.RESOLVED,
) : RootSearchIdAnchorResolver {
    val profiles = mutableListOf<RootSearchIdProfile>()

    override fun resolve(pid: Int, profile: RootSearchIdProfile): RootSearchIdAnchorResult {
        profiles += profile
        if (status != RootSearchIdAnchorStatus.RESOLVED) {
            return RootSearchIdAnchorResult(
                status = status,
                processStartTimeTicks = startTime,
                message = "fixture anchor failure",
            )
        }
        return RootSearchIdAnchorResult(
            status = RootSearchIdAnchorStatus.RESOLVED,
            anchor = RootSearchIdAnchor(
                profileId = profile.profileId,
                moduleName = profile.moduleName,
                moduleSpec = profile.moduleSpec,
                moduleSha256 = requireNotNull(profile.normalizedSha256),
                anchorKind = profile.anchorKind,
                baseAddress = baseAddress,
                processStartTimeTicks = startTime,
            ),
            processStartTimeTicks = startTime,
        )
    }
}

private class RecordingSeedResolver(
    private val values: MutableList<Long>,
    private val startTimes: MutableList<String> = mutableListOf(),
) : RootSeedAddressResolver {
    val seeds = mutableListOf<Long>()

    override fun resolve(pid: Int, seed: Long): RootAddressResolveResult {
        seeds += seed
        val value = values.removeFirst()
        val startTime = if (startTimes.isEmpty()) "start-$pid" else startTimes.removeFirst()
        return RootAddressResolveResult(
            status = RootAddressResolveStatus.RESOLVED,
            seed = seed,
            resolvedAddress = value,
            processStartTimeTicks = startTime,
        )
    }
}

private class RecordingPointerReader(
    private val values: Map<Long, Long>,
    private val startTimes: MutableList<String> = mutableListOf(),
) : TargetScalarReader {
    val addresses = mutableListOf<Long>()

    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult =
        TargetScalarReadResult(isSuccess = false, message = "fixture only supports pointer64")

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult {
        addresses += address
        val value = values[address]
        val startTime = if (startTimes.isEmpty()) "start-$pid" else startTimes.removeFirst()
        return if (value == null) {
            TargetScalarReadResult(
                isSuccess = false,
                processStartTimeTicks = startTime,
                message = "fixture pointer missing",
            )
        } else {
            TargetScalarReadResult(
                isSuccess = true,
                processStartTimeTicks = startTime,
                valueBits = value,
            )
        }
    }
}
