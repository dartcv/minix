package me.dartcv.minix.control.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TargetNativeProbeTest {
    @Test
    fun decodesInternalModulePathAndPositiveLoadBase() {
        val result = decodeNativeProbePayload(
            payload = """
                {
                  "status":"OK",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "regionCount":3,
                  "moduleCount":1,
                  "memoryReadableModuleCount":1,
                  "memoryReadBytes":64,
                  "memoryElfHeaderCount":1,
                  "mapsFingerprint":"fixture",
                  "truncated":false,
                  "message":"",
                  "modules":[{
                    "name":"libfixture.so",
                    "path":"/data/app/libfixture.so",
                    "origin":"APP",
                    "regionCount":3,
                    "mappedBytes":4096,
                    "readableBytes":4096,
                    "executable":true,
                    "fileReadable":true,
                    "elfFile":true,
                    "memoryReadable":true,
                    "memoryReadBytes":64,
                    "memoryElf":true,
                    "loadBaseHex":"0000000071000000"
                  }]
                }
            """.trimIndent(),
            expectedPid = 77,
        )

        val module = result.modules.single()
        assertEquals("libfixture.so", module.name)
        assertEquals("/data/app/libfixture.so", module.path)
        assertEquals(0x71000000L, module.loadBase)
    }

    @Test
    fun decodesLittleEndianInt32BitsWithoutSignLoss() {
        val result = decodeNativeScalarReadPayload(
            payload = """
                {"status":"OK","pid":77,"processStartTimeTicks":"1234","byteCount":4,"valueHex":"12345678","message":""}
            """.trimIndent(),
            expectedPid = 77,
            expectedByteCount = 4,
        )

        assertTrue(result.isSuccess)
        assertEquals(0x12345678, result.int32Value)
        assertEquals(0x12345678L, result.valueBits)
        assertEquals("1234", result.processStartTimeTicks)
    }

    @Test
    fun decodesUnsigned64BitPatternAsStableLongBits() {
        val result = decodeNativeScalarReadPayload(
            payload = """
                {"status":"OK","pid":77,"processStartTimeTicks":"1234","byteCount":8,"valueHex":"8000000000000000","message":""}
            """.trimIndent(),
            expectedPid = 77,
            expectedByteCount = 8,
        )

        assertTrue(result.isSuccess)
        assertEquals(Long.MIN_VALUE, result.int64Value)
    }

    @Test
    fun nonOkStatusDoesNotExposeValueBits() {
        val result = decodeNativeScalarReadPayload(
            payload = """
                {"status":"ADDRESS_NOT_READABLE","pid":77,"byteCount":8,"valueHex":"0000000000000000","message":"outside"}
            """.trimIndent(),
            expectedPid = 77,
            expectedByteCount = 8,
        )

        assertTrue(!result.isSuccess)
        assertNull(result.valueBits)
        assertEquals(NativeScalarReadStatus.ADDRESS_NOT_READABLE, result.status)
        assertEquals("outside", result.message)
    }

    @Test
    fun decodesPresentPagemapEntryAndDerivesPageFrameNumber() {
        val result = decodeNativePagemapReadPayload(
            payload = """
                {"status":"OK","pid":77,"processStartTimeTicks":"1234","addressHex":"000000000005b860","pageSize":4096,"entryHex":"8000000000012345","message":""}
            """.trimIndent(),
            expectedPid = 77,
            expectedAddress = 0x5b860L,
        )

        assertTrue(result.isSuccess)
        assertTrue(result.isPresent)
        assertEquals(4096L, result.pageSize)
        assertEquals(
            java.lang.Long.parseUnsignedLong("8000000000012345", 16),
            result.entryBits,
        )
        assertEquals(0x12345L, result.pageFrameNumber)
    }

    @Test
    fun pagemapNonPresentStatusKeepsTheReadNonSuccessful() {
        val result = decodeNativePagemapReadPayload(
            payload = """
                {"status":"PAGE_NOT_PRESENT","pid":77,"addressHex":"000000000005b860","pageSize":4096,"entryHex":"0000000000000000","message":"Virtual page is not present"}
            """.trimIndent(),
            expectedPid = 77,
            expectedAddress = 0x5b860L,
        )

        assertTrue(!result.isSuccess)
        assertEquals(NativePagemapReadStatus.PAGE_NOT_PRESENT, result.status)
        assertEquals("Virtual page is not present", result.message)
        assertEquals(null, result.entryBits)
    }

    @Test
    fun decodesTypedScalarPatchWithPinnedIdentity() {
        val result = decodeNativeScalarPatchPayload(
            payload = """
                {"status":"APPLIED","pid":77,"processStartTimeTicks":"1234","byteCount":4,"beforeValueHex":"00000000","afterValueHex":"00000001","message":""}
            """.trimIndent(),
            expectedPid = 77,
            expectedByteCount = 4,
            expectedStartTimeTicks = "1234",
        )

        assertTrue(result.isSuccess)
        assertEquals(NativeScalarPatchStatus.APPLIED, result.status)
        assertEquals(0L, result.beforeValueBits)
        assertEquals(1L, result.afterValueBits)
    }

    @Test
    fun rejectsScalarPatchForDifferentPinnedIdentity() {
        val failure = runCatching {
            decodeNativeScalarPatchPayload(
                payload = """
                    {"status":"APPLIED","pid":77,"processStartTimeTicks":"9999","byteCount":4,"beforeValueHex":"00000000","afterValueHex":"00000001","message":""}
                """.trimIndent(),
                expectedPid = 77,
                expectedByteCount = 4,
                expectedStartTimeTicks = "1234",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun executableScalarPatchRequiresAarch64InstructionAlignment() {
        val result = TargetNativeProbe.compareExchangeInt32(
            pid = 77,
            address = 0x1002L,
            expectedStartTimeTicks = "1234",
            expectedValueBits = 0x39449269L,
            desiredValueBits = 0xd503201fL,
            requireExecutableMapping = true,
        )

        assertEquals(NativeScalarPatchStatus.INVALID_ADDRESS, result.status)
        assertTrue(result.message.contains("aligned"))
    }

    @Test
    fun decodesPinnedMemoryRegionBatchInRequestOrder() {
        val requests = listOf(
            NativeMemoryRegionRequest(address = 0x71000000L, byteCount = 4),
            NativeMemoryRegionRequest(address = 0x72000010L, byteCount = 8),
        )
        val result = decodeNativeMemoryRegionBatchPayload(
            payload = """
                {
                  "status":"OK",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"0123456789abcdef",
                  "requestedCount":2,
                  "completedCount":2,
                  "message":"",
                  "regions":[
                    {"addressHex":"0000000071000000","byteCount":4,"bytesHex":"1f2003d5"},
                    {"addressHex":"0000000072000010","byteCount":8,"bytesHex":"80078052f1df0094"}
                  ]
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedRequests = requests,
        )

        assertTrue(result.isSuccess)
        assertEquals("0123456789abcdef", result.mapsFingerprint)
        assertEquals(2, result.completedCount)
        assertArrayEquals(
            byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte()),
            result.regions[0].bytes,
        )
        assertArrayEquals(
            byteArrayOf(
                0x80.toByte(), 0x07, 0x80.toByte(), 0x52,
                0xf1.toByte(), 0xdf.toByte(), 0x00, 0x94.toByte(),
            ),
            result.regions[1].bytes,
        )
    }

    @Test
    fun decodesU32VerificationFailureWithExactFailureItem() {
        val writes = listOf(
            NativeU32WriteRequest(address = 0x71000000L, valueBits = 0xd503201fL),
            NativeU32WriteRequest(address = 0x72000010L, valueBits = 0x52800780L),
        )
        val result = decodeNativeU32BatchPayload(
            payload = """
                {
                  "status":"VERIFY_FAILED",
                  "operation":"ITERATION",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "requestedCount":2,
                  "completedCount":1,
                  "failedIndex":1,
                  "failedAddressHex":"0000000072000010",
                  "expectedValueHex":"52800780",
                  "observedValueHex":"00000000",
                  "message":"verify"
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedOperation = NativeU32BatchOperation.ITERATION,
            expectedWrites = writes,
        )

        assertEquals(NativeMemoryBatchStatus.VERIFY_FAILED, result.status)
        assertEquals(1, result.failedIndex)
        assertEquals(0x72000010L, result.failedAddress)
        assertEquals(0x52800780L, result.expectedValueBits)
        assertEquals(0L, result.observedValueBits)
    }

    @Test
    fun decodesRollbackGuardMismatchWithExactExpectedCurrentValue() {
        val writes = listOf(
            NativeU32WriteRequest(
                address = 0x71000000L,
                valueBits = 0x39449269L,
                expectedCurrentValueBits = 0xd503201fL,
            ),
        )
        val result = decodeNativeU32BatchPayload(
            payload = """
                {
                  "status":"PROFILE_MISMATCH",
                  "operation":"ROLLBACK",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"0123456789abcdef",
                  "requestedCount":1,
                  "completedCount":0,
                  "failedIndex":0,
                  "failedAddressHex":"0000000071000000",
                  "expectedValueHex":"39449269",
                  "observedValueHex":"00000000",
                  "guardValueHex":"d503201f",
                  "message":"guard changed"
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedOperation = NativeU32BatchOperation.ROLLBACK,
            expectedWrites = writes,
        )

        assertEquals(NativeMemoryBatchStatus.PROFILE_MISMATCH, result.status)
        assertEquals(0xd503201fL, result.guardValueBits)
        assertEquals(0L, result.observedValueBits)
    }

    @Test
    fun rollbackBatchRequiresExpectedCurrentValueBeforeLoadingNativeLibrary() {
        val result = TargetNativeProbe.rollbackU32Batch(
            pid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            writes = listOf(
                NativeU32WriteRequest(address = 0x71000000L, valueBits = 0x39449269L),
            ),
        )

        assertEquals(NativeMemoryBatchStatus.INVALID_REQUEST, result.status)
        assertEquals(0, result.failedIndex)
    }

    @Test
    fun u32BatchRejectsMisalignedItemBeforeLoadingNativeLibrary() {
        val result = TargetNativeProbe.runU32Iteration(
            pid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            writes = listOf(
                NativeU32WriteRequest(address = 0x71000002L, valueBits = 0xd503201fL),
            ),
        )

        assertEquals(NativeMemoryBatchStatus.INVALID_ADDRESS, result.status)
        assertEquals(0, result.failedIndex)
    }

    @Test
    fun preservesActualMapsFingerprintOnTargetChangedBatch() {
        val requests = listOf(
            NativeMemoryRegionRequest(address = 0x71000000L, byteCount = 4),
        )
        val result = decodeNativeMemoryRegionBatchPayload(
            payload = """
                {
                  "status":"TARGET_CHANGED",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"fedcba9876543210",
                  "requestedCount":1,
                  "completedCount":0,
                  "message":"maps changed",
                  "regions":[]
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedRequests = requests,
        )

        assertEquals(NativeMemoryBatchStatus.TARGET_CHANGED, result.status)
        assertEquals("fedcba9876543210", result.mapsFingerprint)
    }

    @Test
    fun rejectsNonCanonicalPinnedMapsFingerprintBeforeLoadingNativeLibrary() {
        val result = TargetNativeProbe.runU32Iteration(
            pid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "ABCDEF0123456789",
            writes = listOf(
                NativeU32WriteRequest(address = 0x71000000L, valueBits = 0xd503201fL),
            ),
        )

        assertEquals(NativeMemoryBatchStatus.INVALID_IDENTITY, result.status)
    }

    @Test
    fun decodesSuccessfulAntiFlashCycleWithPreflightSnapshots() {
        val fixture = antiFlashFixture()
        val result = decodeNativeAntiFlashCyclePayload(
            payload = """
                {
                  "status":"OK",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"0123456789abcdef",
                  "requestedCount":17,
                  "completedCount":17,
                  "writeAttempted":true,
                  "bssAddressHex":"0000000073000080",
                  "bssValueHex":"11223344",
                  "message":"",
                  "codeRegionValues":[
                    "01020304","11121314","21222324",
                    "31323334","41424344","51525354"
                  ]
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedRegions = fixture.regions,
            expectedBssAddress = fixture.bssAddress,
            expectedWrites = fixture.writes,
        )

        assertTrue(result.isSuccess)
        assertEquals("0123456789abcdef", result.mapsFingerprint)
        assertEquals(6, result.codeRegionValues.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), result.bssValue)
    }

    @Test
    fun profileMismatchCycleCarriesFullPreflightAndNoWriteAttempt() {
        val fixture = antiFlashFixture()
        val result = decodeNativeAntiFlashCyclePayload(
            payload = """
                {
                  "status":"PROFILE_MISMATCH",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"0123456789abcdef",
                  "requestedCount":17,
                  "completedCount":0,
                  "failedIndex":4,
                  "failedAddressHex":"0000000072000030",
                  "writeAttempted":false,
                  "bssAddressHex":"0000000073000080",
                  "bssValueHex":"11223344",
                  "message":"unknown region",
                  "codeRegionValues":[
                    "01020304","11121314","21222324",
                    "ffffffff","41424344","51525354"
                  ]
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedRegions = fixture.regions,
            expectedBssAddress = fixture.bssAddress,
            expectedWrites = fixture.writes,
        )

        assertEquals(NativeMemoryBatchStatus.PROFILE_MISMATCH, result.status)
        assertEquals(0, result.completedCount)
        assertEquals(4, result.failedIndex)
        assertTrue(!result.writeAttempted)
        assertEquals(6, result.codeRegionValues.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), result.bssValue)
    }

    @Test
    fun prewriteCycleFailurePreservesPartialPreflightAndNativeIdentity() {
        val fixture = antiFlashFixture()
        val result = decodeNativeAntiFlashCyclePayload(
            payload = """
                {
                  "status":"READ_FAILED",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"fedcba9876543210",
                  "requestedCount":17,
                  "completedCount":0,
                  "failedIndex":1,
                  "failedAddressHex":"000000007200000c",
                  "writeAttempted":false,
                  "message":"preflight failed",
                  "codeRegionValues":["01020304","11121314"]
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedRegions = fixture.regions,
            expectedBssAddress = fixture.bssAddress,
            expectedWrites = fixture.writes,
        )

        assertEquals(NativeMemoryBatchStatus.READ_FAILED, result.status)
        assertEquals("fedcba9876543210", result.mapsFingerprint)
        assertEquals(2, result.codeRegionValues.size)
        assertTrue(!result.writeAttempted)
    }

    @Test(expected = IllegalStateException::class)
    fun attemptedWriteCycleRejectsPartialPreflight() {
        val fixture = antiFlashFixture()
        decodeNativeAntiFlashCyclePayload(
            payload = """
                {
                  "status":"VERIFY_FAILED",
                  "pid":77,
                  "processStartTimeTicks":"1234",
                  "mapsFingerprint":"0123456789abcdef",
                  "requestedCount":17,
                  "completedCount":2,
                  "writeAttempted":true,
                  "message":"verify failed",
                  "codeRegionValues":["01020304"]
                }
            """.trimIndent(),
            expectedPid = 77,
            expectedStartTimeTicks = "1234",
            expectedMapsFingerprint = "0123456789abcdef",
            expectedRegions = fixture.regions,
            expectedBssAddress = fixture.bssAddress,
            expectedWrites = fixture.writes,
        )
    }

    private fun antiFlashFixture(): AntiFlashFixture {
        val regions = List(6) { index ->
            val first = (1 + index * 0x10).toByte()
            NativeAntiFlashCycleRegion(
                address = 0x71000000L + index * 0x10L,
                originalBytes = byteArrayOf(first, (first + 1).toByte(), (first + 2).toByte(), (first + 3).toByte()),
                patchBytes = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte()),
            )
        }
        val writes = List(17) { index ->
            NativeU32WriteRequest(
                address = if (index == 16) 0x73000080L else 0x72000000L + index * 0x0cL,
                valueBits = 0x52800780L,
                requireWritableMapping = index == 16,
                requireExecutableMapping = index != 16,
            )
        }
        return AntiFlashFixture(regions, 0x73000080L, writes)
    }

    private data class AntiFlashFixture(
        val regions: List<NativeAntiFlashCycleRegion>,
        val bssAddress: Long,
        val writes: List<NativeU32WriteRequest>,
    )
}
