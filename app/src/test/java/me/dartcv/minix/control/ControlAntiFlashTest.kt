package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAntiFlashTest {
    @Test
    fun exactProfileUsesSixRegionsSeventeenWritesAndTwentyTwoMillisecondDelay() {
        val fixture = fixture()

        assertTrue(fixture.supervisor.start(fixture.request).workerRunning)
        val state = fixture.supervisor.runOneCycle()

        assertEquals(ControlAntiFlashStatus.RUNNING, state.status)
        assertEquals(1L, state.iterationCount)
        assertEquals(17L, state.successfulWriteCount)
        assertEquals(1, fixture.backend.cycleCalls)
        assertEquals(6, fixture.backend.lastRegions.size)
        assertEquals((1..17).toList(), fixture.backend.lastWrites.map { it.index })
        assertTrue(fixture.backend.lastWrites.all { it.expectedCurrentBytes == null })
        assertEquals(listOf(22L), fixture.backend.sleeps)
    }

    @Test
    fun preflightProfileMismatchStopsBeforeAnyWrite() {
        val fixture = fixture()
        fixture.backend.cycleResult = fixture.backend.successCycle().copy(
            status = ControlAntiFlashBackendStatus.PROFILE_MISMATCH,
            codeRegionValues = fixture.profile.codeRegions.mapIndexed { index, region ->
                if (index == 2) ByteArray(region.originalBytes.size) { 0x55 } else region.originalBytes.copyOf()
            },
            completedWriteCount = 0,
            failureIndex = 3,
            writeAttempted = false,
            message = "profile mismatch",
        )

        fixture.supervisor.start(fixture.request)
        val state = fixture.supervisor.runOneCycle()

        assertEquals(ControlAntiFlashStatus.PROFILE_MISMATCH, state.status)
        assertFalse(state.applied)
        assertFalse(state.workerRunning)
        assertEquals(0, fixture.backend.rollbackWrites.size)
    }

    @Test
    fun firstCyclePartialFailureKeepsContextAndRollsBackPerWord() {
        val fixture = fixture()
        fixture.backend.cycleResult = fixture.backend.successCycle().copy(
            status = ControlAntiFlashBackendStatus.VERIFY_FAILED,
            completedWriteCount = 5,
            failureIndex = 6,
            writeAttempted = true,
            message = "verify failed",
        )
        fixture.backend.rollbackReadValues = fixture.profile.writes.mapIndexed { index, write ->
            val original = fixture.originalBytes(write)
            when {
                index < 5 -> write.bytes.copyOf()
                index == 5 -> original.copyOf().also { bytes -> bytes[0] = write.bytes[0] }
                else -> original
            }
        }

        fixture.supervisor.start(fixture.request)
        val failed = fixture.supervisor.runOneCycle()
        val stopped = fixture.supervisor.stopAndRollback()

        assertEquals(ControlAntiFlashStatus.VERIFY_FAILED, failed.status)
        assertTrue(failed.applied)
        assertEquals(5L, failed.successfulWriteCount)
        assertEquals(ControlAntiFlashStatus.STOPPED, stopped.status)
        assertFalse(stopped.applied)
        assertEquals((1..fixture.profile.writes.size).toList(), fixture.backend.rollbackWrites.map { it.index })
        assertEquals(fixture.profile.writes.size, fixture.backend.rollbackWrites.size)
        fixture.backend.rollbackWrites.forEach { write ->
            assertTrue(
                requireNotNull(write.expectedCurrentBytes)
                    .contentEquals(requireNotNull(fixture.backend.rollbackReadValues)[write.index - 1]),
            )
        }
    }

    @Test
    fun rollbackFailureRetainsContextAndCanBeRetried() {
        val fixture = fixture()
        fixture.supervisor.start(fixture.request)
        fixture.supervisor.runOneCycle()
        fixture.backend.rollbackReadValues = fixture.profile.writes.map { it.bytes.copyOf() }
        fixture.backend.rollbackFailuresRemaining = 1

        val first = fixture.supervisor.stopAndRollback()
        val second = fixture.supervisor.stopAndRollback()

        assertEquals(ControlAntiFlashStatus.ROLLBACK_FAILED, first.status)
        assertTrue(first.applied)
        assertEquals(ControlAntiFlashStatus.STOPPED, second.status)
        assertFalse(second.applied)
        assertEquals(2, fixture.backend.rollbackCallCount)
    }

    @Test
    fun repeatedStopPreservesVerifiedRollbackResult() {
        val fixture = fixture()
        fixture.supervisor.start(fixture.request)
        fixture.supervisor.runOneCycle()
        fixture.backend.rollbackReadValues = fixture.profile.writes.map { it.bytes.copyOf() }

        val first = fixture.supervisor.stopAndRollback()
        val second = fixture.supervisor.stopAndRollback()

        assertEquals(ControlAntiFlashStatus.STOPPED, first.status)
        assertEquals("Anti-flash stopped and rollback verified", first.message)
        assertEquals(first, second)
        assertEquals(1, fixture.backend.rollbackCallCount)
    }

    @Test
    fun rollbackGuardChangeFailsClosedAndKeepsContextForRetry() {
        val fixture = fixture()
        fixture.supervisor.start(fixture.request)
        fixture.supervisor.runOneCycle()
        fixture.backend.rollbackReadValues = fixture.profile.writes.map { it.bytes.copyOf() }
        fixture.backend.rollbackGuardMismatch = true

        val stopped = fixture.supervisor.stopAndRollback()

        assertEquals(ControlAntiFlashStatus.ROLLBACK_FAILED, stopped.status)
        assertTrue(stopped.applied)
        assertEquals(1, fixture.backend.rollbackCallCount)
    }

    @Test
    fun mapsGenerationMismatchFailsClosedBeforeWriting() {
        val fixture = fixture()
        fixture.backend.cycleResult = fixture.backend.successCycle().copy(
            status = ControlAntiFlashBackendStatus.TARGET_CHANGED,
            mapsGeneration = "ffffffffffffffff",
            completedWriteCount = 0,
            writeAttempted = false,
            message = "maps changed",
        )

        fixture.supervisor.start(fixture.request)
        val state = fixture.supervisor.runOneCycle()

        assertEquals(ControlAntiFlashStatus.TARGET_CHANGED, state.status)
        assertFalse(state.applied)
        assertFalse(state.requestedEnabled)
        assertEquals(0, fixture.backend.rollbackCallCount)
    }

    @Test
    fun firstCycleMapsChangeAfterWritesRetainsContextAndExplicitRollbackSucceeds() {
        val fixture = fixture()
        fixture.backend.cycleResult = fixture.backend.successCycle().copy(
            status = ControlAntiFlashBackendStatus.TARGET_CHANGED,
            mapsGeneration = "ffffffffffffffff",
            completedWriteCount = fixture.profile.writes.size,
            writeAttempted = true,
            message = "maps changed after writes",
        )
        fixture.backend.rollbackReadValues = fixture.profile.writes.map { it.bytes.copyOf() }

        fixture.supervisor.start(fixture.request)
        val failed = fixture.supervisor.runOneCycle()
        val stopped = fixture.supervisor.stopAndRollback()

        assertEquals(ControlAntiFlashStatus.TARGET_CHANGED, failed.status)
        assertTrue(failed.applied)
        assertFalse(failed.requestedEnabled)
        assertFalse(failed.workerRunning)
        assertEquals(fixture.profile.writes.size.toLong(), failed.successfulWriteCount)
        assertEquals(ControlAntiFlashStatus.STOPPED, stopped.status)
        assertFalse(stopped.applied)
        assertEquals((1..fixture.profile.writes.size).toList(), fixture.backend.rollbackWrites.map { it.index })
        assertEquals(1, fixture.backend.rollbackCallCount)
        fixture.backend.rollbackWrites.forEach { write ->
            assertTrue(
                requireNotNull(write.expectedCurrentBytes)
                    .contentEquals(requireNotNull(fixture.backend.rollbackReadValues)[write.index - 1]),
            )
        }
    }

    private fun fixture(): AntiFlashFixture {
        val profile = ControlAntiFlashProfileCatalog.profile
        val target = ControlAntiFlashTarget(
            pid = PID,
            startTimeTicks = START_TIME,
            mapsGeneration = MAPS_GENERATION,
            gameAppLoadBias = GAME_APP_BASE,
            tprtLoadBias = TPRT_BASE,
            gameAppBssAnchor = GAME_APP_BASE + ControlGameAppArtifact1582.ANON_BSS_4K_OFFSET,
            gameAppPath = "/data/app/fixture/${profile.gameApp.name}",
            tprtPath = "/data/app/fixture/${profile.tprt.name}",
        )
        val request = ControlAntiFlashStartRequest(
            pid = PID,
            startTimeTicks = START_TIME,
            mapsGeneration = MAPS_GENERATION,
            modules = emptyList(),
        )
        val backend = RecordingAntiFlashBackend(profile, target)
        return AntiFlashFixture(
            profile = profile,
            target = target,
            request = request,
            backend = backend,
            supervisor = ControlAntiFlashSupervisor(
                targetResolver = ControlAntiFlashTargetResolver { _, _ ->
                    ControlAntiFlashTargetResolution(ControlAntiFlashResolveStatus.RESOLVED, target)
                },
                backend = backend,
                profile = profile,
            ),
        )
    }

    private companion object {
        const val PID = 77
        const val START_TIME = "12345"
        const val MAPS_GENERATION = "0123456789abcdef"
        const val GAME_APP_BASE = 0x1000_0000L
        const val TPRT_BASE = 0x2000_0000L
    }
}

private data class AntiFlashFixture(
    val profile: ControlAntiFlashProfile,
    val target: ControlAntiFlashTarget,
    val request: ControlAntiFlashStartRequest,
    val backend: RecordingAntiFlashBackend,
    val supervisor: ControlAntiFlashSupervisor,
) {
    fun originalBytes(write: ControlAntiFlashWordWrite): ByteArray {
        if (write.module == ControlAntiFlashModule.GAME_APP_BSS) return backend.initialBss.copyOf()
        return profile.codeRegions.single { region ->
            region.module == write.module && write.offset >= region.rva &&
                write.offset + Int.SIZE_BYTES <= region.rva + region.originalBytes.size
        }.let { region ->
            val offset = (write.offset - region.rva).toInt()
            region.originalBytes.copyOfRange(offset, offset + Int.SIZE_BYTES)
        }
    }
}

private class RecordingAntiFlashBackend(
    private val profile: ControlAntiFlashProfile,
    private val target: ControlAntiFlashTarget,
) : ControlAntiFlashBackend {
    val initialBss = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    var cycleCalls = 0
    var lastRegions: List<ControlAntiFlashCycleRegion> = emptyList()
    var lastWrites: List<ControlAntiFlashMemoryWrite> = emptyList()
    val sleeps = mutableListOf<Long>()
    var cycleResult: ControlAntiFlashCycleResult = successCycle()
    var rollbackReadValues: List<ByteArray>? = null
    val rollbackWrites = mutableListOf<ControlAntiFlashMemoryWrite>()
    var rollbackFailuresRemaining = 0
    var rollbackGuardMismatch = false
    var rollbackCallCount = 0

    fun successCycle() = ControlAntiFlashCycleResult(
        status = ControlAntiFlashBackendStatus.OK,
        processStartTimeTicks = target.startTimeTicks,
        mapsGeneration = target.mapsGeneration,
        codeRegionValues = profile.codeRegions.map { it.originalBytes.copyOf() },
        bssValue = initialBss.copyOf(),
        completedWriteCount = profile.writes.size,
        writeAttempted = true,
    )

    override fun runCycle(
        target: ControlAntiFlashTarget,
        regions: List<ControlAntiFlashCycleRegion>,
        bssRange: ControlAntiFlashMemoryRange,
        writes: List<ControlAntiFlashMemoryWrite>,
    ): ControlAntiFlashCycleResult {
        cycleCalls += 1
        lastRegions = regions
        lastWrites = writes
        return cycleResult
    }

    override fun readBatch(
        target: ControlAntiFlashTarget,
        ranges: List<ControlAntiFlashMemoryRange>,
    ): ControlAntiFlashReadBatchResult = ControlAntiFlashReadBatchResult(
        status = ControlAntiFlashBackendStatus.OK,
        processStartTimeTicks = target.startTimeTicks,
        mapsGeneration = target.mapsGeneration,
        values = requireNotNull(rollbackReadValues).map(ByteArray::copyOf),
    )

    override fun writeAndVerifyBatch(
        target: ControlAntiFlashTarget,
        writes: List<ControlAntiFlashMemoryWrite>,
    ): ControlAntiFlashWriteBatchResult {
        rollbackCallCount += 1
        rollbackWrites += writes
        if (rollbackGuardMismatch) {
            return ControlAntiFlashWriteBatchResult(
                status = ControlAntiFlashBackendStatus.PROFILE_MISMATCH,
                processStartTimeTicks = target.startTimeTicks,
                mapsGeneration = target.mapsGeneration,
                completedWriteCount = 0,
                failureIndex = writes.firstOrNull()?.index,
                message = "rollback guard changed",
            )
        }
        if (rollbackFailuresRemaining > 0) {
            rollbackFailuresRemaining -= 1
            return ControlAntiFlashWriteBatchResult(
                status = ControlAntiFlashBackendStatus.WRITE_FAILED,
                processStartTimeTicks = target.startTimeTicks,
                mapsGeneration = target.mapsGeneration,
                completedWriteCount = 0,
                failureIndex = writes.firstOrNull()?.index,
                message = "rollback failed",
            )
        }
        return ControlAntiFlashWriteBatchResult(
            status = ControlAntiFlashBackendStatus.OK,
            processStartTimeTicks = target.startTimeTicks,
            mapsGeneration = target.mapsGeneration,
            completedWriteCount = writes.size,
        )
    }

    override fun sleepMillis(milliseconds: Long) {
        sleeps += milliseconds
    }
}
