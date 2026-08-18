package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootPlayerPositionBridgeTest {
    @Test
    fun acceptedCommandRequiresTypedSuccessWithAllCoordinates() {
        assertEquals(
            RootInjectionApplyStatus.APPLIED,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = true,
                wireStatus = RootInjectionApplyStatus.APPLIED,
                hasAllEffectiveCoordinates = true,
                appliedAxisCount = 3,
            ),
        )
        assertEquals(
            RootInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = true,
                wireStatus = RootInjectionApplyStatus.APPLIED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 3,
            ),
        )
    }

    @Test
    fun binderBooleanAndTypedStatusMustAgree() {
        assertEquals(
            RootInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = RootInjectionApplyStatus.ALREADY_APPLIED,
                hasAllEffectiveCoordinates = true,
                appliedAxisCount = 0,
            ),
        )
        assertEquals(
            RootInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = true,
                wireStatus = RootInjectionApplyStatus.WRITE_FAILED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 1,
            ),
        )
    }

    @Test
    fun failedCommandKeepsTypedFailureButRejectsMalformedMetadata() {
        assertEquals(
            RootInjectionApplyStatus.WRITE_FAILED,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = RootInjectionApplyStatus.WRITE_FAILED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 2,
            ),
        )
        assertEquals(
            RootInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = null,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 0,
            ),
        )
        assertEquals(
            RootInjectionApplyStatus.INVALID_RESPONSE,
            normalizePlayerPositionBridgeStatus(
                commandAccepted = false,
                wireStatus = RootInjectionApplyStatus.WRITE_FAILED,
                hasAllEffectiveCoordinates = false,
                appliedAxisCount = 4,
            ),
        )
    }
}
