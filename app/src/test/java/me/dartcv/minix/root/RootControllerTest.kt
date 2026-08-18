package me.dartcv.minix.root

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootControllerTest {
    @Test
    fun constructingControllerDoesNotBindUntilRequested() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)

        assertEquals(0, bridge.connectCount)
        assertEquals(RootConnectionStatus.IDLE, controller.state.value.status)

        controller.requestAndConnect()

        assertEquals(1, bridge.connectCount)
        assertEquals(RootConnectionStatus.READY, controller.state.value.status)
        assertEquals(TEST_UID, controller.state.value.uid)
        assertEquals(4242, controller.state.value.servicePid)
        assertEquals("arm64-v8a", controller.state.value.abi)
        assertTrue(controller.state.value.message.contains("\u540c UID \u670d\u52a1\u5df2\u9a8c\u8bc1"))
        assertTrue(
            controller.state.value.message.contains(
                "\u76ee\u6807\u8bc1\u4e66/shared UID \u5c1a\u672a\u9a8c\u8bc1",
            ),
        )
    }

    @Test
    fun mismatchedServiceUidFailsClosed() = runTest {
        val bridge = FakeRootFeatureBridgeClient(serviceUid = TEST_UID + 1)
        val controller = RootController(bridge, TEST_UID)

        controller.requestAndConnect()

        assertEquals(RootConnectionStatus.ERROR, controller.state.value.status)
        assertEquals(1, bridge.connectCount)
        assertNull(controller.state.value.uid)
    }

    @Test
    fun connectionScansCatalogAndOpensFirstRunningChannel() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids[RootTargetChannel.OPPO.packageName] = 2468
            runningTargetPids[RootTargetChannel.KUAISHOU.packageName] = 9753
        }
        val controller = RootController(bridge, TEST_UID)

        controller.requestAndConnect()

        assertEquals(
            RootTargetCatalog.entries.take(3).map(RootTargetChannel::packageName),
            bridge.findTargetCalls,
        )
        assertEquals(RootTargetChannel.OPPO.packageName, controller.state.value.targetPackage)
        assertEquals(2468, controller.state.value.targetPid)
        assertEquals(
            "已自动选择oppo服；目标 UID $TEST_UID 已与服务 UID 核对",
            controller.state.value.message,
        )
        assertEquals(TEST_UID, controller.state.value.targetUid)
    }

    @Test
    fun targetAndFeatureCallsUseTypedBridgeState() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")

        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.READABLE_DATA, true)

        val state = controller.state.value
        assertEquals(777, state.targetPid)
        assertEquals("start-777", state.targetStartTimeTicks)
        assertTrue(state.features.getValue(RootFeature.READABLE_DATA))
        assertFalse(state.features.getValue(RootFeature.DRAW))
        assertTrue(state.nativeProbe.isMemoryReady)
        assertEquals(0, state.readOnlyFields.lifeState.value)
        assertEquals(17, state.readOnlyFields.killCount.value)
        assertEquals(0x0102030405060708L, state.readOnlyFields.dataLongSelector1.value)
        assertEquals(listOf(RootFeature.READABLE_DATA to true), bridge.featureCalls)

        controller.disconnect()
        assertEquals(RootConnectionStatus.IDLE, controller.state.value.status)
        assertTrue(bridge.disconnectCount > 0)
    }

    @Test
    fun readableDataRemainsEnabledWithFieldValuesAfterTargetRefresh() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setFeatureEnabled(RootFeature.READABLE_DATA, true)
        controller.refreshTargetState()

        val state = controller.state.value
        assertTrue(state.features.getValue(RootFeature.READABLE_DATA))
        assertEquals(RootReadOnlyFieldReadStatus.OK, state.readOnlyFields.killCount.status)
        assertEquals(17, state.readOnlyFields.killCount.value)
        assertEquals(
            RootReadOnlyFieldReadStatus.OK,
            state.readOnlyFields.dataLongSelector1.status,
        )
        assertEquals(0x0102030405060708L, state.readOnlyFields.dataLongSelector1.value)
    }

    @Test
    fun readableDataEnableFailureReportsFieldProfileInsteadOfStaleInjectionMessage() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            rejectReadableDataEnable = true
            rejectedReadableDataFields = RootReadOnlyFieldsState(
                profileStatus = RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
                profileSummary = "目标模块未就绪",
                profileId = "fixture-v1",
                targetVersion = "1.0",
            )
            openedInjection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.READY,
                lastApplyStatus = RootInjectionApplyStatus.PRECONDITION_READ_FAILED,
                lastFeature = RootFeature.FLIGHT,
                message = "stale injection failure",
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setFeatureEnabled(RootFeature.READABLE_DATA, true)

        val state = controller.state.value
        assertFalse(state.features.getValue(RootFeature.READABLE_DATA))
        assertEquals("目标模块未就绪", state.message)
        assertFalse(state.message.contains("stale injection failure"))
    }

    @Test
    fun refreshReportsWhyPreviouslyEnabledReadableDataWasDisabled() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.READABLE_DATA, true)
        bridge.dropReadableData(
            RootReadOnlyFieldsState(
                profileStatus = RootReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH,
                profileSummary = "模块版本不匹配",
                profileId = "fixture-v1",
                targetVersion = "1.0",
            ),
        )

        controller.refreshTargetState()

        val state = controller.state.value
        assertFalse(state.features.getValue(RootFeature.READABLE_DATA))
        assertTrue(state.message.contains("数据读取已停用"))
        assertTrue(state.message.contains("模块版本不匹配"))
    }

    @Test
    fun refreshRediscoverProfilesAfterTargetOpenedBeforeNativeModulesLoaded() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportReadableData = false
            openedReadOnlyFields = RootReadOnlyFieldsState(
                profileStatus = RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
                profileSummary = "waiting for GameApp",
            )
            openedInjection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.MODULE_NOT_FOUND,
                profileSummary = "waiting for GameApp",
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        assertTrue(controller.state.value.supportedFeatures.isEmpty())

        bridge.supportFlight = true
        bridge.openedInjection = RootInjectionState(
            profileStatus = RootInjectionProfileStatus.READY,
            profileSummary = "flight ready",
        )
        controller.refreshTargetState()

        assertEquals(listOf("com.example.target", "com.example.target"), bridge.openTargetCalls)
        assertTrue(RootFeature.FLIGHT in controller.state.value.supportedFeatures)
    }

    @Test
    fun refreshRediscoverInjectionWhileReadableDataIsAlreadySupported() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportReadableData = true
            openedReadOnlyFields = readyReadOnlyFields(enabled = false)
            openedInjection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.MODULE_NOT_FOUND,
                profileSummary = "waiting for GameApp executable mapping",
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        assertTrue(RootFeature.READABLE_DATA in controller.state.value.supportedFeatures)

        bridge.supportFakeFlight = true
        bridge.openedInjection = RootInjectionState(
            profileStatus = RootInjectionProfileStatus.READY,
            profileSummary = "executable patch ready",
        )
        controller.refreshTargetState()

        assertEquals(listOf("com.example.target", "com.example.target"), bridge.openTargetCalls)
        assertTrue(RootFeature.FAKE_FLIGHT in controller.state.value.supportedFeatures)
    }

    @Test
    fun unresolvedFeatureReportsItsConcreteDisabledReason() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setFeatureEnabled(RootFeature.AIM, true)

        assertTrue(controller.state.value.message.contains("瞄准未就绪"))
        assertTrue(controller.state.value.message.contains("最终 GameApp writer/宽度/值仍缺"))
        assertTrue(bridge.featureCalls.isEmpty())
    }

    @Test
    fun repeatedFeatureCommandRetriesAfterRuntimePreconditionFailure() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportFlight = true
            openedInjection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.READY,
                lastApplyStatus = RootInjectionApplyStatus.PRECONDITION_READ_FAILED,
                lastFeature = RootFeature.FLIGHT,
                message = "pointer step 0 is null",
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setFeatureEnabled(RootFeature.FLIGHT, true)

        assertEquals(listOf(RootFeature.FLIGHT to true), bridge.featureCalls)
        assertTrue(controller.state.value.features.getValue(RootFeature.FLIGHT))
    }

    @Test
    fun repeatedPositionCommandRetriesAfterRuntimePreconditionFailure() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportPlayerPosition = true
            openedInjection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.READY,
                lastApplyStatus = RootInjectionApplyStatus.PRECONDITION_READ_FAILED,
                lastFeature = RootFeature.PLAYER_TELEPORT,
                message = "pointer step 0 is null",
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setPlayerPosition(RootPlayerPositionRequest(1, 2, 3))

        assertEquals(listOf(RootPlayerPositionRequest(1, 2, 3)), bridge.positionCalls)
        assertEquals(RootInjectionApplyStatus.APPLIED, controller.state.value.playerPosition?.status)
    }

    @Test
    fun playerPositionUsesDedicatedTypedActionAndPublishesResult() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportPlayerPosition = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        val request = RootPlayerPositionRequest(x = -12, y = 34, z = 56)

        controller.setPlayerPosition(request)

        assertEquals(listOf(request), bridge.positionCalls)
        assertEquals(request, controller.state.value.playerPosition?.request)
        assertEquals(3, controller.state.value.playerPosition?.appliedAxisCount)
        assertEquals(
            RootInjectionApplyStatus.APPLIED,
            controller.state.value.playerPosition?.status,
        )
        assertTrue(bridge.featureCalls.isEmpty())
    }

    @Test
    fun playerPositionResultIsClearedWhenTargetIdentityChangesDuringAction() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportPlayerPosition = true
            invalidateTargetOnPositionCall = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setPlayerPosition(RootPlayerPositionRequest(1, 2, 3))

        assertNull(controller.state.value.targetPid)
        assertNull(controller.state.value.targetStartTimeTicks)
        assertNull(controller.state.value.playerPosition)
    }

    @Test
    fun disconnectedPositionFailureClearsPreviousResult() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportPlayerPosition = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setPlayerPosition(RootPlayerPositionRequest(1, 2, 3))
        assertTrue(controller.state.value.playerPosition?.isSuccess == true)
        bridge.failPositionCallAndDisconnect = true

        controller.setPlayerPosition(RootPlayerPositionRequest(4, 5, 6))

        assertEquals(RootConnectionStatus.ERROR, controller.state.value.status)
        assertNull(controller.state.value.targetPid)
        assertNull(controller.state.value.playerPosition)
    }

    @Test
    fun featureFailureAppliesTheServiceSideInvalidatedTargetSnapshot() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        bridge.invalidateTargetOnFeatureCall = true

        controller.setFeatureEnabled(RootFeature.READABLE_DATA, true)

        val state = controller.state.value
        assertNull(state.targetPid)
        assertNull(state.targetStartTimeTicks)
        assertTrue(state.features.values.none { it })
        assertEquals("目标进程已变化或退出：com.example.target", state.message)
    }

    @Test
    fun refreshAppliesTargetExitWithoutRequestingRootAgain() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        bridge.invalidateTargetSnapshot()

        controller.refreshTargetState()

                assertNull(controller.state.value.targetPid)
        assertNull(controller.state.value.targetStartTimeTicks)
        assertEquals("目标进程已变化或退出：com.example.target", controller.state.value.message)
    }

    @Test
    fun controllerExposesIncompleteProfileReasonWithoutFieldValues() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportReadableData = false
            openedReadOnlyFields = RootReadOnlyFieldsState(
                profileStatus = RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE,
                profileSummary = "静态证据未闭合",
                profileId = "miniworld-1.58.2-arm64-draft-v1",
                targetVersion = "1.58.2",
                lifeState = RootInt32FieldState(
                    status = RootReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                ),
                killCount = RootInt32FieldState(
                    status = RootReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                ),
                dataLongSelector1 = RootInt64FieldState(
                    status = RootReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                ),
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")

        controller.openOrRefreshTarget()

        val fields = controller.state.value.readOnlyFields
        assertEquals(RootReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE, fields.profileStatus)
        assertEquals("静态证据未闭合", fields.profileSummary)
        assertNull(fields.lifeState.value)
        assertFalse(RootFeature.READABLE_DATA in controller.state.value.supportedFeatures)
    }

    @Test
    fun controllerPublishesAtomicAntiFlashStateFromFeatureToggle() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        val state = controller.state.value
        assertTrue(state.features.getValue(RootFeature.ANTI_FLASH))
        assertEquals(RootAntiFlashStatus.RUNNING, state.antiFlash.status)
        assertEquals(3L, state.antiFlash.iterationCount)
        assertEquals(51L, state.antiFlash.successfulWriteCount)
        assertEquals(listOf(RootFeature.ANTI_FLASH to true), bridge.featureCalls)
    }

    @Test
    fun antiFlashCanBePrearmedBeforeTheGameStarts() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")

        assertEquals("com.example.target", controller.armAntiFlashForLaunch())
        assertTrue(controller.state.value.antiFlashArmed)
        assertEquals(RootAntiFlashStatus.WAITING_FOR_TARGET, controller.state.value.antiFlash.status)
        assertTrue(bridge.featureCalls.isEmpty())
    }

    @Test
    fun prearmedAntiFlashAttachesWhenTheGameStartsLater() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()

        bridge.runningTargetPids["com.example.target"] = 888
        controller.maintainAntiFlash()

        assertEquals(888, controller.state.value.targetPid)
        assertTrue(controller.state.value.antiFlash.workerRunning)
        assertEquals(listOf(RootFeature.ANTI_FLASH to true), bridge.featureCalls)
    }

    @Test
    fun prearmedAntiFlashRetriesProfileAfterADeferredModuleLoad() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids["com.example.target"] = 889
            supportAntiFlash = false
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()

        controller.maintainAntiFlash()
        assertEquals(RootAntiFlashStatus.WAITING_FOR_TARGET, controller.state.value.antiFlash.status)
        assertTrue(bridge.featureCalls.isEmpty())

        bridge.supportAntiFlash = true
        controller.maintainAntiFlash()

        assertTrue(controller.state.value.antiFlash.workerRunning)
        assertEquals(listOf(RootFeature.ANTI_FLASH to true), bridge.featureCalls)
    }

    @Test
    fun prearmedAntiFlashReattachesAfterTargetRestart() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids["com.example.target"] = 890
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()
        controller.maintainAntiFlash()
        assertEquals(890, controller.state.value.targetPid)

        bridge.runningTargetPids["com.example.target"] = 891
        bridge.invalidateTargetSnapshot()
        controller.maintainAntiFlash()
        controller.maintainAntiFlash()

        assertEquals(891, controller.state.value.targetPid)
        assertTrue(controller.state.value.antiFlash.workerRunning)
        assertEquals(
            listOf(RootFeature.ANTI_FLASH to true, RootFeature.ANTI_FLASH to true),
            bridge.featureCalls,
        )
    }

    @Test
    fun disablingPrearmedAntiFlashWithoutTargetIsLocalOnly() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()

        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, false)

        assertFalse(controller.state.value.antiFlashArmed)
        assertEquals(RootAntiFlashStatus.STOPPED, controller.state.value.antiFlash.status)
        assertTrue(bridge.featureCalls.isEmpty())
    }

    @Test
    fun rollbackFailureRemainsVisibleWhenTargetHasExited() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids["com.example.target"] = 892
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()
        controller.maintainAntiFlash()
        bridge.invalidateTargetWithRollbackFailure()

        controller.maintainAntiFlash()

        assertTrue(controller.state.value.antiFlashArmed)
        assertTrue(controller.state.value.antiFlash.applied)
        assertEquals(RootAntiFlashStatus.ROLLBACK_FAILED, controller.state.value.antiFlash.status)

        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, false)

        assertEquals(RootFeature.ANTI_FLASH to false, bridge.featureCalls.last())
        assertFalse(controller.state.value.antiFlashArmed)
        assertEquals(RootAntiFlashStatus.STOPPED, controller.state.value.antiFlash.status)
        assertFalse(controller.state.value.antiFlash.applied)
    }

    @Test
    fun scanKeepsPrearmedAntiFlashIntent() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage(RootTargetChannel.OPPO.packageName)
        controller.armAntiFlashForLaunch()
        bridge.runningTargetPids[RootTargetChannel.OPPO.packageName] = 893

        controller.scanAndOpenFirstRunningTarget()

        assertTrue(controller.state.value.antiFlashArmed)
        assertEquals(893, controller.state.value.targetPid)
    }

    @Test
    fun switchingRunningTargetStopsAntiFlashBeforePublishingNewPackage() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids["com.example.target"] = 894
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()
        controller.maintainAntiFlash()

        controller.selectTarget(RootTargetChannel.OPPO)

        assertEquals(
            listOf(RootFeature.ANTI_FLASH to true, RootFeature.ANTI_FLASH to false),
            bridge.featureCalls,
        )
        assertEquals(RootTargetChannel.OPPO.packageName, controller.state.value.targetPackage)
        assertNull(controller.state.value.targetPid)
        assertFalse(controller.state.value.antiFlashArmed)
        assertFalse(controller.state.value.antiFlash.applied)
    }

    @Test
    fun switchingTargetKeepsOldPackageWhenAntiFlashRollbackFails() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids["com.example.target"] = 895
            supportAntiFlash = true
            failAntiFlashRollbackOnDisable = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.armAntiFlashForLaunch()
        controller.maintainAntiFlash()

        controller.selectTarget(RootTargetChannel.OPPO)

        assertEquals("com.example.target", controller.state.value.targetPackage)
        assertEquals(895, controller.state.value.targetPid)
        assertTrue(controller.state.value.antiFlash.applied)
        assertEquals(RootAntiFlashStatus.ROLLBACK_FAILED, controller.state.value.antiFlash.status)
        assertTrue(controller.state.value.message.startsWith("切换渠道已取消："))
        assertEquals(
            listOf(RootFeature.ANTI_FLASH to true, RootFeature.ANTI_FLASH to false),
            bridge.featureCalls,
        )
    }

    @Test
    fun scanningDifferentTargetStopsAntiFlashBeforePublishingDetectedPackage() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)
        bridge.runningTargetPids[RootTargetChannel.OPPO.packageName] = 896

        controller.scanAndOpenFirstRunningTarget()

        assertEquals(
            listOf(RootFeature.ANTI_FLASH to true, RootFeature.ANTI_FLASH to false),
            bridge.featureCalls,
        )
        assertEquals(RootTargetChannel.OPPO.packageName, controller.state.value.targetPackage)
        assertEquals(896, controller.state.value.targetPid)
        assertFalse(controller.state.value.antiFlashArmed)
        assertFalse(controller.state.value.antiFlash.applied)
    }

    @Test
    fun scanningDifferentTargetKeepsOldPackageWhenRollbackFails() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
            failAntiFlashRollbackOnDisable = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)
        bridge.runningTargetPids[RootTargetChannel.OPPO.packageName] = 897

        controller.scanAndOpenFirstRunningTarget()

        assertEquals("com.example.target", controller.state.value.targetPackage)
        assertEquals(777, controller.state.value.targetPid)
        assertEquals(RootAntiFlashStatus.ROLLBACK_FAILED, controller.state.value.antiFlash.status)
        assertTrue(controller.state.value.antiFlash.applied)
        assertTrue(controller.state.value.message.startsWith("扫描结果未应用："))
    }

    @Test
    fun scanningSameTargetKeepsRunningAntiFlashWorker() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            runningTargetPids[RootTargetChannel.OPPO.packageName] = 898
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        controller.scanAndOpenFirstRunningTarget()

        assertEquals(listOf(RootFeature.ANTI_FLASH to true), bridge.featureCalls)
        assertEquals(RootTargetChannel.OPPO.packageName, controller.state.value.targetPackage)
        assertEquals(898, controller.state.value.targetPid)
        assertTrue(controller.state.value.antiFlash.workerRunning)
        assertTrue(controller.state.value.antiFlash.applied)
    }

    @Test
    fun refreshingRunningTargetKeepsAntiFlashWorker() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        controller.openOrRefreshTarget()

        assertEquals(listOf(RootFeature.ANTI_FLASH to true), bridge.featureCalls)
        assertEquals(1, bridge.openTargetCalls.size)
        assertEquals("com.example.target", controller.state.value.targetPackage)
        assertEquals(777, controller.state.value.targetPid)
        assertTrue(controller.state.value.antiFlash.workerRunning)
        assertTrue(controller.state.value.antiFlash.applied)
        assertEquals("防闪运行中；已刷新当前目标状态", controller.state.value.message)
    }

    @Test
    fun closingRunningTargetStopsAntiFlashBeforeClosingSession() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        controller.closeTarget()

        assertEquals(
            listOf(RootFeature.ANTI_FLASH to true, RootFeature.ANTI_FLASH to false),
            bridge.featureCalls,
        )
        assertEquals(1, bridge.closeTargetCalls)
        assertNull(controller.state.value.targetPid)
        assertFalse(controller.state.value.antiFlashArmed)
        assertFalse(controller.state.value.antiFlash.applied)
    }

    @Test
    fun closingTargetKeepsSessionWhenAntiFlashRollbackFails() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
            failAntiFlashRollbackOnDisable = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        controller.closeTarget()

        assertEquals(0, bridge.closeTargetCalls)
        assertEquals("com.example.target", controller.state.value.targetPackage)
        assertEquals(777, controller.state.value.targetPid)
        assertEquals(RootAntiFlashStatus.ROLLBACK_FAILED, controller.state.value.antiFlash.status)
        assertTrue(controller.state.value.antiFlash.applied)
        assertTrue(controller.state.value.message.startsWith("关闭目标已取消："))
    }

    @Test
    fun disconnectStopsAntiFlashBeforeUnbinding() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        controller.disconnect()

        assertEquals(
            listOf(RootFeature.ANTI_FLASH to true, RootFeature.ANTI_FLASH to false),
            bridge.featureCalls,
        )
        assertEquals(listOf("disableAntiFlash", "disconnect"), bridge.lifecycleCalls)
        assertEquals(1, bridge.disconnectCount)
        assertFalse(bridge.isConnected)
        assertEquals(RootConnectionStatus.IDLE, controller.state.value.status)
        assertFalse(controller.state.value.antiFlash.applied)
    }

    @Test
    fun disconnectKeepsConnectionAndSessionWhenAntiFlashRollbackFails() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            supportAntiFlash = true
            failAntiFlashRollbackOnDisable = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()
        controller.setFeatureEnabled(RootFeature.ANTI_FLASH, true)

        controller.disconnect()

        assertEquals(listOf("disableAntiFlash"), bridge.lifecycleCalls)
        assertEquals(0, bridge.disconnectCount)
        assertTrue(bridge.isConnected)
        assertEquals(RootConnectionStatus.READY, controller.state.value.status)
        assertEquals("com.example.target", controller.state.value.targetPackage)
        assertEquals(777, controller.state.value.targetPid)
        assertEquals(RootAntiFlashStatus.ROLLBACK_FAILED, controller.state.value.antiFlash.status)
        assertTrue(controller.state.value.antiFlash.applied)
        assertTrue(controller.state.value.message.startsWith("断开控制服务已取消："))
    }

    @Test
    fun searchIdMatchIsPinnedToTheOpenTargetAndReportsOneBasedSlot() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            nextSearchIdResult = RootSearchIdResult(
                status = RootSearchIdStatus.MATCH,
                requestedId = 0L,
                slotIndex = 6,
            )
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.searchId(123_456L)

        assertEquals(listOf(123_456L), bridge.searchIdCalls)
        assertEquals(RootSearchIdStatus.MATCH, controller.state.value.searchIdResult?.status)
        assertEquals(6, controller.state.value.searchIdResult?.slotIndex)
        assertEquals("start-777", controller.state.value.searchIdResult?.processStartTimeTicks)
        assertEquals("SearchID 123456 命中槽位 7/40", controller.state.value.message)
    }

    @Test
    fun searchIdNotFoundIsRetainedUntilTargetIdentityChanges() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.searchId(404L)

        assertEquals(RootSearchIdStatus.NOT_FOUND, controller.state.value.searchIdResult?.status)
        assertEquals("SearchID 404 未在 40 个槽位中命中", controller.state.value.message)

        bridge.invalidateTargetSnapshot()
        controller.refreshTargetState()

        assertNull(controller.state.value.searchIdResult)
        assertNull(controller.state.value.targetPid)
    }

    @Test
    fun searchIdDropsInFlightResultAndStaleMatchMessageWhenTargetChanges() = runTest {
        val bridge = FakeRootFeatureBridgeClient().apply {
            nextSearchIdResult = RootSearchIdResult(
                status = RootSearchIdStatus.MATCH,
                requestedId = 0L,
                slotIndex = 1,
            )
            invalidateTargetOnSearchIdCall = true
        }
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.searchId(7L)

        assertNull(controller.state.value.searchIdResult)
        assertNull(controller.state.value.targetPid)
        assertEquals(
            "目标进程已变化或退出：com.example.target",
            controller.state.value.message,
        )
        assertFalse(controller.state.value.message.contains("命中槽位"))
    }

    @Test
    fun searchIdRejectsValuesOutsideInt32BeforeCallingBridge() = runTest {
        val bridge = FakeRootFeatureBridgeClient()
        val controller = RootController(bridge, TEST_UID)
        controller.requestAndConnect()
        controller.setTargetPackage("com.example.target")
        controller.openOrRefreshTarget()

        controller.searchId(Int.MAX_VALUE.toLong() + 1L)
        controller.searchId(Int.MIN_VALUE.toLong() - 1L)

        assertTrue(bridge.searchIdCalls.isEmpty())
        assertNull(controller.state.value.searchIdResult)
        assertEquals("SearchID 仅接受 int32 范围的目标 ID", controller.state.value.message)
    }
}

private const val TEST_UID = 10_321

private class FakeRootFeatureBridgeClient(
    private val serviceUid: Int = TEST_UID,
) : RootFeatureBridgeClient {
    override var isConnected: Boolean = false
    var connectCount = 0
    var disconnectCount = 0
    var closeTargetCalls = 0
    val featureCalls = mutableListOf<Pair<RootFeature, Boolean>>()
    val lifecycleCalls = mutableListOf<String>()
    val positionCalls = mutableListOf<RootPlayerPositionRequest>()
    val searchIdCalls = mutableListOf<Long>()
    val runningTargetPids = mutableMapOf<String, Int>()
    val findTargetCalls = mutableListOf<String>()
    val openTargetCalls = mutableListOf<String>()
    var invalidateTargetOnFeatureCall = false
    var invalidateTargetOnPositionCall = false
    var invalidateTargetOnSearchIdCall = false
    var failPositionCallAndDisconnect = false
    var supportReadableData = true
    var supportFlight = false
    var supportFakeFlight = false
    var supportPlayerPosition = false
    var supportAntiFlash = false
    var failAntiFlashRollbackOnDisable = false
    var rejectReadableDataEnable = false
    var rejectedReadableDataFields = RootReadOnlyFieldsState()
    var openedReadOnlyFields = readyReadOnlyFields(enabled = false)
    var openedInjection = RootInjectionState(
        profileStatus = RootInjectionProfileStatus.NO_PROFILE,
        profileSummary = "fixture has no injection profile",
    )
    var nextSearchIdResult: RootSearchIdResult? = null
    private var listener: (String) -> Unit = {}
    private var snapshot = RootTargetSnapshot(
        packageName = "",
        pid = null,
        summary = "未打开目标会话",
        features = defaultRootFeatureStates(),
    )

    override fun setConnectionLostListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    override suspend fun connect(): RootBridgeInfo {
        connectCount += 1
        isConnected = true
        return RootBridgeInfo(
            protocolVersion = RootProtocol.VERSION,
            uid = serviceUid,
            servicePid = 4242,
            abi = "arm64-v8a",
            serviceProcessName = "me.dartcv.minix:control",
        )
    }

    override suspend fun findTargetPid(packageName: String): Int? {
        findTargetCalls += packageName
        return runningTargetPids[packageName]
    }

    override suspend fun openOrRefreshTarget(packageName: String): RootTargetSnapshot {
        openTargetCalls += packageName
        val targetPid = runningTargetPids[packageName] ?: 777
        snapshot = RootTargetSnapshot(
            packageName = packageName,
            pid = targetPid,
            targetUid = serviceUid,
            startTimeTicks = "start-$targetPid",
            summary = "$packageName · PID $targetPid · 状态 S · 线程 12 · 映射 600",
            features = defaultRootFeatureStates(),
            supportedFeatures = buildSet {
                if (supportReadableData) add(RootFeature.READABLE_DATA)
                if (supportFlight) add(RootFeature.FLIGHT)
                if (supportFakeFlight) add(RootFeature.FAKE_FLIGHT)
                if (supportPlayerPosition) add(RootFeature.PLAYER_TELEPORT)
                if (supportAntiFlash) add(RootFeature.ANTI_FLASH)
            },
            nativeProbe = RootNativeProbeState(
                status = RootNativeProbeStatus.OK,
                processStartTimeTicks = "start-$targetPid",
                regionCount = 600,
                moduleCount = 30,
                memoryReadableModuleCount = 2,
                memoryReadBytes = 128,
                memoryElfHeaderCount = 2,
            ),
            readOnlyFields = openedReadOnlyFields,
            injection = openedInjection,
        )
        return snapshot
    }

    override suspend fun closeTarget(): RootTargetSnapshot {
        closeTargetCalls += 1
        snapshot = RootTargetSnapshot(
            packageName = "",
            pid = null,
            summary = "目标会话已关闭",
            features = defaultRootFeatureStates(),
        )
        return snapshot
    }

    override suspend fun armAntiFlashForPackage(
        packageName: String,
    ): RootAntiFlashArmBridgeResult {
        snapshot = RootTargetSnapshot(
            packageName = packageName,
            pid = null,
            summary = "Anti-flash launch watcher is armed",
            features = defaultRootFeatureStates().toMutableMap().apply {
                put(RootFeature.ANTI_FLASH, true)
            },
            antiFlash = RootAntiFlashState(
                status = RootAntiFlashStatus.WAITING_FOR_TARGET,
                requestedEnabled = true,
                workerRunning = true,
                profileId = "fixture-antiflash-v1",
                message = "waiting for target",
            ),
        )
        return RootAntiFlashArmBridgeResult(
            commandAccepted = true,
            snapshot = snapshot,
        )
    }

    override suspend fun setFeatureEnabled(
        feature: RootFeature,
        enabled: Boolean,
    ): RootTargetSnapshot {
        featureCalls += feature to enabled
        if (feature == RootFeature.ANTI_FLASH && !enabled) {
            lifecycleCalls += "disableAntiFlash"
        }
        if (feature == RootFeature.ANTI_FLASH && !enabled && failAntiFlashRollbackOnDisable) {
            snapshot = snapshot.copy(
                antiFlash = RootAntiFlashState(
                    status = RootAntiFlashStatus.ROLLBACK_FAILED,
                    requestedEnabled = false,
                    workerRunning = false,
                    applied = true,
                    targetPid = snapshot.pid,
                    targetStartTimeTicks = snapshot.startTimeTicks.orEmpty(),
                    message = "rollback failed",
                ),
            )
            return snapshot
        }
        if (invalidateTargetOnFeatureCall) {
            snapshot = RootTargetSnapshot(
                packageName = snapshot.packageName,
                pid = null,
                summary = "目标进程已变化或退出：${snapshot.packageName}",
                features = defaultRootFeatureStates(),
            )
            return snapshot
        }
        if (feature == RootFeature.READABLE_DATA && enabled && rejectReadableDataEnable) {
            snapshot = snapshot.copy(
                features = snapshot.features.toMutableMap().apply {
                    put(RootFeature.READABLE_DATA, false)
                },
                readOnlyFields = rejectedReadableDataFields,
            )
            return snapshot
        }
        snapshot = snapshot.copy(
            features = snapshot.features.toMutableMap().apply { put(feature, enabled) },
            antiFlash = if (feature == RootFeature.ANTI_FLASH) {
                if (enabled) {
                    RootAntiFlashState(
                        status = RootAntiFlashStatus.RUNNING,
                        requestedEnabled = true,
                        workerRunning = true,
                        applied = true,
                        iterationCount = 3L,
                        successfulWriteCount = 51L,
                        targetPid = snapshot.pid,
                        targetStartTimeTicks = snapshot.startTimeTicks.orEmpty(),
                        profileId = "fixture-antiflash-v1",
                        message = "17 writes verified",
                    )
                } else {
                    RootAntiFlashState(status = RootAntiFlashStatus.STOPPED)
                }
            } else {
                snapshot.antiFlash
            },
            readOnlyFields = if (feature == RootFeature.READABLE_DATA) {
                readyReadOnlyFields(enabled)
            } else {
                snapshot.readOnlyFields
            },
        )
        return snapshot
    }

    override suspend fun setPlayerPosition(
        request: RootPlayerPositionRequest,
    ): RootPlayerPositionBridgeResult {
        positionCalls += request
        if (failPositionCallAndDisconnect) {
            isConnected = false
            error("fixture binder died")
        }
        if (invalidateTargetOnPositionCall) {
            val result = RootPlayerPositionResult(
                request = request,
                status = RootInjectionApplyStatus.TARGET_CHANGED,
                message = "target identity changed",
            )
            snapshot = RootTargetSnapshot(
                packageName = snapshot.packageName,
                pid = null,
                summary = "目标进程已变化或退出：${snapshot.packageName}",
                features = defaultRootFeatureStates(),
                injection = RootInjectionState(
                    lastApplyStatus = RootInjectionApplyStatus.TARGET_CHANGED,
                    lastFeature = RootFeature.PLAYER_TELEPORT,
                    message = result.message,
                ),
            )
            return RootPlayerPositionBridgeResult(snapshot, result)
        }
        val result = RootPlayerPositionResult(
            request = request,
            effectiveX = request.x,
            effectiveY = request.y,
            effectiveZ = request.z,
            appliedAxisCount = 3,
            profileId = "fixture-position-v1",
            status = RootInjectionApplyStatus.APPLIED,
            message = "position applied",
        )
        snapshot = snapshot.copy(
            injection = RootInjectionState(
                profileStatus = RootInjectionProfileStatus.READY,
                lastApplyStatus = RootInjectionApplyStatus.APPLIED,
                lastFeature = RootFeature.PLAYER_TELEPORT,
                message = result.message,
            ),
        )
        return RootPlayerPositionBridgeResult(snapshot, result)
    }

    override suspend fun searchId(requestedId: Long): RootSearchIdBridgeResult {
        searchIdCalls += requestedId
        val configured = nextSearchIdResult
        val result = configured?.copy(
            requestedId = requestedId,
            processStartTimeTicks = configured.processStartTimeTicks.ifBlank {
                snapshot.startTimeTicks.orEmpty()
            },
        ) ?: RootSearchIdResult(
            status = RootSearchIdStatus.NOT_FOUND,
            requestedId = requestedId,
            processStartTimeTicks = snapshot.startTimeTicks.orEmpty(),
        )
        if (invalidateTargetOnSearchIdCall) invalidateTargetSnapshot()
        return RootSearchIdBridgeResult(snapshot, result)
    }

    override suspend fun readTargetState(): RootTargetSnapshot {
        val packageName = snapshot.packageName
        val targetPid = runningTargetPids[packageName]
        if (snapshot.antiFlash.requestedEnabled && snapshot.antiFlash.workerRunning &&
            snapshot.pid == null && targetPid != null && supportAntiFlash
        ) {
            snapshot = openOrRefreshTarget(packageName)
            snapshot = setFeatureEnabled(RootFeature.ANTI_FLASH, true)
        }
        return snapshot
    }

    fun invalidateTargetSnapshot() {
        snapshot = RootTargetSnapshot(
            packageName = snapshot.packageName,
            pid = null,
            summary = "目标进程已变化或退出：${snapshot.packageName}",
            features = defaultRootFeatureStates(),
        )
    }

    fun dropReadableData(fields: RootReadOnlyFieldsState) {
        snapshot = snapshot.copy(
            features = snapshot.features.toMutableMap().apply {
                put(RootFeature.READABLE_DATA, false)
            },
            supportedFeatures = snapshot.supportedFeatures - RootFeature.READABLE_DATA,
            readOnlyFields = fields,
        )
    }

    fun invalidateTargetWithRollbackFailure() {
        snapshot = RootTargetSnapshot(
            packageName = snapshot.packageName,
            pid = null,
            summary = "target exited and rollback failed",
            features = defaultRootFeatureStates(),
            antiFlash = RootAntiFlashState(
                status = RootAntiFlashStatus.ROLLBACK_FAILED,
                requestedEnabled = false,
                workerRunning = false,
                applied = true,
                targetPid = snapshot.pid,
                targetStartTimeTicks = snapshot.startTimeTicks.orEmpty(),
                message = "rollback failed",
            ),
        )
    }

    override fun disconnect() {
        lifecycleCalls += "disconnect"
        disconnectCount += 1
        isConnected = false
    }
}

private fun readyReadOnlyFields(enabled: Boolean): RootReadOnlyFieldsState =
    RootReadOnlyFieldsState(
        profileStatus = RootReadOnlyFieldProfileStatus.READY,
        profileSummary = "3 个字段已验证",
        profileId = "fixture-v1",
        targetVersion = "1.0",
        lifeState = if (enabled) {
            RootInt32FieldState(RootReadOnlyFieldReadStatus.OK, 0)
        } else {
            RootInt32FieldState(RootReadOnlyFieldReadStatus.FEATURE_DISABLED)
        },
        killCount = if (enabled) {
            RootInt32FieldState(RootReadOnlyFieldReadStatus.OK, 17)
        } else {
            RootInt32FieldState(RootReadOnlyFieldReadStatus.FEATURE_DISABLED)
        },
        dataLongSelector1 = if (enabled) {
            RootInt64FieldState(RootReadOnlyFieldReadStatus.OK, 0x0102030405060708L)
        } else {
            RootInt64FieldState(RootReadOnlyFieldReadStatus.FEATURE_DISABLED)
        },
    )
