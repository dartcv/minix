package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlReadOnlyFieldBridgeTest {
    @Test
    fun refreshGateRequiresOpenTargetReadyProfileAndEnabledFeature() {
        assertTrue(
            shouldRefreshReadOnlyFields(
                targetIsOpen = true,
                profileStatus = ControlReadOnlyFieldProfileStatus.READY,
                readableDataEnabled = true,
            ),
        )
        assertFalse(
            shouldRefreshReadOnlyFields(
                targetIsOpen = false,
                profileStatus = ControlReadOnlyFieldProfileStatus.READY,
                readableDataEnabled = true,
            ),
        )
        assertFalse(
            shouldRefreshReadOnlyFields(
                targetIsOpen = true,
                profileStatus = ControlReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                readableDataEnabled = true,
            ),
        )
        assertFalse(
            shouldRefreshReadOnlyFields(
                targetIsOpen = true,
                profileStatus = ControlReadOnlyFieldProfileStatus.READY,
                readableDataEnabled = false,
            ),
        )
    }

    @Test
    fun typedBatchMappingPreservesZeroAnd64BitValues() {
        val state = ControlReadOnlyFieldBatch(
            profileState = ControlReadOnlyFieldProfileState(
                status = ControlReadOnlyFieldProfileStatus.READY,
                profileId = "fixture-v1",
                targetVersion = "1.0",
                fieldIds = ControlReadOnlyFieldId.entries.toSet(),
            ),
            lifeState = okInt32(ControlReadOnlyFieldId.LIFE_STATE, 0),
            killCount = okInt32(ControlReadOnlyFieldId.KILL_COUNT, 17),
            dataLongSelector1 = ControlReadOnlyFieldReadResult(
                status = ControlReadOnlyFieldReadStatus.OK,
                value = ControlInt64FieldValue(
                    ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                    Long.MIN_VALUE,
                ),
            ),
        ).toPublicState()

        assertTrue(state.isProfileReady)
        assertTrue(state.lifeState.isAvailable)
        assertEquals(0, state.lifeState.value)
        assertEquals(17, state.killCount.value)
        assertEquals(Long.MIN_VALUE, state.dataLongSelector1.value)
    }

    @Test
    fun wrongTypedPayloadIsReportedAsReadFailure() {
        val malformed = ControlReadOnlyFieldReadResult(
            status = ControlReadOnlyFieldReadStatus.OK,
            value = ControlInt64FieldValue(ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1, 1L),
        )

        val state = malformed.toInt32State()

        assertEquals(ControlReadOnlyFieldReadStatus.READ_FAILED, state.status)
        assertFalse(state.isAvailable)
    }

    @Test
    fun refreshSuccessOnlyRequiresFieldsDefinedByTheActiveProfile() {
        val batch = ControlReadOnlyFieldBatch(
            profileState = ControlReadOnlyFieldProfileState(
                status = ControlReadOnlyFieldProfileStatus.READY,
                profileId = "fixture-v2",
                targetVersion = "1.58.2",
                fieldIds = setOf(
                    ControlReadOnlyFieldId.KILL_COUNT,
                    ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                ),
            ),
            lifeState = ControlReadOnlyFieldReadResult(
                status = ControlReadOnlyFieldReadStatus.FIELD_NOT_AVAILABLE,
            ),
            killCount = okInt32(ControlReadOnlyFieldId.KILL_COUNT, 0),
            dataLongSelector1 = ControlReadOnlyFieldReadResult(
                status = ControlReadOnlyFieldReadStatus.OK,
                value = ControlInt64FieldValue(ControlReadOnlyFieldId.DATA_LONG_SELECTOR_1, 0L),
            ),
        )

        assertTrue(batch.hasAllDefinedFieldsAvailable())
        assertFalse(
            batch.copy(
                killCount = ControlReadOnlyFieldReadResult(
                    status = ControlReadOnlyFieldReadStatus.READ_FAILED,
                ),
            ).hasAllDefinedFieldsAvailable(),
        )
    }

    private fun okInt32(
        id: ControlReadOnlyFieldId,
        value: Int,
    ): ControlReadOnlyFieldReadResult = ControlReadOnlyFieldReadResult(
        status = ControlReadOnlyFieldReadStatus.OK,
        value = ControlInt32FieldValue(id, value),
    )
}
