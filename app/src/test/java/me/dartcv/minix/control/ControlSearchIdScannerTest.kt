package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSearchIdScannerTest {
    @Test
    fun firstSlotMatchUsesRecoveredModuleSpecChainAndReadOrder() {
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + mapOf(TABLE_ADDRESS to CANDIDATE_ADDRESS),
        )
        val scalarReader = RecordingSearchScalarReader(
            values = mapOf(
                CANDIDATE_ADDRESS + 0x488L to 0x42c80000L,
                CANDIDATE_ADDRESS to 123L,
            ),
        )
        val scanner = readyScanner(seedResolver, scalarReader)

        val result = scanner.scan(PID, START_TIME, readyProfile(), requestedId = 123)

        assertTrue(result.isMatch)
        assertEquals(0, result.slotIndex)
        assertEquals(
            listOf(SEARCH_SEED, 0x2088L, 0x30d8L, TABLE_ADDRESS),
            seedResolver.seeds,
        )
        assertEquals(
            listOf(CANDIDATE_ADDRESS + 0x488L, CANDIDATE_ADDRESS),
            scalarReader.int32Addresses,
        )
    }

    @Test
    fun highMapsAnchorStillUsesTheLowUint32AddressAtTheRecoveredHReader() {
        val scalarReader = RecordingSearchScalarReader(
            values = mapOf(
                HIGH_SEARCH_READ_ADDRESS_LOW32 to 0x2000L,
                0x2088L to 0x3000L,
                0x30d8L to TABLE_ADDRESS,
                TABLE_ADDRESS to CANDIDATE_ADDRESS,
                CANDIDATE_ADDRESS + 0x488L to 0x42c80000L,
                CANDIDATE_ADDRESS to 123L,
            ),
        )
        val scanner = ControlSearchIdScanner(
            anchorResolver = RecordingSearchAnchorResolver(
                resolvedAnchor(baseAddress = HIGH_MODULE_SPEC_ANCHOR),
            ),
            seedResolver = ControlMaskedRemoteAddressResolver(scalarReader),
            scalarReader = scalarReader,
        )

        val result = scanner.scan(PID, START_TIME, readyProfile(), requestedId = 123)

        assertEquals(ControlSearchIdStatus.MATCH, result.status)
        assertEquals(0, result.slotIndex)
        assertTrue(scalarReader.int64Addresses.isEmpty())
        assertEquals(HIGH_SEARCH_READ_ADDRESS_LOW32, scalarReader.int32Addresses.first())
    }

    @Test
    fun lastSlotMatchStopsAtTheFortiethSlot() {
        val slotValues = (0 until 40).associate { index ->
            TABLE_ADDRESS + index * 8L to if (index == 39) CANDIDATE_ADDRESS else 0L
        }
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + slotValues,
        )
        val scalarReader = RecordingSearchScalarReader(
            values = mapOf(
                CANDIDATE_ADDRESS + 0x488L to 0x42c80000L,
                CANDIDATE_ADDRESS to 77L,
            ),
        )

        val result = readyScanner(seedResolver, scalarReader)
            .scan(PID, START_TIME, readyProfile(), requestedId = 77)

        assertEquals(ControlSearchIdStatus.MATCH, result.status)
        assertEquals(39, result.slotIndex)
        assertEquals(TABLE_ADDRESS + 39L * 8L, seedResolver.seeds.last())
        assertFalse(TABLE_ADDRESS + 40L * 8L in seedResolver.seeds)
        assertEquals(43, seedResolver.seeds.size)
    }

    @Test
    fun markerMismatchesCompleteAllSlotsWithoutReadingCandidateIds() {
        val candidates = (0 until 40).associate { index ->
            TABLE_ADDRESS + index * 8L to CANDIDATE_ADDRESS + index * 0x1000L
        }
        val markerValues = candidates.values.associate { candidate ->
            candidate + 0x488L to 0L
        }
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + candidates,
        )
        val scalarReader = RecordingSearchScalarReader(values = markerValues)

        val result = readyScanner(seedResolver, scalarReader)
            .scan(PID, START_TIME, readyProfile(), requestedId = 123)

        assertEquals(ControlSearchIdStatus.NOT_FOUND, result.status)
        assertEquals(40, scalarReader.int32Addresses.size)
        assertTrue(scalarReader.int32Addresses.all { address ->
            address in markerValues.keys
        })
        assertTrue(candidates.values.none { candidate ->
            candidate in scalarReader.int32Addresses
        })
    }

    @Test
    fun signedInt32CandidateIdMatchesNegativeRequest() {
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + mapOf(TABLE_ADDRESS to CANDIDATE_ADDRESS),
        )
        val scalarReader = RecordingSearchScalarReader(
            values = mapOf(
                CANDIDATE_ADDRESS + 0x488L to 0x42c80000L,
                CANDIDATE_ADDRESS to 0xffff_ffffL,
            ),
        )

        val result = readyScanner(seedResolver, scalarReader)
            .scan(PID, START_TIME, readyProfile(), requestedId = -1)

        assertEquals(ControlSearchIdStatus.MATCH, result.status)
        assertEquals(0, result.slotIndex)
    }

    @Test
    fun zeroControlAndOverflowAreInvalidBeforeSlotScanning() {
        val zeroResolver = RecordingSearchSeedResolver(
            values = mapOf(SEARCH_SEED to 0L),
        )
        val zeroReader = RecordingSearchScalarReader()

        val zeroResult = readyScanner(zeroResolver, zeroReader)
            .scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdStatus.INVALID, zeroResult.status)
        assertEquals(ControlSearchIdInvalidReason.RESOLVER_FAILED, zeroResult.invalidReason)
        assertEquals(listOf(SEARCH_SEED), zeroResolver.seeds)
        assertTrue(zeroReader.int32Addresses.isEmpty())

        val overflowAnchorResolver = RecordingSearchAnchorResolver(
            resolvedAnchor(baseAddress = Long.MAX_VALUE),
        )
        val overflowSeedResolver = RecordingSearchSeedResolver()
        val overflowResult = ControlSearchIdScanner(
            anchorResolver = overflowAnchorResolver,
            seedResolver = overflowSeedResolver,
            scalarReader = RecordingSearchScalarReader(),
        ).scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.ADDRESS_OVERFLOW, overflowResult.invalidReason)
        assertTrue(overflowSeedResolver.seeds.isEmpty())
    }

    @Test
    fun emptyCandidateIsSkippedButSlotResolverFailureIsInvalid() {
        val secondSlotSeed = TABLE_ADDRESS + 8L
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + mapOf(TABLE_ADDRESS to 0L),
            failures = setOf(secondSlotSeed),
        )
        val scalarReader = RecordingSearchScalarReader()

        val result = readyScanner(seedResolver, scalarReader)
            .scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdStatus.INVALID, result.status)
        assertEquals(ControlSearchIdInvalidReason.RESOLVER_FAILED, result.invalidReason)
        assertEquals(secondSlotSeed, seedResolver.seeds.last())
        assertTrue(scalarReader.int32Addresses.isEmpty())
    }

    @Test
    fun processIdentityChangeDuringMarkerReadInvalidatesTheSearch() {
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + mapOf(TABLE_ADDRESS to CANDIDATE_ADDRESS),
        )
        val markerAddress = CANDIDATE_ADDRESS + 0x488L
        val scalarReader = RecordingSearchScalarReader(
            values = mapOf(markerAddress to 0x42c80000L),
            startTimes = mapOf(markerAddress to "changed"),
        )

        val result = readyScanner(seedResolver, scalarReader)
            .scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdStatus.INVALID, result.status)
        assertEquals(ControlSearchIdInvalidReason.TARGET_CHANGED, result.invalidReason)
        assertEquals("changed", result.processStartTimeTicks)
    }

    @Test
    fun productionProfileUsesSharedGameArtifactIdentity() {
        val profile = ControlRecoveredSearchIdProfiles.miniWorld1582

        assertTrue(profile.isReadyForScan)
        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, profile.moduleName)
        assertEquals(ControlGameAppArtifact1582.SHA256, profile.normalizedSha256)
        assertEquals(
            "${ControlGameAppArtifact1582.MODULE_NAME}:bss",
            profile.moduleSpec,
        )
        assertEquals(profile, ControlRecoveredSearchIdProfiles.miniWorld1582Draft)
    }

    @Test
    fun incompleteProfileAndAmbiguousAnchorPerformNoMemoryReads() {
        val incompleteAnchorResolver = RecordingSearchAnchorResolver(resolvedAnchor())
        val incompleteSeedResolver = RecordingSearchSeedResolver()
        val incompleteReader = RecordingSearchScalarReader()
        val incompleteScanner = ControlSearchIdScanner(
            anchorResolver = incompleteAnchorResolver,
            seedResolver = incompleteSeedResolver,
            scalarReader = incompleteReader,
        )

        val incompleteResult = incompleteScanner.scan(
            PID,
            START_TIME,
            ControlRecoveredSearchIdProfiles.miniWorld1582.copy(expectedSha256 = null),
            requestedId = 1,
        )

        assertEquals(ControlSearchIdInvalidReason.INVALID_PROFILE, incompleteResult.invalidReason)
        assertEquals(0, incompleteAnchorResolver.calls)
        assertTrue(incompleteSeedResolver.seeds.isEmpty())
        assertTrue(incompleteReader.int32Addresses.isEmpty())

        val ambiguousAnchorResolver = RecordingSearchAnchorResolver(
            ControlSearchIdAnchorResult(
                status = ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS,
                message = "Multiple matching modules",
            ),
        )
        val ambiguousSeedResolver = RecordingSearchSeedResolver()
        val ambiguousResult = ControlSearchIdScanner(
            anchorResolver = ambiguousAnchorResolver,
            seedResolver = ambiguousSeedResolver,
            scalarReader = RecordingSearchScalarReader(),
        ).scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.MODULE_AMBIGUOUS, ambiguousResult.invalidReason)
        assertTrue(ambiguousSeedResolver.seeds.isEmpty())
    }

    @Test
    fun noSuffixProfilePassesValidationAndReachesTheAnchorResolver() {
        val anchorResolver = RecordingSearchAnchorResolver(
            ControlSearchIdAnchorResult(
                status = ControlSearchIdAnchorStatus.UNAVAILABLE,
                message = "fixture unavailable",
            ),
        )
        val seedResolver = RecordingSearchSeedResolver()
        val result = ControlSearchIdScanner(
            anchorResolver = anchorResolver,
            seedResolver = seedResolver,
            scalarReader = RecordingSearchScalarReader(),
        ).scan(
            PID,
            START_TIME,
            readyProfile().copy(moduleSpec = ControlGameAppArtifact1582.MODULE_NAME),
            requestedId = 1,
        )

        assertEquals(1, anchorResolver.calls)
        assertEquals(ControlSearchIdInvalidReason.ANCHOR_UNAVAILABLE, result.invalidReason)
        assertTrue(seedResolver.seeds.isEmpty())
    }

    @Test
    fun longRequestOutsideInt32DoesNotTruncateIntoAMatch() {
        val candidates = (0 until 40).associate { index ->
            TABLE_ADDRESS + index * 8L to CANDIDATE_ADDRESS + index * 0x1000L
        }
        val scalarValues = buildMap {
            candidates.values.forEach { candidate ->
                put(candidate + 0x488L, 0x42c80000L)
                put(candidate, 1L)
            }
        }
        val seedResolver = RecordingSearchSeedResolver(
            values = recoveredControlValues() + candidates,
        )

        val result = readyScanner(
            seedResolver,
            RecordingSearchScalarReader(values = scalarValues),
        ).scan(
            PID,
            START_TIME,
            readyProfile(),
            requestedId = 0x1_0000_0001L,
        )

        assertEquals(ControlSearchIdStatus.NOT_FOUND, result.status)
        assertEquals(43, seedResolver.seeds.size)
    }

    @Test
    fun resolverSeedAndTwentyFourBitContractViolationsAreInvalid() {
        val wrongSeedResolver = RecordingSearchSeedResolver(
            values = mapOf(SEARCH_SEED to 0x2000L),
            reportedSeeds = mapOf(SEARCH_SEED to SEARCH_SEED + 4L),
        )
        val wrongSeedResult = readyScanner(
            wrongSeedResolver,
            RecordingSearchScalarReader(),
        ).scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.RESOLVER_FAILED, wrongSeedResult.invalidReason)

        val outOfRangeResolver = RecordingSearchSeedResolver(
            values = mapOf(SEARCH_SEED to 0x0100_0000L),
        )
        val outOfRangeResult = readyScanner(
            outOfRangeResolver,
            RecordingSearchScalarReader(),
        ).scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.RESOLVER_FAILED, outOfRangeResult.invalidReason)
    }

    @Test
    fun pinnedSessionIdentityRejectsANewProcessBeforeMemoryReads() {
        val seedResolver = RecordingSearchSeedResolver()
        val scanner = ControlSearchIdScanner(
            anchorResolver = RecordingSearchAnchorResolver(
                resolvedAnchor(startTime = "new-start"),
            ),
            seedResolver = seedResolver,
            scalarReader = RecordingSearchScalarReader(),
        )

        val result = scanner.scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.TARGET_CHANGED, result.invalidReason)
        assertTrue(seedResolver.seeds.isEmpty())
    }

    @Test
    fun markerAndCandidateIdReadFailuresRemainInvalid() {
        val markerAddress = CANDIDATE_ADDRESS + 0x488L
        val seedValues = recoveredControlValues() + mapOf(TABLE_ADDRESS to CANDIDATE_ADDRESS)
        val markerFailure = readyScanner(
            RecordingSearchSeedResolver(values = seedValues),
            RecordingSearchScalarReader(failures = setOf(markerAddress)),
        ).scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.READ_FAILED, markerFailure.invalidReason)

        val idFailure = readyScanner(
            RecordingSearchSeedResolver(values = seedValues),
            RecordingSearchScalarReader(
                values = mapOf(markerAddress to 0x42c80000L),
                failures = setOf(CANDIDATE_ADDRESS),
            ),
        ).scan(PID, START_TIME, readyProfile(), requestedId = 1)

        assertEquals(ControlSearchIdInvalidReason.READ_FAILED, idFailure.invalidReason)
    }
}

private class RecordingSearchAnchorResolver(
    private val result: ControlSearchIdAnchorResult,
) : ControlSearchIdAnchorResolver {
    var calls: Int = 0

    override fun resolve(pid: Int, profile: ControlSearchIdProfile): ControlSearchIdAnchorResult {
        calls += 1
        return result
    }
}

private class RecordingSearchSeedResolver(
    private val values: Map<Long, Long> = emptyMap(),
    private val failures: Set<Long> = emptySet(),
    private val startTimes: Map<Long, String> = emptyMap(),
    private val reportedSeeds: Map<Long, Long> = emptyMap(),
) : ControlSeedAddressResolver {
    val seeds = mutableListOf<Long>()

    override fun resolve(pid: Int, seed: Long): ControlAddressResolveResult {
        seeds += seed
        val startTime = startTimes[seed] ?: START_TIME
        if (seed in failures || seed !in values) {
            return ControlAddressResolveResult(
                status = ControlAddressResolveStatus.SCALAR_READ_FAILED,
                seed = seed,
                processStartTimeTicks = startTime,
                message = "fixture failure",
            )
        }
        return ControlAddressResolveResult(
            status = ControlAddressResolveStatus.RESOLVED,
            seed = reportedSeeds[seed] ?: seed,
            resolvedAddress = values.getValue(seed),
            processStartTimeTicks = startTime,
        )
    }
}

private class RecordingSearchScalarReader(
    private val values: Map<Long, Long> = emptyMap(),
    private val failures: Set<Long> = emptySet(),
    private val startTimes: Map<Long, String> = emptyMap(),
) : TargetScalarReader {
    val int32Addresses = mutableListOf<Long>()
    val int64Addresses = mutableListOf<Long>()

    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult {
        int32Addresses += address
        val startTime = startTimes[address] ?: START_TIME
        if (address in failures || address !in values) {
            return TargetScalarReadResult(
                isSuccess = false,
                processStartTimeTicks = startTime,
                message = "fixture failure",
            )
        }
        return TargetScalarReadResult(
            isSuccess = true,
            processStartTimeTicks = startTime,
            valueBits = values.getValue(address),
        )
    }

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult {
        int64Addresses += address
        val startTime = startTimes[address] ?: START_TIME
        if (address in failures || address !in values) {
            return TargetScalarReadResult(
                isSuccess = false,
                processStartTimeTicks = startTime,
                message = "fixture failure",
            )
        }
        return TargetScalarReadResult(
            isSuccess = true,
            processStartTimeTicks = startTime,
            valueBits = values.getValue(address),
        )
    }
}

private fun readyScanner(
    seedResolver: ControlSeedAddressResolver,
    scalarReader: TargetScalarReader,
): ControlSearchIdScanner = ControlSearchIdScanner(
    anchorResolver = RecordingSearchAnchorResolver(resolvedAnchor()),
    seedResolver = seedResolver,
    scalarReader = scalarReader,
)

private fun readyProfile(): ControlSearchIdProfile =
    ControlRecoveredSearchIdProfiles.miniWorld1582.copy(
        profileId = PROFILE_ID,
        expectedSha256 = MODULE_SHA256,
    )

private fun resolvedAnchor(
    baseAddress: Long = MODULE_SPEC_ANCHOR,
    startTime: String = START_TIME,
): ControlSearchIdAnchorResult =
    ControlSearchIdAnchorResult(
        status = ControlSearchIdAnchorStatus.RESOLVED,
        anchor = ControlSearchIdAnchor(
            profileId = PROFILE_ID,
            moduleName = ControlGameAppArtifact1582.MODULE_NAME,
            moduleSpec = "${ControlGameAppArtifact1582.MODULE_NAME}:bss",
            moduleSha256 = MODULE_SHA256,
            anchorKind = ControlModuleAnchorKind.MODULE_SPEC_RESULT,
            baseAddress = baseAddress,
            processStartTimeTicks = startTime,
        ),
        processStartTimeTicks = startTime,
    )

private fun recoveredControlValues(): Map<Long, Long> = mapOf(
    SEARCH_SEED to 0x2000L,
    0x2088L to 0x3000L,
    0x30d8L to TABLE_ADDRESS,
)

private const val PID = 77
private const val START_TIME = "start-77"
private const val PROFILE_ID = "fixture-search-id-v1"
private const val MODULE_SHA256 =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val MODULE_SPEC_ANCHOR = 0x100000L
private const val SEARCH_SEED = MODULE_SPEC_ANCHOR + 0x18760L
private const val HIGH_MODULE_SPEC_ANCHOR = 0x7f10_0000_00L
private const val HIGH_SEARCH_READ_ADDRESS_LOW32 = 0x1001_8760L
private const val TABLE_ADDRESS = 0x4000L
private const val CANDIDATE_ADDRESS = 0x5000L
