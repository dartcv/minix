package me.dartcv.minix.root

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class RootFeatureService : Service() {
    private val serviceAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    private val processInspector = ProcTargetProcessInspector()
    private val targetPackageIdentityVerifier = AndroidTargetPackageIdentityVerifier(
        packageManagerProvider = { packageManager },
        expectedUid = Process.myUid(),
    )
    private val moduleSpecAnchorResolver = ProcRootSearchIdAnchorResolver(
        processInspector = processInspector,
        fingerprintProvider = ProcRootModuleFingerprintProvider,
    )
    private val searchIdScanner = RootSearchIdScanner(
        anchorResolver = moduleSpecAnchorResolver,
        seedResolver = JniRootSeedAddressResolver.instance,
        scalarReader = JniTargetScalarReader,
    )
    private val moduleSpecFieldAddressResolver =
        ProcRootModuleSpecFieldAddressResolver(moduleSpecAnchorResolver)
    private val antiFlashSupervisor = RootAntiFlashSupervisor(
        targetResolver = ProcRootAntiFlashTargetResolver(processInspector),
        backend = JniRootAntiFlashBackend,
    )
    private val antiFlashLaunchRequestResolver = ProcRootAntiFlashLaunchRequestResolver(
        processInspector = processInspector,
    )
    private val antiFlashTprtBootstrapResolver = ProcRootAntiFlashTprtBootstrapResolver(
        processInspector = processInspector,
    )
    private val antiFlashTprtBootstrap = RootAntiFlashTprtBootstrap(
        backend = JniRootAntiFlashBackend,
    )
    private val antiFlashCommandLock = Any()
    private val antiFlashWorkerLock = Any()
    private val antiFlashStopRequested = AtomicBoolean(false)
    @Volatile
    private var antiFlashWorker: Thread? = null
    @Volatile
    private var antiFlashSessionWorker: Thread? = null
    @Volatile
    private var antiFlashBootstrapState = RootAntiFlashState(
        profileId = RootAntiFlashProfileCatalog.profile.profileId,
    )
    private val targetSession = RootTargetSession(
        inspector = processInspector,
        packageIdentityVerifier = targetPackageIdentityVerifier,
        memoryProbe = JniTargetMemoryProbe,
        scalarReader = JniTargetScalarReader,
        addressRecipeResolver = JniRootAddressRecipeResolver.create(moduleSpecAnchorResolver),
        moduleSpecFieldAddressResolver = moduleSpecFieldAddressResolver,
        searchIdScanner = searchIdScanner,
        serviceAbi = serviceAbi,
        injectionExecutor = JniRootInjectionExecutor,
        expectedTargetUid = Process.myUid(),
    )
    /**
     * Gated head-spin supervisor.  It is intentionally not exposed through
     * RootFeature/AIDL yet; keeping the instance here gives target/session
     * teardown one owner and prevents a future surface from accidentally
     * creating a detached worker.
     */
    private val headSpinSupervisor = RootHeadSpinSupervisor(
        targetResolver = RootHeadSpinExactShaTargetResolver(
            JniRootAddressRecipeResolver.create(moduleSpecAnchorResolver),
        ),
        backend = JniRootHeadSpinBackend,
    )
    @Volatile
    private var cachedReadOnlyFields = RootReadOnlyFieldsState()
    @Volatile
    private var cachedPlayerPositionResult: RootPlayerPositionResult? = null
    @Volatile
    private var cachedSearchIdResult: RootSearchIdResult? = null

    private val binder = object : IRootFeatureBridge.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val callingUid = Binder.getCallingUid()
            if (callingUid != Process.myUid()) {
                throw RemoteException(
                    "Control service rejected Binder caller UID $callingUid",
                )
            }
            return super.onTransact(code, data, reply, flags)
        }

        override fun getProtocolVersion(): Int = RootProtocol.VERSION

        override fun getUid(): Int = Process.myUid()

        override fun getServicePid(): Int = Process.myPid()

        override fun getAbi(): String = serviceAbi

        override fun getServiceProcessName(): String =
            processInspector.readProcessName(Process.myPid()).orEmpty()

        override fun findTargetPid(packageName: String): Int =
            targetSession.findTargetPid(packageName) ?: INVALID_PID

        override fun readProcessName(pid: Int): String =
            targetSession.readProcessName(pid).orEmpty()

        override fun verifyTargetProcess(pid: Int, packageName: String): Boolean =
            targetSession.verifyTargetProcess(pid, packageName)

        override fun openOrRefreshTarget(packageName: String): Boolean =
            synchronized(antiFlashCommandLock) {
                val normalized = RootProtocol.normalizePackageName(packageName)
                val currentTarget = targetSession.snapshot()
                val antiFlash = antiFlashStateSnapshot()
                if (currentTarget.packageName == normalized && currentTarget.pid != null &&
                    antiFlash.requestedEnabled && antiFlash.workerRunning
                ) {
                    if (!stopHeadSpinIfActiveLocked()) return@synchronized false
                    return@synchronized true
                }
                if (!stopHeadSpinIfActiveLocked()) return@synchronized false
                if (!stopAntiFlashIfActiveLocked().isFullyReleased()) {
                    return@synchronized false
                }
                resetReadOnlyFieldCache()
                resetPlayerPositionCache()
                resetSearchIdCache()
                targetSession.openOrRefresh(packageName)
            }

        override fun closeTarget() {
            synchronized(antiFlashCommandLock) {
                if (!stopHeadSpinIfActiveLocked()) return@synchronized
                if (!stopAntiFlashIfActiveLocked().isFullyReleased()) return@synchronized
                resetReadOnlyFieldCache()
                resetPlayerPositionCache()
                resetSearchIdCache()
                targetSession.close()
            }
        }

        override fun getTargetPackage(): String = targetSession.snapshot().packageName

        override fun getTargetPid(): Int = targetSession.snapshot().pid ?: INVALID_PID

        override fun getTargetUid(): Int = targetSession.snapshot().targetUid ?: INVALID_UID

        override fun getTargetStartTimeTicks(): String =
            targetSession.snapshot().startTimeTicks.orEmpty()

        override fun getTargetSummary(): String {
            val value = targetSession.refreshSummary()
            if (targetSession.snapshot().pid == null) {
                synchronized(antiFlashCommandLock) {
                    stopHeadSpinIfActiveLocked()
                    if (!isAntiFlashEnabled()) stopAntiFlashIfActiveLocked()
                }
            }
            return value
        }

        override fun getTargetProbeStatus(): String =
            targetSession.snapshot().nativeProbe.status.name

        override fun getTargetProbeMessage(): String =
            targetSession.snapshot().nativeProbe.message

        override fun getTargetProbeRegionCount(): Int =
            targetSession.snapshot().nativeProbe.regionCount

        override fun getTargetProbeModuleCount(): Int =
            targetSession.snapshot().nativeProbe.moduleCount

        override fun getTargetProbeMemoryReadableModuleCount(): Int =
            targetSession.snapshot().nativeProbe.memoryReadableModuleCount

        override fun getTargetProbeMemoryReadBytes(): Long =
            targetSession.snapshot().nativeProbe.memoryReadBytes

        override fun getTargetProbeMemoryElfHeaderCount(): Int =
            targetSession.snapshot().nativeProbe.memoryElfHeaderCount

        override fun getTargetProbeFingerprint(): String =
            targetSession.snapshot().nativeProbe.mapsFingerprint

        override fun isTargetProbeTruncated(): Boolean =
            targetSession.snapshot().nativeProbe.truncated

        override fun getTargetProbeModules(): Array<String> =
            targetSession.snapshot().nativeProbe.modules.toTypedArray()

        override fun getReadOnlyFieldProfileStatus(): String =
            targetSession.fieldProfileState().status.name

        override fun getReadOnlyFieldProfileSummary(): String =
            targetSession.fieldProfileState().summary

        override fun getReadOnlyFieldProfileId(): String =
            targetSession.fieldProfileState().profileId

        override fun getReadOnlyFieldTargetVersion(): String =
            targetSession.fieldProfileState().targetVersion

        override fun refreshReadOnlyFields(): Boolean {
            val batch = targetSession.readVisibleFields()
            cachedReadOnlyFields = batch.toPublicState()
            return batch.hasAllDefinedFieldsAvailable()
        }

        override fun getLifeStateReadStatus(): String =
            cachedReadOnlyFields.lifeState.status.name

        override fun hasLifeStateValue(): Boolean =
            cachedReadOnlyFields.lifeState.isAvailable

        override fun getLifeStateValue(): Int =
            cachedReadOnlyFields.lifeState.value ?: 0

        override fun getLifeStateReadMessage(): String =
            cachedReadOnlyFields.lifeState.message

        override fun getKillCountReadStatus(): String =
            cachedReadOnlyFields.killCount.status.name

        override fun hasKillCountValue(): Boolean =
            cachedReadOnlyFields.killCount.isAvailable

        override fun getKillCountValue(): Int =
            cachedReadOnlyFields.killCount.value ?: 0

        override fun getKillCountReadMessage(): String =
            cachedReadOnlyFields.killCount.message

        override fun getDataLongSelector1ReadStatus(): String =
            cachedReadOnlyFields.dataLongSelector1.status.name

        override fun hasDataLongSelector1Value(): Boolean =
            cachedReadOnlyFields.dataLongSelector1.isAvailable

        override fun getDataLongSelector1Value(): Long =
            cachedReadOnlyFields.dataLongSelector1.value ?: 0L

        override fun getDataLongSelector1ReadMessage(): String =
            cachedReadOnlyFields.dataLongSelector1.message

        override fun getInjectionProfileStatus(): String =
            targetSession.injectionState().profileStatus.name

        override fun getInjectionProfileSummary(): String =
            targetSession.injectionState().profileSummary

        override fun getInjectionProfileId(): String =
            targetSession.injectionState().profileId

        override fun getInjectionTargetVersion(): String =
            targetSession.injectionState().targetVersion

        override fun getInjectionRequiredAbi(): String =
            targetSession.injectionState().requiredAbi

        override fun getLastInjectionApplyStatus(): String =
            targetSession.injectionState().lastApplyStatus.name

        override fun getLastInjectionFeatureId(): String =
            targetSession.injectionState().lastFeature?.wireId.orEmpty()

        override fun getLastInjectionMessage(): String =
            targetSession.injectionState().message

        override fun armAntiFlashForPackage(packageName: String): Boolean =
            synchronized(antiFlashCommandLock) {
                val normalized = RootProtocol.normalizePackageName(packageName)
                if (!RootProtocol.isValidPackageName(normalized)) return@synchronized false
                if (!stopHeadSpinIfActiveLocked()) return@synchronized false
                if (!stopAntiFlashIfActiveLocked().isFullyReleased()) return@synchronized false
                resetReadOnlyFieldCache()
                resetPlayerPositionCache()
                resetSearchIdCache()
                targetSession.openOrRefresh(normalized)
                startAntiFlash()
            }

        override fun getAntiFlashStateJson(): String =
            antiFlashStateSnapshot().toWireJson()

        override fun setPlayerPosition(x: Int, y: Int, z: Int): Boolean {
            val result = targetSession.setPlayerPosition(
                RootPlayerPositionRequest(x = x, y = y, z = z),
            )
            cachedPlayerPositionResult = result
            return result.isSuccess
        }

        override fun getLastPlayerPositionStatus(): String =
            cachedPlayerPositionResult?.status?.name ?: RootInjectionApplyStatus.IDLE.name

        override fun hasLastPlayerPositionX(): Boolean =
            cachedPlayerPositionResult?.effectiveX != null

        override fun getLastPlayerPositionX(): Int =
            cachedPlayerPositionResult?.effectiveX ?: 0

        override fun hasLastPlayerPositionY(): Boolean =
            cachedPlayerPositionResult?.effectiveY != null

        override fun getLastPlayerPositionY(): Int =
            cachedPlayerPositionResult?.effectiveY ?: 0

        override fun hasLastPlayerPositionZ(): Boolean =
            cachedPlayerPositionResult?.effectiveZ != null

        override fun getLastPlayerPositionZ(): Int =
            cachedPlayerPositionResult?.effectiveZ ?: 0

        override fun getLastPlayerPositionAppliedAxisCount(): Int =
            cachedPlayerPositionResult?.appliedAxisCount ?: 0

        override fun getLastPlayerPositionProfileId(): String =
            cachedPlayerPositionResult?.profileId.orEmpty()

        override fun getLastPlayerPositionMessage(): String =
            cachedPlayerPositionResult?.message.orEmpty()

        override fun searchId(requestedId: Long): Boolean {
            val result = targetSession.searchId(requestedId)
            cachedSearchIdResult = result
            return result.status != RootSearchIdStatus.INVALID
        }

        override fun getLastSearchIdStatus(): String =
            cachedSearchIdResult?.status?.name.orEmpty()

        override fun getLastSearchIdRequestedId(): Long =
            cachedSearchIdResult?.requestedId ?: 0L

        override fun hasLastSearchIdSlotIndex(): Boolean =
            cachedSearchIdResult?.slotIndex != null

        override fun getLastSearchIdSlotIndex(): Int =
            cachedSearchIdResult?.slotIndex ?: INVALID_SLOT_INDEX

        override fun getLastSearchIdInvalidReason(): String =
            cachedSearchIdResult?.invalidReason?.name.orEmpty()

        override fun getLastSearchIdProcessStartTimeTicks(): String =
            cachedSearchIdResult?.processStartTimeTicks.orEmpty()

        override fun getLastSearchIdMessage(): String =
            cachedSearchIdResult?.message.orEmpty()

        override fun setFeatureEnabled(featureId: String, enabled: Boolean): Boolean {
            val feature = RootFeature.fromWireId(featureId) ?: return false
            resetReadOnlyFieldCache()
            if (feature == RootFeature.ANTI_FLASH) {
                return if (enabled) startAntiFlash() else {
                    stopAntiFlashIfActive().isFullyReleased()
                }
            }
            return targetSession.setFeatureEnabled(feature, enabled)
        }

        override fun isFeatureEnabled(featureId: String): Boolean {
            val feature = RootFeature.fromWireId(featureId) ?: return false
            if (feature == RootFeature.ANTI_FLASH) return isAntiFlashEnabled()
            return targetSession.isFeatureEnabled(feature)
        }

        override fun getEnabledFeatureIds(): Array<String> = buildList {
            addAll(targetSession.enabledFeatureIds())
            if (isAntiFlashEnabled()) add(RootFeature.ANTI_FLASH.wireId)
        }.toTypedArray()

        override fun getSupportedFeatureIds(): Array<String> = targetSession.supportedFeatureIds()
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.component?.className == RootFeatureService::class.java.name) binder else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        synchronized(antiFlashCommandLock) {
            stopHeadSpinIfActiveLocked()
            stopAntiFlashIfActiveLocked()
            resetReadOnlyFieldCache()
            resetPlayerPositionCache()
            resetSearchIdCache()
            targetSession.close()
        }
        super.onDestroy()
    }

    private fun resetReadOnlyFieldCache() {
        cachedReadOnlyFields = RootReadOnlyFieldsState()
    }

    private fun resetPlayerPositionCache() {
        cachedPlayerPositionResult = null
    }

    private fun resetSearchIdCache() {
        cachedSearchIdResult = null
    }

    private fun startAntiFlash(): Boolean = synchronized(antiFlashCommandLock) {
        if (isAntiFlashEnabled()) return true
        stopAntiFlashIfActiveLocked()
        val readyRequest = targetSession.antiFlashStartRequest()
        if (targetSession.isAntiFlashProfileReady() && readyRequest != null) {
            return startResolvedAntiFlashLocked(readyRequest)
        }

        val packageName = targetSession.snapshot().packageName
        if (!RootProtocol.isValidPackageName(packageName)) {
            publishAntiFlashBootstrapFailure(
                RootAntiFlashStatus.PREFLIGHT_FAILED,
                "Select a target package before arming anti-flash",
            )
            return false
        }
        val packageIdentity = targetPackageIdentityVerifier.verify(packageName)
        if (!packageIdentity.isValid) {
            publishAntiFlashBootstrapFailure(
                RootAntiFlashStatus.PROFILE_MISMATCH,
                packageIdentity.message,
            )
            return false
        }
        val installedProfile = verifyInstalledAntiFlashProfile(packageName)
        if (!installedProfile.isSuccess) {
            publishAntiFlashBootstrapFailure(
                RootAntiFlashStatus.PROFILE_MISMATCH,
                installedProfile.message,
            )
            return false
        }
        JniRootAntiFlashBackend.warmUp()?.let { error ->
            publishAntiFlashBootstrapFailure(
                RootAntiFlashStatus.BACKEND_UNAVAILABLE,
                error.message ?: "Anti-flash native backend failed to load",
            )
            return false
        }

        antiFlashStopRequested.set(false)
        antiFlashBootstrapState = RootAntiFlashState(
            status = RootAntiFlashStatus.WAITING_FOR_TARGET,
            requestedEnabled = true,
            workerRunning = true,
            profileId = RootAntiFlashProfileCatalog.profile.profileId,
            message = "Anti-flash launch watcher is armed",
        )
        val worker = Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                runAntiFlashLaunchLoop(
                    packageName = packageName,
                    installedProfile = requireNotNull(installedProfile.profile),
                )
            },
            ANTI_FLASH_THREAD_NAME,
        ).apply { isDaemon = true }
        synchronized(antiFlashWorkerLock) { antiFlashWorker = worker }
        worker.start()
        true
    }

    private fun startResolvedAntiFlashLocked(request: RootAntiFlashStartRequest): Boolean {
        val started = antiFlashSupervisor.start(request)
        if (!started.requestedEnabled || !started.workerRunning) return false
        antiFlashStopRequested.set(false)
        antiFlashBootstrapState = started
        val worker = Thread(::runResolvedAntiFlashLoop, ANTI_FLASH_THREAD_NAME).apply {
            isDaemon = true
        }
        synchronized(antiFlashWorkerLock) { antiFlashWorker = worker }
        worker.start()
        return true
    }

    private fun runResolvedAntiFlashLoop() {
        try {
            while (!antiFlashStopRequested.get()) {
                val cycle = antiFlashSupervisor.runOneCycle()
                antiFlashBootstrapState = cycle
                if (!cycle.requestedEnabled || !cycle.workerRunning) break
            }
        } finally {
            antiFlashBootstrapState = if (antiFlashSupervisor.stateSnapshot().applied) {
                antiFlashSupervisor.stopAndRollback()
            } else {
                antiFlashSupervisor.stateSnapshot()
            }
            synchronized(antiFlashWorkerLock) {
                if (antiFlashWorker === Thread.currentThread()) antiFlashWorker = null
            }
        }
    }

    private fun runAntiFlashLaunchLoop(
        packageName: String,
        installedProfile: RootAntiFlashInstalledProfile,
    ) {
        try {
            while (!antiFlashStopRequested.get()) {
                val pid = processInspector.findPid(packageName)
                if (pid == null) {
                    val provisional = releaseExitedTprtBootstrapIfNeeded()
                    antiFlashBootstrapState = if (provisional.applied) {
                        provisional.toPublicState(
                            requestedEnabled = true,
                            workerRunning = true,
                            forceStarting = true,
                            message = "tprt bootstrap is active; waiting to reacquire the game process",
                        )
                    } else {
                        antiFlashBootstrapState.copy(
                            status = RootAntiFlashStatus.WAITING_FOR_TARGET,
                            requestedEnabled = true,
                            workerRunning = true,
                            applied = false,
                            targetPid = null,
                            targetStartTimeTicks = "",
                            message = "Anti-flash is waiting for the game process",
                        )
                    }
                    Thread.sleep(ANTI_FLASH_LAUNCH_PID_POLL_MILLIS)
                    continue
                }
                val startTimeTicks = processInspector.readStartTimeTicks(pid).orEmpty()
                antiFlashBootstrapState = antiFlashBootstrapState.copy(
                    status = RootAntiFlashStatus.STARTING,
                    targetPid = pid,
                    targetStartTimeTicks = startTimeTicks,
                    message = "Game found; waiting for the tprt bootstrap mapping",
                )

                val tprtResolution = antiFlashTprtBootstrapResolver.resolve(pid, installedProfile)
                val tprtTarget = tprtResolution.target
                if (!tprtResolution.isSuccess || tprtTarget == null) {
                    if (!processInspector.isAlive(pid)) {
                        releaseExitedTprtBootstrapIfNeeded()
                    }
                    antiFlashBootstrapState = antiFlashBootstrapState.copy(
                        status = when (tprtResolution.status) {
                            RootAntiFlashResolveStatus.MODULE_NOT_FOUND,
                            RootAntiFlashResolveStatus.MAPS_UNAVAILABLE,
                            RootAntiFlashResolveStatus.TARGET_CHANGED -> RootAntiFlashStatus.STARTING
                            else -> tprtResolution.status.toBootstrapPublicStatus()
                        },
                        requestedEnabled = true,
                        workerRunning = true,
                        targetPid = pid,
                        targetStartTimeTicks = startTimeTicks,
                        message = tprtResolution.message.ifBlank {
                            "Waiting for the tprt bootstrap mapping"
                        },
                    )
                    if (tprtResolution.status in setOf(
                            RootAntiFlashResolveStatus.INVALID_REQUEST,
                            RootAntiFlashResolveStatus.MODULE_AMBIGUOUS,
                            RootAntiFlashResolveStatus.FINGERPRINT_MISMATCH,
                            RootAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE,
                        )
                    ) {
                        break
                    }
                    Thread.sleep(ANTI_FLASH_LAUNCH_MAP_POLL_MILLIS)
                    continue
                }

                val provisional = antiFlashTprtBootstrap.ensurePatched(tprtTarget)
                antiFlashBootstrapState = provisional.toPublicState(
                    requestedEnabled = true,
                    workerRunning = true,
                    forceStarting = provisional.isPatched,
                    message = if (provisional.isPatched) {
                        "15 tprt bootstrap writes verified; waiting for GameApp and BSS"
                    } else {
                        provisional.message
                    },
                )
                if (!provisional.isPatched) {
                    if (!provisional.applied && provisional.status == RootAntiFlashStatus.TARGET_CHANGED) {
                        antiFlashTprtBootstrap.rollback()
                    }
                    if (provisional.applied || provisional.status in setOf(
                            RootAntiFlashStatus.PROFILE_MISMATCH,
                            RootAntiFlashStatus.BACKEND_UNAVAILABLE,
                            RootAntiFlashStatus.ROLLBACK_FAILED,
                        )
                    ) {
                        break
                    }
                    Thread.sleep(ANTI_FLASH_LAUNCH_MAP_POLL_MILLIS)
                    continue
                }

                val request = antiFlashLaunchRequestResolver.resolve(pid, installedProfile)
                if (request == null) {
                    if (!processInspector.isAlive(pid)) {
                        val released = antiFlashTprtBootstrap.rollback()
                        antiFlashBootstrapState = released.toPublicState(
                            requestedEnabled = true,
                            workerRunning = true,
                            forceStarting = false,
                            message = "Game exited before GameApp/BSS became ready",
                        )
                    }
                    Thread.sleep(ANTI_FLASH_LAUNCH_MAP_POLL_MILLIS)
                    continue
                }

                val started = antiFlashSupervisor.start(request)
                antiFlashBootstrapState = started
                if (!started.requestedEnabled || !started.workerRunning) {
                    if (started.status == RootAntiFlashStatus.PROFILE_MISMATCH) break
                    Thread.sleep(ANTI_FLASH_LAUNCH_MAP_POLL_MILLIS)
                    continue
                }

                val firstCycle = antiFlashSupervisor.runOneCycle()
                antiFlashBootstrapState = firstCycle
                if (!firstCycle.requestedEnabled || !firstCycle.workerRunning) break
                val handedOff = antiFlashTprtBootstrap.handoff(request)
                if (!handedOff.handedOff) {
                    antiFlashSupervisor.requestStop("tprt bootstrap handoff failed")
                    antiFlashBootstrapState = antiFlashSupervisor.stopAndRollback()
                    break
                }
                launchTargetSessionOpen(packageName, request.pid, request.startTimeTicks)
                while (!antiFlashStopRequested.get()) {
                    val cycle = antiFlashSupervisor.runOneCycle()
                    antiFlashBootstrapState = cycle
                    if (!cycle.requestedEnabled || !cycle.workerRunning) break
                }
                break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            val supervisorFinal = if (antiFlashSupervisor.stateSnapshot().applied) {
                antiFlashSupervisor.stopAndRollback()
            } else {
                antiFlashSupervisor.stateSnapshot()
            }
            val provisionalFinal = antiFlashTprtBootstrap.rollback()
            antiFlashBootstrapState = when {
                !supervisorFinal.isFullyReleased() -> supervisorFinal
                provisionalFinal.applied -> provisionalFinal.toPublicState(
                    requestedEnabled = false,
                    workerRunning = false,
                )
                else -> supervisorFinal
            }
            synchronized(antiFlashWorkerLock) {
                if (antiFlashWorker === Thread.currentThread()) antiFlashWorker = null
            }
            if (antiFlashStopRequested.get() && antiFlashSupervisor.stateSnapshot().isFullyReleased() &&
                !antiFlashTprtBootstrap.stateSnapshot().applied
            ) {
                antiFlashBootstrapState = RootAntiFlashState(
                    status = RootAntiFlashStatus.STOPPED,
                    profileId = RootAntiFlashProfileCatalog.profile.profileId,
                    message = "Anti-flash stopped",
                )
            }
        }
    }

    private fun releaseExitedTprtBootstrapIfNeeded(): RootAntiFlashTprtBootstrapState {
        val current = antiFlashTprtBootstrap.stateSnapshot()
        val targetPid = current.targetPid
        return if (targetPid != null && !processInspector.isAlive(targetPid)) {
            antiFlashTprtBootstrap.rollback()
        } else {
            current
        }
    }

    private fun RootAntiFlashTprtBootstrapState.toPublicState(
        requestedEnabled: Boolean,
        workerRunning: Boolean,
        forceStarting: Boolean = false,
        message: String = this.message,
    ): RootAntiFlashState = RootAntiFlashState(
        status = if (forceStarting && isPatched) RootAntiFlashStatus.STARTING else status,
        requestedEnabled = requestedEnabled,
        workerRunning = workerRunning,
        applied = applied,
        iterationCount = iterationCount,
        successfulWriteCount = successfulWriteCount,
        lastFailureIndex = lastFailureIndex,
        targetPid = targetPid,
        targetStartTimeTicks = targetStartTimeTicks,
        profileId = RootAntiFlashProfileCatalog.profile.profileId,
        message = message.take(256),
    )

    private fun RootAntiFlashResolveStatus.toBootstrapPublicStatus(): RootAntiFlashStatus = when (this) {
        RootAntiFlashResolveStatus.RESOLVED -> RootAntiFlashStatus.STARTING
        RootAntiFlashResolveStatus.INVALID_REQUEST -> RootAntiFlashStatus.PREFLIGHT_FAILED
        RootAntiFlashResolveStatus.TARGET_CHANGED -> RootAntiFlashStatus.TARGET_CHANGED
        RootAntiFlashResolveStatus.MAPS_UNAVAILABLE,
        RootAntiFlashResolveStatus.MODULE_NOT_FOUND -> RootAntiFlashStatus.WAITING_FOR_TARGET
        RootAntiFlashResolveStatus.MODULE_AMBIGUOUS,
        RootAntiFlashResolveStatus.FINGERPRINT_MISMATCH -> RootAntiFlashStatus.PROFILE_MISMATCH
        RootAntiFlashResolveStatus.ADDRESS_OUT_OF_RANGE -> RootAntiFlashStatus.PREFLIGHT_FAILED
    }

    private fun launchTargetSessionOpen(
        packageName: String,
        expectedPid: Int,
        expectedStartTimeTicks: String,
    ) {
        if (antiFlashSessionWorker?.isAlive == true) return
        antiFlashSessionWorker = Thread(
            {
                val opened = targetSession.openOrRefresh(packageName)
                val snapshot = targetSession.snapshot()
                if (!opened || snapshot.pid != expectedPid ||
                    snapshot.startTimeTicks != expectedStartTimeTicks
                ) {
                    return@Thread
                }
            },
            ANTI_FLASH_SESSION_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyInstalledAntiFlashProfile(
        packageName: String,
    ): RootAntiFlashInstalledProfileResult = runCatching {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        verifyRootAntiFlashInstalledProfile(applicationInfo.nativeLibraryDir.orEmpty())
    }.getOrElse { error ->
        RootAntiFlashInstalledProfileResult(
            message = error.message ?: "Unable to inspect installed anti-flash modules",
        )
    }

    private fun publishAntiFlashBootstrapFailure(
        status: RootAntiFlashStatus,
        message: String,
    ) {
        antiFlashBootstrapState = RootAntiFlashState(
            status = status,
            profileId = RootAntiFlashProfileCatalog.profile.profileId,
            message = message.take(256),
        )
    }

    private fun stopAntiFlashIfActive(): RootAntiFlashState =
        synchronized(antiFlashCommandLock) { stopAntiFlashIfActiveLocked() }

    /** Stops the gated head-spin owner before changing or closing a target. */
    private fun stopHeadSpinIfActiveLocked(): Boolean {
        val before = headSpinSupervisor.stateSnapshot()
        if (!before.requestedEnabled && !before.workerRunning && !before.applied) return true
        val after = headSpinSupervisor.stopAndRollback()
        return !after.requestedEnabled && !after.workerRunning && !after.applied
    }

    private fun stopAntiFlashIfActiveLocked(): RootAntiFlashState {
        val before = antiFlashStateSnapshot()
        val worker = synchronized(antiFlashWorkerLock) { antiFlashWorker }
        if (worker == null && before.status in setOf(
                RootAntiFlashStatus.IDLE,
                RootAntiFlashStatus.STOPPED,
            )
        ) {
            return before
        }

        antiFlashStopRequested.set(true)
        antiFlashBootstrapState = before.copy(
            status = RootAntiFlashStatus.STOPPING,
            requestedEnabled = false,
            message = "Stopping anti-flash",
        )
        antiFlashSupervisor.requestStop()
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(ANTI_FLASH_STOP_JOIN_MILLIS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        if (worker?.isAlive == true) {
            return antiFlashStateSnapshot().copy(
                status = RootAntiFlashStatus.STOPPING,
                message = "防闪 worker 正在完成当前事务并回滚",
            )
        }
        synchronized(antiFlashWorkerLock) {
            if (antiFlashWorker === worker && worker?.isAlive != true) antiFlashWorker = null
        }
        val supervisorState = antiFlashSupervisor.stateSnapshot()
        val released = if (supervisorState.isFullyReleased()) {
            supervisorState
        } else {
            antiFlashSupervisor.stopAndRollback()
        }
        if (!released.isFullyReleased()) return released
        val provisionalReleased = antiFlashTprtBootstrap.rollback()
        if (provisionalReleased.applied) {
            antiFlashBootstrapState = provisionalReleased.toPublicState(
                requestedEnabled = false,
                workerRunning = false,
            )
            return antiFlashBootstrapState
        }
        antiFlashBootstrapState = RootAntiFlashState(
            status = RootAntiFlashStatus.STOPPED,
            profileId = RootAntiFlashProfileCatalog.profile.profileId,
            message = released.message.ifBlank { "Anti-flash stopped" },
        )
        return antiFlashBootstrapState
    }

    private fun isAntiFlashEnabled(): Boolean {
        val state = antiFlashStateSnapshot()
        return state.requestedEnabled && state.workerRunning
    }

    private fun antiFlashStateSnapshot(): RootAntiFlashState {
        val supervisor = antiFlashSupervisor.stateSnapshot()
        if (supervisor.requestedEnabled || supervisor.workerRunning || supervisor.applied) {
            return supervisor
        }
        val provisional = antiFlashTprtBootstrap.stateSnapshot()
        if (provisional.applied || provisional.status == RootAntiFlashStatus.ROLLBACK_FAILED) {
            return provisional.toPublicState(
                requestedEnabled = antiFlashBootstrapState.requestedEnabled,
                workerRunning = antiFlashBootstrapState.workerRunning,
            )
        }
        val bootstrap = antiFlashBootstrapState
        return if (bootstrap.requestedEnabled || bootstrap.workerRunning ||
            bootstrap.status !in setOf(RootAntiFlashStatus.IDLE, RootAntiFlashStatus.STOPPED)
        ) {
            bootstrap
        } else {
            supervisor
        }
    }

    private fun RootAntiFlashState.isFullyReleased(): Boolean =
        !requestedEnabled && !workerRunning && !applied

    private companion object {
        const val INVALID_PID = -1
        const val INVALID_UID = -1
        const val INVALID_SLOT_INDEX = -1
        const val ANTI_FLASH_THREAD_NAME = "minix-anti-flash"
        const val ANTI_FLASH_SESSION_THREAD_NAME = "minix-target-open"
        const val ANTI_FLASH_STOP_JOIN_MILLIS = 1_500L
        const val ANTI_FLASH_LAUNCH_PID_POLL_MILLIS = 10L
        const val ANTI_FLASH_LAUNCH_MAP_POLL_MILLIS = 5L
    }
}

internal fun RootReadOnlyFieldBatch.toPublicState(): RootReadOnlyFieldsState =
    RootReadOnlyFieldsState(
        profileStatus = profileState.status,
        profileSummary = profileState.summary,
        profileId = profileState.profileId,
        targetVersion = profileState.targetVersion,
        lifeState = lifeState.toInt32State(),
        killCount = killCount.toInt32State(),
        dataLongSelector1 = dataLongSelector1.toInt64State(),
    )

internal fun RootReadOnlyFieldBatch.hasAllDefinedFieldsAvailable(): Boolean {
    if (!profileState.isReady) return false
    return profileState.fieldIds.all { fieldId ->
        when (fieldId) {
            RootReadOnlyFieldId.LIFE_STATE -> lifeState.isSuccess
            RootReadOnlyFieldId.KILL_COUNT -> killCount.isSuccess
            RootReadOnlyFieldId.DATA_LONG_SELECTOR_1 -> dataLongSelector1.isSuccess
        }
    }
}

internal fun RootReadOnlyFieldReadResult.toInt32State(): RootInt32FieldState {
    val typedValue = value as? RootInt32FieldValue
    val resolvedStatus = if (status == RootReadOnlyFieldReadStatus.OK && typedValue == null) {
        RootReadOnlyFieldReadStatus.READ_FAILED
    } else {
        status
    }
    return RootInt32FieldState(
        status = resolvedStatus,
        value = typedValue?.value,
        message = message.take(256),
    )
}

internal fun RootReadOnlyFieldReadResult.toInt64State(): RootInt64FieldState {
    val typedValue = value as? RootInt64FieldValue
    val resolvedStatus = if (status == RootReadOnlyFieldReadStatus.OK && typedValue == null) {
        RootReadOnlyFieldReadStatus.READ_FAILED
    } else {
        status
    }
    return RootInt64FieldState(
        status = resolvedStatus,
        value = typedValue?.value,
        message = message.take(256),
    )
}
