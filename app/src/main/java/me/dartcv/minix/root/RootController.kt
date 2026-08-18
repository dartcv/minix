package me.dartcv.minix.root

import android.content.Context
import android.os.Process
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootController internal constructor(
    private val bridgeClient: RootFeatureBridgeClient,
    private val expectedServiceUid: Int,
) {
    constructor(context: Context) : this(
        bridgeClient = AndroidRootFeatureBridgeClient(context),
        expectedServiceUid = Process.myUid(),
    )

    private val operationMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(RootRuntimeState())
    val state: StateFlow<RootRuntimeState> = _state.asStateFlow()

    init {
        bridgeClient.setConnectionLostListener { message ->
            generation.incrementAndGet()
            _state.update { current ->
                current.copy(
                    status = RootConnectionStatus.ERROR,
                    message = message,
                    uid = null,
                    servicePid = null,
                    abi = null,
                    targetPid = null,
                    targetUid = null,
                    targetStartTimeTicks = null,
                    targetSummary = "本地控制服务连接已断开",
                    features = defaultRootFeatureStates(),
                    supportedFeatures = emptySet(),
                    nativeProbe = RootNativeProbeState(),
                    readOnlyFields = RootReadOnlyFieldsState(),
                    injection = RootInjectionState(),
                    antiFlash = RootAntiFlashState(),
                    antiFlashArmed = false,
                    playerPosition = null,
                    searchIdResult = null,
                    protocolVersion = null,
                    serviceProcessName = null,
                )
            }
        }
    }

    suspend fun requestAndConnect() {
        val requestGeneration = generation.incrementAndGet()
        operationMutex.withLock {
            if (bridgeClient.isConnected && _state.value.status == RootConnectionStatus.READY) {
                scanAndOpenFirstRunningTargetLocked()
                return
            }

            _state.update {
                it.copy(
                    status = RootConnectionStatus.REQUESTING,
                    message = "正在绑定同 UID 本地控制服务",
                )
            }

            try {
                if (generation.get() != requestGeneration) return
                _state.update {
                    it.copy(
                        status = RootConnectionStatus.CONNECTING,
                        message = "正在连接本地 Binder 控制服务",
                    )
                }

                val info = bridgeClient.connect()
                check(info.protocolVersion == RootProtocol.VERSION) {
                    "控制协议版本不兼容：${info.protocolVersion}"
                }
                check(info.uid == expectedServiceUid) {
                    "本地控制服务 UID 不匹配：${info.uid}，预期 $expectedServiceUid"
                }
                check(info.servicePid > 0) { "本地控制服务 PID 无效" }
                check(info.abi.isNotBlank()) { "本地控制服务 ABI 为空" }

                if (generation.get() != requestGeneration) {
                    bridgeClient.disconnect()
                    return
                }
                _state.update { current ->
                    current.copy(
                        status = RootConnectionStatus.READY,
                        message = "同 UID 服务已验证；目标证书/shared UID 尚未验证",
                        uid = info.uid,
                        servicePid = info.servicePid,
                        abi = info.abi,
                        targetPid = null,
                        targetUid = null,
                        targetStartTimeTicks = null,
                        targetSummary = if (current.targetPackage.isBlank()) {
                            "未打开目标会话"
                        } else {
                            "等待打开目标：${current.targetPackage}"
                        },
                        features = defaultRootFeatureStates(),
                        supportedFeatures = emptySet(),
                        nativeProbe = RootNativeProbeState(),
                        readOnlyFields = RootReadOnlyFieldsState(),
                        injection = RootInjectionState(),
                        antiFlash = RootAntiFlashState(),
                        antiFlashArmed = false,
                        playerPosition = null,
                        searchIdResult = null,
                        protocolVersion = info.protocolVersion,
                        serviceProcessName = info.serviceProcessName,
                    )
                }
                if (generation.get() == requestGeneration) {
                    scanAndOpenFirstRunningTargetLocked()
                }
            } catch (error: CancellationException) {
                bridgeClient.disconnect()
                if (generation.get() == requestGeneration) {
                    _state.update {
                        it.copy(
                            status = RootConnectionStatus.IDLE,
                            message = "本地控制服务连接已取消",
                            targetPid = null,
                            targetUid = null,
                            targetStartTimeTicks = null,
                            features = defaultRootFeatureStates(),
                            supportedFeatures = emptySet(),
                            nativeProbe = RootNativeProbeState(),
                            readOnlyFields = RootReadOnlyFieldsState(),
                            injection = RootInjectionState(),
                            antiFlash = RootAntiFlashState(),
                            antiFlashArmed = false,
                            playerPosition = null,
                            searchIdResult = null,
                        )
                    }
                }
                throw error
            } catch (error: Exception) {
                bridgeClient.disconnect()
                if (generation.get() == requestGeneration) {
                    _state.update {
                        it.copy(
                            status = RootConnectionStatus.ERROR,
                            message = "本地控制服务连接失败：${error.message ?: error.javaClass.simpleName}",
                            uid = null,
                            servicePid = null,
                            abi = null,
                            targetPid = null,
                            targetUid = null,
                            targetStartTimeTicks = null,
                            features = defaultRootFeatureStates(),
                            supportedFeatures = emptySet(),
                            nativeProbe = RootNativeProbeState(),
                            readOnlyFields = RootReadOnlyFieldsState(),
                            injection = RootInjectionState(),
                            antiFlash = RootAntiFlashState(),
                            antiFlashArmed = false,
                            playerPosition = null,
                            searchIdResult = null,
                            protocolVersion = null,
                            serviceProcessName = null,
                        )
                    }
                }
            }
        }
    }

    fun setTargetPackage(value: String) {
        val normalized = RootProtocol.normalizePackageName(value)
        val current = _state.value
        if (current.targetPackage != normalized && current.requiresSerializedTargetSwitch()) {
            _state.update {
                it.copy(message = "目标会话或防闪仍在运行；请使用串行渠道切换")
            }
            return
        }
        applyTargetPackage(normalized)
    }

    suspend fun selectTarget(target: RootTargetChannel) {
        operationMutex.withLock {
            val normalized = RootProtocol.normalizePackageName(target.packageName)
            val current = _state.value
            if (current.targetPackage == normalized) return@withLock

            if (current.requiresSerializedTargetSwitch() &&
                !stopActiveTargetForSwitchLocked("切换渠道已取消")
            ) {
                return@withLock
            }

            applyTargetPackage(normalized)
        }
    }

    private fun applyTargetPackage(normalized: String) {
        _state.update { current ->
            if (current.targetPackage == normalized) {
                current
            } else {
                current.copy(
                    targetPackage = normalized,
                    targetPid = null,
                    targetUid = null,
                    targetStartTimeTicks = null,
                    targetSummary = if (normalized.isBlank()) {
                        "未选择目标包"
                    } else {
                        "等待打开目标：$normalized"
                    },
                    features = defaultRootFeatureStates(),
                    supportedFeatures = emptySet(),
                    nativeProbe = RootNativeProbeState(),
                    readOnlyFields = RootReadOnlyFieldsState(),
                    injection = RootInjectionState(),
                    antiFlash = RootAntiFlashState(),
                    antiFlashArmed = false,
                    playerPosition = null,
                    searchIdResult = null,
                )
            }
        }
    }

    suspend fun scanAndOpenFirstRunningTarget() {
        operationMutex.withLock {
            val current = _state.value
            if (current.status != RootConnectionStatus.READY || !bridgeClient.isConnected) {
                _state.update { it.copy(message = "请先连接本地控制服务") }
                return
            }
            scanAndOpenFirstRunningTargetLocked()
        }
    }

    suspend fun openOrRefreshTarget() {
        operationMutex.withLock {
            val current = _state.value
            if (current.status != RootConnectionStatus.READY || !bridgeClient.isConnected) {
                _state.update { it.copy(message = "请先连接本地控制服务") }
                return
            }
            val packageName = current.targetPackage
            if (!RootProtocol.isValidPackageName(packageName)) {
                _state.update { it.copy(message = "目标包名格式无效") }
                return
            }

            if (current.antiFlash.workerRunning || current.antiFlash.applied) {
                runCatching { bridgeClient.readTargetState() }
                    .onSuccess { snapshot ->
                        applyTargetSnapshot(
                            snapshot = snapshot,
                            message = if (snapshot.antiFlash.status == RootAntiFlashStatus.ROLLBACK_FAILED) {
                                snapshot.antiFlash.message.ifBlank { snapshot.summary }
                            } else {
                                "防闪运行中；已刷新当前目标状态"
                            },
                        )
                    }
                    .onFailure(::handleRemoteFailure)
                return
            }

            runCatching { bridgeClient.openOrRefreshTarget(packageName) }
                .onSuccess { snapshot -> applyTargetSnapshot(snapshot) }
                .onFailure(::handleRemoteFailure)
        }
    }

    suspend fun refreshTargetState() {
        operationMutex.withLock {
            val current = _state.value
            if (
                current.status != RootConnectionStatus.READY ||
                !bridgeClient.isConnected ||
                !current.hasVerifiedTargetIdentity
            ) {
                return
            }

            val rediscoverProfiles = current.requiresTargetProfileRediscovery()
            runCatching {
                if (rediscoverProfiles) {
                    bridgeClient.openOrRefreshTarget(current.targetPackage)
                } else {
                    bridgeClient.readTargetState()
                }
            }
                .onSuccess { snapshot ->
                    applyTargetSnapshot(
                        snapshot = snapshot,
                        message = refreshedTargetMessage(current, snapshot),
                    )
                }
                .onFailure(::handleRemoteFailure)
        }
    }

    /** Arms anti-flash before the target process exists, then returns the selected launch package. */
    suspend fun armAntiFlashForLaunch(): String? = operationMutex.withLock {
        val current = _state.value
        val packageName = current.targetPackage
        if (current.status != RootConnectionStatus.READY || !bridgeClient.isConnected) {
            _state.update { it.copy(message = "请先连接本地控制服务") }
            return@withLock null
        }
        if (!RootProtocol.isValidPackageName(packageName)) {
            _state.update { it.copy(message = "请先选择有效的游戏渠道") }
            return@withLock null
        }
        publishAntiFlashWaiting("正在校验防闪模块并启动服务侧监听")
        val armResult = runCatching { bridgeClient.armAntiFlashForPackage(packageName) }
            .getOrElse { error ->
                handleRemoteFailure(error)
                return@withLock null
            }
        val armed = armResult.snapshot
        applyTargetSnapshot(armed)
        val ready = armResult.commandAccepted && armed.antiFlash.requestedEnabled &&
            armed.antiFlash.workerRunning
        _state.update { state ->
            state.copy(
                antiFlashArmed = ready,
                message = if (ready) {
                    "防闪监听已在控制服务中预置；准备启动游戏"
                } else {
                    armed.antiFlash.message.ifBlank { armed.summary }
                },
            )
        }
        packageName.takeIf { ready }
    }

    /** Advances the pre-armed target discovery/profile/worker state by one bounded step. */
    suspend fun maintainAntiFlash() {
        operationMutex.withLock { maintainAntiFlashLocked() }
    }

    suspend fun setFeatureEnabled(feature: RootFeature, enabled: Boolean) {
        operationMutex.withLock {
            val current = _state.value
            if (feature == RootFeature.ANTI_FLASH) {
                if (!enabled) {
                    _state.update { state -> state.copy(antiFlashArmed = false) }
                    val serviceMayNeedRollback = current.antiFlash.applied ||
                        current.antiFlash.workerRunning ||
                        current.antiFlash.status == RootAntiFlashStatus.ROLLBACK_FAILED
                    if (!bridgeClient.isConnected ||
                        !current.hasVerifiedTargetIdentity && !serviceMayNeedRollback
                    ) {
                        _state.update { state ->
                            state.copy(
                                message = "防闪预置已关闭",
                                features = state.features.toMutableMap().apply {
                                    put(RootFeature.ANTI_FLASH, false)
                                },
                                antiFlash = RootAntiFlashState(status = RootAntiFlashStatus.STOPPED),
                            )
                        }
                        return
                    }
                    runCatching { bridgeClient.setFeatureEnabled(feature, false) }
                        .onSuccess { snapshot ->
                            applyTargetSnapshot(snapshot, message = "防闪已关闭")
                            _state.update { it.copy(antiFlashArmed = false) }
                        }
                        .onFailure(::handleRemoteFailure)
                    return
                }
                if (current.status != RootConnectionStatus.READY || !bridgeClient.isConnected) {
                    _state.update { it.copy(message = "请先连接本地控制服务") }
                    return
                }
                if (!RootProtocol.isValidPackageName(current.targetPackage)) {
                    _state.update { it.copy(message = "请先选择有效的游戏渠道") }
                    return
                }
                publishAntiFlashWaiting("防闪已预置；等待游戏进程和精确模块指纹")
                maintainAntiFlashLocked()
                return
            }
            if (
                current.status != RootConnectionStatus.READY ||
                !bridgeClient.isConnected ||
                !current.hasVerifiedTargetIdentity
            ) {
                _state.update { it.copy(message = "目标会话尚未打开") }
                return
            }
            if (feature !in current.supportedFeatures) {
                _state.update { it.copy(message = unsupportedFeatureMessage(feature, current)) }
                return
            }
            runCatching { bridgeClient.setFeatureEnabled(feature, enabled) }
                .onSuccess { snapshot ->
                    val applied = snapshot.features[feature] == enabled
                    applyTargetSnapshot(
                        snapshot = snapshot,
                        message = if (applied) {
                            if (feature == RootFeature.ANTI_FLASH &&
                                snapshot.antiFlash.status == RootAntiFlashStatus.ROLLBACK_FAILED
                            ) {
                                snapshot.antiFlash.message.ifBlank { "防闪回滚失败" }
                            } else {
                                "${feature.label}${if (enabled) "已启用" else "已关闭"}"
                            }
                        } else {
                            featureApplyFailureMessage(feature, snapshot)
                        },
                    )
                }
                .onFailure(::handleRemoteFailure)
        }
    }

    suspend fun setPlayerPosition(request: RootPlayerPositionRequest) {
        operationMutex.withLock {
            val current = _state.value
            val pinnedPid = current.targetPid
            val pinnedStartTimeTicks = current.targetStartTimeTicks
            if (
                current.status != RootConnectionStatus.READY ||
                !bridgeClient.isConnected ||
                !current.hasVerifiedTargetIdentity
            ) {
                _state.update { it.copy(message = "目标会话尚未打开") }
                return
            }
            if (RootFeature.PLAYER_TELEPORT !in current.supportedFeatures) {
                _state.update { it.copy(message = "玩家传送的三轴地址配方尚未就绪") }
                return
            }
            runCatching { bridgeClient.setPlayerPosition(request) }
                .onSuccess { bridgeResult ->
                    val result = bridgeResult.result
                    val resultBelongsToPinnedSession = bridgeResult.snapshot.pid == pinnedPid &&
                        bridgeResult.snapshot.startTimeTicks == pinnedStartTimeTicks
                    applyTargetSnapshot(
                        snapshot = bridgeResult.snapshot,
                        message = result.message.ifBlank {
                            bridgeResult.snapshot.injection.message.ifBlank {
                                bridgeResult.snapshot.summary
                            }
                        },
                    )
                    _state.update { state ->
                        state.copy(
                            playerPosition = result.takeIf { resultBelongsToPinnedSession },
                        )
                    }
                }
                .onFailure(::handleRemoteFailure)
        }
    }

    suspend fun searchId(requestedId: Long) {
        operationMutex.withLock {
            val current = _state.value
            val pinnedPid = current.targetPid
            val pinnedStartTimeTicks = current.targetStartTimeTicks
            if (
                current.status != RootConnectionStatus.READY ||
                !bridgeClient.isConnected ||
                !current.hasVerifiedTargetIdentity
            ) {
                _state.update { it.copy(message = "目标会话尚未打开") }
                return
            }
            if (!current.nativeProbe.isMemoryReady) {
                _state.update { it.copy(message = "SearchID 等待 native 内存探测就绪") }
                return
            }
            if (requestedId !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                _state.update { it.copy(message = "SearchID 仅接受 int32 范围的目标 ID") }
                return
            }
            runCatching { bridgeClient.searchId(requestedId) }
                .onSuccess { bridgeResult ->
                    val result = bridgeResult.result
                    val belongsToPinnedSession = bridgeResult.snapshot.pid == pinnedPid &&
                        bridgeResult.snapshot.startTimeTicks == pinnedStartTimeTicks &&
                        (result.processStartTimeTicks.isBlank() ||
                            result.processStartTimeTicks == pinnedStartTimeTicks)
                    applyTargetSnapshot(
                        snapshot = bridgeResult.snapshot,
                        message = if (belongsToPinnedSession) {
                            result.toUserMessage()
                        } else {
                            bridgeResult.snapshot.summary.ifBlank {
                                "SearchID 结果已丢弃：目标进程身份发生变化"
                            }
                        },
                    )
                    _state.update { state ->
                        state.copy(
                            searchIdResult = result.takeIf { belongsToPinnedSession },
                        )
                    }
                }
                .onFailure(::handleRemoteFailure)
        }
    }

    suspend fun closeTarget() {
        operationMutex.withLock {
            if (!bridgeClient.isConnected) {
                _state.update {
                    it.copy(
                        targetPid = null,
                        targetUid = null,
                        targetStartTimeTicks = null,
                        targetSummary = "目标会话已关闭",
                        features = defaultRootFeatureStates(),
                        supportedFeatures = emptySet(),
                        nativeProbe = RootNativeProbeState(),
                        readOnlyFields = RootReadOnlyFieldsState(),
                        injection = RootInjectionState(),
                        antiFlash = RootAntiFlashState(),
                        antiFlashArmed = false,
                        playerPosition = null,
                        searchIdResult = null,
                    )
                }
                return
            }
            if (_state.value.requiresSerializedTargetSwitch() &&
                !stopActiveTargetForSwitchLocked("关闭目标已取消")
            ) {
                return
            }
            runCatching { bridgeClient.closeTarget() }
                .onSuccess { snapshot ->
                    _state.update { it.copy(antiFlashArmed = false) }
                    applyTargetSnapshot(snapshot)
                }
                .onFailure(::handleRemoteFailure)
        }
    }

    suspend fun disconnect() {
        generation.incrementAndGet()
        operationMutex.withLock {
            if (bridgeClient.isConnected && _state.value.requiresSerializedTargetSwitch() &&
                !stopActiveTargetForSwitchLocked("断开控制服务已取消")
            ) {
                return@withLock
            }
            bridgeClient.disconnect()
            _state.update { current ->
                RootRuntimeState(
                    status = RootConnectionStatus.IDLE,
                    message = "本地控制服务已断开",
                    targetPackage = current.targetPackage,
                    targetSummary = if (current.targetPackage.isBlank()) {
                        "未打开目标会话"
                    } else {
                        "等待打开目标：${current.targetPackage}"
                    },
                    antiFlashArmed = false,
                )
            }
        }
    }

    private fun applyTargetSnapshot(
        snapshot: RootTargetSnapshot,
        message: String? = null,
    ) {
        _state.update { current ->
            current.copy(
                message = message ?: snapshot.summary,
                targetPackage = snapshot.packageName.ifBlank { current.targetPackage },
                targetPid = snapshot.pid,
                targetUid = snapshot.targetUid,
                targetStartTimeTicks = snapshot.startTimeTicks,
                targetSummary = snapshot.summary,
                features = snapshot.features,
                supportedFeatures = snapshot.supportedFeatures,
                nativeProbe = snapshot.nativeProbe,
                readOnlyFields = snapshot.readOnlyFields,
                injection = snapshot.injection,
                antiFlash = if (
                    current.antiFlashArmed && snapshot.pid == null &&
                    !snapshot.antiFlash.applied &&
                    snapshot.antiFlash.status != RootAntiFlashStatus.ROLLBACK_FAILED
                ) {
                    waitingAntiFlashState(snapshot.antiFlash.message.ifBlank {
                        "防闪已预置；等待游戏进程"
                    })
                } else {
                    snapshot.antiFlash
                },
                antiFlashArmed = current.antiFlashArmed ||
                    snapshot.antiFlash.requestedEnabled && snapshot.antiFlash.workerRunning,
                playerPosition = current.playerPosition.takeIf {
                    snapshot.pid != null &&
                        snapshot.startTimeTicks == current.targetStartTimeTicks
                },
                searchIdResult = current.searchIdResult.takeIf {
                    snapshot.pid != null &&
                        snapshot.startTimeTicks == current.targetStartTimeTicks
                },
            )
        }
    }

    private suspend fun maintainAntiFlashLocked() {
        var current = _state.value
        if (!current.antiFlashArmed || current.status != RootConnectionStatus.READY ||
            !bridgeClient.isConnected
        ) {
            return
        }
        val packageName = current.targetPackage
        if (!RootProtocol.isValidPackageName(packageName)) return

        if (!current.hasVerifiedTargetIdentity && current.antiFlash.requestedEnabled &&
            current.antiFlash.workerRunning
        ) {
            runCatching { bridgeClient.readTargetState() }
                .onSuccess { snapshot -> applyTargetSnapshot(snapshot) }
                .onFailure(::handleRemoteFailure)
            return
        }

        // A residual patch must stay visible and blocks a new target until an explicit
        // stop retries the rollback. Re-opening here would discard the service context.
        if (current.antiFlash.applied && !current.antiFlash.workerRunning) return

        if (current.hasVerifiedTargetIdentity && current.antiFlash.workerRunning &&
            current.antiFlash.requestedEnabled
        ) {
            runCatching { bridgeClient.readTargetState() }
                .onSuccess { snapshot -> applyTargetSnapshot(snapshot) }
                .onFailure(::handleRemoteFailure)
            return
        }

        val failedCurrentTarget = current.targetPid != null &&
            current.antiFlash.targetPid == current.targetPid &&
            current.antiFlash.status !in setOf(
                RootAntiFlashStatus.IDLE,
                RootAntiFlashStatus.STARTING,
                RootAntiFlashStatus.WAITING_FOR_TARGET,
                RootAntiFlashStatus.RUNNING,
            )
        if (failedCurrentTarget) {
            val snapshot = runCatching { bridgeClient.readTargetState() }
                .getOrElse {
                    handleRemoteFailure(it)
                    return
                }
            applyTargetSnapshot(snapshot)
            current = _state.value
            if (current.hasVerifiedTargetIdentity &&
                current.targetPid == snapshot.pid &&
                current.targetStartTimeTicks == snapshot.startTimeTicks
            ) {
                return
            }
        }

        if (!current.hasVerifiedTargetIdentity) {
            val targetPid = runCatching { bridgeClient.findTargetPid(packageName) }
                .getOrElse {
                    handleRemoteFailure(it)
                    return
                }
            if (targetPid == null) {
                publishAntiFlashWaiting("防闪已预置；等待游戏进程")
                return
            }
        }

        if (!current.hasVerifiedTargetIdentity || RootFeature.ANTI_FLASH !in current.supportedFeatures) {
            val snapshot = runCatching { bridgeClient.openOrRefreshTarget(packageName) }
                .getOrElse {
                    handleRemoteFailure(it)
                    return
                }
            applyTargetSnapshot(snapshot)
            current = _state.value
            if (!current.hasVerifiedTargetIdentity) {
                publishAntiFlashWaiting(snapshot.summary)
                return
            }
            if (RootFeature.ANTI_FLASH !in current.supportedFeatures) {
                publishAntiFlashWaiting("目标已发现；等待 GameApp 与 tprt 精确指纹就绪")
                return
            }
        }

        val snapshot = runCatching {
            bridgeClient.setFeatureEnabled(RootFeature.ANTI_FLASH, true)
        }.getOrElse {
            handleRemoteFailure(it)
            return
        }
        val running = snapshot.antiFlash.requestedEnabled && snapshot.antiFlash.workerRunning
        applyTargetSnapshot(
            snapshot = snapshot,
            message = if (running) {
                "防闪已启用"
            } else {
                snapshot.antiFlash.message.ifBlank { snapshot.summary }
            },
        )
    }

    private fun publishAntiFlashWaiting(message: String) {
        _state.update { current ->
            current.copy(
                message = message,
                antiFlashArmed = true,
                features = current.features.toMutableMap().apply {
                    if (!current.antiFlash.workerRunning) put(RootFeature.ANTI_FLASH, false)
                },
                antiFlash = if (current.antiFlash.workerRunning) {
                    current.antiFlash
                } else {
                    waitingAntiFlashState(message)
                },
            )
        }
    }

    private fun waitingAntiFlashState(message: String) = RootAntiFlashState(
        status = RootAntiFlashStatus.WAITING_FOR_TARGET,
        requestedEnabled = true,
        workerRunning = false,
        applied = false,
        profileId = RootAntiFlashProfileCatalog.profile.profileId,
        message = message.take(256),
    )

    private suspend fun scanAndOpenFirstRunningTargetLocked() {
        _state.update {
            it.copy(message = "正在扫描 ${RootTargetCatalog.entries.size} 个游戏渠道包")
        }
        val detected = try {
            RootTargetCatalog.entries.firstOrNull { target ->
                bridgeClient.findTargetPid(target.packageName) != null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handleRemoteFailure(error)
            return
        }
        if (detected == null) {
            if (_state.value.requiresSerializedTargetSwitch() &&
                !stopActiveTargetForSwitchLocked("扫描结果未应用")
            ) {
                return
            }
            _state.update { current ->
                current.copy(
                    message = "同 UID 服务已验证；未发现运行中的游戏目标；目标证书/shared UID 尚未验证",
                    targetPid = null,
                    targetUid = null,
                    targetStartTimeTicks = null,
                    targetSummary = if (current.targetPackage.isBlank()) {
                        "未发现运行中的渠道包"
                    } else {
                        "未发现运行中的目标：${current.targetPackage}"
                    },
                    features = defaultRootFeatureStates(),
                    supportedFeatures = emptySet(),
                    nativeProbe = RootNativeProbeState(),
                    readOnlyFields = RootReadOnlyFieldsState(),
                    injection = RootInjectionState(),
                    antiFlash = if (current.antiFlashArmed) {
                        waitingAntiFlashState("防闪已预置；等待游戏进程")
                    } else {
                        RootAntiFlashState()
                    },
                    antiFlashArmed = current.antiFlashArmed,
                    playerPosition = null,
                    searchIdResult = null,
                )
            }
            return
        }

        val beforeSwitch = _state.value
        val samePackage = beforeSwitch.targetPackage == detected.packageName
        if (samePackage && (beforeSwitch.antiFlash.workerRunning || beforeSwitch.antiFlash.applied)) {
            val snapshot = runCatching { bridgeClient.readTargetState() }
                .getOrElse {
                    handleRemoteFailure(it)
                    return
                }
            applyTargetSnapshot(
                snapshot = snapshot,
                message = if (snapshot.antiFlash.status == RootAntiFlashStatus.ROLLBACK_FAILED) {
                    snapshot.antiFlash.message.ifBlank { snapshot.summary }
                } else if (snapshot.pid != null) {
                    "已保持${detected.channelLabel}目标会话；目标 UID ${snapshot.targetUid} 已与服务 UID 核对"
                } else {
                    snapshot.summary
                },
            )
            return
        }
        if (!samePackage && beforeSwitch.requiresSerializedTargetSwitch() &&
            !stopActiveTargetForSwitchLocked("扫描结果未应用")
        ) {
            return
        }

        _state.update { current ->
            val keepPrearmedIntent = current.antiFlashArmed &&
                current.targetPackage == detected.packageName
            current.copy(
                targetPackage = detected.packageName,
                targetPid = null,
                targetUid = null,
                targetStartTimeTicks = null,
                targetSummary = "已检测到${detected.channelLabel}，正在打开目标",
                features = defaultRootFeatureStates(),
                supportedFeatures = emptySet(),
                nativeProbe = RootNativeProbeState(),
                readOnlyFields = RootReadOnlyFieldsState(),
                injection = RootInjectionState(),
                antiFlash = if (keepPrearmedIntent) {
                    waitingAntiFlashState("防闪已预置；正在打开目标")
                } else {
                    RootAntiFlashState()
                },
                antiFlashArmed = keepPrearmedIntent,
                playerPosition = null,
                searchIdResult = null,
            )
        }
        runCatching { bridgeClient.openOrRefreshTarget(detected.packageName) }
            .onSuccess { snapshot ->
                applyTargetSnapshot(
                    snapshot = snapshot,
                    message = if (snapshot.pid != null) {
                        "已自动选择${detected.channelLabel}；目标 UID ${snapshot.targetUid} 已与服务 UID 核对"
                    } else {
                        snapshot.summary
                    },
                )
            }
            .onFailure(::handleRemoteFailure)
    }

    private fun handleRemoteFailure(error: Throwable) {
        val connectionAlive = bridgeClient.isConnected
        _state.update { current ->
            current.copy(
                status = if (connectionAlive) current.status else RootConnectionStatus.ERROR,
                message = error.message ?: "本地控制操作失败",
                targetPid = if (connectionAlive) current.targetPid else null,
                targetUid = if (connectionAlive) current.targetUid else null,
                targetStartTimeTicks = if (connectionAlive) current.targetStartTimeTicks else null,
                features = if (connectionAlive) current.features else defaultRootFeatureStates(),
                supportedFeatures = if (connectionAlive) current.supportedFeatures else emptySet(),
                nativeProbe = if (connectionAlive) current.nativeProbe else RootNativeProbeState(),
                readOnlyFields = if (connectionAlive) {
                    current.readOnlyFields
                } else {
                    RootReadOnlyFieldsState()
                },
                injection = if (connectionAlive) current.injection else RootInjectionState(),
                antiFlash = if (connectionAlive) current.antiFlash else RootAntiFlashState(),
                antiFlashArmed = connectionAlive && current.antiFlashArmed,
                playerPosition = if (connectionAlive) current.playerPosition else null,
                searchIdResult = if (connectionAlive) current.searchIdResult else null,
            )
        }
    }

    private fun unsupportedFeatureMessage(
        feature: RootFeature,
        state: RootRuntimeState,
    ): String = when (feature) {
        RootFeature.AIM -> "瞄准未就绪：40 槽候选已闭合，最终 GameApp writer/宽度/值仍缺"
        RootFeature.DRAW -> "绘制未就绪：实体与矩阵链已闭合，完整投影和九字段映射仍缺"
        RootFeature.FAKE_FLIGHT -> "模拟飞行未就绪：${state.injection.profileSummary}"
        RootFeature.HITBOX -> "命中区域未就绪：旧 worker 100 槽已闭合，但当前 exact-SHA 全实体集合映射/生命周期未唯一化"
        RootFeature.READABLE_DATA -> "数据读取未就绪：${state.readOnlyFields.profileSummary}"
        RootFeature.FLIGHT -> "飞行未就绪：${state.injection.profileSummary}"
        RootFeature.PLAYER_TELEPORT -> "玩家传送的三轴地址配方尚未就绪"
        RootFeature.ANTI_FLASH -> state.antiFlash.message.ifBlank { "防闪档案尚未就绪" }
    }

    private fun featureApplyFailureMessage(
        feature: RootFeature,
        snapshot: RootTargetSnapshot,
    ): String = when (feature) {
        RootFeature.ANTI_FLASH ->
            snapshot.antiFlash.message.ifBlank { snapshot.summary }

        RootFeature.READABLE_DATA -> if (
            snapshot.readOnlyFields.profileStatus != RootReadOnlyFieldProfileStatus.IDLE
        ) {
            snapshot.readOnlyFields.profileSummary.ifBlank { snapshot.summary }
        } else {
            snapshot.summary
        }

        else -> snapshot.injection.message.ifBlank { snapshot.summary }
    }

    private fun refreshedTargetMessage(
        previous: RootRuntimeState,
        snapshot: RootTargetSnapshot,
    ): String {
        if (snapshot.pid == null) return snapshot.summary
        if (snapshot.supportedFeatures.any { feature -> feature !in previous.supportedFeatures }) {
            return snapshot.summary
        }
        val disabledFeature = RootFeature.entries.firstOrNull { feature ->
            previous.features[feature] == true && snapshot.features[feature] != true
        } ?: return previous.message
        val reason = featureApplyFailureMessage(disabledFeature, snapshot)
        return "${disabledFeature.label}已停用：$reason"
    }

    private fun RootRuntimeState.requiresSerializedTargetSwitch(): Boolean =
        targetPid != null || antiFlash.workerRunning || antiFlash.applied

    private fun RootRuntimeState.requiresTargetProfileRediscovery(): Boolean {
        if (!hasVerifiedTargetIdentity || antiFlash.workerRunning || antiFlash.applied) return false
        if (!nativeProbe.isMemoryReady) return true
        return readOnlyFields.profileStatus.isTransientProfileLoadState() ||
            injection.profileStatus.isTransientProfileLoadState()
    }

    private suspend fun stopActiveTargetForSwitchLocked(cancelPrefix: String): Boolean {
        val current = _state.value
        if (!current.requiresSerializedTargetSwitch()) return true
        if (current.status != RootConnectionStatus.READY || !bridgeClient.isConnected) {
            _state.update {
                it.copy(message = "$cancelPrefix：旧目标仍在活动，控制服务不可用")
            }
            return false
        }

        val snapshot = runCatching {
            bridgeClient.setFeatureEnabled(RootFeature.ANTI_FLASH, false)
        }.getOrElse { error ->
            handleRemoteFailure(error)
            _state.update { state -> state.copy(message = "$cancelPrefix：${state.message}") }
            return false
        }
        applyTargetSnapshot(snapshot)
        val stoppedWithoutResidue = !snapshot.antiFlash.requestedEnabled &&
            !snapshot.antiFlash.workerRunning && !snapshot.antiFlash.applied
        if (stoppedWithoutResidue) return true

        val detail = snapshot.antiFlash.message.ifBlank { "防闪停止或回滚尚未完成" }
        _state.update { it.copy(message = "$cancelPrefix：$detail") }
        return false
    }

}

private fun RootSearchIdResult.toUserMessage(): String = when (status) {
    RootSearchIdStatus.MATCH ->
        "SearchID $requestedId 命中槽位 ${(slotIndex ?: 0) + 1}/${RootRecoveredSearchIdProfiles.miniWorld1582.recipe.slotCount}"
    RootSearchIdStatus.NOT_FOUND ->
        "SearchID $requestedId 未在 ${RootRecoveredSearchIdProfiles.miniWorld1582.recipe.slotCount} 个槽位中命中"
    RootSearchIdStatus.INVALID -> "SearchID 扫描失败：${message.ifBlank {
        invalidReason?.name ?: RootSearchIdInvalidReason.INVALID_RESPONSE.name
    }}"
}

private fun RootReadOnlyFieldProfileStatus.isTransientProfileLoadState(): Boolean = when (this) {
    RootReadOnlyFieldProfileStatus.IDLE,
    RootReadOnlyFieldProfileStatus.MODULE_NOT_FOUND,
    RootReadOnlyFieldProfileStatus.FINGERPRINT_UNAVAILABLE,
    -> true

    else -> false
}

private fun RootInjectionProfileStatus.isTransientProfileLoadState(): Boolean = when (this) {
    RootInjectionProfileStatus.IDLE,
    RootInjectionProfileStatus.MODULE_NOT_FOUND,
    RootInjectionProfileStatus.FINGERPRINT_UNAVAILABLE,
    -> true

    else -> false
}
