package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootAntiFlashWireTest {
    @Test
    fun stateRoundTripsThroughAtomicJsonPayload() {
        val expected = RootAntiFlashState(
            status = RootAntiFlashStatus.RUNNING,
            requestedEnabled = true,
            workerRunning = true,
            applied = true,
            iterationCount = 17L,
            successfulWriteCount = 289L,
            targetPid = 1234,
            targetStartTimeTicks = "56789",
            profileId = "miniworld-1.58.2-antiflash-v1",
            message = "17 writes verified",
        )

        assertEquals(expected, decodeRootAntiFlashStateJson(expected.toWireJson()))
    }

    @Test
    fun nullableFieldsRemainAbsent() {
        val decoded = decodeRootAntiFlashStateJson(RootAntiFlashState().toWireJson())

        assertNull(decoded.lastFailureIndex)
        assertNull(decoded.targetPid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedTargetIdentityIsRejected() {
        val payload = RootAntiFlashState(targetStartTimeTicks = "12x").toWireJson()

        decodeRootAntiFlashStateJson(payload)
    }
}
