package me.dartcv.minix.root

import me.dartcv.minix.root.nativeadapter.NativeMemoryBatchStatus
import me.dartcv.minix.root.nativeadapter.NativeU32BatchOperation
import me.dartcv.minix.root.nativeadapter.NativeU32BatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadSpinNativeBackendTest {
    @Test
    fun codeFailureWithoutObservationLeavesAfterWordUnknown() {
        val result = batchResult(
            status = NativeMemoryBatchStatus.WRITE_FAILED,
            requestedCount = 1,
            completedCount = 0,
        )

        val mapped = result.toHeadSpinCodeWriteResult(
            expectedWord = RESTORE_WORD,
            desiredWord = PATCHED_WORD,
        )

        assertEquals(RootHeadSpinBackendStatus.WRITE_FAILED, mapped.status)
        assertEquals(RESTORE_WORD, mapped.beforeWord)
        assertNull(mapped.afterWord)
    }

    @Test
    fun codeFailureWithExplicitObservedOriginalIsDistinguishedFromUnknown() {
        val result = batchResult(
            status = NativeMemoryBatchStatus.WRITE_FAILED,
            requestedCount = 1,
            completedCount = 0,
            observedValueBits = RESTORE_WORD.toLong() and U32_MASK,
        )

        val mapped = result.toHeadSpinCodeWriteResult(
            expectedWord = RESTORE_WORD,
            desiredWord = PATCHED_WORD,
        )

        assertEquals(RESTORE_WORD, mapped.beforeWord)
        assertEquals(RESTORE_WORD, mapped.afterWord)
    }

    @Test
    fun completedCodeWriteReportsDesiredAfterWordEvenOnTerminalStatus() {
        val result = batchResult(
            status = NativeMemoryBatchStatus.VERIFY_FAILED,
            requestedCount = 1,
            completedCount = 1,
        )

        val mapped = result.toHeadSpinCodeWriteResult(
            expectedWord = RESTORE_WORD,
            desiredWord = PATCHED_WORD,
        )

        assertEquals(RootHeadSpinBackendStatus.VERIFY_FAILED, mapped.status)
        assertEquals(PATCHED_WORD, mapped.afterWord)
    }

    @Test
    fun verifyFailureCarriesNativeObservedAfterWord() {
        val observed = 0x1234_5678
        val result = batchResult(
            status = NativeMemoryBatchStatus.VERIFY_FAILED,
            requestedCount = 1,
            completedCount = 0,
            failedIndex = 0,
            observedValueBits = observed.toLong() and U32_MASK,
        )

        val mapped = result.toHeadSpinCodeWriteResult(
            expectedWord = RESTORE_WORD,
            desiredWord = PATCHED_WORD,
        )

        assertEquals(RootHeadSpinBackendStatus.VERIFY_FAILED, mapped.status)
        assertEquals(observed, mapped.beforeWord)
        assertEquals(observed, mapped.afterWord)
    }

    @Test
    fun nativeProfileMismatchWithObservedOriginalPreservesAlreadyRestoredValue() {
        val result = batchResult(
            status = NativeMemoryBatchStatus.PROFILE_MISMATCH,
            requestedCount = 1,
            completedCount = 0,
            failedIndex = 0,
            observedValueBits = RESTORE_WORD.toLong() and U32_MASK,
        )

        val mapped = result.toHeadSpinCodeWriteResult(
            expectedWord = PATCHED_WORD,
            desiredWord = RESTORE_WORD,
        )

        assertEquals(RootHeadSpinBackendStatus.PROFILE_MISMATCH, mapped.status)
        assertEquals(RESTORE_WORD, mapped.beforeWord)
        assertEquals(RESTORE_WORD, mapped.afterWord)
    }

    @Test
    fun partialAxisResultReportsOnlyCompletedAxisAndObservedFailedAxis() {
        val expectedA = 10.0f.toRawBits()
        val expectedB = 20.0f.toRawBits()
        val desiredA = 11.0f.toRawBits()
        val desiredB = 21.0f.toRawBits()
        val result = batchResult(
            status = NativeMemoryBatchStatus.PARTIAL_WRITE,
            requestedCount = 2,
            completedCount = 1,
            failedIndex = 1,
            observedValueBits = expectedB.toLong() and U32_MASK,
        )

        val mapped = result.toHeadSpinAxisWriteResult(
            expectedBitsA = expectedA,
            desiredBitsA = desiredA,
            expectedBitsB = expectedB,
            desiredBitsB = desiredB,
        )

        assertEquals(RootHeadSpinBackendStatus.WRITE_FAILED, mapped.status)
        assertTrue(mapped.appliedA)
        assertFalse(mapped.appliedB)
        assertEquals(desiredA, mapped.afterBitsA)
        assertEquals(expectedB, mapped.beforeBitsB)
        assertEquals(expectedB, mapped.afterBitsB)
    }

    @Test
    fun failedFirstAxisWithoutObservationDoesNotInventEitherAfterValue() {
        val result = batchResult(
            status = NativeMemoryBatchStatus.WRITE_FAILED,
            requestedCount = 2,
            completedCount = 0,
            failedIndex = 0,
        )

        val mapped = result.toHeadSpinAxisWriteResult(
            expectedBitsA = 1,
            desiredBitsA = 2,
            expectedBitsB = 3,
            desiredBitsB = 4,
        )

        assertFalse(mapped.appliedAny)
        assertNull(mapped.afterBitsA)
        assertNull(mapped.afterBitsB)
    }

    private fun batchResult(
        status: NativeMemoryBatchStatus,
        requestedCount: Int,
        completedCount: Int,
        failedIndex: Int? = null,
        observedValueBits: Long? = null,
    ): NativeU32BatchResult = NativeU32BatchResult(
        status = status,
        operation = NativeU32BatchOperation.ROLLBACK,
        pid = 4242,
        processStartTimeTicks = "start-4242",
        mapsFingerprint = "maps-4242",
        requestedCount = requestedCount,
        completedCount = completedCount,
        failedIndex = failedIndex,
        observedValueBits = observedValueBits,
    )

    private companion object {
        const val RESTORE_WORD = 0x72a80bea.toInt()
        const val PATCHED_WORD = 0xb9005909.toInt()
        const val U32_MASK = 0xffff_ffffL
    }
}
