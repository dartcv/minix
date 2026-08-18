package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlAntiFlashWireTest {
    @Test
    fun stateRoundTripsThroughAtomicJsonPayload() {
        val expected = ControlAntiFlashState(
            status = ControlAntiFlashStatus.RUNNING,
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

        assertEquals(expected, decodeControlAntiFlashStateJson(expected.toWireJson()))
    }

    @Test
    fun nullableFieldsRemainAbsent() {
        val decoded = decodeControlAntiFlashStateJson(ControlAntiFlashState().toWireJson())

        assertNull(decoded.lastFailureIndex)
        assertNull(decoded.targetPid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedTargetIdentityIsRejected() {
        val payload = ControlAntiFlashState(targetStartTimeTicks = "12x").toWireJson()

        decodeControlAntiFlashStateJson(payload)
    }
}
