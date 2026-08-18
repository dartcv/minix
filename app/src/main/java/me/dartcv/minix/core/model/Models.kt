package me.dartcv.minix.core.model

import me.dartcv.minix.root.RootRuntimeState
import me.dartcv.minix.root.RootReadOnlyFieldsState

enum class AppDestination(val label: String) {
    HOME("主页"),
    CONTROLS("控制"),
    PRESETS("预设"),
    LIBRARY("本地库"),
    SETTINGS("设置"),
}

enum class OverlaySessionState {
    STOPPED,
    NEEDS_PERMISSION,
    STARTING,
    ACTIVE,
}

enum class ResponseMode(val label: String) {
    SOFT("柔和"),
    BALANCED("平衡"),
    FAST("快速"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class AccentOption(val label: String) {
    INK("墨色"),
    MINT("薄荷"),
    CORAL("珊瑚"),
    SKY("天蓝"),
}

data class PermissionSnapshot(
    val canDrawOverlays: Boolean = false,
    val notificationGranted: Boolean = true,
    val notificationRequired: Boolean = false,
)

data class AppSettings(
    val gridEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val panelOpacity: Float = 0.88f,
    val panelScale: Float = 1f,
    val overlayX: Int = 24,
    val overlayY: Int = 180,
    val overlayExpanded: Boolean = false,
    val selectedPreset: String = "平衡",
    val sensitivity: Float = 0.72f,
    val responseMode: ResponseMode = ResponseMode.BALANCED,
    val alignmentEnabled: Boolean = true,
    val motionPreviewEnabled: Boolean = false,
    val effectHighlightEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentOption: AccentOption = AccentOption.INK,
    val backgroundUri: String? = null,
    val audioUri: String? = null,
    val favoriteEntryIds: Set<String> = emptySet(),
)

sealed interface FeatureValue {
    data class Toggle(val enabled: Boolean) : FeatureValue
    data class Range(val value: Float) : FeatureValue
    data class Choice(val value: String) : FeatureValue
}

data class FeatureDefinition(
    val id: String,
    val title: String,
    val group: String,
    val defaultValue: FeatureValue,
)

data class OverlayStyle(
    val opacity: Float,
    val scale: Float,
    val gridEnabled: Boolean,
    val alignmentEnabled: Boolean,
    val accentOption: AccentOption,
)

data class ControlConfig(
    val groupValues: Map<String, FeatureValue>,
    val overlayStyle: OverlayStyle,
    val selectedPresetId: String?,
)

data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val gridEnabled: Boolean,
    val panelOpacity: Float,
    val panelScale: Float,
    val sensitivity: Float,
    val responseMode: ResponseMode,
    val accentOption: AccentOption,
    val isBuiltIn: Boolean = false,
)

data class MapPoint(
    val id: String,
    val name: String,
    val group: String,
    val x: Float,
    val y: Float,
)

data class SensorPreview(
    val pitch: Float = 1.8f,
    val roll: Float = -0.7f,
    val yaw: Float = 14.2f,
)

data class LocalEntry(
    val id: String,
    val name: String,
    val group: String,
    val detail: String,
)

object DemoEntries {
    val all: List<LocalEntry> = listOf(
        LocalEntry("entry_alpha", "晨雾布局", "常用", "本地示例 · 标准响应"),
        LocalEntry("entry_beta", "夜航布局", "收藏", "本地示例 · 高对比"),
        LocalEntry("entry_gamma", "训练布局", "练习", "本地示例 · 柔和响应"),
    )
}

object BuiltInPresets {
    val all: List<Preset> = listOf(
        Preset(
            id = "lightweight",
            name = "轻量",
            description = "紧凑面板 · 低透明度",
            gridEnabled = false,
            panelOpacity = 0.72f,
            panelScale = 0.86f,
            sensitivity = 0.58f,
            responseMode = ResponseMode.SOFT,
            accentOption = AccentOption.SKY,
            isBuiltIn = true,
        ),
        Preset(
            id = "balanced",
            name = "平衡",
            description = "标准尺寸 · 网格预览",
            gridEnabled = true,
            panelOpacity = 0.88f,
            panelScale = 1f,
            sensitivity = 0.72f,
            responseMode = ResponseMode.BALANCED,
            accentOption = AccentOption.INK,
            isBuiltIn = true,
        ),
        Preset(
            id = "focus",
            name = "专注",
            description = "大尺寸面板 · 高对比",
            gridEnabled = true,
            panelOpacity = 1f,
            panelScale = 1.14f,
            sensitivity = 0.86f,
            responseMode = ResponseMode.FAST,
            accentOption = AccentOption.MINT,
            isBuiltIn = true,
        ),
    )
}

object DefaultMapPoints {
    val all: List<MapPoint> = listOf(
        MapPoint("center", "中心参考", "常用", 0.5f, 0.5f),
        MapPoint("upper_left", "左上布局", "布局", 0.25f, 0.3f),
    )
}

data class RuntimeState(
    val refreshedAt: Long = System.currentTimeMillis(),
    val sensorPreview: SensorPreview = SensorPreview(),
    val activityLog: List<String> = listOf(
        "本地工作区已初始化",
        "配置只保存在当前设备",
        "悬浮工具等待用户启动",
    ),
)

data class MainUiState(
    val destination: AppDestination = AppDestination.HOME,
    val settings: AppSettings = AppSettings(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val overlayState: OverlaySessionState = OverlaySessionState.STOPPED,
    val runtime: RuntimeState = RuntimeState(),
    val presets: List<Preset> = BuiltInPresets.all,
    val mapPoints: List<MapPoint> = DefaultMapPoints.all,
    val favoriteEntryIds: Set<String> = emptySet(),
    val rootState: RootRuntimeState = RootRuntimeState(),
) {
    val readOnlyFields: RootReadOnlyFieldsState
        get() = rootState.readOnlyFields
}
