package me.dartcv.minix

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.dartcv.minix.media.LocalAudioController
import me.dartcv.minix.overlay.OverlayController
import me.dartcv.minix.permissions.PermissionCoordinator
import me.dartcv.minix.ui.MinixApp
import me.dartcv.minix.ui.MinixActions
import me.dartcv.minix.ui.theme.MinixTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val permissionCoordinator by lazy { PermissionCoordinator(this) }
    private val audioController by lazy { LocalAudioController(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissions()
        if (permissionCoordinator.snapshot().canDrawOverlays) {
            startOverlay()
        }
    }

    private val backgroundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        viewModel.setBackgroundUri(uri.toString())
    }

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        audioController.stop()
        viewModel.setAudioUri(uri.toString())
    }

    private val importDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { readDocument(uri) }
            if (raw == null) {
                viewModel.record("导入失败：文件不可读或超过 1 MB")
            } else {
                viewModel.importLocalDataJson(raw)
            }
        }
    }

    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@registerForActivityResult
        val json = viewModel.exportLocalDataJson() ?: return@registerForActivityResult
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) { writeDocument(uri, json) }
            viewModel.record(if (written) "本地数据导出完成" else "导出失败：无法写入文件")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val audioPlaying = audioController.isPlaying.collectAsStateWithLifecycle().value
            MinixTheme(
                themeMode = uiState.settings.themeMode,
                accentOption = uiState.settings.accentOption,
            ) {
                MinixApp(
                    uiState = uiState,
                    audioPlaying = audioPlaying,
                    actions = MinixActions(
                        onDestinationSelected = viewModel::selectDestination,
                        onRefresh = viewModel::refreshPreview,
                        onStartOverlay = ::handleStartOverlay,
                        onStopOverlay = {
                            OverlayController.stop(this)
                            viewModel.record("悬浮工具已停止")
                        },
                        onConnectControlService = viewModel::connectControlService,
                        onDisconnectControlService = viewModel::disconnectControlService,
                        onControlTargetSelected = viewModel::selectControlTarget,
                        onScanControlTargets = viewModel::scanControlTargets,
                        onRefreshControlTarget = viewModel::refreshControlTarget,
                        onLaunchGameWithAntiFlash = ::launchGameWithAntiFlash,
                        onControlFeatureChanged = viewModel::setControlFeatureEnabled,
                        onPlayerPositionApply = viewModel::setPlayerPosition,
                        onSearchId = viewModel::searchId,
                        onGridChanged = viewModel::setGridEnabled,
                        onHapticsChanged = viewModel::setHapticsEnabled,
                        onOpacityChanged = viewModel::setPanelOpacity,
                        onScaleChanged = viewModel::setPanelScale,
                        onSensitivityChanged = viewModel::setSensitivity,
                        onResponseModeChanged = viewModel::setResponseMode,
                        onAlignmentChanged = viewModel::setAlignmentEnabled,
                        onMotionPreviewChanged = viewModel::setMotionPreviewEnabled,
                        onEffectHighlightChanged = viewModel::setEffectHighlightEnabled,
                        onPresetSelected = { preset -> viewModel.selectPreset(preset) },
                        onSavePreset = viewModel::saveCurrentPreset,
                        onDeletePreset = viewModel::deletePreset,
                        onAddMapPoint = viewModel::addMapPoint,
                        onRenameMapPoint = viewModel::renameMapPoint,
                        onDeleteMapPoint = viewModel::deleteMapPoint,
                        onToggleFavoriteEntry = viewModel::toggleFavoriteEntry,
                        onThemeModeChanged = viewModel::setThemeMode,
                        onAccentChanged = viewModel::setAccentOption,
                        onPickBackground = { backgroundPickerLauncher.launch(arrayOf("image/*")) },
                        onClearBackground = { viewModel.setBackgroundUri(null) },
                        onPickAudio = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        onClearAudio = {
                            audioController.stop()
                            viewModel.setAudioUri(null)
                        },
                        onToggleAudio = { audioController.toggle(uiState.settings.audioUri) },
                        onImportData = {
                            importDataLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                        onExportData = { exportDataLauncher.launch("minix-local-data-v1.json") },
                        onResetSettings = {
                            audioController.stop()
                            OverlayController.stop(this)
                            viewModel.disconnectControlService()
                            viewModel.resetSettings()
                        },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onDestroy() {
        audioController.release()
        super.onDestroy()
    }

    private fun refreshPermissions() {
        viewModel.refreshPermissions(permissionCoordinator.snapshot())
    }

    private fun handleStartOverlay() {
        val snapshot = permissionCoordinator.snapshot()
        viewModel.refreshPermissions(snapshot)

        if (!snapshot.canDrawOverlays) {
            try {
                startActivity(permissionCoordinator.overlaySettingsIntent())
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !snapshot.notificationGranted
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startOverlay()
    }

    private fun startOverlay() {
        OverlayController.start(this)
        viewModel.record("悬浮工具正在启动")
    }

    private fun launchGameWithAntiFlash() {
        lifecycleScope.launch {
            val packageName = viewModel.armAntiFlashForLaunch() ?: return@launch
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                viewModel.setControlFeatureEnabled(me.dartcv.minix.root.RootFeature.ANTI_FLASH, false)
                viewModel.record("未找到游戏启动入口：$packageName")
                return@launch
            }
            runCatching {
                startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onSuccess {
                viewModel.record("游戏已启动；服务侧防闪监听已就绪")
            }.onFailure { error ->
                viewModel.setControlFeatureEnabled(me.dartcv.minix.root.RootFeature.ANTI_FLASH, false)
                viewModel.record("启动游戏失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun readDocument(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (output.length + count > MAX_IMPORT_CHARS) return null
                    output.append(buffer, 0, count)
                }
                output.toString().takeIf { it.toByteArray().size <= MAX_IMPORT_BYTES }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun writeDocument(uri: Uri, content: String): Boolean {
        return try {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(content)
            } != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 1_048_576
        private const val MAX_IMPORT_CHARS = 1_048_576
    }
}
