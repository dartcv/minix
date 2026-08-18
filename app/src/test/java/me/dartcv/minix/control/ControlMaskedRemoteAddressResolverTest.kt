package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMaskedRemoteAddressResolverTest {
    @Test
    fun readsTheLowUint32SeedAndKeepsOnlyTheLow24Bits() {
        val scalar = RecordingResolverScalarReader(valueBits = 0x12abcdefL)
        val resolver = ControlMaskedRemoteAddressResolver(scalarReader = scalar)

        val result = resolver.resolve(pid = 77, seed = 0x5b860L)

        assertTrue(result.isSuccess)
        assertEquals(0xabcdefL, result.resolvedAddress)
        assertEquals(listOf(0x5b860L), scalar.int32Addresses)
        assertEquals("start-77", result.processStartTimeTicks)
    }

    @Test
    fun fullUint32SeedIsPassedAsAPositiveRemoteAddress() {
        val scalar = RecordingResolverScalarReader(valueBits = 0xffffffffL)
        val resolver = ControlMaskedRemoteAddressResolver(scalarReader = scalar)

        val result = resolver.resolve(pid = 77, seed = 0xffff_ffffL)

        assertTrue(result.isSuccess)
        assertEquals(0x00ff_ffffL, result.resolvedAddress)
        assertEquals(listOf(0xffff_ffffL), scalar.int32Addresses)
    }

    @Test
    fun scalarFailureReturnsATypedResolverFailure() {
        val scalar = RecordingResolverScalarReader(
            isSuccess = false,
            message = "read failed",
        )
        val resolver = ControlMaskedRemoteAddressResolver(scalarReader = scalar)

        val result = resolver.resolve(pid = 77, seed = 0x5b860L)

        assertFalse(result.isSuccess)
        assertEquals(ControlAddressResolveStatus.SCALAR_READ_FAILED, result.status)
        assertEquals("read failed", result.message)
    }

    @Test
    fun highSeedStillUsesOnlyItsLowUint32AddressAndKeepsTheLow24Value() {
        val scalar = RecordingResolverScalarReader(valueBits = 0x1122_3344_5566_7788L)
        val resolver = ControlMaskedRemoteAddressResolver(scalarReader = scalar)

        val result = resolver.resolve(pid = 77, seed = 0x7f12_3456_78L)

        assertTrue(result.isSuccess)
        assertEquals(0x7f12_3456_78L, result.seed)
        assertEquals(0x66_7788L, result.resolvedAddress)
        assertEquals(listOf(0x1234_5678L), scalar.int32Addresses)
        assertTrue(scalar.int64Addresses.isEmpty())
    }

    @Test
    fun resolverRejectsAHighSeedThatTruncatesToZeroUint32() {
        val scalar = RecordingResolverScalarReader()
        val resolver = ControlMaskedRemoteAddressResolver(scalarReader = scalar)

        val result = resolver.resolve(pid = 77, seed = 0x1_0000_0000L)

        assertEquals(ControlAddressResolveStatus.INVALID_SEED, result.status)
        assertTrue(scalar.int32Addresses.isEmpty())
        assertTrue(scalar.int64Addresses.isEmpty())
    }
}

private class RecordingResolverScalarReader(
    private val valueBits: Long = 0L,
    private val isSuccess: Boolean = true,
    private val message: String = "",
) : TargetScalarReader {
    val int32Addresses = mutableListOf<Long>()
    val int64Addresses = mutableListOf<Long>()

    override fun readInt32(pid: Int, address: Long): TargetScalarReadResult {
        int32Addresses += address
        return TargetScalarReadResult(
            isSuccess = isSuccess,
            processStartTimeTicks = "start-$pid",
            valueBits = valueBits.takeIf { isSuccess },
            message = message,
        )
    }

    override fun readInt64(pid: Int, address: Long): TargetScalarReadResult {
        int64Addresses += address
        return TargetScalarReadResult(
            isSuccess = isSuccess,
            processStartTimeTicks = "start-$pid",
            valueBits = valueBits.takeIf { isSuccess },
            message = message,
        )
    }
}
