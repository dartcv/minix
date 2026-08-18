package me.dartcv.minix.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface ControlBridgeClient {
    val isConnected: Boolean

    fun setConnectionLostListener(listener: (String) -> Unit)
    suspend fun connect(): ControlBridgeInfo
    suspend fun findTargetPid(packageName: String): Int?
    suspend fun openOrRefreshTarget(packageName: String): ControlTargetSnapshot
    suspend fun closeTarget(): ControlTargetSnapshot
    suspend fun armAntiFlashForPackage(packageName: String): ControlAntiFlashArmBridgeResult
    suspend fun setFeatureEnabled(feature: ControlFeature, enabled: Boolean): ControlTargetSnapshot
    suspend fun setPlayerPosition(
        request: ControlPlayerPositionRequest,
    ): ControlPlayerPositionBridgeResult
    suspend fun searchId(requestedId: Long): ControlSearchIdBridgeResult
    suspend fun readTargetState(): ControlTargetSnapshot
    fun disconnect()
}

internal data class ControlAntiFlashArmBridgeResult(
    val commandAccepted: Boolean,
    val snapshot: ControlTargetSnapshot,
)

internal class AndroidControlBridgeClient(
    context: Context,
) : ControlBridgeClient {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val expectedComponent = ComponentName(appContext, ControlService::class.java)

    @Volatile
    private var remote: IControlBridge? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    @Volatile
    private var serviceStarted = false

    @Volatile
    private var connectionLostListener: (String) -> Unit = {}

    private var linkedBinder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        handleConnectionLost("本地控制服务进程已退出")
    }

    override val isConnected: Boolean
        get() = remote?.asBinder()?.isBinderAlive == true

    override fun setConnectionLostListener(listener: (String) -> Unit) {
        connectionLostListener = listener
    }

    override suspend fun connect(): ControlBridgeInfo {
        val bridge = remote?.takeIf { it.asBinder().isBinderAlive }
            ?: withTimeout(ControlProtocol.CONNECTION_TIMEOUT_MILLIS) { awaitBridge() }
        return withContext(Dispatchers.IO) {
            ControlBridgeInfo(
                protocolVersion = bridge.getProtocolVersion(),
                uid = bridge.getUid(),
                servicePid = bridge.getServicePid(),
                abi = bridge.getAbi(),
                serviceProcessName = bridge.getServiceProcessName(),
            )
        }
    }

    override suspend fun findTargetPid(packageName: String): Int? =
        withRemote { bridge -> bridge.findTargetPid(packageName).takeIf { it > 0 } }

    override suspend fun openOrRefreshTarget(packageName: String): ControlTargetSnapshot =
        withRemote { bridge ->
            bridge.openOrRefreshTarget(packageName)
            readTargetState(bridge, expectedPackage = packageName)
        }

    override suspend fun closeTarget(): ControlTargetSnapshot = withRemote { bridge ->
        bridge.closeTarget()
        readTargetState(bridge)
    }

    override suspend fun armAntiFlashForPackage(
        packageName: String,
    ): ControlAntiFlashArmBridgeResult = withRemote { bridge ->
        val commandAccepted = bridge.armAntiFlashForPackage(packageName)
        ControlAntiFlashArmBridgeResult(
            commandAccepted = commandAccepted,
            snapshot = readTargetState(bridge, expectedPackage = packageName),
        )
    }

    override suspend fun setFeatureEnabled(
        feature: ControlFeature,
        enabled: Boolean,
    ): ControlTargetSnapshot = withRemote { bridge ->
        bridge.setFeatureEnabled(feature.wireId, enabled)
        readTargetState(bridge)
    }

    override suspend fun setPlayerPosition(
        request: ControlPlayerPositionRequest,
    ): ControlPlayerPositionBridgeResult = withRemote { bridge ->
        val commandAccepted = bridge.setPlayerPosition(request.x, request.y, request.z)
        val effectiveX = if (bridge.hasLastPlayerPositionX()) {
            bridge.getLastPlayerPositionX()
        } else {
            null
        }
        val effectiveY = if (bridge.hasLastPlayerPositionY()) {
            bridge.getLastPlayerPositionY()
        } else {
            null
        }
        val effectiveZ = if (bridge.hasLastPlayerPositionZ()) {
            bridge.getLastPlayerPositionZ()
        } else {
            null
        }
        val wireStatus = ControlInjectionApplyStatus.fromWireValue(
            bridge.getLastPlayerPositionStatus(),
        )
        val appliedAxisCount = bridge.getLastPlayerPositionAppliedAxisCount()
        val status = normalizePlayerPositionBridgeStatus(
            commandAccepted = commandAccepted,
            wireStatus = wireStatus,
            hasAllEffectiveCoordinates = effectiveX != null && effectiveY != null && effectiveZ != null,
            appliedAxisCount = appliedAxisCount,
        )
        val result = ControlPlayerPositionResult(
            request = request,
            effectiveX = effectiveX,
            effectiveY = effectiveY,
            effectiveZ = effectiveZ,
            appliedAxisCount = appliedAxisCount.coerceIn(0, 3),
            profileId = bridge.getLastPlayerPositionProfileId().trim().take(96),
            status = status,
            message = bridge.getLastPlayerPositionMessage().trim().take(256),
        )
        ControlPlayerPositionBridgeResult(
            snapshot = readTargetState(bridge),
            result = result,
        )
    }

    override suspend fun searchId(requestedId: Long): ControlSearchIdBridgeResult =
        withRemote { bridge ->
            val commandAccepted = bridge.searchId(requestedId)
            val slotIndex = if (bridge.hasLastSearchIdSlotIndex()) {
                bridge.getLastSearchIdSlotIndex()
            } else {
                null
            }
            val result = normalizeSearchIdBridgeResult(
                commandAccepted = commandAccepted,
                requestedId = requestedId,
                returnedRequestedId = bridge.getLastSearchIdRequestedId(),
                wireStatus = ControlSearchIdStatus.fromWireValue(bridge.getLastSearchIdStatus()),
                slotIndex = slotIndex,
                invalidReason = ControlSearchIdInvalidReason.fromWireValue(
                    bridge.getLastSearchIdInvalidReason(),
                ),
                processStartTimeTicks = bridge.getLastSearchIdProcessStartTimeTicks()
                    .trim()
                    .take(64),
                message = bridge.getLastSearchIdMessage().trim().take(256),
                slotCount = ControlRecoveredSearchIdProfiles.miniWorld1582.recipe.slotCount,
            )
            ControlSearchIdBridgeResult(
                snapshot = readTargetState(bridge),
                result = result,
            )
        }

    override suspend fun readTargetState(): ControlTargetSnapshot =
        withRemote { bridge -> readTargetState(bridge) }

    override fun disconnect() {
        val (connection, shouldStopService) = synchronized(lock) {
            val value = activeConnection
            activeConnection = null
            remote = null
            unlinkDeathRecipientLocked()
            val stop = serviceStarted
            serviceStarted = false
            value to stop
        }
        if (connection != null) {
            runOnMain {
                runCatching { appContext.unbindService(connection) }
            }
        }
        if (shouldStopService) {
            runOnMain {
                runCatching { appContext.stopService(Intent().setComponent(expectedComponent)) }
            }
        }
    }

    private suspend fun awaitBridge(): IControlBridge = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (name != expectedComponent) {
                    clearConnection(this)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SecurityException("本地控制服务组件身份不匹配：$name"),
                        )
                    }
                    return
                }
                val bridge = IControlBridge.Stub.asInterface(service)
                synchronized(lock) {
                    remote = bridge
                    linkedBinder = service
                    runCatching { service.linkToDeath(deathRecipient, 0) }
                }
                if (continuation.isActive) continuation.resume(bridge)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                handleConnectionLost("本地控制服务连接已断开")
            }

            override fun onBindingDied(name: ComponentName) {
                handleConnectionLost("本地控制服务 Binder 已失效")
            }

            override fun onNullBinding(name: ComponentName) {
                clearConnection(this)
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("本地控制服务没有返回 Binder"),
                    )
                }
            }
        }

        synchronized(lock) { activeConnection = connection }
        continuation.invokeOnCancellation {
            val shouldUnbind = synchronized(lock) {
                if (activeConnection === connection) {
                    activeConnection = null
                    remote = null
                    unlinkDeathRecipientLocked()
                    true
                } else {
                    false
                }
            }
            if (shouldUnbind) {
                runOnMain { runCatching { appContext.unbindService(connection) } }
            }
        }

        runOnMain {
            try {
                val serviceIntent = Intent().setComponent(expectedComponent)
                val started = appContext.startService(serviceIntent)
                check(started == expectedComponent) {
                    "本地控制服务启动请求未返回预期组件"
                }
                synchronized(lock) { serviceStarted = true }
                val bound = appContext.bindService(
                    serviceIntent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
                if (!bound) {
                    clearConnection(connection)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("本地控制服务绑定请求被系统拒绝"),
                        )
                    }
                }
            } catch (error: Exception) {
                clearConnection(connection)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private suspend fun <T> withRemote(block: (IControlBridge) -> T): T {
        val bridge = remote?.takeIf { it.asBinder().isBinderAlive }
            ?: error("本地控制服务尚未连接")
        return withContext(Dispatchers.IO) { block(bridge) }
    }

    private fun readTargetState(
        bridge: IControlBridge,
        expectedPackage: String? = null,
    ): ControlTargetSnapshot {
        val summary = bridge.getTargetSummary()
        val packageName = bridge.getTargetPackage()
        val targetPid = bridge.getTargetPid().takeIf { it > 0 }
        val targetUid = bridge.getTargetUid().takeIf { it >= 0 }
        val targetStartTimeTicks = bridge.getTargetStartTimeTicks()
            .trim()
            .take(32)
            .takeIf(String::isNotEmpty)
        if (targetPid != null) {
            check(targetUid != null) {
                "本地控制服务返回的目标进程缺少 UID"
            }
            check(targetUid == bridge.getUid()) {
                "目标进程 UID 与控制服务 UID 不匹配：$targetUid"
            }
            check(targetStartTimeTicks != null) {
                "本地控制服务返回的目标进程缺少启动标识"
            }
            val packageToVerify = expectedPackage ?: packageName
            check(bridge.verifyTargetProcess(targetPid, packageToVerify)) {
                "本地控制服务返回了不匹配的目标进程"
            }
        }
        // Field refresh can advance the same process' maps generation and atomically
        // replace the session's feature/profile state. Use the first read only as the
        // gate for the refresh, then publish feature IDs and probe data read afterwards.
        val enabledIdsBeforeFieldRefresh = bridge.getEnabledFeatureIds().toSet()
        val readOnlyFields = readReadOnlyFields(
            bridge = bridge,
            targetIsOpen = targetPid != null,
            readableDataEnabled =
                ControlFeature.READABLE_DATA.wireId in enabledIdsBeforeFieldRefresh,
        )
        val enabledIds = bridge.getEnabledFeatureIds().toSet()
        val supportedIds = bridge.getSupportedFeatureIds().toSet()
        val probeStatus = ControlNativeProbeStatus.fromWireValue(bridge.getTargetProbeStatus())
            ?: ControlNativeProbeStatus.INVALID_RESPONSE
        val nativeProbe = ControlNativeProbeState(
            status = probeStatus,
            processStartTimeTicks = targetStartTimeTicks.orEmpty(),
            regionCount = bridge.getTargetProbeRegionCount().coerceAtLeast(0),
            moduleCount = bridge.getTargetProbeModuleCount().coerceAtLeast(0),
            memoryReadableModuleCount = bridge
                .getTargetProbeMemoryReadableModuleCount()
                .coerceAtLeast(0),
            memoryReadBytes = bridge.getTargetProbeMemoryReadBytes().coerceAtLeast(0L),
            memoryElfHeaderCount = bridge.getTargetProbeMemoryElfHeaderCount().coerceAtLeast(0),
            mapsFingerprint = bridge.getTargetProbeFingerprint().take(32),
            truncated = bridge.isTargetProbeTruncated(),
            modules = bridge.getTargetProbeModules()
                .orEmpty()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .take(ControlProtocol.MAX_PROBE_MODULES)
                .toList(),
            message = bridge.getTargetProbeMessage().take(256),
        )
        val injection = readInjectionState(bridge)
        val antiFlashWireState = decodeControlAntiFlashStateJson(
            bridge.getAntiFlashStateJson().take(ControlProtocol.MAX_ANTI_FLASH_STATE_JSON_CHARS + 1),
        )
        // Anti-flash state carries its own process identity and may be the only
        // remaining rollback context when the target snapshot changes mid-read.
        val antiFlash = antiFlashWireState
        val finalPackageName = bridge.getTargetPackage()
        val finalTargetPid = bridge.getTargetPid().takeIf { it > 0 }
        val finalTargetUid = bridge.getTargetUid().takeIf { it >= 0 }
        val finalStartTimeTicks = bridge.getTargetStartTimeTicks()
            .trim()
            .take(32)
            .takeIf(String::isNotEmpty)
        val targetInvalidatedDuringRead = targetPid != null &&
            (finalPackageName != packageName ||
                finalTargetPid != targetPid ||
                finalTargetUid != targetUid ||
                finalStartTimeTicks != targetStartTimeTicks)
        val effectiveSummary = if (targetInvalidatedDuringRead) {
            bridge.getTargetSummary()
        } else {
            summary
        }
        val effectivePid = targetPid.takeUnless { targetInvalidatedDuringRead }
        val effectiveUid = targetUid.takeUnless { targetInvalidatedDuringRead }
        val effectiveStartTime = targetStartTimeTicks.takeUnless { targetInvalidatedDuringRead }
        val effectiveEnabledIds = enabledIds
            .takeUnless { targetInvalidatedDuringRead }
            .orEmpty()
            .toMutableSet()
            .apply {
                if (antiFlash.requestedEnabled && antiFlash.workerRunning) {
                    add(ControlFeature.ANTI_FLASH.wireId)
                } else {
                    remove(ControlFeature.ANTI_FLASH.wireId)
                }
            }
        val effectiveSupportedIds = supportedIds.takeUnless { targetInvalidatedDuringRead }.orEmpty()
        return ControlTargetSnapshot(
            packageName = packageName,
            pid = effectivePid,
            targetUid = effectiveUid,
            startTimeTicks = effectiveStartTime,
            summary = effectiveSummary,
            features = ControlFeature.entries.associateWith { it.wireId in effectiveEnabledIds },
            supportedFeatures = ControlFeature.entries.filterTo(mutableSetOf()) {
                it.wireId in effectiveSupportedIds
            },
            nativeProbe = if (targetInvalidatedDuringRead) ControlNativeProbeState() else nativeProbe,
            readOnlyFields = if (targetInvalidatedDuringRead) {
                ControlReadOnlyFieldsState()
            } else {
                readOnlyFields
            },
            injection = if (targetInvalidatedDuringRead) ControlInjectionState() else injection,
            antiFlash = antiFlash,
        )
    }

    private fun readInjectionState(bridge: IControlBridge): ControlInjectionState {
        val profileStatus = ControlInjectionProfileStatus.fromWireValue(
            bridge.getInjectionProfileStatus(),
        ) ?: ControlInjectionProfileStatus.INVALID_RESPONSE
        val applyStatus = ControlInjectionApplyStatus.fromWireValue(
            bridge.getLastInjectionApplyStatus(),
        ) ?: ControlInjectionApplyStatus.INVALID_RESPONSE
        return ControlInjectionState(
            profileStatus = profileStatus,
            profileSummary = bridge.getInjectionProfileSummary().trim().take(256),
            profileId = bridge.getInjectionProfileId().trim().take(96),
            targetVersion = bridge.getInjectionTargetVersion().trim().take(64),
            requiredAbi = bridge.getInjectionRequiredAbi().trim().take(32),
            lastApplyStatus = applyStatus,
            lastFeature = ControlFeature.fromWireId(
                bridge.getLastInjectionFeatureId().trim().take(64),
            ),
            message = bridge.getLastInjectionMessage().trim().take(256),
        )
    }

    private fun readReadOnlyFields(
        bridge: IControlBridge,
        targetIsOpen: Boolean,
        readableDataEnabled: Boolean,
    ): ControlReadOnlyFieldsState {
        val baseState = readReadOnlyFieldProfileState(bridge)
        val profileStatus = baseState.profileStatus
        if (!targetIsOpen || profileStatus != ControlReadOnlyFieldProfileStatus.READY) {
            val message = baseState.profileSummary
            return baseState.copy(
                lifeState = ControlInt32FieldState(
                    status = ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                    message = message,
                ),
                killCount = ControlInt32FieldState(
                    status = ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                    message = message,
                ),
                dataLongSelector1 = ControlInt64FieldState(
                    status = ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY,
                    message = message,
                ),
            )
        }
        if (!readableDataEnabled) {
            return baseState.copy(
                lifeState = ControlInt32FieldState(
                    status = ControlReadOnlyFieldReadStatus.FEATURE_DISABLED,
                    message = "数据读取未启用",
                ),
                killCount = ControlInt32FieldState(
                    status = ControlReadOnlyFieldReadStatus.FEATURE_DISABLED,
                    message = "数据读取未启用",
                ),
                dataLongSelector1 = ControlInt64FieldState(
                    status = ControlReadOnlyFieldReadStatus.FEATURE_DISABLED,
                    message = "数据读取未启用",
                ),
            )
        }

        check(shouldRefreshReadOnlyFields(targetIsOpen, profileStatus, readableDataEnabled))
        bridge.refreshReadOnlyFields()
        val refreshedBaseState = readReadOnlyFieldProfileState(bridge)
        return refreshedBaseState.copy(
            lifeState = decodeInt32Field(
                statusWireValue = bridge.getLifeStateReadStatus(),
                hasValue = bridge.hasLifeStateValue(),
                value = { bridge.getLifeStateValue() },
                message = bridge.getLifeStateReadMessage(),
            ),
            killCount = decodeInt32Field(
                statusWireValue = bridge.getKillCountReadStatus(),
                hasValue = bridge.hasKillCountValue(),
                value = { bridge.getKillCountValue() },
                message = bridge.getKillCountReadMessage(),
            ),
            dataLongSelector1 = decodeInt64Field(
                statusWireValue = bridge.getDataLongSelector1ReadStatus(),
                hasValue = bridge.hasDataLongSelector1Value(),
                value = { bridge.getDataLongSelector1Value() },
                message = bridge.getDataLongSelector1ReadMessage(),
            ),
        )
    }

    private fun readReadOnlyFieldProfileState(
        bridge: IControlBridge,
    ): ControlReadOnlyFieldsState {
        val profileStatus = ControlReadOnlyFieldProfileStatus.fromWireValue(
            bridge.getReadOnlyFieldProfileStatus(),
        ) ?: ControlReadOnlyFieldProfileStatus.IDLE
        val profileSummary = bridge.getReadOnlyFieldProfileSummary().trim().take(256)
        return ControlReadOnlyFieldsState(
            profileStatus = profileStatus,
            profileSummary = profileSummary.ifBlank { "字段档案状态未知" },
            profileId = bridge.getReadOnlyFieldProfileId().trim().take(96),
            targetVersion = bridge.getReadOnlyFieldTargetVersion().trim().take(64),
        )
    }

    private fun decodeInt32Field(
        statusWireValue: String,
        hasValue: Boolean,
        value: () -> Int,
        message: String,
    ): ControlInt32FieldState {
        val decodedStatus = ControlReadOnlyFieldReadStatus.fromWireValue(statusWireValue)
            ?: ControlReadOnlyFieldReadStatus.READ_FAILED
        val resolvedStatus = if (decodedStatus == ControlReadOnlyFieldReadStatus.OK && !hasValue) {
            ControlReadOnlyFieldReadStatus.READ_FAILED
        } else {
            decodedStatus
        }
        return ControlInt32FieldState(
            status = resolvedStatus,
            value = if (resolvedStatus == ControlReadOnlyFieldReadStatus.OK && hasValue) value() else null,
            message = message.trim().take(256),
        )
    }

    private fun decodeInt64Field(
        statusWireValue: String,
        hasValue: Boolean,
        value: () -> Long,
        message: String,
    ): ControlInt64FieldState {
        val decodedStatus = ControlReadOnlyFieldReadStatus.fromWireValue(statusWireValue)
            ?: ControlReadOnlyFieldReadStatus.READ_FAILED
        val resolvedStatus = if (decodedStatus == ControlReadOnlyFieldReadStatus.OK && !hasValue) {
            ControlReadOnlyFieldReadStatus.READ_FAILED
        } else {
            decodedStatus
        }
        return ControlInt64FieldState(
            status = resolvedStatus,
            value = if (resolvedStatus == ControlReadOnlyFieldReadStatus.OK && hasValue) value() else null,
            message = message.trim().take(256),
        )
    }

    private fun handleConnectionLost(message: String) {
        val (connection, shouldNotify, shouldStopService) = synchronized(lock) {
            val hadConnection = remote != null || activeConnection != null
            val value = activeConnection
            remote = null
            activeConnection = null
            unlinkDeathRecipientLocked()
            val stop = serviceStarted
            serviceStarted = false
            Triple(value, hadConnection, stop)
        }
        connection?.let { value ->
            runOnMain { runCatching { appContext.unbindService(value) } }
        }
        if (shouldStopService) {
            runOnMain {
                runCatching { appContext.stopService(Intent().setComponent(expectedComponent)) }
            }
        }
        if (shouldNotify) connectionLostListener(message)
    }

    private fun clearConnection(connection: ServiceConnection) {
        synchronized(lock) {
            if (activeConnection === connection) {
                activeConnection = null
                remote = null
                unlinkDeathRecipientLocked()
            }
        }
        runOnMain { runCatching { appContext.unbindService(connection) } }
    }

    private fun unlinkDeathRecipientLocked() {
        linkedBinder?.let { binder ->
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        linkedBinder = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}

internal fun shouldRefreshReadOnlyFields(
    targetIsOpen: Boolean,
    profileStatus: ControlReadOnlyFieldProfileStatus,
    readableDataEnabled: Boolean,
): Boolean = targetIsOpen &&
    profileStatus == ControlReadOnlyFieldProfileStatus.READY &&
    readableDataEnabled

internal fun normalizePlayerPositionBridgeStatus(
    commandAccepted: Boolean,
    wireStatus: ControlInjectionApplyStatus?,
    hasAllEffectiveCoordinates: Boolean,
    appliedAxisCount: Int,
): ControlInjectionApplyStatus {
    val decodedStatus = wireStatus ?: return ControlInjectionApplyStatus.INVALID_RESPONSE
    val typedSuccess = decodedStatus == ControlInjectionApplyStatus.APPLIED ||
        decodedStatus == ControlInjectionApplyStatus.ALREADY_APPLIED
    return if (
        commandAccepted != typedSuccess ||
        typedSuccess && !hasAllEffectiveCoordinates ||
        appliedAxisCount !in 0..3
    ) {
        ControlInjectionApplyStatus.INVALID_RESPONSE
    } else {
        decodedStatus
    }
}

internal fun normalizeSearchIdBridgeResult(
    commandAccepted: Boolean,
    requestedId: Long,
    returnedRequestedId: Long,
    wireStatus: ControlSearchIdStatus?,
    slotIndex: Int?,
    invalidReason: ControlSearchIdInvalidReason?,
    processStartTimeTicks: String,
    message: String,
    slotCount: Int,
): ControlSearchIdResult {
    fun invalidResponse(detail: String): ControlSearchIdResult = ControlSearchIdResult(
        status = ControlSearchIdStatus.INVALID,
        requestedId = requestedId,
        processStartTimeTicks = processStartTimeTicks,
        invalidReason = ControlSearchIdInvalidReason.INVALID_RESPONSE,
        message = detail,
    )

    if (returnedRequestedId != requestedId) {
        return invalidResponse("SearchID bridge returned a mismatched request ID")
    }
    val status = wireStatus ?: return invalidResponse("SearchID bridge returned an unknown status")
    val shapeValid = when (status) {
        ControlSearchIdStatus.MATCH -> commandAccepted &&
            slotIndex != null && slotIndex in 0 until slotCount && invalidReason == null
        ControlSearchIdStatus.NOT_FOUND -> commandAccepted && slotIndex == null && invalidReason == null
        ControlSearchIdStatus.INVALID -> !commandAccepted && slotIndex == null && invalidReason != null
    }
    if (!shapeValid) return invalidResponse("SearchID bridge returned an inconsistent typed result")
    return ControlSearchIdResult(
        status = status,
        requestedId = requestedId,
        slotIndex = slotIndex,
        processStartTimeTicks = processStartTimeTicks,
        invalidReason = invalidReason,
        message = message,
    )
}
