package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSearchIdBridgeTest {
    @Test
    fun validTypedResultsPreserveMatchNotFoundAndFailureShapes() {
        val match = normalize(
            accepted = true,
            status = RootSearchIdStatus.MATCH,
            slot = 39,
        )
        assertEquals(RootSearchIdStatus.MATCH, match.status)
        assertEquals(39, match.slotIndex)
        assertNull(match.invalidReason)

        val notFound = normalize(
            accepted = true,
            status = RootSearchIdStatus.NOT_FOUND,
        )
        assertEquals(RootSearchIdStatus.NOT_FOUND, notFound.status)
        assertNull(notFound.slotIndex)
        assertNull(notFound.invalidReason)

        val failure = normalize(
            accepted = false,
            status = RootSearchIdStatus.INVALID,
            reason = RootSearchIdInvalidReason.READ_FAILED,
        )
        assertEquals(RootSearchIdStatus.INVALID, failure.status)
        assertEquals(RootSearchIdInvalidReason.READ_FAILED, failure.invalidReason)
    }

    @Test
    fun requestIdentityMustRoundTripAcrossBinder() {
        val result = normalizeSearchIdBridgeResult(
            commandAccepted = true,
            requestedId = 123L,
            returnedRequestedId = 124L,
            wireStatus = RootSearchIdStatus.NOT_FOUND,
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
                status = RootSearchIdStatus.MATCH,
                slot = 0,
            ),
            "inconsistent typed result",
        )
        assertInvalidResponse(
            normalize(
                accepted = true,
                status = RootSearchIdStatus.MATCH,
                slot = 40,
            ),
            "inconsistent typed result",
        )
        assertInvalidResponse(
            normalize(
                accepted = true,
                status = RootSearchIdStatus.NOT_FOUND,
                reason = RootSearchIdInvalidReason.READ_FAILED,
            ),
            "inconsistent typed result",
        )
        assertInvalidResponse(
            normalize(
                accepted = false,
                status = RootSearchIdStatus.INVALID,
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
        status: RootSearchIdStatus?,
        slot: Int? = null,
        reason: RootSearchIdInvalidReason? = null,
    ): RootSearchIdResult = normalizeSearchIdBridgeResult(
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

    private fun assertInvalidResponse(result: RootSearchIdResult, messagePart: String) {
        assertEquals(RootSearchIdStatus.INVALID, result.status)
        assertEquals(RootSearchIdInvalidReason.INVALID_RESPONSE, result.invalidReason)
        assertNull(result.slotIndex)
        assertTrue(result.message.contains(messagePart))
    }
}
