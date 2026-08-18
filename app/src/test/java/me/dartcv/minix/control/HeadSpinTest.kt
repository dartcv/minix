package me.dartcv.minix.control

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadSpinTest {
    @Test
    fun uiContractKeepsInvertedProgressAndSelectorsSeparate() {
        assertEquals(45, ControlHeadSpinUiContract.displayToRaw(955))
        assertEquals(500, ControlHeadSpinUiContract.displayToRaw(500))
        assertEquals(900, ControlHeadSpinUiContract.displayToRaw(100))
        assertEquals(990, ControlHeadSpinUiContract.displayToRaw(10))
        assertEquals(955, ControlHeadSpinUiContract.rawToDisplay(45))
        assertEquals(10, ControlHeadSpinUiContract.rawToDisplay(990))
        assertEquals(17, ControlHeadSpinUiContract.CHARACTER_SELECTOR)
        assertEquals(18, ControlHeadSpinUiContract.CROSSHAIR_SELECTOR)
        assertEquals(null, ControlHeadSpinUiContract.displayToRaw(9))
        assertEquals(null, ControlHeadSpinUiContract.rawToDisplay(0))
    }

    @Test
    fun profilePinsExactHashesRestoreWordAndMaskedRecipes() {
        val profile = ControlHeadSpinProfileCatalog.profile

        assertEquals(ControlHeadSpinProfileCatalog.LIB_CLIENT_SHA256, profile.libClientSha256)
        assertEquals(ControlHeadSpinProfileCatalog.GAME_APP_SHA256, profile.gameAppSha256)
        assertEquals(0x72a80bea.toInt(), profile.restoreWord)
        assertEquals(0xb9005909.toInt(), profile.patchedWord)
        assertEquals(ControlAddressDereferenceMode.LEGACY_MASKED_H, profile.axisRecipes[ControlHeadSpinAxis.A]?.dereferenceMode)
        assertEquals(listOf(0x38L, 0x08L, 0x50L), profile.axisRecipes[ControlHeadSpinAxis.B]?.intermediateOffsets)
        assertEquals(0x54L, profile.axisRecipes[ControlHeadSpinAxis.A]?.finalOffset)
        assertEquals(0x58L, profile.axisRecipes[ControlHeadSpinAxis.B]?.finalOffset)
    }

    @Test
    fun exactShaResolverRejectsFingerprintMismatchAndResolvesBothAxes() {
        val identity = identity()
        val profile = ControlHeadSpinProfileCatalog.profile
        val resolver = ControlHeadSpinExactShaTargetResolver { pid, recipe ->
            assertEquals(identity.pid, pid)
            ControlAddressRecipeResult(
                status = ControlAddressRecipeStatus.RESOLVED,
                address = 0x7000_0000L + recipe.finalOffset,
                processStartTimeTicks = identity.startTimeTicks,
            )
        }
        val request = ControlHeadSpinStartRequest(
            identity = identity,
            modules = modules(),
            intervalInput = 10,
        )

        val resolved = resolver.resolve(request, profile)

        assertTrue(resolved.isSuccess)
        assertEquals(0x1000_0000L + profile.patchRva, resolved.target?.codeAddress)
        assertEquals(0x7000_0054L, resolved.target?.axisAddressA)
        assertEquals(0x7000_0058L, resolved.target?.axisAddressB)

        val mismatch = resolver.resolve(
            request.copy(
                modules = modules().map { module ->
                    if (module.name == ControlGameAppArtifact1582.MODULE_NAME) {
                        module.copy(sha256 = "a".repeat(64))
                    } else {
                        module
                    }
                },
            ),
            profile,
        )
        assertEquals(ControlHeadSpinResolveStatus.FINGERPRINT_MISMATCH, mismatch.status)
    }

    @Test
    fun repeatedEnableIsIdempotentAndDisableJoinsAndRollsBack() {
        val backend = RecordingHeadSpinBackend(identity())
        val sleeper = GateHeadSpinSleeper()
        val workers = RecordingWorkerFactory()
        val supervisor = supervisor(backend, sleeper, workers)
        val request = request()

        val started = supervisor.enable(request)
        assertTrue(started.requestedEnabled)
        assertTrue(sleeper.entered.await(2, TimeUnit.SECONDS))

        val repeated = supervisor.enable(request)
        assertEquals(started.generation, repeated.generation)
        assertEquals(1, workers.created)
        assertEquals(1, backend.codeWrites)

        val stopped = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.STOPPED, stopped.status)
        assertFalse(stopped.requestedEnabled)
        assertFalse(stopped.workerRunning)
        assertFalse(stopped.applied)
        assertEquals(0x72a80bea.toInt(), backend.codeWord)
        assertEquals(10.0f.toRawBits(), backend.axisBitsA)
        assertEquals(20.0f.toRawBits(), backend.axisBitsB)
        assertTrue(backend.axisWrites >= 2)
        assertEquals(2, backend.codeWrites)
    }

    @Test
    fun unknownCodeWordIsRejectedBeforeAnyPatch() {
        val backend = RecordingHeadSpinBackend(identity()).apply {
            codeWord = 0x1234_5678
        }
        val supervisor = supervisor(backend, GateHeadSpinSleeper(), RecordingWorkerFactory())

        val result = supervisor.enable(request())

        assertEquals(ControlHeadSpinStatus.PROFILE_MISMATCH, result.status)
        assertFalse(result.applied)
        assertEquals(0, backend.codeWrites)
        assertEquals(0, backend.axisWrites)
    }

    @Test
    fun mapsChangeKeepsRollbackContextAndRetryCanReleaseIt() {
        val backend = RecordingHeadSpinBackend(identity())
        val sleeper = GateHeadSpinSleeper()
        val supervisor = supervisor(backend, sleeper, RecordingWorkerFactory())

        supervisor.enable(request())
        assertTrue(sleeper.entered.await(2, TimeUnit.SECONDS))
        backend.rejectWrites = true

        val failed = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.TARGET_CHANGED, failed.status)
        assertTrue(failed.applied)
        assertFalse(failed.workerRunning)

        backend.rejectWrites = false
        val retried = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.STOPPED, retried.status)
        assertFalse(retried.applied)
        assertEquals(0x72a80bea.toInt(), backend.codeWord)
    }

    @Test
    fun partialPairWriteIsTrackedAndRolledBackBeforeCodeRestore() {
        val backend = RecordingHeadSpinBackend(identity()).apply {
            partialAxisWriteOnce = true
        }
        val supervisor = supervisor(backend, GateHeadSpinSleeper(), RecordingWorkerFactory())

        supervisor.enable(request())
        assertTrue(backend.partialAxisWriteSeen.await(2, TimeUnit.SECONDS))

        val failed = awaitStatus(supervisor, ControlHeadSpinStatus.WRITE_FAILED)
        assertEquals(ControlHeadSpinStatus.WRITE_FAILED, failed.status)
        assertTrue(failed.applied)
        assertFalse(failed.workerRunning)
        // Axis A was the only half of the pair that the fixture committed.
        assertEquals(11.0f.toRawBits(), backend.axisBitsA)
        assertEquals(20.0f.toRawBits(), backend.axisBitsB)

        val stopped = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.STOPPED, stopped.status)
        assertFalse(stopped.applied)
        assertEquals(10.0f.toRawBits(), backend.axisBitsA)
        assertEquals(20.0f.toRawBits(), backend.axisBitsB)
        assertEquals(0x72a80bea.toInt(), backend.codeWord)
        assertEquals(2, backend.axisWrites)
        assertEquals(2, backend.codeWrites)
    }

    @Test
    fun disableAcknowledgementPreventsWritesAfterReturn() {
        val backend = RecordingHeadSpinBackend(identity())
        val sleeper = GateHeadSpinSleeper()
        val supervisor = supervisor(backend, sleeper, RecordingWorkerFactory())

        supervisor.enable(request())
        assertTrue(sleeper.entered.await(2, TimeUnit.SECONDS))

        val stopped = supervisor.disable()
        val axisWritesAtReturn = backend.axisWrites
        val codeWritesAtReturn = backend.codeWrites

        assertEquals(ControlHeadSpinStatus.STOPPED, stopped.status)
        assertFalse(stopped.workerRunning)
        Thread.sleep(50L)
        assertEquals(axisWritesAtReturn, backend.axisWrites)
        assertEquals(codeWritesAtReturn, backend.codeWrites)
    }

    @Test
    fun failedCodeCompareExchangeWithUnchangedWordDropsProvisionalContext() {
        val backend = RecordingHeadSpinBackend(identity()).apply {
            codeWriteUnchangedFailure = true
        }
        val sleeper = GateHeadSpinSleeper()
        val supervisor = supervisor(backend, sleeper, RecordingWorkerFactory())

        val failed = supervisor.enable(request())

        assertEquals(ControlHeadSpinStatus.WRITE_FAILED, failed.status)
        assertFalse(failed.applied)
        assertFalse(failed.snapshotReady)
        assertEquals(1, backend.codeWrites)

        // No disable call is needed because the compare-exchange explicitly
        // confirmed that the original word remained untouched.
        backend.codeWriteUnchangedFailure = false
        val started = supervisor.enable(request())
        assertTrue(started.requestedEnabled)
        assertTrue(sleeper.entered.await(2, TimeUnit.SECONDS))
        assertEquals(2, backend.codeWrites)
        assertEquals(ControlHeadSpinStatus.STOPPED, supervisor.disable().status)
    }

    @Test
    fun unknownCodeWriteOutcomeRetainsContextForGuardedDisableRetry() {
        val backend = RecordingHeadSpinBackend(identity()).apply {
            codeWriteUnknownFailure = true
        }
        val supervisor = supervisor(backend, GateHeadSpinSleeper(), RecordingWorkerFactory())

        val failed = supervisor.enable(request())

        assertEquals(ControlHeadSpinStatus.WRITE_FAILED, failed.status)
        assertTrue(failed.applied)
        assertTrue(failed.snapshotReady)

        // The fixture did not change the word but deliberately omitted the
        // after value. The supervisor must still issue a guarded restore probe
        // instead of silently dropping the rollback context.
        val stopped = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.STOPPED, stopped.status)
        assertFalse(stopped.applied)
        assertEquals(0x72a80bea.toInt(), backend.codeWord)
        assertEquals(2, backend.codeWrites)
    }

    @Test
    fun codeWriteExceptionRetainsContextForGuardedDisableRetry() {
        val backend = RecordingHeadSpinBackend(identity()).apply {
            codeWriteThrowsOnce = true
        }
        val supervisor = supervisor(backend, GateHeadSpinSleeper(), RecordingWorkerFactory())

        val failed = supervisor.enable(request())

        assertEquals(ControlHeadSpinStatus.BACKEND_UNAVAILABLE, failed.status)
        assertTrue(failed.applied)
        assertTrue(failed.snapshotReady)

        val stopped = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.STOPPED, stopped.status)
        assertFalse(stopped.applied)
        assertEquals(0x72a80bea.toInt(), backend.codeWord)
        assertEquals(2, backend.codeWrites)
    }

    @Test
    fun nativeProfileMismatchWithObservedRestoreWordCompletesRollback() {
        val backend = RecordingHeadSpinBackend(identity())
        val sleeper = GateHeadSpinSleeper()
        val supervisor = supervisor(backend, sleeper, RecordingWorkerFactory())

        supervisor.enable(request())
        assertTrue(sleeper.entered.await(2, TimeUnit.SECONDS))
        // Simulate another guarded actor restoring the code word before our
        // rollback reaches it. Native reports this as PROFILE_MISMATCH with an
        // observed original word, which is a verified already-restored state.
        backend.codeWord = 0x72a80bea.toInt()
        backend.reportAlreadyRestoredOnRollback = true

        val stopped = supervisor.disable()

        assertEquals(ControlHeadSpinStatus.STOPPED, stopped.status)
        assertFalse(stopped.applied)
        assertEquals(0x72a80bea.toInt(), backend.codeWord)
    }

    private fun supervisor(
        backend: RecordingHeadSpinBackend,
        sleeper: GateHeadSpinSleeper,
        workers: RecordingWorkerFactory,
    ): ControlHeadSpinSupervisor = ControlHeadSpinSupervisor(
        targetResolver = ControlHeadSpinTargetResolver { _, profile ->
            ControlHeadSpinTargetResolution(
                status = ControlHeadSpinResolveStatus.RESOLVED,
                target = target(profile),
                processStartTimeTicks = identity().startTimeTicks,
            )
        },
        backend = backend,
        sleeper = sleeper,
        workerFactory = workers,
        stopJoinMillis = 1_000L,
    )

    private fun awaitStatus(
        supervisor: ControlHeadSpinSupervisor,
        expected: ControlHeadSpinStatus,
    ): ControlHeadSpinState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        var current = supervisor.stateSnapshot()
        while (current.status != expected && System.nanoTime() < deadline) {
            Thread.sleep(1L)
            current = supervisor.stateSnapshot()
        }
        return current
    }

    private fun request(): ControlHeadSpinStartRequest = ControlHeadSpinStartRequest(
        identity = identity(),
        modules = modules(),
        intervalInput = 10,
    )

    private fun target(profile: ControlHeadSpinProfile): ControlHeadSpinTarget = ControlHeadSpinTarget(
        identity = identity(),
        gameAppSha256 = profile.gameAppSha256,
        libClientSha256 = profile.libClientSha256,
        gameAppLoadBase = 0x1000_0000L,
        codeAddress = 0x2000_0000L,
        axisAddressA = 0x3000_0054L,
        axisAddressB = 0x3000_0058L,
    )

    private fun identity(): ControlHeadSpinTargetIdentity = ControlHeadSpinTargetIdentity(
        pid = 4242,
        startTimeTicks = "start-4242",
        mapsFingerprint = "maps-4242",
    )

    private fun modules(): List<ControlNativeModuleIdentity> = listOf(
        ControlNativeModuleIdentity(
            name = ControlGameAppArtifact1582.MODULE_NAME,
            path = "/data/app/lib/${ControlGameAppArtifact1582.MODULE_NAME}",
            loadBase = 0x1000_0000L,
            mappedBytes = 0x2000_0000L,
            memoryElf = true,
            sha256 = ControlHeadSpinProfileCatalog.GAME_APP_SHA256,
        ),
        ControlNativeModuleIdentity(
            name = "libClient.so",
            path = "/data/app/lib/libClient.so",
            loadBase = 0x4000_0000L,
            mappedBytes = 0x0100_0000L,
            memoryElf = true,
            sha256 = ControlHeadSpinProfileCatalog.LIB_CLIENT_SHA256,
        ),
    )

    private class RecordingWorkerFactory : ControlHeadSpinWorkerFactory {
        var created = 0

        override fun create(name: String, task: Runnable): Thread {
            created += 1
            return Thread(task, name).apply { isDaemon = true }
        }
    }

    private class GateHeadSpinSleeper : ControlHeadSpinSleeper {
        val entered = CountDownLatch(1)

        override fun sleepMicros(micros: Long) {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (error: InterruptedException) {
                throw error
            }
        }
    }

    private class RecordingHeadSpinBackend(
        private val identity: ControlHeadSpinTargetIdentity,
    ) : ControlHeadSpinBackend {
        var codeWord = 0x72a80bea.toInt()
        var axisBitsA = 10.0f.toRawBits()
        var axisBitsB = 20.0f.toRawBits()
        var codeWrites = 0
        var axisWrites = 0
        var rejectWrites = false
        var partialAxisWriteOnce = false
        val partialAxisWriteSeen = CountDownLatch(1)
        var codeWriteUnchangedFailure = false
        var codeWriteUnknownFailure = false
        var codeWriteThrowsOnce = false
        var reportAlreadyRestoredOnRollback = false

        @Synchronized
        override fun readPreflight(target: ControlHeadSpinTarget): ControlHeadSpinPreflightResult =
            ControlHeadSpinPreflightResult(
                status = ControlHeadSpinBackendStatus.OK,
                processStartTimeTicks = identity.startTimeTicks,
                mapsFingerprint = identity.mapsFingerprint,
                codeWord = codeWord,
                axisBitsA = axisBitsA,
                axisBitsB = axisBitsB,
            )

        @Synchronized
        override fun compareExchangeCodeWord(
            target: ControlHeadSpinTarget,
            expectedWord: Int,
            desiredWord: Int,
        ): ControlHeadSpinCodeWriteResult {
            codeWrites += 1
            if (rejectWrites) return changedCode()
            val before = codeWord
            if (codeWriteThrowsOnce) {
                codeWriteThrowsOnce = false
                throw IllegalStateException("fixture code backend exception")
            }
            if (codeWriteUnchangedFailure) {
                return ControlHeadSpinCodeWriteResult(
                    status = ControlHeadSpinBackendStatus.WRITE_FAILED,
                    processStartTimeTicks = identity.startTimeTicks,
                    mapsFingerprint = identity.mapsFingerprint,
                    beforeWord = before,
                    afterWord = before,
                    message = "fixture compare-exchange left the original word unchanged",
                )
            }
            if (codeWriteUnknownFailure) {
                codeWriteUnknownFailure = false
                return ControlHeadSpinCodeWriteResult(
                    status = ControlHeadSpinBackendStatus.WRITE_FAILED,
                    processStartTimeTicks = identity.startTimeTicks,
                    mapsFingerprint = identity.mapsFingerprint,
                    beforeWord = before,
                    message = "fixture omitted the after word",
                )
            }
            if (before != expectedWord) {
                return ControlHeadSpinCodeWriteResult(
                    status = if (reportAlreadyRestoredOnRollback &&
                        expectedWord == 0xb9005909.toInt() &&
                        desiredWord == 0x72a80bea.toInt()
                    ) {
                        ControlHeadSpinBackendStatus.PROFILE_MISMATCH
                    } else {
                        ControlHeadSpinBackendStatus.EXPECTED_VALUE_MISMATCH
                    },
                    processStartTimeTicks = identity.startTimeTicks,
                    mapsFingerprint = identity.mapsFingerprint,
                    beforeWord = before,
                    afterWord = before,
                )
            }
            codeWord = desiredWord
            return ControlHeadSpinCodeWriteResult(
                status = ControlHeadSpinBackendStatus.OK,
                processStartTimeTicks = identity.startTimeTicks,
                mapsFingerprint = identity.mapsFingerprint,
                beforeWord = before,
                afterWord = codeWord,
            )
        }

        @Synchronized
        override fun compareExchangeAxisPair(
            target: ControlHeadSpinTarget,
            expectedBitsA: Int,
            desiredBitsA: Int,
            expectedBitsB: Int,
            desiredBitsB: Int,
        ): ControlHeadSpinAxisWriteResult {
            axisWrites += 1
            if (rejectWrites) return changedAxes()
            val beforeA = axisBitsA
            val beforeB = axisBitsB
            if (beforeA != expectedBitsA || beforeB != expectedBitsB) {
                return ControlHeadSpinAxisWriteResult(
                    status = ControlHeadSpinBackendStatus.EXPECTED_VALUE_MISMATCH,
                    processStartTimeTicks = identity.startTimeTicks,
                    mapsFingerprint = identity.mapsFingerprint,
                    beforeBitsA = beforeA,
                    afterBitsA = beforeA,
                    beforeBitsB = beforeB,
                    afterBitsB = beforeB,
                )
            }
            if (partialAxisWriteOnce) {
                partialAxisWriteOnce = false
                axisBitsA = desiredBitsA
                partialAxisWriteSeen.countDown()
                return ControlHeadSpinAxisWriteResult(
                    status = ControlHeadSpinBackendStatus.WRITE_FAILED,
                    processStartTimeTicks = identity.startTimeTicks,
                    mapsFingerprint = identity.mapsFingerprint,
                    appliedA = beforeA != desiredBitsA,
                    appliedB = false,
                    beforeBitsA = beforeA,
                    afterBitsA = axisBitsA,
                    beforeBitsB = beforeB,
                    afterBitsB = beforeB,
                    message = "fixture committed only axis A",
                )
            }
            axisBitsA = desiredBitsA
            axisBitsB = desiredBitsB
            return ControlHeadSpinAxisWriteResult(
                status = ControlHeadSpinBackendStatus.OK,
                processStartTimeTicks = identity.startTimeTicks,
                mapsFingerprint = identity.mapsFingerprint,
                appliedA = beforeA != desiredBitsA,
                appliedB = beforeB != desiredBitsB,
                beforeBitsA = beforeA,
                afterBitsA = axisBitsA,
                beforeBitsB = beforeB,
                afterBitsB = axisBitsB,
            )
        }

        private fun changedCode() = ControlHeadSpinCodeWriteResult(
            status = ControlHeadSpinBackendStatus.TARGET_CHANGED,
            processStartTimeTicks = "changed",
            mapsFingerprint = "changed",
            message = "fixture target changed",
        )

        private fun changedAxes() = ControlHeadSpinAxisWriteResult(
            status = ControlHeadSpinBackendStatus.TARGET_CHANGED,
            processStartTimeTicks = "changed",
            mapsFingerprint = "changed",
            message = "fixture target changed",
        )
    }
}
