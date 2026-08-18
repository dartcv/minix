package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSearchIdBridgeTest {
    @Test
    fun validTypedResultsPreserveMatchNotFoundAndFailureShapes() {
        val match = normalize(
            accepted = true,
            status = ControlSearchIdStatus.MATCH,
            slot = 39,
        )
        assertEquals(ControlSearchIdStatus.MATCH, match.status)
        assertEquals(39, match.slotIndex)
        assertNull(match.invalidReason)

        val notFound = normalize(
            accepted = true,
            status = ControlSearchIdStatus.NOT_FOUND,
        )
        assertEquals(ControlSearchIdStatus.NOT_FOUND, notFound.status)
        assertNull(notFound.slotIndex)
        assertNull(notFound.invalidReason)

        val failure = normalize(
            accepted = false,
            status = ControlSearchIdStatus.INVALID,
            reason = ControlSearchIdInvalidReason.READ_FAILED,
        )
        assertEquals(ControlSearchIdStatus.INVALID, failure.status)
        assertEquals(ControlSearchIdInvalidReason.READ_FAILED, failure.invalidReason)
    }

    @Test
    fun requestIdentityMustRoundTripAcrossBinder() {
        val result = normalizeSearchIdBridgeResult(
            commandAccepted = true,
            requestedId = 123L,
            returnedRequestedId = 124L,
            wireStatus = ControlSearchIdStatus.NOT_FOUND,
            slotIndex = null,
            invalidReason = null,
            processStartTimeTicks = "start-1",
            message = "",
            slotCount = 40,
        )

        assertInvalidResponse(result, "mismatched request ID")
    }

    @Test
    fun binderBooleanAndTypedPayloadMustHaveConsistentShape() {
        assertInvalidResponse(
            normalize(
                accepted = false,
                status = ControlSearchIdStatus.MATCH,
                slot = 0,
            ),
            "inconsistent typed result",
        )
        assertInvalidResponse(
            normalize(
                accepted = true,
                status = ControlSearchIdStatus.MATCH,
                slot = 40,
            ),
            "inconsistent typed result",
        )
        assertInvalidResponse(
            normalize(
                accepted = true,
                status = ControlSearchIdStatus.NOT_FOUND,
                reason = ControlSearchIdInvalidReason.READ_FAILED,
            ),
            "inconsistent typed result",
        )
        assertInvalidResponse(
            normalize(
                accepted = false,
                status = ControlSearchIdStatus.INVALID,
            ),
            "inconsistent typed result",
        )
    }

    @Test
    fun unknownWireStatusBecomesTypedInvalidResponse() {
        val result = normalize(
            accepted = false,
            status = null,
        )

        assertInvalidResponse(result, "unknown status")
    }

    private fun normalize(
        accepted: Boolean,
        status: ControlSearchIdStatus?,
        slot: Int? = null,
        reason: ControlSearchIdInvalidReason? = null,
    ): ControlSearchIdResult = normalizeSearchIdBridgeResult(
        commandAccepted = accepted,
        requestedId = 123L,
        returnedRequestedId = 123L,
        wireStatus = status,
        slotIndex = slot,
        invalidReason = reason,
        processStartTimeTicks = "start-1",
        message = "fixture",
        slotCount = 40,
    )

    private fun assertInvalidResponse(result: ControlSearchIdResult, messagePart: String) {
        assertEquals(ControlSearchIdStatus.INVALID, result.status)
        assertEquals(ControlSearchIdInvalidReason.INVALID_RESPONSE, result.invalidReason)
        assertNull(result.slotIndex)
        assertTrue(result.message.contains(messagePart))
    }
}
