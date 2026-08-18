package me.dartcv.minix.control

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlAntiFlashTprtBootstrapTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolverAcceptsTprtBeforeGameAppAndIgnoresUnrelatedMappings() {
        val first = resolveTprt(
            mapsText = mapsText(unrelatedPath = "[anon:before-gameapp]"),
            directoryName = "proc-first",
        )
        val second = resolveTprt(
            mapsText = mapsText(unrelatedPath = "/data/app/fixture/liblibGameApp.so"),
            directoryName = "proc-second",
        )

        assertEquals(ControlAntiFlashResolveStatus.RESOLVED, first.status)
        assertEquals(ControlAntiFlashResolveStatus.RESOLVED, second.status)
        val firstTarget = requireNotNull(first.target)
        val secondTarget = requireNotNull(second.target)
        assertEquals(TPRT_BASE, firstTarget.tprtLoadBias)
        assertEquals(TPRT_PATH, firstTarget.tprtPath)
        assertEquals(16, firstTarget.mapsGeneration.length)
        assertEquals(firstTarget.mapsGeneration, secondTarget.mapsGeneration)
        assertNotEquals("0000000000000000", firstTarget.mapsGeneration)
    }

    @Test
    fun unknownTprtRegionStopsBeforeAnyWrite() {
        val fixture = fixture()
        fixture.backend.regionReadValues[0][0] = 0x55

        val state = fixture.bootstrap.ensurePatched(fixture.target)

        assertEquals(ControlAntiFlashStatus.PROFILE_MISMATCH, state.status)
        assertFalse(state.applied)
        assertEquals(2, state.lastFailureIndex)
        assertTrue(fixture.backend.writeCalls.isEmpty())
    }

    @Test
    fun ensurePatchedConditionallyWritesAndVerifiesAllFifteenTprtWords() {
        val fixture = fixture()

        val state = fixture.bootstrap.ensurePatched(fixture.target)

        assertEquals(ControlAntiFlashStatus.RUNNING, state.status)
        assertTrue(state.isPatched)
        assertEquals(1L, state.iterationCount)
        assertEquals(15L, state.successfulWriteCount)
        assertEquals(1, fixture.backend.writeCalls.size)
        val writes = fixture.backend.writeCalls.single()
        assertEquals((2..16).toList(), writes.map { it.index })
        writes.forEach { write ->
            assertTrue(write.bytes.contentEquals(fixture.patchBytes(write.index)))
            assertTrue(
                requireNotNull(write.expectedCurrentBytes)
                    .contentEquals(fixture.originalBytes(write.index)),
            )
        }
    }

    @Test
    fun rollbackRestoresAllFifteenWordsWithCurrentPatchGuards() {
        val fixture = fixture()
        fixture.bootstrap.ensurePatched(fixture.target)
        fixture.backend.rollbackReadValues = fixture.tprtWrites.map { it.bytes.copyOf() }

        val state = fixture.bootstrap.rollback()

        assertEquals(ControlAntiFlashStatus.STOPPED, state.status)
        assertFalse(state.applied)
        assertFalse(state.handedOff)
        assertEquals(2, fixture.backend.writeCalls.size)
        val rollbackWrites = fixture.backend.writeCalls.last()
        assertEquals((2..16).toList(), rollbackWrites.map { it.index })
        rollbackWrites.forEach { write ->
            assertTrue(write.bytes.contentEquals(fixture.originalBytes(write.index)))
            assertTrue(
                requireNotNull(write.expectedCurrentBytes)
                    .contentEquals(fixture.patchBytes(write.index)),
            )
        }
    }

    @Test
    fun pidIdentityChangeReleasesBootstrapWithoutWritingToReplacementProcess() {
        val fixture = fixture()
        fixture.bootstrap.ensurePatched(fixture.target)
        fixture.backend.rollbackIdentityChanged = true

        val state = fixture.bootstrap.rollback()

        assertEquals(ControlAntiFlashStatus.STOPPED, state.status)
        assertFalse(state.applied)
        assertEquals(1, fixture.backend.writeCalls.size)
        assertTrue(state.message.contains("identity"))
    }

    @Test
    fun successfulFullRequestHandoffTransfersRollbackOwnershipWithoutAWrite() {
        val fixture = fixture()
        fixture.bootstrap.ensurePatched(fixture.target)

        val state = fixture.bootstrap.handoff(fixture.fullRequest())

        assertEquals(ControlAntiFlashStatus.STOPPED, state.status)
        assertFalse(state.applied)
        assertTrue(state.handedOff)
        assertEquals(1, fixture.backend.writeCalls.size)
        fixture.bootstrap.rollback()
        assertEquals(1, fixture.backend.writeCalls.size)
    }

    private fun resolveTprt(
        mapsText: String,
        directoryName: String,
    ): ControlAntiFlashTprtBootstrapResolution {
        val procControl = temporaryFolder.newFolder(directoryName)
        val pidDirectory = File(procControl, PID.toString()).apply { mkdirs() }
        File(pidDirectory, "maps").writeText(mapsText)
        val inspector = object : TargetProcessInspector {
            override fun findPid(packageName: String): Int? = PID
            override fun readProcessName(pid: Int): String? = PACKAGE_NAME
            override fun readStartTimeTicks(pid: Int): String? = START_TIME
            override fun readEffectiveUid(pid: Int): Int? = UID
            override fun readBrief(pid: Int): TargetProcessBrief? = null
            override fun isAlive(pid: Int): Boolean = pid == PID
        }
        return ProcControlAntiFlashTprtBootstrapResolver(inspector, procControl).resolve(
            pid = PID,
            installedProfile = ControlAntiFlashInstalledProfile(
                gameAppPath = GAME_APP_PATH,
                tprtPath = TPRT_PATH,
            ),
        )
    }

    private fun mapsText(unrelatedPath: String): String = buildString {
        appendLine(mapRow(TPRT_BASE, TPRT_BASE + TPRT_RX_SIZE, "r-xp", TPRT_PATH, 102))
        appendLine(mapRow(UNRELATED_BASE, UNRELATED_BASE + 0x1000L, "rw-p", unrelatedPath, 0, "00:00"))
    }

    private fun mapRow(
        start: Long,
        end: Long,
        permissions: String,
        path: String,
        inode: Long,
        device: String = "fe:54",
    ): String = "%x-%x %s 00000000 %s %d %s".format(
        start,
        end,
        permissions,
        device,
        inode,
        path,
    )

    private fun fixture(): TprtBootstrapFixture {
        val profile = ControlAntiFlashProfileCatalog.profile
        val target = ControlAntiFlashTprtBootstrapTarget(
            pid = PID,
            startTimeTicks = START_TIME,
            mapsGeneration = MAPS_GENERATION,
            tprtLoadBias = TPRT_BASE,
            tprtPath = TPRT_PATH,
            scopedMappings = emptyList(),
        )
        val backend = RecordingTprtBootstrapBackend(profile, target)
        return TprtBootstrapFixture(
            profile = profile,
            target = target,
            backend = backend,
            bootstrap = ControlAntiFlashTprtBootstrap(backend, profile),
        )
    }

    private companion object {
        const val PID = 77
        const val UID = 10_552
        const val START_TIME = "12345"
        const val MAPS_GENERATION = "0123456789abcdef"
        const val PACKAGE_NAME = "com.minitech.miniworld"
        const val TPRT_BASE = 0x2_0000_0000L
        const val TPRT_RX_SIZE = 0x0018_5000L
        const val UNRELATED_BASE = 0x3_0000_0000L
        const val GAME_APP_PATH = "/data/app/fixture/lib/arm64/liblibGameApp.so"
        const val TPRT_PATH = "/data/app/fixture/lib/arm64/libtprt.so"
    }
}

private data class TprtBootstrapFixture(
    val profile: ControlAntiFlashProfile,
    val target: ControlAntiFlashTprtBootstrapTarget,
    val backend: RecordingTprtBootstrapBackend,
    val bootstrap: ControlAntiFlashTprtBootstrap,
) {
    val tprtWrites: List<ControlAntiFlashWordWrite>
        get() = profile.writes.filter { it.module == ControlAntiFlashModule.TPRT }

    fun originalBytes(index: Int): ByteArray = wordBytes(index, usePatch = false)

    fun patchBytes(index: Int): ByteArray = wordBytes(index, usePatch = true)

    fun fullRequest(): ControlAntiFlashStartRequest = ControlAntiFlashStartRequest(
        pid = target.pid,
        startTimeTicks = target.startTimeTicks,
        mapsGeneration = "fedcba9876543210",
        modules = listOf(
            ControlNativeModuleIdentity(
                name = profile.tprt.name,
                path = target.tprtPath,
                loadBase = target.tprtLoadBias,
                mappedBytes = profile.tprt.fileSizeBytes,
                memoryElf = true,
                sha256 = profile.tprt.sha256,
            ),
        ),
    )

    private fun wordBytes(index: Int, usePatch: Boolean): ByteArray {
        val write = tprtWrites.single { it.index == index }
        val region = profile.codeRegions.single { candidate ->
            candidate.module == ControlAntiFlashModule.TPRT && write.offset >= candidate.rva &&
                write.offset + Int.SIZE_BYTES <= candidate.rva + candidate.originalBytes.size
        }
        val bytes = if (usePatch) region.patchBytes else region.originalBytes
        val offset = (write.offset - region.rva).toInt()
        return bytes.copyOfRange(offset, offset + Int.SIZE_BYTES)
    }
}

private class RecordingTprtBootstrapBackend(
    profile: ControlAntiFlashProfile,
    private val target: ControlAntiFlashTprtBootstrapTarget,
) : ControlAntiFlashBackend {
    private val tprtRegions = profile.codeRegions.filter { it.module == ControlAntiFlashModule.TPRT }
    val regionReadValues = tprtRegions.map { it.originalBytes.copyOf() }.toMutableList()
    var rollbackReadValues: List<ByteArray>? = null
    var rollbackIdentityChanged = false
    val writeCalls = mutableListOf<List<ControlAntiFlashMemoryWrite>>()

    override fun runCycle(
        target: ControlAntiFlashTarget,
        regions: List<ControlAntiFlashCycleRegion>,
        bssRange: ControlAntiFlashMemoryRange,
        writes: List<ControlAntiFlashMemoryWrite>,
    ) = ControlAntiFlashCycleResult(
        status = ControlAntiFlashBackendStatus.UNAVAILABLE,
        message = "not used by tprt bootstrap tests",
    )

    override fun readBatch(
        target: ControlAntiFlashTarget,
        ranges: List<ControlAntiFlashMemoryRange>,
    ): ControlAntiFlashReadBatchResult {
        if (ranges.size == 15 && rollbackIdentityChanged) {
            return ControlAntiFlashReadBatchResult(
                status = ControlAntiFlashBackendStatus.TARGET_CHANGED,
                processStartTimeTicks = "99999",
                mapsGeneration = "ffffffffffffffff",
                message = "PID identity changed",
            )
        }
        val values = if (ranges.size == 15) {
            requireNotNull(rollbackReadValues)
        } else {
            regionReadValues
        }
        return ControlAntiFlashReadBatchResult(
            status = ControlAntiFlashBackendStatus.OK,
            processStartTimeTicks = this.target.startTimeTicks,
            mapsGeneration = this.target.mapsGeneration,
            values = values.map(ByteArray::copyOf),
        )
    }

    override fun writeAndVerifyBatch(
        target: ControlAntiFlashTarget,
        writes: List<ControlAntiFlashMemoryWrite>,
    ): ControlAntiFlashWriteBatchResult {
        writeCalls += writes.map { write ->
            write.copy(
                bytes = write.bytes.copyOf(),
                expectedCurrentBytes = write.expectedCurrentBytes?.copyOf(),
            )
        }
        return ControlAntiFlashWriteBatchResult(
            status = ControlAntiFlashBackendStatus.OK,
            processStartTimeTicks = this.target.startTimeTicks,
            mapsGeneration = this.target.mapsGeneration,
            completedWriteCount = writes.size,
        )
    }

    override fun sleepMillis(milliseconds: Long) = Unit
}
