package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlInjectionTest {
    @Test
    fun productionProfileRequiresTheExactGameArtifactBeforeExposingClosedFeatures() {
        val resolver = ControlInjectionProfileCatalog.resolver

        assertEquals(
            ControlInjectionProfileStatus.MODULE_NOT_FOUND,
            resolver.resolve(
                packageName = ControlTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = emptyList(),
            ).state.profileStatus,
        )
        assertEquals(
            ControlInjectionProfileStatus.FINGERPRINT_UNAVAILABLE,
            resolver.resolve(
                packageName = ControlTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = listOf(productionModule(sha256 = null)),
            ).state.profileStatus,
        )
        assertEquals(
            ControlInjectionProfileStatus.FINGERPRINT_MISMATCH,
            resolver.resolve(
                packageName = ControlTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = listOf(productionModule(sha256 = "b".repeat(64))),
            ).state.profileStatus,
        )
        assertEquals(
            ControlInjectionProfileStatus.MODULE_AMBIGUOUS,
            resolver.resolve(
                packageName = ControlTargetCatalog.default.packageName,
                serviceAbi = "arm64-v8a",
                modules = listOf(
                    productionModule(),
                    productionModule().copy(path = "/data/app/duplicate/${ControlGameAppArtifact1582.MODULE_NAME}"),
                ),
            ).state.profileStatus,
        )
        val result = resolver.resolve(
            packageName = ControlTargetCatalog.default.packageName,
            serviceAbi = "arm64-v8a",
            modules = listOf(productionModule()),
        )

        assertEquals(ControlInjectionProfileStatus.READY, result.state.profileStatus)
        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, result.profile?.moduleName)
        assertEquals(ControlGameAppArtifact1582.SHA256, result.profile?.moduleSha256)
        assertEquals(
            setOf(ControlFeature.FLIGHT, ControlFeature.FAKE_FLIGHT),
            result.profile?.patches?.keys,
        )
        val patch = result.profile?.patches?.get(ControlFeature.FLIGHT)
        assertNull(patch?.address)
        assertEquals(ControlRecoveredInjectionRecipes.flightCommon10, patch?.addressRecipe)
        assertEquals(0L, patch?.disabledValueBits)
        assertEquals(8L, patch?.enabledValueBits)
        assertEquals(8L, patch?.valueMask)
        assertEquals(ControlInjectionMappingRequirement.WRITABLE, patch?.mappingRequirement)
        assertEquals(
            ControlAddressDereferenceMode.POINTER64,
            patch?.addressRecipe?.dereferenceMode,
        )
        assertEquals(
            ControlGameAppArtifact1582.PLAYER_CONTROL_BSS_OFFSET,
            patch?.addressRecipe?.initialSeed,
        )
        assertEquals(listOf(0x1a0L), patch?.addressRecipe?.intermediateOffsets)
        val fakeFlightPatch = result.profile?.patches?.get(ControlFeature.FAKE_FLIGHT)
        assertEquals(0x7000_0000L + 0x052e13f8L, fakeFlightPatch?.address)
        assertEquals(0x39449269L, fakeFlightPatch?.disabledValueBits)
        assertEquals(0xd503201fL, fakeFlightPatch?.enabledValueBits)
        assertEquals(ControlInjectionMappingRequirement.EXECUTABLE, fakeFlightPatch?.mappingRequirement)
        val fakeFlightEvidence = ControlInjectionProfileCatalog.candidates
            .single()
            .patches
            .single { it.feature == ControlFeature.FAKE_FLIGHT }
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
                ControlPlayerPositionAxis.X to ControlRecoveredInjectionRecipes.playerPositionX,
                ControlPlayerPositionAxis.Y to ControlRecoveredInjectionRecipes.playerPositionY,
                ControlPlayerPositionAxis.Z to ControlRecoveredInjectionRecipes.playerPositionZ,
            ),
            result.profile?.playerPosition?.axisRecipes,
        )
        assertEquals(100, result.profile?.playerPosition?.rawUnitsPerWorldUnit)
    }

    @Test
    fun completeProfileRequiresExactAbiAndFingerprint() {
        val resolver = ControlInjectionProfileResolver(listOf(completeCandidate()))

        assertEquals(
            ControlInjectionProfileStatus.ABI_MISMATCH,
            resolver.resolve(PACKAGE_NAME, "x86_64", listOf(matchedModule())).state.profileStatus,
        )
        assertEquals(
            ControlInjectionProfileStatus.FINGERPRINT_MISMATCH,
            resolver.resolve(
                PACKAGE_NAME,
                "arm64-v8a",
                listOf(matchedModule().copy(sha256 = "b".repeat(64))),
            ).state.profileStatus,
        )
        assertEquals(
            ControlInjectionProfileStatus.READY,
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
        assertTrue(ControlFeature.FLIGHT in session.snapshot().supportedFeatures)
        assertTrue(session.setFeatureEnabled(ControlFeature.FLIGHT, true))

        assertEquals(1L, scalarReader.valueBits)
        assertEquals(2, scalarReader.readCount)
        assertEquals(1, executor.requests.size)
        assertEquals("start-100", executor.requests.single().expectedStartTimeTicks)
        assertEquals(ControlInjectionApplyStatus.APPLIED, session.injectionState().lastApplyStatus)
        assertEquals(ControlFeature.FLIGHT, session.injectionState().lastFeature)
        assertTrue(session.isFeatureEnabled(ControlFeature.FLIGHT))
    }

    @Test
    fun sameProcessMapsRefreshKeepsEnabledFlightState() {
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val memoryProbe = MutableInjectionMemoryProbe("maps-a")
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = memoryProbe,
            scalarReader = scalarReader,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(completeCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(session.setFeatureEnabled(ControlFeature.FLIGHT, true))
        memoryProbe.mapsFingerprint = "maps-b"

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
        assertTrue(session.isFeatureEnabled(ControlFeature.FLIGHT))
        assertEquals(1, executor.requests.size)
    }

    @Test
    fun unexpectedScalarNeverReachesWriter() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 7L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val session = injectionSession(inspector, scalarReader, executor)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertFalse(session.setFeatureEnabled(ControlFeature.FLIGHT, true))

        assertTrue(executor.requests.isEmpty())
        assertEquals(
            ControlInjectionApplyStatus.EXPECTED_VALUE_MISMATCH,
            session.injectionState().lastApplyStatus,
        )
        assertFalse(session.isFeatureEnabled(ControlFeature.FLIGHT))
    }

    @Test
    fun maskedScalarPatchPreservesUnrelatedPlayerFlags() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0x25L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(maskedCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(session.setFeatureEnabled(ControlFeature.FLIGHT, true))

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
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(
                listOf(executablePatchCandidate()),
            ),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(ControlFeature.FAKE_FLIGHT in session.snapshot().supportedFeatures)
        assertTrue(session.setFeatureEnabled(ControlFeature.FAKE_FLIGHT, true))
        assertEquals(enabledWord, scalarReader.valueBits)
        assertTrue(session.isFeatureEnabled(ControlFeature.FAKE_FLIGHT))

        assertTrue(session.setFeatureEnabled(ControlFeature.FAKE_FLIGHT, false))
        assertEquals(disabledWord, scalarReader.valueBits)
        assertFalse(session.isFeatureEnabled(ControlFeature.FAKE_FLIGHT))
        assertEquals(4, scalarReader.readCount)
        assertEquals(2, executor.requests.size)
        assertEquals(
            listOf(disabledWord, enabledWord),
            executor.requests.map(ControlInjectionWriteRequest::expectedValueBits),
        )
        assertEquals(
            listOf(enabledWord, disabledWord),
            executor.requests.map(ControlInjectionWriteRequest::desiredValueBits),
        )
        assertTrue(executor.requests.all {
            it.address == 0x100024L &&
                it.byteCount == Int.SIZE_BYTES &&
                it.mappingRequirement == ControlInjectionMappingRequirement.EXECUTABLE
        })
    }

    @Test
    fun writerIdentityMismatchInvalidatesPinnedSession() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = ControlInjectionExecutor { request ->
            ControlInjectionWriteResult(
                status = ControlInjectionApplyStatus.TARGET_CHANGED,
                processStartTimeTicks = "different",
                message = "fixture changed",
            )
        }
        val session = injectionSession(inspector, scalarReader, executor)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertFalse(session.setFeatureEnabled(ControlFeature.FLIGHT, true))

        assertNull(session.snapshot().pid)
        assertEquals(ControlInjectionApplyStatus.TARGET_CHANGED, session.injectionState().lastApplyStatus)
    }

    @Test
    fun recipePatchResolvesAddressBeforeReadCompareWriteVerify() {
        val inspector = InjectionTargetInspector()
        val scalarReader = MutableInjectionScalarReader(valueBits = 0L)
        val executor = RecordingInjectionExecutor(scalarReader)
        val recipeResolver = RecordingInjectionRecipeResolver(0x220000L)
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = recipeResolver,
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(recipeCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(session.setFeatureEnabled(ControlFeature.FLIGHT, true))

        assertEquals(listOf(ControlRecoveredInjectionRecipes.flightCommon10), recipeResolver.recipes)
        assertEquals(listOf(0x220000L, 0x220000L), scalarReader.addresses)
        assertEquals(0x220000L, executor.requests.single().address)
        assertEquals(
            ControlInjectionMappingRequirement.WRITABLE,
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
            executor = ControlInjectionExecutor { error("fixture backend crash") },
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertFalse(session.setFeatureEnabled(ControlFeature.FLIGHT, true))

        assertEquals(ControlInjectionApplyStatus.WRITE_FAILED, session.injectionState().lastApplyStatus)
        assertFalse(session.isFeatureEnabled(ControlFeature.FLIGHT))
    }

    @Test
    fun playerPositionResolvesAndVerifiesThreeDedicatedAxes() {
        val inspector = InjectionTargetInspector()
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )
        val request = ControlPlayerPositionRequest(x = -1, y = 20, z = 30)

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        assertTrue(ControlFeature.PLAYER_TELEPORT in session.snapshot().supportedFeatures)
        val result = session.setPlayerPosition(request)

        assertEquals(ControlInjectionApplyStatus.APPLIED, result.status)
        assertEquals(3, result.appliedAxisCount)
        assertEquals(0xffff_ffffL, scalarReader.values.getValue(0x3000L))
        assertEquals(20L, scalarReader.values.getValue(0x3004L))
        assertEquals(30L, scalarReader.values.getValue(0x3008L))
        assertEquals(
            listOf(0x3000L, 0x3004L, 0x3008L),
            executor.requests.map(ControlInjectionWriteRequest::address),
        )
        assertTrue(executor.requests.all {
            it.mappingRequirement == ControlInjectionMappingRequirement.WRITABLE
        })
        assertEquals(ControlFeature.PLAYER_TELEPORT, session.injectionState().lastFeature)
    }

    @Test
    fun playerPositionAppliesProfileCoordinateScale() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 100L, 0x3004L to 200L, 0x3008L to 300L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(
                listOf(positionCandidate(rawUnitsPerWorldUnit = 100)),
            ),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(ControlPlayerPositionRequest(-1, 20, 30))

        assertEquals(ControlInjectionApplyStatus.APPLIED, result.status)
        assertEquals(0xffff_ff9cL, scalarReader.values.getValue(0x3000L))
        assertEquals(2_000L, scalarReader.values.getValue(0x3004L))
        assertEquals(3_000L, scalarReader.values.getValue(0x3008L))
        assertEquals(
            listOf(0xffff_ff9cL, 2_000L, 3_000L),
            executor.requests.map(ControlInjectionWriteRequest::desiredValueBits),
        )
    }

    @Test
    fun playerPositionRejectsCoordinateThatOverflowsScaledInt32() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(
                listOf(positionCandidate(rawUnitsPerWorldUnit = 100)),
            ),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(ControlPlayerPositionRequest(Int.MAX_VALUE, 20, 30))

        assertEquals(ControlInjectionApplyStatus.INVALID_RESPONSE, result.status)
        assertTrue(result.message.contains("scale 100"))
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun playerPositionContinuesAfterSameProcessMapsRefresh() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val memoryProbe = MutableInjectionMemoryProbe("maps-a")
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = memoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        memoryProbe.mapsFingerprint = "maps-b"

        val result = session.setPlayerPosition(ControlPlayerPositionRequest(10, 20, 30))

        assertEquals(ControlInjectionApplyStatus.APPLIED, result.status)
        assertEquals(3, result.appliedAxisCount)
        assertEquals("maps-b", session.snapshot().nativeProbe.mapsFingerprint)
        assertEquals(3, executor.requests.size)
    }

    @Test
    fun playerPositionContinuesWhenMapsAdvanceBetweenGenerationAndFullInspection() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = RacingInjectionMemoryProbe(),
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))

        val result = session.setPlayerPosition(ControlPlayerPositionRequest(10, 20, 30))

        assertEquals(ControlInjectionApplyStatus.APPLIED, result.status)
        assertEquals(3, result.appliedAxisCount)
        assertEquals("maps-c", session.snapshot().nativeProbe.mapsFingerprint)
        assertEquals(100, session.snapshot().pid)
        assertEquals(3, executor.requests.size)
    }

    @Test
    fun playerPositionFailureRollsBackPreviouslyWrittenAxes() {
        val inspector = InjectionTargetInspector()
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val original = mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L)
        val scalarReader = PositionScalarReader(original)
        val executor = PositionInjectionExecutor(
            values = scalarReader.values,
            failAddress = 0x3008L,
        )
        val session = ControlTargetSession(
            inspector = inspector,
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(ControlPlayerPositionRequest(10, 20, 30))

        assertEquals(ControlInjectionApplyStatus.WRITE_FAILED, result.status)
        assertEquals(mapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L), scalarReader.values)
        assertEquals(
            listOf(10L, 20L, 30L, 2L, 1L),
            executor.requests.map(ControlInjectionWriteRequest::desiredValueBits),
        )
    }

    @Test
    fun playerPositionAlreadyMatchesWithoutWritingAndPreservesIntBitPatterns() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(
                0x3000L to 0x8000_0000L,
                0x3004L to 0L,
                0x3008L to 0x7fff_ffffL,
            ),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(
            ControlPlayerPositionRequest(Int.MIN_VALUE, 0, Int.MAX_VALUE),
        )

        assertEquals(ControlInjectionApplyStatus.ALREADY_APPLIED, result.status)
        assertEquals(0, result.appliedAxisCount)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun playerPositionRejectsDuplicateResolvedAxisAddresses() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3008L to 3L),
        )
        val executor = PositionInjectionExecutor(scalarReader.values)
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(ControlPlayerPositionRequest(10, 20, 30))

        assertEquals(ControlInjectionApplyStatus.INVALID_RESPONSE, result.status)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun playerPositionVerificationFailureRollsBackCurrentAxis() {
        val addresses = mapOf(
            ControlRecoveredInjectionRecipes.playerPositionX to 0x3000L,
            ControlRecoveredInjectionRecipes.playerPositionY to 0x3004L,
            ControlRecoveredInjectionRecipes.playerPositionZ to 0x3008L,
        )
        val scalarReader = PositionScalarReader(
            mutableMapOf(0x3000L to 1L, 0x3004L to 2L, 0x3008L to 3L),
        )
        val requests = mutableListOf<ControlInjectionWriteRequest>()
        val executor = ControlInjectionExecutor { request ->
            requests += request
            ControlInjectionWriteResult(
                status = if (request.desiredValueBits == 10L) {
                    ControlInjectionApplyStatus.APPLIED
                } else {
                    ControlInjectionApplyStatus.ALREADY_APPLIED
                },
                processStartTimeTicks = request.expectedStartTimeTicks,
            )
        }
        val session = ControlTargetSession(
            inspector = InjectionTargetInspector(),
            memoryProbe = InjectionMemoryProbe,
            scalarReader = scalarReader,
            addressRecipeResolver = PositionRecipeResolver(addresses),
            serviceAbi = "arm64-v8a",
            injectionProfileResolver = ControlInjectionProfileResolver(listOf(positionCandidate())),
            injectionExecutor = executor,
        )

        assertTrue(session.openOrRefresh(PACKAGE_NAME))
        val result = session.setPlayerPosition(ControlPlayerPositionRequest(10, 20, 30))

        assertEquals(ControlInjectionApplyStatus.VERIFY_FAILED, result.status)
        assertEquals(listOf(10L, 1L), requests.map(ControlInjectionWriteRequest::desiredValueBits))
        assertEquals(1L, scalarReader.values.getValue(0x3000L))
    }

    private fun injectionSession(
        inspector: InjectionTargetInspector,
        scalarReader: MutableInjectionScalarReader,
        executor: ControlInjectionExecutor,
    ): ControlTargetSession = ControlTargetSession(
        inspector = inspector,
        memoryProbe = InjectionMemoryProbe,
        scalarReader = scalarReader,
        serviceAbi = "arm64-v8a",
        injectionProfileResolver = ControlInjectionProfileResolver(listOf(completeCandidate())),
        injectionExecutor = executor,
    )

    private fun completeCandidate(): ControlInjectionProfileCandidate = ControlInjectionProfileCandidate(
        profileId = "fixture-arm64-v1",
        schemaVersion = ControlInjectionProfileCatalog.SCHEMA_VERSION,
        targetVersion = "1.0",
        packageNames = setOf(PACKAGE_NAME),
        requiredAbi = "arm64-v8a",
        moduleEvidence = ControlModuleIdentityEvidence(
            moduleName = MODULE_NAME,
            expectedSha256 = SHA256,
            sourceArtifact = "fixture.json",
            sourceReference = "fixture module",
        ),
        patches = listOf(
            ControlInjectionScalarPatchEvidence(
                feature = ControlFeature.FLIGHT,
                moduleOffset = 0x20L,
                byteCount = Int.SIZE_BYTES,
                disabledValueBits = 0L,
                enabledValueBits = 1L,
                mappingRequirement = ControlInjectionMappingRequirement.WRITABLE,
                sourceArtifact = "fixture.json",
                sourceReference = "fixture scalar patch",
            ),
        ),
    )

    private fun recipeCandidate(): ControlInjectionProfileCandidate = ControlInjectionProfileCandidate(
        profileId = "fixture-recipe-arm64-v1",
        schemaVersion = ControlInjectionProfileCatalog.SCHEMA_VERSION,
        targetVersion = "1.0",
        packageNames = setOf(PACKAGE_NAME),
        requiredAbi = "arm64-v8a",
        moduleEvidence = ControlModuleIdentityEvidence(
            moduleName = "libunresolved.so",
            expectedSha256 = null,
            sourceArtifact = "fixture.json",
            sourceReference = "recipe does not require a module offset",
        ),
        patches = listOf(
            ControlInjectionScalarPatchEvidence(
                feature = ControlFeature.FLIGHT,
                moduleOffset = null,
                addressRecipe = ControlRecoveredInjectionRecipes.flightCommon10,
                byteCount = Int.SIZE_BYTES,
                disabledValueBits = 0L,
                enabledValueBits = 8L,
                mappingRequirement = ControlInjectionMappingRequirement.WRITABLE,
                sourceArtifact = "fixture.json",
                sourceReference = "fixture recipe patch",
            ),
        ),
    )

    private fun maskedCandidate(): ControlInjectionProfileCandidate = completeCandidate().copy(
        profileId = "fixture-masked-arm64-v1",
        patches = listOf(
            completeCandidate().patches.single().copy(
                disabledValueBits = 0L,
                enabledValueBits = 8L,
                valueMask = 8L,
            ),
        ),
    )

    private fun executablePatchCandidate(): ControlInjectionProfileCandidate = completeCandidate().copy(
        profileId = "fixture-executable-arm64-v1",
        patches = listOf(
            ControlInjectionScalarPatchEvidence(
                feature = ControlFeature.FAKE_FLIGHT,
                moduleOffset = 0x24L,
                byteCount = Int.SIZE_BYTES,
                disabledValueBits = 0x39449269L,
                enabledValueBits = 0xd503201fL,
                mappingRequirement = ControlInjectionMappingRequirement.EXECUTABLE,
                sourceArtifact = "fixture-executable.json",
                sourceReference = "fixture AArch64 instruction patch",
            ),
        ),
    )

    private fun positionCandidate(
        rawUnitsPerWorldUnit: Int = 1,
    ): ControlInjectionProfileCandidate = recipeCandidate().copy(
        profileId = "fixture-position-arm64-v1",
        playerPositionEvidence = ControlPlayerPositionEvidence(
            axisRecipes = mapOf(
                ControlPlayerPositionAxis.X to ControlRecoveredInjectionRecipes.playerPositionX,
                ControlPlayerPositionAxis.Y to ControlRecoveredInjectionRecipes.playerPositionY,
                ControlPlayerPositionAxis.Z to ControlRecoveredInjectionRecipes.playerPositionZ,
            ),
            rawUnitsPerWorldUnit = rawUnitsPerWorldUnit,
            sourceArtifact = "fixture-position.json",
            sourceReference = "fixture three-axis position recipes",
        ),
    )

    private fun matchedModule(): ControlNativeModuleIdentity = ControlNativeModuleIdentity(
        name = MODULE_NAME,
        path = "/data/app/fixture/$MODULE_NAME",
        loadBase = 0x100000L,
        mappedBytes = 0x1000L,
        memoryElf = true,
        sha256 = SHA256,
    )

    private fun productionModule(
        sha256: String? = ControlGameAppArtifact1582.SHA256,
    ): ControlNativeModuleIdentity = ControlNativeModuleIdentity(
        name = ControlGameAppArtifact1582.MODULE_NAME,
        path = "/data/app/fixture/${ControlGameAppArtifact1582.MODULE_NAME}",
        loadBase = 0x7000_0000L,
        mappedBytes = ControlGameAppArtifact1582.LOAD_VIRTUAL_END,
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
        state = ControlNativeProbeState(
            status = ControlNativeProbeStatus.OK,
            processStartTimeTicks = "start-100",
            regionCount = 4,
            moduleCount = 1,
            memoryReadableModuleCount = 1,
            memoryReadBytes = 64,
            memoryElfHeaderCount = 1,
            mapsFingerprint = "fixture-maps",
        ),
        modules = listOf(
            ControlNativeModuleIdentity(
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
        state = ControlNativeProbeState(
            status = ControlNativeProbeStatus.OK,
            processStartTimeTicks = "start-100",
            regionCount = 4,
            moduleCount = 1,
            memoryReadableModuleCount = 1,
            memoryReadBytes = 64,
            memoryElfHeaderCount = 1,
            mapsFingerprint = mapsFingerprint,
        ),
        modules = listOf(
            ControlNativeModuleIdentity(
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

    override fun inspectGeneration(pid: Int): ControlNativeProbeState =
        injectionMemoryInspection("maps-b").state
}

private fun injectionMemoryInspection(mapsFingerprint: String): TargetMemoryInspection =
    TargetMemoryInspection(
        state = ControlNativeProbeState(
            status = ControlNativeProbeStatus.OK,
            processStartTimeTicks = "start-100",
            regionCount = 4,
            moduleCount = 1,
            memoryReadableModuleCount = 1,
            memoryReadBytes = 64,
            memoryElfHeaderCount = 1,
            mapsFingerprint = mapsFingerprint,
        ),
        modules = listOf(
            ControlNativeModuleIdentity(
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

private class RecordingInjectionExecutor(
    private val scalarReader: MutableInjectionScalarReader,
) : ControlInjectionExecutor {
    val requests = mutableListOf<ControlInjectionWriteRequest>()

    override fun apply(request: ControlInjectionWriteRequest): ControlInjectionWriteResult {
        requests += request
        scalarReader.valueBits = request.desiredValueBits
        return ControlInjectionWriteResult(
            status = ControlInjectionApplyStatus.APPLIED,
            processStartTimeTicks = request.expectedStartTimeTicks,
        )
    }
}

private class PositionRecipeResolver(
    private val addresses: Map<ControlResolverAddressRecipe, Long>,
) : ControlAddressRecipeResolver {
    override fun resolve(pid: Int, recipe: ControlResolverAddressRecipe): ControlAddressRecipeResult =
        ControlAddressRecipeResult(
            status = if (recipe in addresses) {
                ControlAddressRecipeStatus.RESOLVED
            } else {
                ControlAddressRecipeStatus.INVALID_RECIPE
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
) : ControlInjectionExecutor {
    val requests = mutableListOf<ControlInjectionWriteRequest>()

    override fun apply(request: ControlInjectionWriteRequest): ControlInjectionWriteResult {
        requests += request
        if (request.address == failAddress && request.desiredValueBits == 30L) {
            return ControlInjectionWriteResult(
                status = ControlInjectionApplyStatus.WRITE_FAILED,
                processStartTimeTicks = request.expectedStartTimeTicks,
                message = "fixture axis write failed",
            )
        }
        val current = values[request.address]
        if (current == request.desiredValueBits) {
            return ControlInjectionWriteResult(
                status = ControlInjectionApplyStatus.ALREADY_APPLIED,
                processStartTimeTicks = request.expectedStartTimeTicks,
            )
        }
        if (current != request.expectedValueBits) {
            return ControlInjectionWriteResult(
                status = ControlInjectionApplyStatus.EXPECTED_VALUE_MISMATCH,
                processStartTimeTicks = request.expectedStartTimeTicks,
            )
        }
        values[request.address] = request.desiredValueBits
        return ControlInjectionWriteResult(
            status = ControlInjectionApplyStatus.APPLIED,
            processStartTimeTicks = request.expectedStartTimeTicks,
        )
    }
}
