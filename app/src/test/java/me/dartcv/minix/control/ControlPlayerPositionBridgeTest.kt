package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlPlayerPositionBridgeTest {
    @Test
    fun acceptedCommandRequiresTypedSuccessWithAllCoordinates() {
        assertEquals(
            ControlInjectionApplyStatus.APPLIED,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = true,
                wireStatus = ControlInjectionApplyStatus.APPLIED,
                hasAllEffectiveCoordinates = true,
                appliedAxisCount = 3,
            ),
        )
        assertEquals(
            ControlInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = true,
                wireStatus = ControlInjectionApplyStatus.APPLIED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 3,
            ),
        )
    }

    @Test
    fun binderBooleanAndTypedStatusMustAgree() {
        assertEquals(
            ControlInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = ControlInjectionApplyStatus.ALREADY_APPLIED,
                hasAllEffectiveCoordinates = true,
                appliedAxisCount = 0,
            ),
        )
        assertEquals(
            ControlInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = true,
                wireStatus = ControlInjectionApplyStatus.WRITE_FAILED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 1,
            ),
        )
    }

    @Test
    fun failedCommandKeepsTypedFailureButRejectsMalformedMetadata() {
        assertEquals(
            ControlInjectionApplyStatus.WRITE_FAILED,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = ControlInjectionApplyStatus.WRITE_FAILED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 2,
            ),
        )
        assertEquals(
            ControlInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = null,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 0,
            ),
        )
        assertEquals(
            ControlInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = ControlInjectionApplyStatus.WRITE_FAILED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 4,
            ),
        )
    }
}
