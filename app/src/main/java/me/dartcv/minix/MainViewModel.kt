package me.dartcv.minix

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.AppDestination
import me.dartcv.minix.core.model.BuiltInPresets
import me.dartcv.minix.core.model.LocalDataSnapshot
import me.dartcv.minix.core.model.MainUiState
import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.PermissionSnapshot
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import me.dartcv.minix.core.model.ThemeMode
import me.dartcv.minix.core.runtime.PreviewRuntime
import me.dartcv.minix.core.serialization.CodecResult
import me.dartcv.minix.core.serialization.LocalDataCodec
import me.dartcv.minix.core.storage.SettingsRepository
import me.dartcv.minix.overlay.OverlayController
import me.dartcv.minix.control.ControlConnectionStatus
import me.dartcv.minix.control.ControlFeature
import me.dartcv.minix.control.ControlPlayerPositionRequest
import me.dartcv.minix.control.ControlRuntimeState
import me.dartcv.minix.control.ControlTargetChannel

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val previewRuntime = PreviewRuntime()
    // UI-facing code talks to the certificate/shared-UID control service.
    private val controlController = (application as MinixApplication).controlController
    private val permissions = MutableStateFlow(PermissionSnapshot())
    private val destination = MutableStateFlow(AppDestination.HOME)

    init {
        viewModelScope.launch {
            controlController.requestAndConnect()
            previewRuntime.record(controlController.state.value.message)
        }
        viewModelScope.launch {
            controlController.state.runControlPolling(
                maintainAntiFlash = controlController::maintainAntiFlash,
                refreshTargetState = controlController::refreshTargetState,
            )
        }
    }

    private val localContent = combine(
        settingsRepository.customPresets,
        settingsRepository.mapPoints,
    ) { customPresets, mapPoints ->
        val builtInIds = BuiltInPresets.all.mapTo(HashSet(), Preset::id)
        val builtInNames = BuiltInPresets.all.mapTo(HashSet(), Preset::name)
        val filteredCustomPresets = customPresets.filterNot { preset ->
            preset.id in builtInIds || preset.name in builtInNames
        }
        (BuiltInPresets.all + filteredCustomPresets) to mapPoints
    }

    val uiState = combine(
        combine(settingsRepository.settings, OverlayController.state) { settings, overlay ->
            settings to overlay
        },
        combine(permissions, destination, previewRuntime.state) { permission, selected, runtime ->
            Triple(permission, selected, runtime)
        },
        localContent,
        controlController.state,
    ) { (settings, overlay), (permission, selected, runtime), (presets, mapPoints), controlState ->
        MainUiState(
            destination = selected,
            settings = settings,
            permissions = permission,
            overlayState = overlay,
            runtime = runtime,
            presets = presets,
            mapPoints = mapPoints,
            favoriteEntryIds = settings.favoriteEntryIds,
            controlState = controlState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun refreshPermissions(snapshot: PermissionSnapshot) {
        permissions.value = snapshot
    }

    fun selectDestination(value: AppDestination) {
        destination.value = value
    }

    fun refreshPreview() {
        previewRuntime.refresh()
    }

    fun connectControlService() = viewModelScope.launch {
        controlController.requestAndConnect()
        previewRuntime.record(controlController.state.value.message)
    }

    fun disconnectControlService() = viewModelScope.launch {
        controlController.disconnect()
        previewRuntime.record(controlController.state.value.message)
    }

    fun selectControlTarget(value: ControlTargetChannel) {
        viewModelScope.launch {
            controlController.selectTarget(value)
            previewRuntime.record(controlController.state.value.message)
        }
    }

    fun scanControlTargets() = viewModelScope.launch {
        controlController.scanAndOpenFirstRunningTarget()
        previewRuntime.record(controlController.state.value.message)
    }

    fun refreshControlTarget() = viewModelScope.launch {
        controlController.openOrRefreshTarget()
        previewRuntime.record(controlController.state.value.message)
    }

    suspend fun armAntiFlashForLaunch(): String? = controlController.armAntiFlashForLaunch()

    fun setControlFeatureEnabled(feature: ControlFeature, enabled: Boolean) = viewModelScope.launch {
        controlController.setFeatureEnabled(feature, enabled)
        previewRuntime.record(controlController.state.value.message)
    }

    fun setPlayerPosition(request: ControlPlayerPositionRequest) = viewModelScope.launch {
        controlController.setPlayerPosition(request)
        previewRuntime.record(controlController.state.value.message)
    }

    fun searchId(requestedId: Long) = viewModelScope.launch {
        controlController.searchId(requestedId)
        previewRuntime.record(controlController.state.value.message)
    }

    fun record(message: String) {
        previewRuntime.record(message)
    }

    fun setGridEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setGridEnabled(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHapticsEnabled(enabled)
    }

    fun setPanelOpacity(value: Float) = viewModelScope.launch {
        settingsRepository.setPanelOpacity(value)
    }

    fun setPanelScale(value: Float) = viewModelScope.launch {
        settingsRepository.setPanelScale(value)
    }

    fun setSensitivity(value: Float) = viewModelScope.launch {
        settingsRepository.setSensitivity(value)
    }

    fun setResponseMode(value: ResponseMode) = viewModelScope.launch {
        settingsRepository.setResponseMode(value)
    }

    fun setAlignmentEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAlignmentEnabled(enabled)
    }

    fun setMotionPreviewEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setMotionPreviewEnabled(enabled)
    }

    fun setEffectHighlightEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setEffectHighlightEnabled(enabled)
    }

    fun setThemeMode(value: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(value)
    }

    fun setAccentOption(value: AccentOption) = viewModelScope.launch {
        settingsRepository.setAccentOption(value)
    }

    fun setBackgroundUri(uri: String?) = viewModelScope.launch {
        settingsRepository.setBackgroundUri(uri)
        previewRuntime.record(if (uri == null) "已清除本地背景" else "已选择本地背景")
    }

    fun setAudioUri(uri: String?) = viewModelScope.launch {
        settingsRepository.setAudioUri(uri)
        previewRuntime.record(if (uri == null) "已清除本地音频" else "已选择本地音频")
    }

    fun saveCurrentPreset(name: String) = viewModelScope.launch {
        val normalizedName = name.trim().take(64)
        if (normalizedName.isBlank()) {
            previewRuntime.record("预设名称不能为空")
            return@launch
        }
        if (BuiltInPresets.all.any { it.name == normalizedName }) {
            previewRuntime.record("预设名称与内置预设重复")
            return@launch
        }
        val settings = uiState.value.settings
        val customPresets = uiState.value.presets.filterNot(Preset::isBuiltIn).toMutableList()
        if (customPresets.size >= 97 && customPresets.none { it.name == normalizedName }) {
            previewRuntime.record("本地预设数量已达上限")
            return@launch
        }
        val preset = Preset(
            id = "custom_${System.currentTimeMillis()}",
            name = normalizedName,
            description = "用户保存的本地配置",
            gridEnabled = settings.gridEnabled,
            panelOpacity = settings.panelOpacity,
            panelScale = settings.panelScale,
            sensitivity = settings.sensitivity,
            responseMode = settings.responseMode,
            accentOption = settings.accentOption,
        )
        customPresets.removeAll { it.name == normalizedName }
        customPresets += preset
        settingsRepository.setCustomPresets(customPresets)
        settingsRepository.applyPreset(preset)
        previewRuntime.record("已保存本地预设：$normalizedName")
    }

    fun deletePreset(id: String) = viewModelScope.launch {
        val preset = uiState.value.presets.firstOrNull { it.id == id } ?: return@launch
        if (preset.isBuiltIn) return@launch
        settingsRepository.setCustomPresets(
            uiState.value.presets.filterNot { it.isBuiltIn || it.id == id },
        )
        if (uiState.value.settings.selectedPreset == preset.name) {
            settingsRepository.applyPreset(BuiltInPresets.all[1])
        }
        previewRuntime.record("已删除本地预设：${preset.name}")
    }

    fun addMapPoint(name: String) = viewModelScope.launch {
        val normalizedName = name.trim().take(64)
        if (normalizedName.isBlank()) return@launch
        val points = uiState.value.mapPoints.toMutableList()
        if (points.size >= 500) {
            previewRuntime.record("点位数量已达上限")
            return@launch
        }
        val offset = (points.size % 5) * 0.08f
        points += MapPoint(
            id = "point_${System.currentTimeMillis()}",
            name = normalizedName,
            group = "自定义",
            x = (0.34f + offset).coerceAtMost(0.82f),
            y = (0.42f + offset).coerceAtMost(0.82f),
        )
        settingsRepository.setMapPoints(points)
        previewRuntime.record("已添加点位：$normalizedName")
    }

    fun renameMapPoint(id: String, name: String) = viewModelScope.launch {
        val normalizedName = name.trim().take(64)
        if (normalizedName.isBlank()) return@launch
        val updated = uiState.value.mapPoints.map { point ->
            if (point.id == id) point.copy(name = normalizedName) else point
        }
        settingsRepository.setMapPoints(updated)
        previewRuntime.record("已重命名点位：$normalizedName")
    }

    fun deleteMapPoint(id: String) = viewModelScope.launch {
        val point = uiState.value.mapPoints.firstOrNull { it.id == id } ?: return@launch
        settingsRepository.setMapPoints(uiState.value.mapPoints.filterNot { it.id == id })
        previewRuntime.record("已删除点位：${point.name}")
    }

    fun toggleFavoriteEntry(id: String) = viewModelScope.launch {
        val willFavorite = id !in uiState.value.favoriteEntryIds
        settingsRepository.toggleFavoriteEntry(id)
        previewRuntime.record(if (willFavorite) "已收藏本地条目" else "已取消收藏本地条目")
    }

    fun exportLocalDataJson(): String? {
        val state = uiState.value
        return when (
            val result = LocalDataCodec.encode(
                LocalDataSnapshot(
                    settings = state.settings,
                    presets = state.presets,
                    mapPoints = state.mapPoints,
                ),
            )
        ) {
            is CodecResult.Success -> result.value
            is CodecResult.Failure -> {
                previewRuntime.record("导出失败：${result.error.detail}")
                null
            }
        }
    }

    fun importLocalDataJson(raw: String) {
        when (val result = LocalDataCodec.decode(raw)) {
            is CodecResult.Success -> viewModelScope.launch {
                val imported = result.value
                val builtInIds = BuiltInPresets.all.mapTo(HashSet(), Preset::id)
                val builtInNames = BuiltInPresets.all.mapTo(HashSet(), Preset::name)
                val customPresets = imported.presets
                    .filterNot { preset ->
                        preset.isBuiltIn || preset.id in builtInIds || preset.name in builtInNames
                    }
                    .take(97)
                val availablePresetNames = (BuiltInPresets.all + customPresets)
                    .mapTo(HashSet(), Preset::name)
                val importedSettings = if (imported.settings.selectedPreset in availablePresetNames) {
                    imported.settings
                } else {
                    imported.settings.copy(selectedPreset = BuiltInPresets.all[1].name)
                }
                settingsRepository.importLocalData(
                    settings = importedSettings,
                    presets = customPresets,
                    mapPoints = imported.mapPoints,
                )
                previewRuntime.record(
                    if (customPresets.size < imported.presets.count { !it.isBuiltIn }) {
                        "本地数据导入完成，冲突或超限预设已跳过"
                    } else {
                        "本地数据导入完成"
                    },
                )
            }

            is CodecResult.Failure -> {
                previewRuntime.record("导入失败：${result.error.detail}")
            }
        }
    }

    fun selectPreset(name: String) = viewModelScope.launch {
        val preset = uiState.value.presets.firstOrNull { it.name == name }
        if (preset == null) {
            settingsRepository.selectPreset(name)
        } else {
            settingsRepository.applyPreset(preset)
        }
        previewRuntime.record("已切换到$name 预设")
    }

    fun selectPreset(preset: Preset) = viewModelScope.launch {
        settingsRepository.applyPreset(preset)
        previewRuntime.record("已切换到${preset.name}预设")
    }

    fun resetSettings() = viewModelScope.launch {
        settingsRepository.reset()
        previewRuntime.record("本地配置已重置")
    }

}

private enum class ControlPollingMode(val intervalMillis: Long) {
    INACTIVE(CONTROL_TARGET_REFRESH_INTERVAL_MILLIS),
    ANTI_FLASH_STARTUP(ANTI_FLASH_STARTUP_POLL_MILLIS),
    ANTI_FLASH_RUNNING(CONTROL_TARGET_REFRESH_INTERVAL_MILLIS),
    TARGET_REFRESH(CONTROL_TARGET_REFRESH_INTERVAL_MILLIS),
}

private fun ControlRuntimeState.controlPollingMode(): ControlPollingMode = when {
    status != ControlConnectionStatus.READY -> ControlPollingMode.INACTIVE
    antiFlashArmed && !antiFlash.workerRunning -> ControlPollingMode.ANTI_FLASH_STARTUP
    antiFlashArmed -> ControlPollingMode.ANTI_FLASH_RUNNING
    hasVerifiedTargetIdentity -> ControlPollingMode.TARGET_REFRESH
    else -> ControlPollingMode.INACTIVE
}

internal suspend fun Flow<ControlRuntimeState>.runControlPolling(
    maintainAntiFlash: suspend () -> Unit,
    refreshTargetState: suspend () -> Unit,
) {
    map(ControlRuntimeState::controlPollingMode)
        .distinctUntilChanged()
        .collectLatest { mode ->
            if (mode == ControlPollingMode.INACTIVE) return@collectLatest
            while (currentCoroutineContext().isActive) {
                when (mode) {
                    ControlPollingMode.ANTI_FLASH_STARTUP,
                    ControlPollingMode.ANTI_FLASH_RUNNING,
                    -> maintainAntiFlash()

                    ControlPollingMode.TARGET_REFRESH -> refreshTargetState()
                    ControlPollingMode.INACTIVE -> Unit
                }
                delay(mode.intervalMillis)
            }
        }
}

private const val CONTROL_TARGET_REFRESH_INTERVAL_MILLIS = 2_000L
private const val ANTI_FLASH_STARTUP_POLL_MILLIS = 100L
