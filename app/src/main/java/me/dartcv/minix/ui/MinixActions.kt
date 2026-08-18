package me.dartcv.minix.ui

import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.AppDestination
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import me.dartcv.minix.core.model.ThemeMode
import me.dartcv.minix.root.RootFeature
import me.dartcv.minix.root.RootPlayerPositionRequest
import me.dartcv.minix.root.RootTargetChannel

data class MinixActions(
    val onDestinationSelected: (AppDestination) -> Unit,
    val onRefresh: () -> Unit,
    val onStartOverlay: () -> Unit,
    val onStopOverlay: () -> Unit,
    /** Connects the in-process/shared-UID control service. */
    val onConnectControlService: () -> Unit,
    /** Disconnects the in-process/shared-UID control service. */
    val onDisconnectControlService: () -> Unit,
    val onControlTargetSelected: (RootTargetChannel) -> Unit,
    val onScanControlTargets: () -> Unit,
    val onRefreshControlTarget: () -> Unit,
    val onLaunchGameWithAntiFlash: () -> Unit,
    val onControlFeatureChanged: (RootFeature, Boolean) -> Unit,
    val onPlayerPositionApply: (RootPlayerPositionRequest) -> Unit,
    val onSearchId: (Long) -> Unit,
    val onGridChanged: (Boolean) -> Unit,
    val onHapticsChanged: (Boolean) -> Unit,
    val onOpacityChanged: (Float) -> Unit,
    val onScaleChanged: (Float) -> Unit,
    val onSensitivityChanged: (Float) -> Unit,
    val onResponseModeChanged: (ResponseMode) -> Unit,
    val onAlignmentChanged: (Boolean) -> Unit,
    val onMotionPreviewChanged: (Boolean) -> Unit,
    val onEffectHighlightChanged: (Boolean) -> Unit,
    val onPresetSelected: (Preset) -> Unit,
    val onSavePreset: (String) -> Unit,
    val onDeletePreset: (String) -> Unit,
    val onAddMapPoint: (String) -> Unit,
    val onRenameMapPoint: (String, String) -> Unit,
    val onDeleteMapPoint: (String) -> Unit,
    val onToggleFavoriteEntry: (String) -> Unit,
    val onThemeModeChanged: (ThemeMode) -> Unit,
    val onAccentChanged: (AccentOption) -> Unit,
    val onPickBackground: () -> Unit,
    val onClearBackground: () -> Unit,
    val onPickAudio: () -> Unit,
    val onClearAudio: () -> Unit,
    val onToggleAudio: () -> Unit,
    val onImportData: () -> Unit,
    val onExportData: () -> Unit,
    val onResetSettings: () -> Unit,
)
