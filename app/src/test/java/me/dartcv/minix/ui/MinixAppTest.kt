package me.dartcv.minix.ui

import me.dartcv.minix.control.ControlConnectionStatus
import me.dartcv.minix.control.ControlFeature
import me.dartcv.minix.control.ControlInjectionApplyStatus
import me.dartcv.minix.control.ControlInjectionProfileStatus
import me.dartcv.minix.control.ControlInjectionState
import me.dartcv.minix.control.ControlNativeProbeState
import me.dartcv.minix.control.ControlNativeProbeStatus
import me.dartcv.minix.control.ControlReadOnlyFieldProfileStatus
import me.dartcv.minix.control.ControlReadOnlyFieldsState
import me.dartcv.minix.control.ControlRuntimeState
import me.dartcv.minix.control.ControlSearchIdInvalidReason
import me.dartcv.minix.control.ControlSearchIdResult
import me.dartcv.minix.control.ControlSearchIdStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinixAppTest {
    @Test
    fun verifiedControlsAppearBeforeEvidenceGatedControls() {
        assertEquals(
            listOf(
                ControlFeature.FLIGHT,
                ControlFeature.FAKE_FLIGHT,
                ControlFeature.ANTI_FLASH,
                ControlFeature.READABLE_DATA,
                ControlFeature.AIM,
                ControlFeature.DRAW,
                ControlFeature.HITBOX,
            ),
            controlFeatureDisplayOrder,
        )
        assertFalse(ControlFeature.PLAYER_TELEPORT in controlFeatureDisplayOrder)
    }

    @Test
    fun antiFlashLaunchButtonUsesDistinctIdleArmedAndRunningLabels() {
        assertEquals(
            "预置防闪并启动游戏",
            antiFlashLaunchButtonLabel(armed = false, workerRunning = false),
        )
        assertEquals(
            "防闪已预置，启动游戏",
            antiFlashLaunchButtonLabel(armed = true, workerRunning = false),
        )
        assertEquals(
            "防闪运行中，返回游戏",
            antiFlashLaunchButtonLabel(armed = true, workerRunning = true),
        )
    }

    @Test
    fun unresolvedFeaturesExplainWhyTheirSwitchesAreDisabled() {
        val state = ControlRuntimeState(
            status = ControlConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
        )

        assertEquals("瞄准 · 未实现", controlFeatureTitle(ControlFeature.AIM))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.AIM).contains("最终瞄准 writer"))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.DRAW).contains("完整投影"))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.HITBOX).contains("旧 worker 100 槽已闭合"))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.HITBOX).contains("全实体集合映射/生命周期未唯一化"))
        assertEquals("模拟飞行", controlFeatureTitle(ControlFeature.FAKE_FLIGHT))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.FAKE_FLIGHT).contains("档案未就绪"))
    }

    @Test
    fun implementedFeaturesReportTheirOwnProfileReadiness() {
        val state = ControlRuntimeState(
            status = ControlConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
            readOnlyFields = ControlReadOnlyFieldsState(
                profileStatus = ControlReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                profileSummary = "字段证据未闭合",
            ),
            injection = ControlInjectionState(
                profileStatus = ControlInjectionProfileStatus.FINGERPRINT_MISMATCH,
                profileSummary = "模块版本不匹配",
            ),
        )

        assertEquals(
            "字段档案未就绪：字段证据未闭合",
            controlFeatureSubtitle(state, ControlFeature.READABLE_DATA),
        )
        assertEquals(
            "飞行档案未就绪：模块版本不匹配",
            controlFeatureSubtitle(state, ControlFeature.FLIGHT),
        )
        assertEquals(
            "模拟飞行档案未就绪：模块版本不匹配",
            controlFeatureSubtitle(state, ControlFeature.FAKE_FLIGHT),
        )
    }

    @Test
    fun runtimePreconditionFailureKeepsRetryAvailable() {
        val state = ControlRuntimeState(
            status = ControlConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
            supportedFeatures = setOf(
                ControlFeature.FLIGHT,
                ControlFeature.FAKE_FLIGHT,
                ControlFeature.PLAYER_TELEPORT,
            ),
            injection = ControlInjectionState(
                profileStatus = ControlInjectionProfileStatus.READY,
                lastApplyStatus = ControlInjectionApplyStatus.PRECONDITION_READ_FAILED,
                lastFeature = ControlFeature.FLIGHT,
                message = "pointer step 0 is null",
            ),
        )

        assertTrue(controlFeatureControlEnabled(state, ControlFeature.FLIGHT))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.FLIGHT).contains("pointer step 0 is null"))
        assertTrue(controlFeatureControlEnabled(state, ControlFeature.FAKE_FLIGHT))
        assertTrue(controlFeatureSubtitle(state, ControlFeature.FAKE_FLIGHT).contains("执行段补丁档案已载入"))
        assertTrue(controlFeatureControlEnabled(state, ControlFeature.PLAYER_TELEPORT))
    }

    @Test
    fun searchIdInputAcceptsExactlyTheInt32Domain() {
        assertEquals(Int.MAX_VALUE.toLong(), parseSearchIdInput("2147483647"))
        assertEquals(Int.MIN_VALUE.toLong(), parseSearchIdInput("-2147483648"))
        assertEquals(0L, parseSearchIdInput("0"))
        assertEquals(null, parseSearchIdInput("2147483648"))
        assertEquals(null, parseSearchIdInput("-2147483649"))
        assertEquals(null, parseSearchIdInput("-"))
        assertEquals(null, parseSearchIdInput("12x"))
    }

    @Test
    fun searchIdControlRequiresReadyPinnedMemorySessionAndValidInput() {
        val ready = ControlRuntimeState(
            status = ControlConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
            nativeProbe = ControlNativeProbeState(
                status = ControlNativeProbeStatus.OK,
                processStartTimeTicks = "start-42",
                memoryReadableModuleCount = 1,
                memoryReadBytes = 64,
            ),
        )

        assertTrue(searchIdControlEnabled(ready, 123L))
        assertFalse(searchIdControlEnabled(ready, null))
        assertFalse(searchIdControlEnabled(ready.copy(targetUid = 10002), 123L))
        assertFalse(searchIdControlEnabled(ready.copy(nativeProbe = ControlNativeProbeState()), 123L))
        assertFalse(searchIdControlEnabled(ready.copy(status = ControlConnectionStatus.ERROR), 123L))
    }

    @Test
    fun searchIdResultLabelsExposeMatchNotFoundAndTypedFailure() {
        assertEquals(
            "已命中 · ID 77 · 槽位 7/40",
            searchIdResultLabel(
                ControlSearchIdResult(
                    status = ControlSearchIdStatus.MATCH,
                    requestedId = 77L,
                    slotIndex = 6,
                ),
            ),
        )
        assertEquals(
            "未命中 · ID 88 · 已扫描 40 槽",
            searchIdResultLabel(
                ControlSearchIdResult(
                    status = ControlSearchIdStatus.NOT_FOUND,
                    requestedId = 88L,
                ),
            ),
        )
        assertEquals(
            "扫描失败 · TARGET_CHANGED",
            searchIdResultLabel(
                ControlSearchIdResult(
                    status = ControlSearchIdStatus.INVALID,
                    requestedId = 99L,
                    invalidReason = ControlSearchIdInvalidReason.TARGET_CHANGED,
                ),
            ),
        )
    }
}
