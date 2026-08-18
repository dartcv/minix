package me.dartcv.minix.control

enum class ControlConnectionStatus {
    IDLE,
    REQUESTING,
    CONNECTING,
    READY,
    UNAVAILABLE,
    DENIED,
    ERROR,
}

enum class ControlFeature(
    val wireId: String,
    val label: String,
) {
    AIM("aim", "瞄准"),
    DRAW("draw", "绘制"),
    FLIGHT("flight", "飞行"),
    FAKE_FLIGHT("fake_flight", "模拟飞行"),
    PLAYER_TELEPORT("player_teleport", "玩家传送"),
    HITBOX("hitbox", "命中区域"),
    ANTI_FLASH("anti_flash", "防闪"),
    READABLE_DATA("readable_data", "数据读取"),
    ;

    companion object {
        fun fromWireId(value: String): ControlFeature? = entries.firstOrNull { it.wireId == value }
    }
}

data class ControlRuntimeState(
    val status: ControlConnectionStatus = ControlConnectionStatus.IDLE,
    val message: String = "本地控制服务尚未连接",
    val uid: Int? = null,
    val servicePid: Int? = null,
    val abi: String? = null,
    val targetPackage: String = ControlTargetCatalog.default.packageName,
    val targetPid: Int? = null,
    val targetUid: Int? = null,
    val targetStartTimeTicks: String? = null,
    val targetSummary: String = "等待打开目标：${ControlTargetCatalog.default.packageName}",
    val features: Map<ControlFeature, Boolean> = defaultControlFeatureStates(),
    val supportedFeatures: Set<ControlFeature> = emptySet(),
    val nativeProbe: ControlNativeProbeState = ControlNativeProbeState(),
    val readOnlyFields: ControlReadOnlyFieldsState = ControlReadOnlyFieldsState(),
    val injection: ControlInjectionState = ControlInjectionState(),
    val antiFlash: ControlAntiFlashState = ControlAntiFlashState(),
    /** User intent is kept across a game restart so the worker can re-arm when the profile returns. */
    val antiFlashArmed: Boolean = false,
    val playerPosition: ControlPlayerPositionResult? = null,
    val searchIdResult: ControlSearchIdResult? = null,
    val protocolVersion: Int? = null,
    val serviceProcessName: String? = null,
) {
    val hasVerifiedTargetIdentity: Boolean
        get() = targetPid != null &&
            targetUid != null &&
            targetUid == uid &&
            !targetStartTimeTicks.isNullOrBlank()
}

internal fun ControlInjectionState.hasBlockingRuntimeFailureFor(feature: ControlFeature): Boolean =
    lastFeature == feature && when (lastApplyStatus) {
        ControlInjectionApplyStatus.IDLE,
        ControlInjectionApplyStatus.APPLIED,
        ControlInjectionApplyStatus.ALREADY_APPLIED,
        -> false

        else -> true
    }

internal data class ControlBridgeInfo(
    val protocolVersion: Int,
    val uid: Int,
    val servicePid: Int,
    val abi: String,
    val serviceProcessName: String,
)

internal data class ControlTargetSnapshot(
    val packageName: String,
    val pid: Int?,
    val targetUid: Int? = null,
    val startTimeTicks: String? = null,
    val summary: String,
    val features: Map<ControlFeature, Boolean>,
    val supportedFeatures: Set<ControlFeature> = emptySet(),
    val nativeProbe: ControlNativeProbeState = ControlNativeProbeState(),
    val readOnlyFields: ControlReadOnlyFieldsState = ControlReadOnlyFieldsState(),
    val injection: ControlInjectionState = ControlInjectionState(),
    val antiFlash: ControlAntiFlashState = ControlAntiFlashState(),
)

internal data class ControlPlayerPositionBridgeResult(
    val snapshot: ControlTargetSnapshot,
    val result: ControlPlayerPositionResult,
)

internal data class ControlSearchIdBridgeResult(
    val snapshot: ControlTargetSnapshot,
    val result: ControlSearchIdResult,
)

data class ControlInt32FieldState(
    val status: ControlReadOnlyFieldReadStatus = ControlReadOnlyFieldReadStatus.IDLE,
    val value: Int? = null,
    val message: String = "",
) {
    val isAvailable: Boolean
        get() = status == ControlReadOnlyFieldReadStatus.OK && value != null
}

data class ControlInt64FieldState(
    val status: ControlReadOnlyFieldReadStatus = ControlReadOnlyFieldReadStatus.IDLE,
    val value: Long? = null,
    val message: String = "",
) {
    val isAvailable: Boolean
        get() = status == ControlReadOnlyFieldReadStatus.OK && value != null
}

data class ControlReadOnlyFieldsState(
    val profileStatus: ControlReadOnlyFieldProfileStatus = ControlReadOnlyFieldProfileStatus.IDLE,
    val profileSummary: String = "尚未解析字段档案",
    val profileId: String = "",
    val targetVersion: String = "",
    val lifeState: ControlInt32FieldState = ControlInt32FieldState(),
    val killCount: ControlInt32FieldState = ControlInt32FieldState(),
    val dataLongSelector1: ControlInt64FieldState = ControlInt64FieldState(),
) {
    val isProfileReady: Boolean
        get() = profileStatus == ControlReadOnlyFieldProfileStatus.READY
}

fun defaultControlFeatureStates(): Map<ControlFeature, Boolean> =
    ControlFeature.entries.associateWith { false }
