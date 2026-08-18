package me.dartcv.minix.root

enum class RootConnectionStatus {
    IDLE,
    REQUESTING,
    CONNECTING,
    READY,
    UNAVAILABLE,
    DENIED,
    ERROR,
}

enum class RootFeature(
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
        fun fromWireId(value: String): RootFeature? = entries.firstOrNull { it.wireId == value }
    }
}

data class RootRuntimeState(
    val status: RootConnectionStatus = RootConnectionStatus.IDLE,
    val message: String = "本地控制服务尚未连接",
    val uid: Int? = null,
    val servicePid: Int? = null,
    val abi: String? = null,
    val targetPackage: String = RootTargetCatalog.default.packageName,
    val targetPid: Int? = null,
    val targetUid: Int? = null,
    val targetStartTimeTicks: String? = null,
    val targetSummary: String = "等待打开目标：${RootTargetCatalog.default.packageName}",
    val features: Map<RootFeature, Boolean> = defaultRootFeatureStates(),
    val supportedFeatures: Set<RootFeature> = emptySet(),
    val nativeProbe: RootNativeProbeState = RootNativeProbeState(),
    val readOnlyFields: RootReadOnlyFieldsState = RootReadOnlyFieldsState(),
    val injection: RootInjectionState = RootInjectionState(),
    val antiFlash: RootAntiFlashState = RootAntiFlashState(),
    /** User intent is kept across a game restart so the worker can re-arm when the profile returns. */
    val antiFlashArmed: Boolean = false,
    val playerPosition: RootPlayerPositionResult? = null,
    val searchIdResult: RootSearchIdResult? = null,
    val protocolVersion: Int? = null,
    val serviceProcessName: String? = null,
) {
    val hasVerifiedTargetIdentity: Boolean
        get() = targetPid != null &&
            targetUid != null &&
            targetUid == uid &&
            !targetStartTimeTicks.isNullOrBlank()
}

internal fun RootInjectionState.hasBlockingRuntimeFailureFor(feature: RootFeature): Boolean =
    lastFeature == feature && when (lastApplyStatus) {
        RootInjectionApplyStatus.IDLE,
        RootInjectionApplyStatus.APPLIED,
        RootInjectionApplyStatus.ALREADY_APPLIED,
        -> false

        else -> true
    }

internal data class RootBridgeInfo(
    val protocolVersion: Int,
    val uid: Int,
    val servicePid: Int,
    val abi: String,
    val serviceProcessName: String,
)

internal data class RootTargetSnapshot(
    val packageName: String,
    val pid: Int?,
    val targetUid: Int? = null,
    val startTimeTicks: String? = null,
    val summary: String,
    val features: Map<RootFeature, Boolean>,
    val supportedFeatures: Set<RootFeature> = emptySet(),
    val nativeProbe: RootNativeProbeState = RootNativeProbeState(),
    val readOnlyFields: RootReadOnlyFieldsState = RootReadOnlyFieldsState(),
    val injection: RootInjectionState = RootInjectionState(),
    val antiFlash: RootAntiFlashState = RootAntiFlashState(),
)

internal data class RootPlayerPositionBridgeResult(
    val snapshot: RootTargetSnapshot,
    val result: RootPlayerPositionResult,
)

internal data class RootSearchIdBridgeResult(
    val snapshot: RootTargetSnapshot,
    val result: RootSearchIdResult,
)

data class RootInt32FieldState(
    val status: RootReadOnlyFieldReadStatus = RootReadOnlyFieldReadStatus.IDLE,
    val value: Int? = null,
    val message: String = "",
) {
    val isAvailable: Boolean
        get() = status == RootReadOnlyFieldReadStatus.OK && value != null
}

data class RootInt64FieldState(
    val status: RootReadOnlyFieldReadStatus = RootReadOnlyFieldReadStatus.IDLE,
    val value: Long? = null,
    val message: String = "",
) {
    val isAvailable: Boolean
        get() = status == RootReadOnlyFieldReadStatus.OK && value != null
}

data class RootReadOnlyFieldsState(
    val profileStatus: RootReadOnlyFieldProfileStatus = RootReadOnlyFieldProfileStatus.IDLE,
    val profileSummary: String = "尚未解析字段档案",
    val profileId: String = "",
    val targetVersion: String = "",
    val lifeState: RootInt32FieldState = RootInt32FieldState(),
    val killCount: RootInt32FieldState = RootInt32FieldState(),
    val dataLongSelector1: RootInt64FieldState = RootInt64FieldState(),
) {
    val isProfileReady: Boolean
        get() = profileStatus == RootReadOnlyFieldProfileStatus.READY
}

fun defaultRootFeatureStates(): Map<RootFeature, Boolean> =
    RootFeature.entries.associateWith { false }
