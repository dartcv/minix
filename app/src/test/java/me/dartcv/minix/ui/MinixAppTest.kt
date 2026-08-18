package me.dartcv.minix.ui

import me.dartcv.minix.root.RootConnectionStatus
import me.dartcv.minix.root.RootFeature
import me.dartcv.minix.root.RootInjectionApplyStatus
import me.dartcv.minix.root.RootInjectionProfileStatus
import me.dartcv.minix.root.RootInjectionState
import me.dartcv.minix.root.RootNativeProbeState
import me.dartcv.minix.root.RootNativeProbeStatus
import me.dartcv.minix.root.RootReadOnlyFieldProfileStatus
import me.dartcv.minix.root.RootReadOnlyFieldsState
import me.dartcv.minix.root.RootRuntimeState
import me.dartcv.minix.root.RootSearchIdInvalidReason
import me.dartcv.minix.root.RootSearchIdResult
import me.dartcv.minix.root.RootSearchIdStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinixAppTest {
    @Test
    fun verifiedControlsAppearBeforeEvidenceGatedControls() {
        assertEquals(
            listOf(
                RootFeature.FLIGHT,
                RootFeature.FAKE_FLIGHT,
                RootFeature.ANTI_FLASH,
                RootFeature.READABLE_DATA,
                RootFeature.AIM,
                RootFeature.DRAW,
                RootFeature.HITBOX,
            ),
            controlFeatureDisplayOrder,
        )
        assertFalse(RootFeature.PLAYER_TELEPORT in controlFeatureDisplayOrder)
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
        val state = RootRuntimeState(
            status = RootConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
        )

        assertEquals("瞄准 · 未实现", controlFeatureTitle(RootFeature.AIM))
        assertTrue(controlFeatureSubtitle(state, RootFeature.AIM).contains("最终瞄准 writer"))
        assertTrue(controlFeatureSubtitle(state, RootFeature.DRAW).contains("完整投影"))
        assertTrue(controlFeatureSubtitle(state, RootFeature.HITBOX).contains("旧 worker 100 槽已闭合"))
        assertTrue(controlFeatureSubtitle(state, RootFeature.HITBOX).contains("全实体集合映射/生命周期未唯一化"))
        assertEquals("模拟飞行", controlFeatureTitle(RootFeature.FAKE_FLIGHT))
        assertTrue(controlFeatureSubtitle(state, RootFeature.FAKE_FLIGHT).contains("档案未就绪"))
    }

    @Test
    fun implementedFeaturesReportTheirOwnProfileReadiness() {
        val state = RootRuntimeState(
            status = RootConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
            readOnlyFields = RootReadOnlyFieldsState(
                profileStatus = RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                profileSummary = "字段证据未闭合",
            ),
            injection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.FINGERPRINT_MISMATCH,
                profileSummary = "模块版本不匹配",
            ),
        )

        assertEquals(
            "字段档案未就绪：字段证据未闭合",
            controlFeatureSubtitle(state, RootFeature.READABLE_DATA),
        )
        assertEquals(
            "飞行档案未就绪：模块版本不匹配",
            controlFeatureSubtitle(state, RootFeature.FLIGHT),
        )
        assertEquals(
            "模拟飞行档案未就绪：模块版本不匹配",
            controlFeatureSubtitle(state, RootFeature.FAKE_FLIGHT),
        )
    }

    @Test
    fun runtimePreconditionFailureKeepsRetryAvailable() {
        val state = RootRuntimeState(
            status = RootConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
            supportedFeatures = setOf(
                RootFeature.FLIGHT,
                RootFeature.FAKE_FLIGHT,
                RootFeature.PLAYER_TELEPORT,
            ),
            injection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.READY,
                lastApplyStatus = RootInjectionApplyStatus.PRECONDITION_READ_FAILED,
                lastFeature = RootFeature.FLIGHT,
                message = "pointer step 0 is null",
            ),
        )

        assertTrue(controlFeatureControlEnabled(state, RootFeature.FLIGHT))
        assertTrue(controlFeatureSubtitle(state, RootFeature.FLIGHT).contains("pointer step 0 is null"))
        assertTrue(controlFeatureControlEnabled(state, RootFeature.FAKE_FLIGHT))
        assertTrue(controlFeatureSubtitle(state, RootFeature.FAKE_FLIGHT).contains("执行段补丁档案已载入"))
        assertTrue(controlFeatureControlEnabled(state, RootFeature.PLAYER_TELEPORT))
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
        val ready = RootRuntimeState(
            status = RootConnectionStatus.READY,
            uid = 10001,
            targetPid = 42,
            targetUid = 10001,
            targetStartTimeTicks = "start-42",
            nativeProbe = RootNativeProbeState(
                status = RootNativeProbeStatus.OK,
                processStartTimeTicks = "start-42",
                memoryReadableModuleCount = 1,
                memoryReadBytes = 64,
            ),
        )

        assertTrue(searchIdControlEnabled(ready, 123L))
        assertFalse(searchIdControlEnabled(ready, null))
        assertFalse(searchIdControlEnabled(ready.copy(targetUid = 10002), 123L))
        assertFalse(searchIdControlEnabled(ready.copy(nativeProbe = RootNativeProbeState()), 123L))
        assertFalse(searchIdControlEnabled(ready.copy(status = RootConnectionStatus.ERROR), 123L))
    }

    @Test
    fun searchIdResultLabelsExposeMatchNotFoundAndTypedFailure() {
        assertEquals(
            "已命中 · ID 77 · 槽位 7/40",
            searchIdResultLabel(
                RootSearchIdResult(
                    status = RootSearchIdStatus.MATCH,
                    requestedId = 77L,
                    slotIndex = 6,
                ),
            ),
        )
        assertEquals(
            "未命中 · ID 88 · 已扫描 40 槽",
            searchIdResultLabel(
                RootSearchIdResult(
                    status = RootSearchIdStatus.NOT_FOUND,
                    requestedId = 88L,
                ),
            ),
        )
        assertEquals(
            "扫描失败 · TARGET_CHANGED",
            searchIdResultLabel(
                RootSearchIdResult(
                    status = RootSearchIdStatus.INVALID,
                    requestedId = 99L,
                    invalidReason = RootSearchIdInvalidReason.TARGET_CHANGED,
                ),
            ),
        )
    }
}
