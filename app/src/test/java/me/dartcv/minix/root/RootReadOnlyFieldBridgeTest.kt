package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootReadOnlyFieldBridgeTest {
    @Test
    fun refreshGateRequiresOpenTargetReadyProfileAndEnabledFeature() {
        assertTrue(
            shouldRefreshReadOnlyFields(
                targetIsOpen = true,
                profileStatus = RootReadOnlyFieldProfileStatus.READY,
                readableDataEnabled = true,
            ),
        )
        assertFalse(
            shouldRefreshReadOnlyFields(
                targetIsOpen = false,
                profileStatus = RootReadOnlyFieldProfileStatus.READY,
                readableDataEnabled = true,
            ),
        )
        assertFalse(
            shouldRefreshReadOnlyFields(
                targetIsOpen = true,
                profileStatus = RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                readableDataEnabled = true,
            ),
        )
        assertFalse(
            shouldRefreshReadOnlyFields(
                targetIsOpen = true,
                profileStatus = RootReadOnlyFieldProfileStatus.READY,
                readableDataEnabled = false,
            ),
        )
    }

    @Test
    fun typedBatchMappingPreservesZeroAnd64BitValues() {
        val state = RootReadOnlyFieldBatch(
            profileState = RootReadOnlyFieldProfileState(
                status = RootReadOnlyFieldProfileStatus.READY,
                profileId = "fixture-v1",
                targetVersion = "1.0",
                fieldIds = RootReadOnlyFieldId.entries.toSet(),
            ),
            lifeState = okInt32(RootReadOnlyFieldId.LIFE_STATE, 0),
            killCount = okInt32(RootReadOnlyFieldId.KILL_COUNT, 17),
            dataLongSelector1 = RootReadOnlyFieldReadResult(
                status = RootReadOnlyFieldReadStatus.OK,
                value = RootInt64FieldValue(
                    RootReadOnlyFieldId.DATA_LONG_SELECTOR_1,
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
        val malformed = RootReadOnlyFieldReadResult(
            status = RootReadOnlyFieldReadStatus.OK,
            value = RootInt64FieldValue(RootReadOnlyFieldId.DATA_LONG_SELECTOR_1, 1L),
        )

        val state = malformed.toInt32State()

        assertEquals(RootReadOnlyFieldReadStatus.READ_FAILED, state.status)
        assertFalse(state.isAvailable)
    }

    @Test
    fun refreshSuccessOnlyRequiresFieldsDefinedByTheActiveProfile() {
        val batch = RootReadOnlyFieldBatch(
            profileState = RootReadOnlyFieldProfileState(
                status = RootReadOnlyFieldProfileStatus.READY,
                profileId = "fixture-v2",
                targetVersion = "1.58.2",
                fieldIds = setOf(
                    RootReadOnlyFieldId.KILL_COUNT,
                    RootReadOnlyFieldId.DATA_LONG_SELECTOR_1,
                ),
            ),
            lifeState = RootReadOnlyFieldReadResult(
                status = RootReadOnlyFieldReadStatus.FIELD_NOT_AVAILABLE,
            ),
            killCount = okInt32(RootReadOnlyFieldId.KILL_COUNT, 0),
            dataLongSelector1 = RootReadOnlyFieldReadResult(
                status = RootReadOnlyFieldReadStatus.OK,
                value = RootInt64FieldValue(RootReadOnlyFieldId.DATA_LONG_SELECTOR_1, 0L),
            ),
        )

        assertTrue(batch.hasAllDefinedFieldsAvailable())
        assertFalse(
            batch.copy(
                killCount = RootReadOnlyFieldReadResult(
                    status = RootReadOnlyFieldReadStatus.READ_FAILED,
                ),
            ).hasAllDefinedFieldsAvailable(),
        )
    }

    private fun okInt32(
        id: RootReadOnlyFieldId,
        value: Int,
    ): RootReadOnlyFieldReadResult = RootReadOnlyFieldReadResult(
        status = RootReadOnlyFieldReadStatus.OK,
        value = RootInt32FieldValue(id, value),
    )
}
