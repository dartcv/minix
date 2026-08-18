package me.dartcv.minix.root

import me.dartcv.minix.root.nativeadapter.NativeMemoryBatchStatus
import me.dartcv.minix.root.nativeadapter.NativeMemoryRegionRequest
import me.dartcv.minix.root.nativeadapter.NativeU32BatchResult
import me.dartcv.minix.root.nativeadapter.NativeU32WriteRequest
import me.dartcv.minix.root.nativeadapter.TargetNativeProbe

/**
 * Native adapter for the gated head-spin supervisor.
 *
 * The existing native batch primitive is used in its guarded rollback mode for
 * both forward and reverse exchanges.  It pins the process start-time and the
 * selected maps fingerprint before opening /proc/<pid>/mem, validates every
 * mapping, and verifies each u32 after writing.  The supervisor still treats a
 * two-axis operation as potentially partial and retains the first-axis
 * snapshot for a compensating rollback.
 */
internal object JniRootHeadSpinBackend : RootHeadSpinBackend {
    override fun readPreflight(target: RootHeadSpinTarget): RootHeadSpinPreflightResult {
        val result = runCatching {
            TargetNativeProbe.readMemoryRegions(
                pid = target.identity.pid,
                expectedStartTimeTicks = target.identity.startTimeTicks,
                expectedMapsFingerprint = target.identity.mapsFingerprint,
                requests = listOf(
                    NativeMemoryRegionRequest(
                        address = target.codeAddress,
                        byteCount = Int.SIZE_BYTES,
                        requireExecutableMapping = true,
                    ),
                    NativeMemoryRegionRequest(
                        address = target.axisAddressA,
                        byteCount = Int.SIZE_BYTES,
                        requireWritableMapping = true,
                    ),
                    NativeMemoryRegionRequest(
                        address = target.axisAddressB,
                        byteCount = Int.SIZE_BYTES,
                        requireWritableMapping = true,
                    ),
                ),
            )
        }.getOrElse { error ->
            return RootHeadSpinPreflightResult(
                status = RootHeadSpinBackendStatus.UNAVAILABLE,
                processStartTimeTicks = target.identity.startTimeTicks,
                mapsFingerprint = target.identity.mapsFingerprint,
                message = error.message ?: "Head-spin native preflight failed",
            )
        }
        val status = result.status.toHeadSpinBackendStatus()
        if (!result.isSuccess || result.regions.size != 3) {
            return RootHeadSpinPreflightResult(
                status = status,
                processStartTimeTicks = result.processStartTimeTicks,
                mapsFingerprint = result.mapsFingerprint,
                message = result.message.ifBlank { "Head-spin preflight batch was incomplete" },
            )
        }
        val code = result.regions[0].bytes.toU32Bits()
        val axisA = result.regions[1].bytes.toU32Bits()
        val axisB = result.regions[2].bytes.toU32Bits()
        if (code == null || axisA == null || axisB == null) {
            return RootHeadSpinPreflightResult(
                status = RootHeadSpinBackendStatus.INVALID_RESPONSE,
                processStartTimeTicks = result.processStartTimeTicks,
                mapsFingerprint = result.mapsFingerprint,
                message = "Head-spin preflight returned a non-u32 region",
            )
        }
        return RootHeadSpinPreflightResult(
            status = status,
            processStartTimeTicks = result.processStartTimeTicks,
            mapsFingerprint = result.mapsFingerprint,
            codeWord = code,
            axisBitsA = axisA,
            axisBitsB = axisB,
            message = result.message,
        )
    }

    override fun compareExchangeCodeWord(
        target: RootHeadSpinTarget,
        expectedWord: Int,
        desiredWord: Int,
    ): RootHeadSpinCodeWriteResult {
        val result = guardedBatch(
            target = target,
            writes = listOf(
                NativeU32WriteRequest(
                    address = target.codeAddress,
                    valueBits = desiredWord.toU32Long(),
                    expectedCurrentValueBits = expectedWord.toU32Long(),
                    requireExecutableMapping = true,
                ),
            ),
        )
        return result.toHeadSpinCodeWriteResult(expectedWord, desiredWord)
    }

    override fun compareExchangeAxisPair(
        target: RootHeadSpinTarget,
        expectedBitsA: Int,
        desiredBitsA: Int,
        expectedBitsB: Int,
        desiredBitsB: Int,
    ): RootHeadSpinAxisWriteResult {
        val result = guardedBatch(
            target = target,
            writes = listOf(
                NativeU32WriteRequest(
                    address = target.axisAddressA,
                    valueBits = desiredBitsA.toU32Long(),
                    expectedCurrentValueBits = expectedBitsA.toU32Long(),
                    requireWritableMapping = true,
                ),
                NativeU32WriteRequest(
                    address = target.axisAddressB,
                    valueBits = desiredBitsB.toU32Long(),
                    expectedCurrentValueBits = expectedBitsB.toU32Long(),
                    requireWritableMapping = true,
                ),
            ),
        )
        return result.toHeadSpinAxisWriteResult(
            expectedBitsA = expectedBitsA,
            desiredBitsA = desiredBitsA,
            expectedBitsB = expectedBitsB,
            desiredBitsB = desiredBitsB,
        )
    }

    private fun guardedBatch(
        target: RootHeadSpinTarget,
        writes: List<NativeU32WriteRequest>,
    ) = runCatching {
        TargetNativeProbe.rollbackU32Batch(
            pid = target.identity.pid,
            expectedStartTimeTicks = target.identity.startTimeTicks,
            expectedMapsFingerprint = target.identity.mapsFingerprint,
            writes = writes,
        )
    }.getOrElse { error ->
        // Keep the result shape available to the supervisor so it can retain
        // the rollback context even when JNI loading or marshaling fails.
        NativeU32BatchResult(
            status = NativeMemoryBatchStatus.NATIVE_ERROR,
            operation = me.dartcv.minix.root.nativeadapter.NativeU32BatchOperation.ROLLBACK,
            pid = target.identity.pid,
            processStartTimeTicks = target.identity.startTimeTicks,
            mapsFingerprint = target.identity.mapsFingerprint,
            requestedCount = writes.size,
            message = error.message ?: "Head-spin native batch failed",
        )
    }
}

private fun NativeMemoryBatchStatus.toHeadSpinBackendStatus(): RootHeadSpinBackendStatus = when (this) {
    NativeMemoryBatchStatus.OK -> RootHeadSpinBackendStatus.OK
    NativeMemoryBatchStatus.TARGET_CHANGED,
    NativeMemoryBatchStatus.TARGET_NOT_RUNNING,
    -> RootHeadSpinBackendStatus.TARGET_CHANGED
    NativeMemoryBatchStatus.PROFILE_MISMATCH,
    NativeMemoryBatchStatus.ADDRESS_NOT_READABLE,
    -> RootHeadSpinBackendStatus.PROFILE_MISMATCH
    NativeMemoryBatchStatus.READ_FAILED,
    NativeMemoryBatchStatus.PARTIAL_READ,
    NativeMemoryBatchStatus.MAPS_UNREADABLE,
    -> RootHeadSpinBackendStatus.READ_FAILED
    NativeMemoryBatchStatus.WRITE_FAILED,
    NativeMemoryBatchStatus.PARTIAL_WRITE,
    -> RootHeadSpinBackendStatus.WRITE_FAILED
    NativeMemoryBatchStatus.VERIFY_FAILED -> RootHeadSpinBackendStatus.VERIFY_FAILED
    NativeMemoryBatchStatus.NATIVE_UNAVAILABLE,
    NativeMemoryBatchStatus.NATIVE_ERROR,
    NativeMemoryBatchStatus.MEM_OPEN_FAILED,
    -> RootHeadSpinBackendStatus.UNAVAILABLE
    NativeMemoryBatchStatus.INVALID_PID,
    NativeMemoryBatchStatus.INVALID_IDENTITY,
    NativeMemoryBatchStatus.INVALID_REQUEST,
    NativeMemoryBatchStatus.INVALID_ADDRESS,
    NativeMemoryBatchStatus.INVALID_SIZE,
    NativeMemoryBatchStatus.PROC_UNREADABLE,
    NativeMemoryBatchStatus.INVALID_RESPONSE,
    -> RootHeadSpinBackendStatus.INVALID_RESPONSE
}

private fun Int.toU32Long(): Long = toLong() and 0xffff_ffffL

/**
 * Converts a native single-item result without inventing an after value.
 * `completedCount` is the only affirmative write acknowledgement; an
 * `observedValueBits` value is carried through only when native supplied it.
 * A missing observation therefore remains `afterWord=null`, allowing the
 * supervisor's UNCERTAIN ownership state to retain rollback context.
 */
internal fun NativeU32BatchResult.toHeadSpinCodeWriteResult(
    expectedWord: Int,
    desiredWord: Int,
): RootHeadSpinCodeWriteResult {
    val observed = observedValueBits?.toInt()
    val completed = completedCount >= 1
    return RootHeadSpinCodeWriteResult(
        status = status.toHeadSpinBackendStatus(),
        processStartTimeTicks = processStartTimeTicks,
        mapsFingerprint = mapsFingerprint,
        beforeWord = if (completed) expectedWord else observed ?: expectedWord,
        afterWord = when {
            completed -> desiredWord
            observed != null -> observed
            else -> null
        },
        message = message,
    )
}

/**
 * Converts a two-item native result while preserving partial/unknown writes.
 * `failedIndex` identifies which item the optional observation belongs to;
 * an omitted observation leaves that item's after bits null rather than
 * pretending the expected value was read back.
 */
internal fun NativeU32BatchResult.toHeadSpinAxisWriteResult(
    expectedBitsA: Int,
    desiredBitsA: Int,
    expectedBitsB: Int,
    desiredBitsB: Int,
): RootHeadSpinAxisWriteResult {
    val firstApplied = completedCount >= 1
    val secondApplied = completedCount >= 2
    val observed = observedValueBits?.toInt()
    val observedA = if (failedIndex == 0) observed else null
    val observedB = if (failedIndex == 1) observed else null
    return RootHeadSpinAxisWriteResult(
        status = status.toHeadSpinBackendStatus(),
        processStartTimeTicks = processStartTimeTicks,
        mapsFingerprint = mapsFingerprint,
        appliedA = firstApplied,
        appliedB = secondApplied,
        beforeBitsA = observedA ?: expectedBitsA,
        afterBitsA = when {
            firstApplied -> desiredBitsA
            observedA != null -> observedA
            else -> null
        },
        beforeBitsB = observedB ?: expectedBitsB,
        afterBitsB = when {
            secondApplied -> desiredBitsB
            observedB != null -> observedB
            else -> null
        },
        message = message,
    )
}

private fun ByteArray.toU32Bits(): Int? {
    if (size != Int.SIZE_BYTES) return null
    return (this[0].toInt() and 0xff) or
        ((this[1].toInt() and 0xff) shl 8) or
        ((this[2].toInt() and 0xff) shl 16) or
        ((this[3].toInt() and 0xff) shl 24)
}
