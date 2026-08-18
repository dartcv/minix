package me.dartcv.minix.control

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcControlSearchIdAnchorResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun suffixRoutePinsOffsetZeroModuleThenSelectsIndependentAnonBssMapping() {
        val fixture = fixture(
            moduleLoadLine(),
            mapLine(
                start = 0x1800_0000L,
                end = 0x1801_0000L,
                permissions = "rw-p",
                path = "[anon:.bss]",
            ),
            anonBssLine(),
        )

        val result = fixture.resolver.resolve(PID, readyProfile())

        assertEquals(ControlSearchIdAnchorStatus.RESOLVED, result.status)
        assertEquals(EXPECTED_ANON_BSS, result.anchor?.baseAddress)
        assertEquals(MODULE_PATH, fixture.fingerprintProvider.paths.single())
        assertEquals(START_TIME, result.processStartTimeTicks)
        assertEquals(MODULE_SHA256, result.anchor?.moduleSha256)
    }

    @Test
    fun successfulFingerprintCacheHitsAndInvalidatesOnIdentityOrRelevantMapsChange() {
        val fixture = fixture(moduleLoadLine(), anonBssLine())

        assertTrue(fixture.resolver.resolve(PID, readyProfile()).isSuccess)
        assertTrue(fixture.resolver.resolve(PID, readyProfile()).isSuccess)
        assertEquals(listOf(MODULE_PATH), fixture.fingerprintProvider.paths)

        fixture.inspector.forcedValue = "new-start"
        val newIdentity = fixture.resolver.resolve(PID, readyProfile())
        assertTrue(newIdentity.isSuccess)
        assertEquals("new-start", newIdentity.processStartTimeTicks)
        assertEquals(listOf(MODULE_PATH, MODULE_PATH), fixture.fingerprintProvider.paths)

        writeMaps(
            fixture.mapsFile,
            listOf(
                moduleLoadLine(loadBias = SECOND_LOAD_BIAS),
                anonBssLine(loadBias = SECOND_LOAD_BIAS),
            ),
        )
        val remapped = fixture.resolver.resolve(PID, readyProfile())
        assertTrue(remapped.isSuccess)
        assertEquals(
            SECOND_LOAD_BIAS + ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
            remapped.anchor?.baseAddress,
        )
        assertEquals(3, fixture.fingerprintProvider.paths.size)
    }

    @Test
    fun explicitCacheInvalidationForcesFingerprintRecalculation() {
        val fixture = fixture(moduleLoadLine(), anonBssLine())

        assertTrue(fixture.resolver.resolve(PID, readyProfile()).isSuccess)
        assertTrue(fixture.resolver.resolve(PID, readyProfile()).isSuccess)
        assertEquals(listOf(MODULE_PATH), fixture.fingerprintProvider.paths)

        fixture.resolver.invalidateCache()

        assertTrue(fixture.resolver.resolve(PID, readyProfile()).isSuccess)
        assertEquals(listOf(MODULE_PATH, MODULE_PATH), fixture.fingerprintProvider.paths)
    }

    @Test
    fun noSuffixRetainsRecoveredSemanticsAndReturnsFingerprintPinnedLoadBias() {
        val fixture = fixture(moduleLoadLine(), anonBssLine())
        val profile = readyProfile().copy(moduleSpec = ControlGameAppArtifact1582.MODULE_NAME)

        assertTrue(profile.isReadyForScan)
        val result = fixture.resolver.resolve(PID, profile)

        assertEquals(ControlSearchIdAnchorStatus.RESOLVED, result.status)
        assertEquals(LOAD_BIAS, result.anchor?.baseAddress)
        assertEquals(MODULE_PATH, fixture.fingerprintProvider.paths.single())
    }

    @Test
    fun suffixTextIsIgnoredAndConsecutiveDelimitersMatchStrtok() {
        val noSuffix = requireNotNull(
            parseRecoveredModuleSpec(ControlGameAppArtifact1582.MODULE_NAME),
        )
        val arbitrarySuffix = requireNotNull(
            parseRecoveredModuleSpec(
                "${ControlGameAppArtifact1582.MODULE_NAME}::anything:ignored",
            ),
        )

        assertFalse(noSuffix.hasSuffixToken)
        assertTrue(arbitrarySuffix.hasSuffixToken)
        assertEquals(ControlGameAppArtifact1582.MODULE_NAME, arbitrarySuffix.moduleName)
        assertNull(parseRecoveredModuleSpec("../${ControlGameAppArtifact1582.MODULE_NAME}:bss"))
    }

    @Test
    fun moduleNameSubstringAndSyntheticCombinedRowsAreNotModuleIdentity() {
        val fixture = fixture(
            mapLine(
                start = LOAD_BIAS,
                end = LOAD_BIAS + MODULE_LOAD_SIZE,
                permissions = "r-xp",
                path = "/data/prefix-${ControlGameAppArtifact1582.MODULE_NAME}",
            ),
            mapLine(
                start = EXPECTED_ANON_BSS,
                end = EXPECTED_ANON_BSS_END,
                permissions = "r-xp",
                path = "$MODULE_PATH [anon:.bss]",
            ),
        )

        val result = fixture.resolver.resolve(PID, readyProfile())

        assertEquals(ControlSearchIdAnchorStatus.MODULE_NOT_FOUND, result.status)
        assertTrue(fixture.fingerprintProvider.paths.isEmpty())
    }

    @Test
    fun missingOrInvalidAnonBssMappingFailsAfterModuleFingerprinting() {
        val invalidAnchors = listOf(
            emptyList(),
            listOf(anonBssLine(permissions = "r-xp")),
            listOf(anonBssLine(permissions = "rwxp")),
            listOf(anonBssLine(path = "[anon:other]")),
            listOf(anonBssLine(start = EXPECTED_ANON_BSS + 0x1000L)),
            listOf(anonBssLine(end = EXPECTED_ANON_BSS_END - 0x1000L)),
        )

        invalidAnchors.forEach { anchorLines ->
            val fixture = fixture(*(listOf(moduleLoadLine()) + anchorLines).toTypedArray())
            val result = fixture.resolver.resolve(PID, readyProfile())

            assertEquals(ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND, result.status)
            assertEquals(listOf(MODULE_PATH), fixture.fingerprintProvider.paths)
            assertNull(result.anchor)
        }
    }

    @Test
    fun multipleFingerprintMatchedModulesOrLoadBiasesAreAmbiguous() {
        val twoPaths = fixture(
            moduleLoadLine(path = MODULE_PATH, loadBias = LOAD_BIAS),
            moduleLoadLine(path = SECOND_MODULE_PATH, loadBias = SECOND_LOAD_BIAS),
            anonBssLine(loadBias = LOAD_BIAS),
            anonBssLine(loadBias = SECOND_LOAD_BIAS),
        )

        val twoPathResult = twoPaths.resolver.resolve(PID, readyProfile())

        assertEquals(ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS, twoPathResult.status)
        assertEquals(setOf(MODULE_PATH, SECOND_MODULE_PATH), twoPaths.fingerprintProvider.paths.toSet())

        val duplicateLoadBias = fixture(
            moduleLoadLine(),
            moduleLoadLine(end = LOAD_BIAS + MODULE_LOAD_SIZE + 0x1000L),
            anonBssLine(),
        )

        val duplicateResult = duplicateLoadBias.resolver.resolve(PID, readyProfile())

        assertEquals(ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS, duplicateResult.status)
        assertEquals(listOf(MODULE_PATH), duplicateLoadBias.fingerprintProvider.paths)
    }

    @Test
    fun duplicateExpectedAnonBssRowsAreAmbiguous() {
        val fixture = fixture(moduleLoadLine(), anonBssLine(), anonBssLine())

        val result = fixture.resolver.resolve(PID, readyProfile())

        assertEquals(ControlSearchIdAnchorStatus.MODULE_AMBIGUOUS, result.status)
        assertNull(result.anchor)
    }

    @Test
    fun fingerprintMismatchAndUnavailableCandidateFailClosed() {
        val mismatch = fixture(
            moduleLoadLine(),
            anonBssLine(),
            defaultFingerprint = OTHER_SHA256,
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.FINGERPRINT_MISMATCH, mismatch.status)
        assertNull(mismatch.anchor)

        val unavailable = fixture(
            moduleLoadLine(),
            anonBssLine(),
            defaultFingerprint = null,
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.FINGERPRINT_UNAVAILABLE, unavailable.status)
        assertNull(unavailable.anchor)
    }

    @Test
    fun oneUnfingerprintableCandidatePreventsSelectingAnotherMatchingModule() {
        val fixture = fixture(
            moduleLoadLine(path = MODULE_PATH, loadBias = LOAD_BIAS),
            moduleLoadLine(path = SECOND_MODULE_PATH, loadBias = SECOND_LOAD_BIAS),
            anonBssLine(loadBias = LOAD_BIAS),
            fingerprints = mapOf(
                MODULE_PATH to MODULE_SHA256,
                SECOND_MODULE_PATH to null,
            ),
        )

        val result = fixture.resolver.resolve(PID, readyProfile())

        assertEquals(ControlSearchIdAnchorStatus.FINGERPRINT_UNAVAILABLE, result.status)
        assertNull(result.anchor)
    }

    @Test
    fun processIdentityChangeDuringMapsOrFingerprintingFailsClosed() {
        val duringInitialMaps = fixture(
            moduleLoadLine(),
            anonBssLine(),
            startTimes = listOf(START_TIME, "changed"),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.TARGET_CHANGED, duringInitialMaps.status)

        val duringFingerprint = fixture(
            moduleLoadLine(),
            anonBssLine(),
            startTimes = listOf(START_TIME, START_TIME, "changed"),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.TARGET_CHANGED, duringFingerprint.status)
        assertNull(duringFingerprint.anchor)
    }

    @Test
    fun moduleOrAnchorRangeChangeDuringFingerprintingFailsClosed() {
        val changedModule = fixture(
            moduleLoadLine(),
            anonBssLine(),
            mapsAfterFirstFingerprint = listOf(
                moduleLoadLine(end = LOAD_BIAS + MODULE_LOAD_SIZE + 0x1000L),
                anonBssLine(),
            ),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.TARGET_CHANGED, changedModule.status)

        val changedAnchor = fixture(
            moduleLoadLine(),
            anonBssLine(),
            mapsAfterFirstFingerprint = listOf(
                moduleLoadLine(),
                anonBssLine(end = EXPECTED_ANON_BSS_END - 0x1000L),
            ),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.TARGET_CHANGED, changedAnchor.status)
        assertNull(changedAnchor.anchor)
    }

    @Test
    fun addressOverflowAndMalformedMapsFailClosed() {
        val overflowLoadBias = 0x7fff_ffff_fc00_0000L
        val overflow = fixture(
            moduleLoadLine(
                loadBias = overflowLoadBias,
                end = overflowLoadBias + 0x10_0000L,
            ),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.ANCHOR_NOT_FOUND, overflow.status)

        val malformed = fixture(
            moduleLoadLine(),
            "not-a-maps-row",
            anonBssLine(),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.UNAVAILABLE, malformed.status)

        val oversized = fixture(
            moduleLoadLine(),
            "x".repeat(4_097),
            anonBssLine(),
        ).resolver.resolve(PID, readyProfile())
        assertEquals(ControlSearchIdAnchorStatus.UNAVAILABLE, oversized.status)
    }

    private fun fixture(
        vararg lines: String,
        defaultFingerprint: String? = MODULE_SHA256,
        fingerprints: Map<String, String?> = emptyMap(),
        startTimes: List<String?> = listOf(START_TIME),
        mapsAfterFirstFingerprint: List<String>? = null,
    ): ResolverFixture {
        val procControl = temporaryFolder.newFolder("proc-${temporaryFolder.root.listFiles().orEmpty().size}")
        val processDirectory = File(procControl, PID.toString()).apply { mkdirs() }
        val mapsFile = File(processDirectory, "maps")
        writeMaps(mapsFile, lines.asList())
        val provider = RecordingFingerprintProvider(
            defaultFingerprint = defaultFingerprint,
            fingerprints = fingerprints,
            afterFirstRead = mapsAfterFirstFingerprint?.let { replacement ->
                { writeMaps(mapsFile, replacement) }
            },
        )
        val inspector = SequencedStartTimeInspector(startTimes)
        val resolver = ProcControlSearchIdAnchorResolver(
            processInspector = inspector,
            fingerprintProvider = provider,
            procControl = procControl,
        )
        return ResolverFixture(resolver, provider, inspector, mapsFile)
    }

    private fun readyProfile(): ControlSearchIdProfile =
        ControlRecoveredSearchIdProfiles.miniWorld1582.copy(
            profileId = "fixture-module-spec-anchor-v2",
            expectedSha256 = MODULE_SHA256,
        )

    private data class ResolverFixture(
        val resolver: ProcControlSearchIdAnchorResolver,
        val fingerprintProvider: RecordingFingerprintProvider,
        val inspector: SequencedStartTimeInspector,
        val mapsFile: File,
    )

    private class RecordingFingerprintProvider(
        private val defaultFingerprint: String?,
        private val fingerprints: Map<String, String?>,
        private val afterFirstRead: (() -> Unit)?,
    ) : TargetModuleFingerprintProvider {
        val paths = mutableListOf<String>()

        override fun sha256(pid: Int, mappedPath: String, loadBase: Long): String? {
            paths += mappedPath
            if (paths.size == 1) afterFirstRead?.invoke()
            return if (mappedPath in fingerprints) {
                fingerprints[mappedPath]
            } else {
                defaultFingerprint
            }
        }
    }

    private class SequencedStartTimeInspector(
        private val values: List<String?>,
    ) : TargetProcessInspector {
        private var index = 0
        var forcedValue: String? = null

        override fun readStartTimeTicks(pid: Int): String? {
            forcedValue?.let { return it }
            val selected = values[minOf(index, values.lastIndex)]
            index += 1
            return selected
        }

        override fun readEffectiveUid(pid: Int): Int? = 10_321

        override fun findPid(packageName: String): Int? = null
        override fun readProcessName(pid: Int): String? = null
        override fun readBrief(pid: Int): TargetProcessBrief? = null
        override fun isAlive(pid: Int): Boolean = true
    }

    private companion object {
        const val PID = 77
        const val START_TIME = "start-77"
        const val MODULE_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_SHA256 =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val MODULE_PATH = "/data/liblibGameApp.so"
        const val SECOND_MODULE_PATH = "/other/liblibGameApp.so"
        const val LOAD_BIAS = 0x1000_0000L
        const val SECOND_LOAD_BIAS = 0x3000_0000L
        const val MODULE_LOAD_SIZE = 0x0a13_e000L
        const val EXPECTED_ANON_BSS = LOAD_BIAS + 0x0a85_d000L
        const val EXPECTED_ANON_BSS_END = LOAD_BIAS + 0x0b3c_1000L
    }
}

private fun moduleLoadLine(
    path: String = "/data/liblibGameApp.so",
    loadBias: Long = 0x1000_0000L,
    end: Long = loadBias + 0x0a13_e000L,
): String = mapLine(
    start = loadBias,
    end = end,
    permissions = "r-xp",
    path = path,
)

private fun anonBssLine(
    loadBias: Long = 0x1000_0000L,
    start: Long = loadBias + ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
    end: Long = loadBias + ControlGameAppArtifact1582.ANON_BSS_4K_END,
    permissions: String = "rw-p",
    path: String = "[anon:.bss]",
): String = mapLine(
    start = start,
    end = end,
    permissions = permissions,
    path = path,
)

private fun mapLine(
    start: Long,
    end: Long,
    permissions: String,
    path: String,
    fileOffset: Long = 0L,
): String = buildString {
    append(start.toString(16))
    append('-')
    append(end.toString(16))
    append(' ')
    append(permissions)
    append(' ')
    append(fileOffset.toString(16).padStart(8, '0'))
    append(" 00:00 0 ")
    append(path)
}

private fun writeMaps(file: File, lines: List<String>) {
    file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
}
