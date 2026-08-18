package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTargetSessionTest {
    @Test
    fun openPrefersMainProcessAndBuildsBoundedProcessSummary() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(
                330 to "com.example.target:worker",
                210 to "com.example.target",
            ),
            briefs = mutableMapOf(
                210 to TargetProcessBrief(
                    name = "target",
                    state = "S (sleeping)",
                    threads = 18,
                    vmRss = "24576 kB",
                    readableMapLines = 842,
                    effectiveUid = TEST_TARGET_UID,
                ),
            ),
        )
        val session = ControlTargetSession(inspector, expectedTargetUid = TEST_TARGET_UID)

        assertTrue(session.openOrRefresh("com.example.target"))
        val snapshot = session.snapshot()

        assertEquals(210, snapshot.pid)
        assertEquals(TEST_TARGET_UID, snapshot.targetUid)
        assertEquals("start-210", snapshot.startTimeTicks)
        assertTrue(snapshot.summary.contains("PID 210"))
        assertTrue(snapshot.summary.contains("UID $TEST_TARGET_UID"))
        assertTrue(snapshot.summary.contains("状态 S (sleeping)"))
        assertTrue(snapshot.summary.contains("线程 18"))
        assertTrue(snapshot.summary.contains("映射 842"))
        assertTrue(snapshot.summary.contains("RSS 24576 kB"))
    }

    @Test
    fun featureStateIsTypedAndClearsWhenTargetExits() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            fieldProfileResolver = ReadyFieldProfileResolver,
        )
        assertTrue(session.openOrRefresh("com.example.target"))

        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))
        assertTrue(session.isFeatureEnabled(ControlFeature.READABLE_DATA))
        assertEquals(arrayOf("readable_data").toList(), session.enabledFeatureIds().toList())
        assertEquals(arrayOf("readable_data").toList(), session.supportedFeatureIds().toList())

        inspector.processes.clear()
        assertFalse(session.openOrRefresh("com.example.target"))
        assertNull(session.snapshot().pid)
        assertTrue(session.snapshot().features.values.none { it })
    }

    @Test
    fun unsupportedFeatureDoesNotBecomeEnabled() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val session = ControlTargetSession(inspector, ReadyTargetMemoryProbe)

        assertTrue(session.openOrRefresh("com.example.target"))
        assertFalse(session.setFeatureEnabled(ControlFeature.AIM, true))
        assertFalse(session.isFeatureEnabled(ControlFeature.AIM))
    }

    @Test
    fun antiFlashRequestPinsTheInspectedModuleGeneration() {
        val packageName = ControlTargetCatalog.default.packageName
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to packageName),
            startTimes = mutableMapOf(100 to "12345"),
        )
        val profile = ControlAntiFlashProfileCatalog.profile
        val modules = listOf(
            antiFlashModule(profile.gameApp.name, profile.gameApp.sha256, 0x1000_0000L),
            antiFlashModule(profile.tprt.name, profile.tprt.sha256, 0x2000_0000L),
        )
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = FixedModulesTargetMemoryProbe("12345", "maps-v1", modules),
            serviceAbi = profile.requiredAbi,
        )

        assertTrue(session.openOrRefresh(packageName))

        val request = requireNotNull(session.antiFlashStartRequest())
        assertEquals(100, request.pid)
        assertEquals("12345", request.startTimeTicks)
        assertEquals("maps-v1", request.mapsGeneration)
        assertEquals(modules, request.modules)
        assertTrue(session.isAntiFlashProfileReady())
        assertTrue(ControlFeature.ANTI_FLASH in session.snapshot().supportedFeatures)
    }

    @Test
    fun antiFlashProfileMismatchKeepsFeatureUnsupportedButPreservesRequestEvidence() {
        val packageName = ControlTargetCatalog.default.packageName
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to packageName),
            startTimes = mutableMapOf(100 to "12345"),
        )
        val profile = ControlAntiFlashProfileCatalog.profile
        val modules = listOf(
            antiFlashModule(profile.gameApp.name, "0".repeat(64), 0x1000_0000L),
            antiFlashModule(profile.tprt.name, profile.tprt.sha256, 0x2000_0000L),
        )
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = FixedModulesTargetMemoryProbe("12345", "maps-v1", modules),
            serviceAbi = profile.requiredAbi,
        )

        assertTrue(session.openOrRefresh(packageName))

        assertEquals(modules, requireNotNull(session.antiFlashStartRequest()).modules)
        assertFalse(session.isAntiFlashProfileReady())
        assertFalse(ControlFeature.ANTI_FLASH in session.snapshot().supportedFeatures)
    }

    @Test
    fun invalidTargetNeverReachesProcessInspector() {
        val inspector = FakeTargetProcessInspector()
        val session = ControlTargetSession(inspector)

        assertFalse(session.openOrRefresh("com.example.target;id"))
        assertEquals(0, inspector.findCalls)
        assertEquals("目标包名格式无效", session.snapshot().summary)
    }

    @Test
    fun targetUidMismatchFailsBeforeNativeProbe() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
            effectiveUids = mutableMapOf(100 to TEST_TARGET_UID + 1),
        )
        val memoryProbe = RecordingTargetMemoryProbe()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = memoryProbe,
            expectedTargetUid = TEST_TARGET_UID,
        )

        assertFalse(session.openOrRefresh("com.example.target"))
        assertNull(session.snapshot().pid)
        assertNull(session.snapshot().targetUid)
        assertTrue(session.snapshot().summary.contains("UID 不匹配"))
        assertTrue(memoryProbe.pids.isEmpty())
    }

    @Test
    fun packageIdentityMismatchFailsBeforeProcessLookupAndNativeProbe() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val memoryProbe = RecordingTargetMemoryProbe()
        val session = ControlTargetSession(
            inspector = inspector,
            packageIdentityVerifier = TargetPackageIdentityVerifier {
                TargetPackageIdentityVerification(false, "目标包签名证书不匹配")
            },
            memoryProbe = memoryProbe,
        )

        assertFalse(session.openOrRefresh("com.example.target"))
        assertEquals(0, inspector.findCalls)
        assertTrue(session.snapshot().summary.contains("签名证书不匹配"))
        assertTrue(memoryProbe.pids.isEmpty())
    }

    @Test
    fun packageIdentityMismatchBlocksAutomaticPidScan() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val session = ControlTargetSession(
            inspector = inspector,
            packageIdentityVerifier = TargetPackageIdentityVerifier {
                TargetPackageIdentityVerification(false, "目标包签名证书不匹配")
            },
        )

        assertNull(session.findTargetPid("com.example.target"))
        assertEquals(0, inspector.findCalls)
    }

    @Test
    fun unreadableTargetUidFailsBeforeNativeProbe() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
            effectiveUids = mutableMapOf(),
            provideDefaultUid = false,
        )
        val memoryProbe = RecordingTargetMemoryProbe()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = memoryProbe,
            expectedTargetUid = TEST_TARGET_UID,
        )

        assertFalse(session.openOrRefresh("com.example.target"))
        assertTrue(session.snapshot().summary.contains("UID 不可读取"))
        assertTrue(memoryProbe.pids.isEmpty())
    }

    @Test
    fun targetUidChangeInvalidatesAnOpenSession() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
            effectiveUids = mutableMapOf(100 to TEST_TARGET_UID),
        )
        val session = ControlTargetSession(
            inspector = inspector,
            expectedTargetUid = TEST_TARGET_UID,
        )
        assertTrue(session.openOrRefresh("com.example.target"))

        inspector.effectiveUids[100] = TEST_TARGET_UID + 1

        assertFalse(session.verifyTargetProcess(100, "com.example.target"))
        session.refreshSummary()
        assertNull(session.snapshot().pid)
        assertNull(session.snapshot().targetUid)
        assertTrue(session.snapshot().summary.contains("UID 不匹配"))
    }

    @Test
    fun samePidWithDifferentStartTimeInvalidatesTheSession() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
            startTimes = mutableMapOf(100 to "1000"),
        )
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = FixedIdentityTargetMemoryProbe("1000"),
            fieldProfileResolver = ReadyFieldProfileResolver,
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))

        inspector.startTimes[100] = "2000"

        assertFalse(session.setFeatureEnabled(ControlFeature.READABLE_DATA, false))
        assertNull(session.snapshot().pid)
        assertNull(session.snapshot().startTimeTicks)
        assertTrue(session.snapshot().features.values.none { it })
        assertTrue(session.snapshot().summary.contains("启动标识已变化"))
    }

    @Test
    fun identityRefreshDoesNotRepeatTheHeavyProcessSummaryRead() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val session = ControlTargetSession(inspector, ReadyTargetMemoryProbe)

        assertTrue(session.openOrRefresh("com.example.target"))
        assertEquals(1, inspector.briefCalls)

        session.refreshSummary()

        assertEquals(1, inspector.briefCalls)
    }

    @Test
    fun productionProfileWithoutTheExactModuleNeverEnablesReadableData() {
        val packageName = ControlTargetCatalog.default.packageName
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to packageName),
        )
        val scalarReader = RecordingTargetScalarReader()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            scalarReader = scalarReader,
        )

        assertTrue(session.openOrRefresh(packageName))
        assertEquals(
            ControlReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
            session.fieldProfileState().status,
        )
        val batch = session.readVisibleFields()

        assertFalse(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))
        assertFalse(session.isFeatureEnabled(ControlFeature.READABLE_DATA))
        assertEquals(ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY, batch.lifeState.status)
        assertTrue(scalarReader.int32Addresses.isEmpty())
        assertTrue(scalarReader.int64Addresses.isEmpty())
    }

    @Test
    fun fieldReadsUseOnlyResolvedTypedProfileAddresses() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val scalarReader = RecordingTargetScalarReader()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            scalarReader = scalarReader,
            fieldProfileResolver = ReadyFieldProfileResolver,
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))

        val batch = session.readVisibleFields()

        assertTrue(batch.lifeState.isSuccess)
        assertEquals(
            0x12345678,
            (batch.lifeState.value as ControlInt32FieldValue).value,
        )
        assertTrue(batch.killCount.isSuccess)
        assertTrue(batch.dataLongSelector1.isSuccess)
        assertEquals(
            0x0102030405060708L,
            (batch.dataLongSelector1.value as ControlInt64FieldValue).value,
        )
        assertEquals(listOf(0x100020L, 0x100024L), scalarReader.int32Addresses)
        assertEquals(listOf(0x100030L), scalarReader.int64Addresses)
    }

    @Test
    fun recipeBackedFieldResolvesAddressInsideTheControlSession() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val scalarReader = RecordingTargetScalarReader()
        val recipeResolver = RecordingAddressRecipeResolver(address = 0x100040L)
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            scalarReader = scalarReader,
            fieldProfileResolver = ReadyRecipeFieldProfileResolver,
            addressRecipeResolver = recipeResolver,
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))

        val result = session.readField(ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1)

        assertTrue(result.isSuccess)
        assertEquals(listOf(0x100040L), scalarReader.int64Addresses)
        assertEquals(listOf(ControlRecoveredAddressRecipes.dataLongSelector1), recipeResolver.recipes)
    }

    @Test
    fun killCountUsesPinnedModuleSpecAnchorRecipeAndNeverTheHResolver() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val scalarReader = RecordingTargetScalarReader()
        val hResolver = RecordingAddressRecipeResolver(address = 0x1111L)
        val moduleResolver = RecordingModuleSpecFieldResolver(address = 0x7001_cc78L)
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            scalarReader = scalarReader,
            fieldProfileResolver = ReadyModuleSpecFieldProfileResolver,
            addressRecipeResolver = hResolver,
            moduleSpecFieldAddressResolver = moduleResolver,
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))

        val result = session.readField(ControlReadOnlyFieldId.KILL_COUNT)

        assertTrue(result.isSuccess)
        assertEquals(0x12345678, (result.value as ControlInt32FieldValue).value)
        assertEquals(listOf(0x7001_cc78L), scalarReader.int32Addresses)
        assertEquals(listOf("start-100"), moduleResolver.expectedStartTimes)
        assertEquals(
            listOf(ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET),
            moduleResolver.recipes.map(ControlModuleSpecFieldAddressRecipe::finalOffset),
        )
        assertTrue(hResolver.recipes.isEmpty())
    }

    @Test
    fun killCountInvalidatesSessionWhenIdentityChangesAfterModuleResolution() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
            startTimes = mutableMapOf(100 to "start-100"),
        )
        val scalarReader = RecordingTargetScalarReader()
        val moduleResolver = RecordingModuleSpecFieldResolver(0x7001_cc78L) {
            inspector.startTimes[100] = "reused-pid"
        }
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            scalarReader = scalarReader,
            fieldProfileResolver = ReadyModuleSpecFieldProfileResolver,
            moduleSpecFieldAddressResolver = moduleResolver,
        )
        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))

        val result = session.readField(ControlReadOnlyFieldId.KILL_COUNT)

        assertEquals(ControlReadOnlyFieldReadStatus.TARGET_CHANGED, result.status)
        assertNull(session.snapshot().pid)
        assertTrue(scalarReader.int32Addresses.isEmpty())
    }

    @Test
    fun killCountInvalidatesSessionWhenIdentityChangesDuringScalarRead() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
            startTimes = mutableMapOf(100 to "start-100"),
        )
        val scalarReader = ChangingIdentityScalarReader {
            inspector.startTimes[100] = "reused-pid"
        }
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            scalarReader = scalarReader,
            fieldProfileResolver = ReadyModuleSpecFieldProfileResolver,
            moduleSpecFieldAddressResolver = RecordingModuleSpecFieldResolver(0x7001_cc78L),
        )
        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))

        val result = session.readField(ControlReadOnlyFieldId.KILL_COUNT)

        assertEquals(ControlReadOnlyFieldReadStatus.TARGET_CHANGED, result.status)
        assertNull(session.snapshot().pid)
        assertEquals(listOf(0x7001_cc78L), scalarReader.int32Addresses)
    }

    @Test
    fun productionSearchIdWiringReachesTheFingerprintGatedAnchorResolver() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to ControlTargetCatalog.default.packageName),
        )
        val anchorResolver = RecordingSessionAnchorResolver()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            searchIdScanner = ControlSearchIdScanner(
                anchorResolver = anchorResolver,
                seedResolver = ControlSeedAddressResolver { _, seed ->
                    ControlAddressResolveResult(ControlAddressResolveStatus.RESOLVED, seed, 1L)
                },
                scalarReader = NoTargetScalarReader,
            ),
        )
        assertTrue(session.openOrRefresh(ControlTargetCatalog.default.packageName))

        val result = session.searchId(123L)

        assertEquals(ControlSearchIdStatus.INVALID, result.status)
        assertEquals(ControlSearchIdInvalidReason.ANCHOR_UNAVAILABLE, result.invalidReason)
        assertEquals(1, anchorResolver.calls)
    }

    @Test
    fun featureChangeContinuesAfterSameProcessMapsRefresh() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val memoryProbe = MutableMapsTargetMemoryProbe("maps-a")
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = memoryProbe,
            fieldProfileResolver = ReadyFieldProfileResolver,
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        memoryProbe.mapsFingerprint = "maps-b"

        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
        assertTrue(session.isFeatureEnabled(ControlFeature.READABLE_DATA))
    }

    @Test
    fun visibleFieldReadPreservesReadableDataAcrossMapsRefresh() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val memoryProbe = MutableMapsTargetMemoryProbe("maps-a")
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = memoryProbe,
            scalarReader = RecordingTargetScalarReader(),
            fieldProfileResolver = ReadyFieldProfileResolver,
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))
        memoryProbe.mapsFingerprint = "maps-b"

        val batch = session.readVisibleFields()

        assertTrue(batch.lifeState.isSuccess)
        assertTrue(batch.killCount.isSuccess)
        assertTrue(batch.dataLongSelector1.isSuccess)
        assertTrue(session.isFeatureEnabled(ControlFeature.READABLE_DATA))
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
    }

    @Test
    fun sameProcessMapsChangePreservesSupportedFeaturesAndInvalidatesResolverCache() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val memoryProbe = MutableMapsTargetMemoryProbe("maps-a")
        val anchorResolver = RecordingSessionAnchorResolver()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = memoryProbe,
            fieldProfileResolver = ReadyFieldProfileResolver,
            searchIdScanner = recordingSearchIdScanner(anchorResolver),
        )

        assertTrue(session.openOrRefresh("com.example.target"))
        assertTrue(session.setFeatureEnabled(ControlFeature.READABLE_DATA, true))
        val invalidationsBeforeChange = anchorResolver.invalidations

        memoryProbe.mapsFingerprint = "maps-b"

        assertTrue(session.openOrRefresh("com.example.target"))
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
        assertTrue(session.isFeatureEnabled(ControlFeature.READABLE_DATA))
        assertEquals(invalidationsBeforeChange + 1, anchorResolver.invalidations)
    }

    @Test
    fun closeAndIdentityFailureInvalidateResolverCache() {
        val inspector = FakeTargetProcessInspector(
            processes = mutableMapOf(100 to "com.example.target"),
        )
        val anchorResolver = RecordingSessionAnchorResolver()
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = ReadyTargetMemoryProbe,
            searchIdScanner = recordingSearchIdScanner(anchorResolver),
        )
        assertTrue(session.openOrRefresh("com.example.target"))
        val afterOpen = anchorResolver.invalidations

        inspector.processes.clear()
        session.refreshSummary()
        assertEquals(afterOpen + 1, anchorResolver.invalidations)

        session.close()
        assertEquals(afterOpen + 2, anchorResolver.invalidations)
    }
}

private fun recordingSearchIdScanner(
    anchorResolver: ControlSearchIdAnchorResolver,
): ControlSearchIdScanner = ControlSearchIdScanner(
    anchorResolver = anchorResolver,
    seedResolver = ControlSeedAddressResolver { _, seed ->
        ControlAddressResolveResult(ControlAddressResolveStatus.RESOLVED, seed, 1L)
    },
    scalarReader = NoTargetScalarReader,
)

private class RecordingSessionAnchorResolver : ControlSearchIdAnchorResolver {
    var calls = 0
    var invalidations = 0

    override fun resolve(pid: Int, profile: ControlSearchIdProfile): ControlSearchIdAnchorResult {
        calls += 1
        return ControlSearchIdAnchorResult(ControlSearchIdAnchorStatus.UNAVAILABLE)
    }

    override fun invalidateCache() {
        invalidations += 1
    }
}

private class MutableMapsTargetMemoryProbe(
    var mapsFingerprint: String,
) : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection(
        state = readyNativeState("start-$pid", mapsFingerprint),
        modules = listOf(readyModule()),
    )
}

private object ReadyTargetMemoryProbe : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection(
        state = readyNativeState("start-$pid"),
        modules = listOf(readyModule()),
    )
}

private class FixedIdentityTargetMemoryProbe(
    private val startTimeTicks: String,
) : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection(
        state = readyNativeState(startTimeTicks),
        modules = listOf(readyModule()),
    )
}

private class FixedModulesTargetMemoryProbe(
    private val startTimeTicks: String,
    private val mapsFingerprint: String,
    private val modules: List<ControlNativeModuleIdentity>,
) : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection(
        state = readyNativeState(startTimeTicks, mapsFingerprint),
        modules = modules,
    )
}

private fun readyNativeState(
    startTimeTicks: String,
    mapsFingerprint: String = "fixture",
): ControlNativeProbeState = ControlNativeProbeState(
        status = ControlNativeProbeStatus.OK,
        processStartTimeTicks = startTimeTicks,
        regionCount = 120,
        moduleCount = 8,
        memoryReadableModuleCount = 2,
        memoryReadBytes = 128,
        memoryElfHeaderCount = 2,
        mapsFingerprint = mapsFingerprint,
    )

private fun readyModule(): ControlNativeModuleIdentity = ControlNativeModuleIdentity(
    name = "libfixture.so",
    path = "/data/app/libfixture.so",
    loadBase = 0x100000L,
    mappedBytes = 0x10000L,
    memoryElf = true,
    sha256 = "a".repeat(64),
)

private fun antiFlashModule(
    name: String,
    sha256: String,
    loadBase: Long,
): ControlNativeModuleIdentity = ControlNativeModuleIdentity(
    name = name,
    path = "/data/app/lib/arm64/$name",
    loadBase = loadBase,
    mappedBytes = 0x0100_0000L,
    memoryElf = true,
    sha256 = sha256,
)

private val ReadyFieldProfileResolver = ControlReadOnlyFieldProfileResolver(
    candidates = listOf(
        ControlReadOnlyFieldProfileCandidate(
            profileId = "fixture-v1",
            schemaVersion = 1,
            targetVersion = "1.0",
            packageNames = setOf("com.example.target"),
            moduleEvidence = ControlModuleIdentityEvidence(
                moduleName = "libfixture.so",
                expectedSha256 = "a".repeat(64),
                sourceArtifact = "fixture.json",
                sourceReference = "module",
            ),
            fields = listOf(
                ControlInt32FieldDefinition(
                    ControlReadOnlyFieldId.LIFE_STATE,
                    ControlModuleOffsetEvidence(0x20L, "fixture.json", "life"),
                ),
                ControlInt32FieldDefinition(
                    ControlReadOnlyFieldId.KILL_COUNT,
                    ControlModuleOffsetEvidence(0x24L, "fixture.json", "kills"),
                ),
                ControlInt64FieldDefinition(
                    ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    ControlModuleOffsetEvidence(0x30L, "fixture.json", "long"),
                ),
            ),
        ),
    ),
)

private val ReadyRecipeFieldProfileResolver = ControlReadOnlyFieldProfileResolver(
    candidates = listOf(
        ControlReadOnlyFieldProfileCandidate(
            profileId = "fixture-recipe-v1",
            schemaVersion = 1,
            targetVersion = "1.0",
            packageNames = setOf("com.example.target"),
            moduleEvidence = ControlModuleIdentityEvidence(
                moduleName = "libfixture.so",
                expectedSha256 = "a".repeat(64),
                sourceArtifact = "fixture.json",
                sourceReference = "module",
            ),
            fields = listOf(
                ControlInt64FieldDefinition(
                    id = ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    offsetEvidence = ControlModuleOffsetEvidence(
                        moduleOffset = null,
                        sourceArtifact = "fixture.json",
                        sourceReference = "recipe",
                    ),
                    addressRecipe = ControlRecoveredAddressRecipes.dataLongSelector1,
                ),
            ),
        ),
    ),
)

private val FixtureModuleSpecAnchorProfile = ControlRecoveredSearchIdProfiles.miniWorld1582.copy(
    profileId = "fixture-module-spec-anchor-v1",
    targetVersion = "1.0",
    moduleName = "libfixture.so",
    moduleSpec = "libfixture.so:bss",
    expectedSha256 = "a".repeat(64),
)

private val ReadyModuleSpecFieldProfileResolver = ControlReadOnlyFieldProfileResolver(
    candidates = listOf(
        ControlReadOnlyFieldProfileCandidate(
            profileId = "fixture-module-spec-v1",
            schemaVersion = 1,
            targetVersion = "1.0",
            packageNames = setOf("com.example.target"),
            moduleEvidence = ControlModuleIdentityEvidence(
                moduleName = "libfixture.so",
                expectedSha256 = "a".repeat(64),
                sourceArtifact = "fixture.json",
                sourceReference = "module",
            ),
            fields = listOf(
                ControlInt32FieldDefinition(
                    id = ControlReadOnlyFieldId.KILL_COUNT,
                    offsetEvidence = ControlModuleOffsetEvidence(
                        moduleOffset = null,
                        sourceArtifact = "fixture.json",
                        sourceReference = "module-spec recipe",
                    ),
                    moduleSpecAddressRecipe = ControlModuleSpecFieldAddressRecipe(
                        anchorProfile = FixtureModuleSpecAnchorProfile,
                        finalOffset = ControlGameAppArtifact1582.KILL_COUNT_ANCHOR_OFFSET,
                        sourceArtifact = "fixture.json",
                        sourceReference = "anchor plus offset",
                    ),
                ),
            ),
        ),
    ),
)

private class RecordingAddressRecipeResolver(
    private val address: Long,
) : ControlAddressRecipeResolver {
    val recipes = mutableListOf<ControlResolverAddressRecipe>()

    override fun resolve(pid: Int, recipe: ControlResolverAddressRecipe): ControlAddressRecipeResult {
        recipes += recipe
        return ControlAddressRecipeResult(
            status = ControlAddressRecipeStatus.RESOLVED,
            address = address,
            processStartTimeTicks = "start-$pid",
        )
    }
}

private class RecordingModuleSpecFieldResolver(
    private val address: Long,
    private val onResolve: () -> Unit = {},
) : ControlModuleSpecFieldAddressResolver {
    val recipes = mutableListOf<ControlModuleSpecFieldAddressRecipe>()
    val expectedStartTimes = mutableListOf<String>()

    override fun resolve(
        pid: Int,
        expectedStartTimeTicks: String,
        recipe: ControlModuleSpecFieldAddressRecipe,
    ): ControlAddressRecipeResult {
        recipes += recipe
        expectedStartTimes += expectedStartTimeTicks
        onResolve()
        return ControlAddressRecipeResult(
            status = ControlAddressRecipeStatus.RESOLVED,
            address = address,
            processStartTimeTicks = expectedStartTimeTicks,
        )
    }
}

private class RecordingTargetScalarReader : TargetScalarReader {
    val int32Addresses = mutableListOf<Long>()
    val int64Addresses = mutableListOf<Long>()

    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult {
        int32Addresses += address
        return TargetScalarReadResult(
            isSuccess = true,
            processStartTimeTicks = "start-$pid",
            valueBits = 0x12345678L,
        )
    }

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult {
        int64Addresses += address
        return TargetScalarReadResult(
            isSuccess = true,
            processStartTimeTicks = "start-$pid",
            valueBits = 0x0102030405060708L,
        )
    }
}

private class ChangingIdentityScalarReader(
    private val onRead: () -> Unit,
) : TargetScalarReader {
    val int32Addresses = mutableListOf<Long>()

    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult {
        int32Addresses += address
        onRead()
        return TargetScalarReadResult(
            isSuccess = true,
            processStartTimeTicks = "start-$pid",
            valueBits = 17L,
        )
    }

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult =
        TargetScalarReadResult(isSuccess = false, message = "unexpected int64 read")
}

private class FakeTargetProcessInspector(
    val processes: MutableMap<Int, String> = mutableMapOf(),
    val startTimes: MutableMap<Int, String> = mutableMapOf(),
    val effectiveUids: MutableMap<Int, Int> = mutableMapOf(),
    private val briefs: MutableMap<Int, TargetProcessBrief> = mutableMapOf(),
    private val provideDefaultUid: Boolean = true,
) : TargetProcessInspector {
    var findCalls: Int = 0
    var briefCalls: Int = 0

    override fun findPid(packageName: String): Int? {
        findCalls += 1
        return processes.entries
            .sortedBy { it.key }
            .firstOrNull { it.value == packageName }
            ?.key
            ?: processes.entries
                .sortedBy { it.key }
                .firstOrNull { ControlProtocol.processMatchesPackage(it.value, packageName) }
                ?.key
    }

    override fun readProcessName(pid: Int): String? = processes[pid]

    override fun readStartTimeTicks(pid: Int): String? =
        startTimes[pid] ?: pid.takeIf(processes::containsKey)?.let { "start-$it" }

    override fun readEffectiveUid(pid: Int): Int? =
        effectiveUids[pid] ?: TEST_TARGET_UID.takeIf {
            provideDefaultUid && processes.containsKey(pid)
        }

    override fun readBrief(pid: Int): TargetProcessBrief? {
        briefCalls += 1
        return briefs[pid]
    }

    override fun isAlive(pid: Int): Boolean = pid in processes
}

private class RecordingTargetMemoryProbe : TargetMemoryProbe {
    val pids = mutableListOf<Int>()

    override fun inspect(pid: Int): TargetMemoryInspection {
        pids += pid
        return TargetMemoryInspection()
    }
}

private const val TEST_TARGET_UID = 10_321
