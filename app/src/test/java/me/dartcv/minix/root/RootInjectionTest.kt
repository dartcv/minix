package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootInjectionTest {
    @Test
    fun productionProfileRequiresTheExactGameArtifactBeforeExposingClosedFeatures() {
        val resolver = RootInjectionProfileCatalog.resolver

        assertEquals(
            RootInjectionProfileStatus.MODULE_NOT_FOUND,
            resolver.resolve(
                packageName = RootTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = emptyList(),
            ).state.profileStatus,
        )
        assertEquals(
            RootInjectionProfileStatus.FINGERPRINT_UNAVAILABLE,
            resolver.resolve(
                packageName = RootTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = listOf(productionModule(sha256 = null)),
            ).state.profileStatus,
        )
        assertEquals(
            RootInjectionProfileStatus.FINGERPRINT_MISMATCH,
            resolver.resolve(
                packageName = RootTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = listOf(productionModule(sha256 = "b".repeat(64))),
            ).state.profileStatus,
        )
        assertEquals(
            RootInjectionProfileStatus.MODULE_AMBIGUOUS,
            resolver.resolve(
                packageName = RootTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = listOf(
                    productionModule(),
                    productionModule().copy(path = "/data/app/duplicate/${RootGameAppArtifact1582.MODULE_NAME}"),
                ),
            ).state.profileStatus,
        )
        val result = resolver.resolve(
            packageName = RootTargetCatalog.default.packageName,
            serviceAbi = "arm64-v8a",
            modules = listOf(productionModule()),
        )

        assertEquals(RootInjectionProfileStatus.READY, result.state.profileStatus)
        assertEquals(RootGameAppArtifact1582.MODULE_NAME, result.profile?.moduleName)
        assertEquals(RootGameAppArtifact1582.SHA256, result.profile?.moduleSha256)
        assertEquals(
            setOf(RootFeature.FLIGHT, RootFeature.FAKE_FLIGHT),
            result.profile?.patches?.keys,
        )
        val patch = result.profile?.patches?.get(RootFeature.FLIGHT)
        assertNull(patch?.address)
        assertEquals(RootRecoveredInjectionRecipes.flightCommon10, patch?.addressRecipe)
        assertEquals(0L, patch?.disabledValueBits)
        assertEquals(8L, patch?.enabledValueBits)
        assertEquals(8L, patch?.valueMask)
        assertEquals(RootInjectionMappingRequirement.WRITABLE, patch?.mappingRequirement)
        assertEquals(
            RootAddressDereferenceMode.POINTER64,
            patch?.addressRecipe?.dereferenceMode,
        )
        assertEquals(
            RootGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
            patch?.addressRecipe?.initialSeed,
        )
        assertEquals(listOf(0x1a0L), patch?.addressRecipe?.intermediateOffsets)
        val fakeFlightPatch = result.profile?.patches?.get(RootFeature.FAKE_FLIGHT)
        assertEquals(0x7000_0000L + 0x052e13f8L, fakeFlightPatch?.address)
        assertEquals(0x39449269L, fakeFlightPatch?.disabledValueBits)
        assertEquals(0xd503201fL, fakeFlightPatch?.enabledValueBits)
        assertEquals(RootInjectionMappingRequirement.EXECUTABLE, fakeFlightPatch?.mappingRequirement)
        val fakeFlightEvidence = RootInjectionProfileCatalog.candidates
            .single()
            .patches
            .single { it.feature == RootFeature.FAKE_FLIGHT }
        assertTrue(fakeFlightEvidence.isComplete)
        assertNull(fakeFlightEvidence.absoluteAddress)
        assertEquals(0x052e13f8L, fakeFlightEvidence.moduleOffset)
        assertNull(fakeFlightEvidence.addressRecipe)
        assertEquals(
            "work/fake-flight-recovery-20260817/README.md",
            fakeFlightEvidence.sourceArtifact,
        )
        assertEquals(
            mapOf(
                RootPlayerPositionAxis.X to RootRecoveredInjectionRecipes.playerPositionX,
                RootPlayerPositionAxis.Y to RootRecoveredInjectionRecipes.playerPositionY,
                RootPlayerPositionAxis.Z to RootRecoveredInjectionRecipes.playerPositionZ,
            ),
            result.profile?.playerPosition?.axisRecipes,
        )
        assertEquals(100, result.profile?.playerPosition?.rawUnitsPerWorldUnit)
    }

    @Test
    fun completeProfileRequiresExactAbiAndFingerprint() {
        val resolver = RootInjectionProfileResolver(listOf(completeCandidate()))

        assertEquals(
            RootInjectionProfileStatus.ABI_MISMATCH,
            resolver.resolve(PACKAGE_NAME, "x86_64", listOf(matchedModule())).state.profileStatus,
        )
        assertEquals(
            RootInjectionProfileStatus.FINGERPRINT_MISMATCH,
            resolver.resolve(
                PACKAGE_NAME,
                "arm64-v8a",
                listOf(matchedModule().copy(sha256 = "b".repeat(64))),
            ).state.profileStatus,
        )
        assertEquals(
            RootInjectionProfileStatus.READY,
            resolver.resolve(PACKAGE_NAME, "arm64-v8a", listOf(matchedModule())).state.profileStatus,
        )
    }

    @Test
    fun sessionUsesReadCompareWriteVerifyAndPublishesTypedResult() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val session = injectionSession(inspector, scalarReader, executor)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(RootFeature.FLIGHT in session.snapshot().supportedFeatures)
        assertTrue(session.setFeatureEnabled(RootFeature.FLIGHT, true))

        assertEquals(1L, scalarReader.valueBits)
        assertEquals(2, scalarReader.readCount)
        assertEquals(1, executor.requests.size)
        assertEquals("start-100", executor.requests.single().expectedStartTimeTicks)
        assertEquals(RootInjectionApplyStatus.APPLIED, session.injectionState().lastApplyStatus)
        assertEquals(RootFeature.FLIGHT, session.injectionState().lastFeature)
        assertTrue(session.isFeatureEnabled(RootFeature.FLIGHT))
    }

    @Test
    fun sameProcessMapsRefreshKeepsEnabledFlightState() {
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val memoryProbe = MutableInjectionMemoryProbe("maps-a")
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = memoryProbe,
            scalarReader = scalarReader,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(completeCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(session.setFeatureEnabled(RootFeature.FLIGHT, true))
        memoryProbe.mapsFingerprint = "maps-b"

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
        assertTrue(session.isFeatureEnabled(RootFeature.FLIGHT))
        assertEquals(1, executor.requests.size)
    }

    @Test
    fun unexpectedScalarNeverReachesWriter() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 7L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val session = injectionSession(inspector, scalarReader, executor)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertFalse(session.setFeatureEnabled(RootFeature.FLIGHT, true))

        assertTrue(executor.requests.isEmpty())
        assertEquals(
            RootInjectionApplyStatus.EXPECTED_VALUE_MISMATCH,
            session.injectionState().lastApplyStatus,
        )
        assertFalse(session.isFeatureEnabled(RootFeature.FLIGHT))
    }

    @Test
    fun maskedScalarPatchPreservesUnrelatedPlayerFlags() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0x25L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val session = RootTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(maskedCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(session.setFeatureEnabled(RootFeature.FLIGHT, true))

        assertEquals(0x2dL, scalarReader.valueBits)
        assertEquals(0x25L, executor.requests.single().expectedValueBits)
        assertEquals(0x2dL, executor.requests.single().desiredValueBits)
    }

    @Test
    fun executablePatchUsesGuardedBidirectionalWordsAndReadback() {
        val disabledWord = 0x39449269L
        val enabledWord = 0xd503201fL
        val scalarReader = MutableInjectionScalarReader(valueBits = disabledWord)
        val executor = RecordingInjectionExecutor(scalarReader)
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(
                listOf(executablePatchCandidate()),
            ),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(RootFeature.FAKE_FLIGHT in session.snapshot().supportedFeatures)
        assertTrue(session.setFeatureEnabled(RootFeature.FAKE_FLIGHT, true))
        assertEquals(enabledWord, scalarReader.valueBits)
        assertTrue(session.isFeatureEnabled(RootFeature.FAKE_FLIGHT))

        assertTrue(session.setFeatureEnabled(RootFeature.FAKE_FLIGHT, false))
        assertEquals(disabledWord, scalarReader.valueBits)
        assertFalse(session.isFeatureEnabled(RootFeature.FAKE_FLIGHT))
        assertEquals(4, scalarReader.readCount)
        assertEquals(2, executor.requests.size)
        assertEquals(
            listOf(disabledWord, enabledWord),
            executor.requests.map(RootInjectionWriteRequest::expectedValueBits),
        )
        assertEquals(
            listOf(enabledWord, disabledWord),
            executor.requests.map(RootInjectionWriteRequest::desiredValueBits),
        )
        assertTrue(executor.requests.all {
            it.address == 0x100024L &&
                it.byteCount == Int.SIZE_BYTES &&
                it.mappingRequirement == RootInjectionMappingRequirement.EXECUTABLE
        })
    }

    @Test
    fun writerIdentityMismatchInvalidatesPinnedSession() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = RootInjectionExecutor { request ->
            RootInjectionWriteResult(
                status = RootInjectionApplyStatus.TARGET_CHANGED,
                processStartTimeTicks = "different",
                message = "fixture changed",
            )
        }
        val session = injectionSession(inspector, scalarReader, executor)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertFalse(session.setFeatureEnabled(RootFeature.FLIGHT, true))

        assertNull(session.snapshot().pid)
        assertEquals(RootInjectionApplyStatus.TARGET_CHANGED, session.injectionState().lastApplyStatus)
    }

    @Test
    fun recipePatchResolvesAddressBeforeReadCompareWriteVerify() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val recipeResolver = RecordingInjectionRecipeResolver(0x220000L)
        val session = RootTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = recipeResolver,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(recipeCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(session.setFeatureEnabled(RootFeature.FLIGHT, true))

        assertEquals(listOf(RootRecoveredInjectionRecipes.flightCommon10), recipeResolver.recipes)
        assertEquals(listOf(0x220000L, 0x220000L), scalarReader.addresses)
        assertEquals(0x220000L, executor.requests.single().address)
        assertEquals(
            RootInjectionMappingRequirement.WRITABLE,
            executor.requests.single().mappingRequirement,
        )
    }

    @Test
    fun backendExceptionIsConvertedToTypedWriteFailure() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val session = injectionSession(
            inspector = inspector,
            scalarReader = scalarReader,
            executor = RootInjectionExecutor { error("fixture backend crash") },
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertFalse(session.setFeatureEnabled(RootFeature.FLIGHT, true))

        assertEquals(RootInjectionApplyStatus.WRITE_FAILED, session.injectionState().lastApplyStatus)
        assertFalse(session.isFeatureEnabled(RootFeature.FLIGHT))
    }

    @Test
    fun playerPositionResolvesAndVerifiesThreeDedicatedAxes() {
        val inspector = InjectionTargetInspector()
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = RootTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )
        val request = RootPlayerPositionRequest(x = -1, y = 20, z = 30)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(RootFeature.PLAYER_TELEPORT in session.snapshot().supportedFeatures)
        val result = session.setPlayerPosition(request)

        assertEquals(RootInjectionApplyStatus.APPLIED, result.status)
        assertEquals(3, result.appliedAxisCount)
        assertEquals(0xffff_ffffL, scalarReader.values.getValue(0x3000L))
        assertEquals(20L, scalarReader.values.getValue(0x3004L))
        assertEquals(30L, scalarReader.values.getValue(0x3008L))
        assertEquals(
            listOf(0x3000L, 0x3004L, 0x3008L),
            executor.requests.map(RootInjectionWriteRequest::address),
        )
        assertTrue(executor.requests.all {
            it.mappingRequirement == RootInjectionMappingRequirement.WRITABLE
        })
        assertEquals(RootFeature.PLAYER_TELEPORT, session.injectionState().lastFeature)
    }

    @Test
    fun playerPositionAppliesProfileCoordinateScale() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 100L, 0x3004L to 200L, 0x3008L to 300L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(
                listOf(positionCandidate(rawUnitsPerWorldUnit = 100)),
            ),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(RootPlayerPositionRequest(-1, 20, 30))

        assertEquals(RootInjectionApplyStatus.APPLIED, result.status)
        assertEquals(0xffff_ff9cL, scalarReader.values.getValue(0x3000L))
        assertEquals(2_000L, scalarReader.values.getValue(0x3004L))
        assertEquals(3_000L, scalarReader.values.getValue(0x3008L))
        assertEquals(
            listOf(0xffff_ff9cL, 2_000L, 3_000L),
            executor.requests.map(RootInjectionWriteRequest::desiredValueBits),
        )
    }

    @Test
    fun playerPositionRejectsCoordinateThatOverflowsScaledInt32() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(
                listOf(positionCandidate(rawUnitsPerWorldUnit = 100)),
            ),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(RootPlayerPositionRequest(Int.MAX_VALUE, 20, 30))

        assertEquals(RootInjectionApplyStatus.INVALID_RESPONSE, result.status)
        assertTrue(result.message.contains("scale 100"))
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun playerPositionContinuesAfterSameProcessMapsRefresh() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val memoryProbe = MutableInjectionMemoryProbe("maps-a")
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = memoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        memoryProbe.mapsFingerprint = "maps-b"

        val result = session.setPlayerPosition(RootPlayerPositionRequest(10, 20, 30))

        assertEquals(RootInjectionApplyStatus.APPLIED, result.status)
        assertEquals(3, result.appliedAxisCount)
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
        assertEquals(3, executor.requests.size)
    }

    @Test
    fun playerPositionContinuesWhenMapsAdvanceBetweenGenerationAndFullInspection() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = RacingInjectionMemoryProbe(),
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))

        val result = session.setPlayerPosition(RootPlayerPositionRequest(10, 20, 30))

        assertEquals(RootInjectionApplyStatus.APPLIED, result.status)
        assertEquals(3, result.appliedAxisCount)
        assertEquals("maps-c", session.snapshot().nativeProbe.mapsFingerprint)
        assertEquals(100, session.snapshot().pid)
        assertEquals(3, executor.requests.size)
    }

    @Test
    fun playerPositionFailureRollsBackPreviouslyWrittenAxes() {
        val inspector = InjectionTargetInspector()
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val original = mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L)
        val scalarReader = PositionScalarReader(original)
        val executor = PositionInjectionExecutor(
            values = scalarReader.values,
            failAddress = 0x3008L,
        )
        val session = RootTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(RootPlayerPositionRequest(10, 20, 30))

        assertEquals(RootInjectionApplyStatus.WRITE_FAILED, result.status)
        assertEquals(mapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L), scalarReader.values)
        assertEquals(
            listOf(10L, 20L, 30L, 2L, 1L),
            executor.requests.map(RootInjectionWriteRequest::desiredValueBits),
        )
    }

    @Test
    fun playerPositionAlreadyMatchesWithoutWritingAndPreservesIntBitPatterns() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(
                0x3000L to 0x8000_0000L,
                0x3004L to 0L,
                0x3008L to 0x7fff_ffffL,
            ),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(
            RootPlayerPositionRequest(Int.MIN_VALUE, 0, Int.MAX_VALUE),
        )

        assertEquals(RootInjectionApplyStatus.ALREADY_APPLIED, result.status)
        assertEquals(0, result.appliedAxisCount)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun playerPositionRejectsDuplicateResolvedAxisAddresses() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(RootPlayerPositionRequest(10, 20, 30))

        assertEquals(RootInjectionApplyStatus.INVALID_RESPONSE, result.status)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun playerPositionVerificationFailureRollsBackCurrentAxis() {
        val addresses = mapOf(
            RootRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            RootRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            RootRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val requests = mutableListOf<RootInjectionWriteRequest>()
        val executor = RootInjectionExecutor { request ->
            requests += request
            RootInjectionWriteResult(
                status = if (request.desiredValueBits == 10L) {
                    RootInjectionApplyStatus.APPLIED
                } else {
                    RootInjectionApplyStatus.ALREADY_APPLIED
                },
                processStartTimeTicks = request.expectedStartTimeTicks,
            )
        }
        val session = RootTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = RootInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(RootPlayerPositionRequest(10, 20, 30))

        assertEquals(RootInjectionApplyStatus.VERIFY_FAILED, result.status)
        assertEquals(listOf(10L, 1L), requests.map(RootInjectionWriteRequest::desiredValueBits))
        assertEquals(1L, scalarReader.values.getValue(0x3000L))
    }

    private fun injectionSession(
        inspector: InjectionTargetInspector,
        scalarReader: MutableInjectionScalarReader,
        executor: RootInjectionExecutor,
    ): RootTargetSession = RootTargetSession(
        inspector = inspector,
        memoryProbe = InjectionMemoryProbe,
        scalarReader = scalarReader,
        serviceAbi = "arm64-v8a",
        injectionProfileResolver = RootInjectionProfileResolver(listOf(completeCandidate())),
        injectionExecutor = executor,
    )

    private fun completeCandidate(): RootInjectionProfileCandidate = RootInjectionProfileCandidate(
        profileId = "fixture-arm64-v1",
        schemaVersion = RootInjectionProfileCatalog.SCHEMA_VERSION,
        targetVersion = "1.0",
        packageNames = setOf(PACKAGE_NAME),
        requiredAbi = "arm64-v8a",
        moduleEvidence = RootModuleIdentityEvidence(
            moduleName = MODULE_NAME,
            expectedSha256 = SHA256,
            sourceArtifact = "fixture.json",
            sourceReference = "fixture module",
        ),
        patches = listOf(
            RootInjectionScalarPatchEvidence(
                feature = RootFeature.FLIGHT,
                moduleOffset = 0x20L,
                byteCount = Int.SIZE_BYTES,
                disabledValueBits = 0L,
                enabledValueBits = 1L,
                mappingRequirement = RootInjectionMappingRequirement.WRITABLE,
                sourceArtifact = "fixture.json",
                sourceReference = "fixture scalar patch",
            ),
        ),
    )

    private fun recipeCandidate(): RootInjectionProfileCandidate = RootInjectionProfileCandidate(
        profileId = "fixture-recipe-arm64-v1",
        schemaVersion = RootInjectionProfileCatalog.SCHEMA_VERSION,
        targetVersion = "1.0",
        packageNames = setOf(PACKAGE_NAME),
        requiredAbi = "arm64-v8a",
        moduleEvidence = RootModuleIdentityEvidence(
            moduleName = "libunresolved.so",
            expectedSha256 = null,
            sourceArtifact = "fixture.json",
            sourceReference = "recipe does not require a module offset",
        ),
        patches = listOf(
            RootInjectionScalarPatchEvidence(
                feature = RootFeature.FLIGHT,
                moduleOffset = null,
                addressRecipe = RootRecoveredInjectionRecipes.flightCommon10,
                byteCount = Int.SIZE_BYTES,
                disabledValueBits = 0L,
                enabledValueBits = 8L,
                mappingRequirement = RootInjectionMappingRequirement.WRITABLE,
                sourceArtifact = "fixture.json",
                sourceReference = "fixture recipe patch",
            ),
        ),
    )

    private fun maskedCandidate(): RootInjectionProfileCandidate = completeCandidate().copy(
        profileId = "fixture-masked-arm64-v1",
        patches = listOf(
            completeCandidate().patches.single().copy(
                disabledValueBits = 0L,
                enabledValueBits = 8L,
                valueMask = 8L,
            ),
        ),
    )

    private fun executablePatchCandidate(): RootInjectionProfileCandidate = completeCandidate().copy(
        profileId = "fixture-executable-arm64-v1",
        patches = listOf(
            RootInjectionScalarPatchEvidence(
                feature = RootFeature.FAKE_FLIGHT,
                moduleOffset = 0x24L,
                byteCount = Int.SIZE_BYTES,
                disabledValueBits = 0x39449269L,
                enabledValueBits = 0xd503201fL,
                mappingRequirement = RootInjectionMappingRequirement.EXECUTABLE,
                sourceArtifact = "fixture-executable.json",
                sourceReference = "fixture AArch64 instruction patch",
            ),
        ),
    )

    private fun positionCandidate(
        rawUnitsPerWorldUnit: Int = 1,
    ): RootInjectionProfileCandidate = recipeCandidate().copy(
        profileId = "fixture-position-arm64-v1",
        playerPositionEvidence = RootPlayerPositionEvidence(
            axisRecipes = mapOf(
                RootPlayerPositionAxis.X to RootRecoveredInjectionRecipes.playerPositionX,
                RootPlayerPositionAxis.Y to RootRecoveredInjectionRecipes.playerPositionY,
                RootPlayerPositionAxis.Z to RootRecoveredInjectionRecipes.playerPositionZ,
            ),
            rawUnitsPerWorldUnit = rawUnitsPerWorldUnit,
            sourceArtifact = "fixture-position.json",
            sourceReference = "fixture three-axis position recipes",
        ),
    )

    private fun matchedModule(): RootNativeModuleIdentity = RootNativeModuleIdentity(
        name = MODULE_NAME,
        path = "/data/app/fixture/$MODULE_NAME",
        loadBase = 0x100000L,
        mappedBytes = 0x1000L,
        memoryElf = true,
        sha256 = SHA256,
    )

    private fun productionModule(
        sha256: String? = RootGameAppArtifact1582.SHA256,
    ): RootNativeModuleIdentity = RootNativeModuleIdentity(
        name = RootGameAppArtifact1582.MODULE_NAME,
        path = "/data/app/fixture/${RootGameAppArtifact1582.MODULE_NAME}",
        loadBase = 0x7000_0000L,
        mappedBytes = RootGameAppArtifact1582.LOAD_VIRTUAL_END,
        memoryElf = true,
        sha256 = sha256,
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.target"
        const val MODULE_NAME = "libfixture.so"
        val SHA256 = "a".repeat(64)
    }
}

private class InjectionTargetInspector : TargetProcessInspector {
    override fun findPid(packageName: String): Int? =
        if (packageName == "com.example.target") 100 else null

    override fun readProcessName(pid: Int): String? =
        if (pid == 100) "com.example.target" else null

    override fun readStartTimeTicks(pid: Int): String? =
        if (pid == 100) "start-100" else null

    override fun readEffectiveUid(pid: Int): Int? =
        if (pid == 100) 10_321 else null

    override fun readBrief(pid: Int): TargetProcessBrief? = null

    override fun isAlive(pid: Int): Boolean = pid == 100
}

private object InjectionMemoryProbe : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection(
        state = RootNativeProbeState(
            status = RootNativeProbeStatus.OK,
            processStartTimeTicks = "start-100",
            regionCount = 4,
            moduleCount = 1,
            memoryReadableModuleCount = 1,
            memoryReadBytes = 64,
            memoryElfHeaderCount = 1,
            mapsFingerprint = "fixture-maps",
        ),
        modules = listOf(
            RootNativeModuleIdentity(
                name = "libfixture.so",
                path = "/data/app/fixture/libfixture.so",
                loadBase = 0x100000L,
                mappedBytes = 0x1000L,
                memoryElf = true,
                sha256 = "a".repeat(64),
            ),
        ),
    )
}

private class MutableInjectionMemoryProbe(
    var mapsFingerprint: String,
) : TargetMemoryProbe {
    override fun inspect(pid: Int): TargetMemoryInspection = TargetMemoryInspection(
        state = RootNativeProbeState(
            status = RootNativeProbeStatus.OK,
            processStartTimeTicks = "start-100",
            regionCount = 4,
            moduleCount = 1,
            memoryReadableModuleCount = 1,
            memoryReadBytes = 64,
            memoryElfHeaderCount = 1,
            mapsFingerprint = mapsFingerprint,
        ),
        modules = listOf(
            RootNativeModuleIdentity(
                name = "libfixture.so",
                path = "/data/app/fixture/libfixture.so",
                loadBase = 0x100000L,
                mappedBytes = 0x1000L,
                memoryElf = true,
                sha256 = "a".repeat(64),
            ),
        ),
    )
}

private class RacingInjectionMemoryProbe : TargetMemoryProbe {
    private var inspectionCount = 0

    override fun inspect(pid: Int): TargetMemoryInspection {
        inspectionCount += 1
        return injectionMemoryInspection(
            mapsFingerprint = if (inspectionCount == 1) "maps-a" else "maps-c",
        )
    }

    override fun inspectGeneration(pid: Int): RootNativeProbeState =
        injectionMemoryInspection("maps-b").state
}

private fun injectionMemoryInspection(mapsFingerprint: String): TargetMemoryInspection =
    TargetMemoryInspection(
        state = RootNativeProbeState(
            status = RootNativeProbeStatus.OK,
            processStartTimeTicks = "start-100",
            regionCount = 4,
            moduleCount = 1,
            memoryReadableModuleCount = 1,
            memoryReadBytes = 64,
            memoryElfHeaderCount = 1,
            mapsFingerprint = mapsFingerprint,
        ),
        modules = listOf(
            RootNativeModuleIdentity(
                name = "libfixture.so",
                path = "/data/app/fixture/libfixture.so",
                loadBase = 0x100000L,
                mappedBytes = 0x1000L,
                memoryElf = true,
                sha256 = "a".repeat(64),
            ),
        ),
    )

private class MutableInjectionScalarReader(
    var valueBits: Long,
) : TargetScalarReader {
    var readCount: Int = 0
    val addresses = mutableListOf<Long>()

    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult {
        readCount += 1
        addresses += address
        return TargetScalarReadResult(
            isSuccess = true,
            processStartTimeTicks = "start-$pid",
            valueBits = valueBits,
        )
    }

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult =
        readInt32(pid, address)
}

private class RecordingInjectionRecipeResolver(
    private val address: Long,
) : RootAddressRecipeResolver {
    val recipes = mutableListOf<RootResolverAddressRecipe>()

    override fun resolve(pid: Int, recipe: RootResolverAddressRecipe): RootAddressRecipeResult {
        recipes += recipe
        return RootAddressRecipeResult(
            status = RootAddressRecipeStatus.RESOLVED,
            address = address,
            processStartTimeTicks = "start-$pid",
        )
    }
}

private class RecordingInjectionExecutor(
    private val scalarReader: MutableInjectionScalarReader,
) : RootInjectionExecutor {
    val requests = mutableListOf<RootInjectionWriteRequest>()

    override fun apply(request: RootInjectionWriteRequest): RootInjectionWriteResult {
        requests += request
        scalarReader.valueBits = request.desiredValueBits
        return RootInjectionWriteResult(
            status = RootInjectionApplyStatus.APPLIED,
            processStartTimeTicks = request.expectedStartTimeTicks,
        )
    }
}

private class PositionRecipeResolver(
    private val addresses: Map<RootResolverAddressRecipe, Long>,
) : RootAddressRecipeResolver {
    override fun resolve(pid: Int, recipe: RootResolverAddressRecipe): RootAddressRecipeResult =
        RootAddressRecipeResult(
            status = if (recipe in addresses) {
                RootAddressRecipeStatus.RESOLVED
            } else {
                RootAddressRecipeStatus.INVALID_RECIPE
            },
            address = addresses[recipe],
            processStartTimeTicks = "start-$pid",
        )
}

private class PositionScalarReader(
    val values: MutableMap<Long, Long>,
) : TargetScalarReader {
    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult =
        values[address]?.let { value ->
            TargetScalarReadResult(
                isSuccess = true,
                processStartTimeTicks = "start-$pid",
                valueBits = value,
            )
        } ?: TargetScalarReadResult(
            isSuccess = false,
            processStartTimeTicks = "start-$pid",
            message = "fixture address missing",
        )

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult =
        readInt32(pid, address)
}

private class PositionInjectionExecutor(
    private val values: MutableMap<Long, Long>,
    private val failAddress: Long? = null,
) : RootInjectionExecutor {
    val requests = mutableListOf<RootInjectionWriteRequest>()

    override fun apply(request: RootInjectionWriteRequest): RootInjectionWriteResult {
        requests += request
        if (request.address == failAddress && request.desiredValueBits == 30L) {
            return RootInjectionWriteResult(
                status = RootInjectionApplyStatus.WRITE_FAILED,
                processStartTimeTicks = request.expectedStartTimeTicks,
                message = "fixture axis write failed",
            )
        }
        val current = values[request.address]
        if (current == request.desiredValueBits) {
            return RootInjectionWriteResult(
                status = RootInjectionApplyStatus.ALREADY_APPLIED,
                processStartTimeTicks = request.expectedStartTimeTicks,
            )
        }
        if (current != request.expectedValueBits) {
            return RootInjectionWriteResult(
                status = RootInjectionApplyStatus.EXPECTED_VALUE_MISMATCH,
                processStartTimeTicks = request.expectedStartTimeTicks,
            )
        }
        values[request.address] = request.desiredValueBits
        return RootInjectionWriteResult(
            status = RootInjectionApplyStatus.APPLIED,
            processStartTimeTicks = request.expectedStartTimeTicks,
        )
    }
}
