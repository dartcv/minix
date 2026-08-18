package me.dartcv.minix.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import me.dartcv.minix.core.model.AppDestination
import me.dartcv.minix.core.model.DemoEntries
import me.dartcv.minix.core.model.LocalEntry
import me.dartcv.minix.core.model.MainUiState
import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.OverlaySessionState
import me.dartcv.minix.control.ControlConnectionStatus
import me.dartcv.minix.control.ControlAntiFlashStatus
import me.dartcv.minix.control.ControlFeature
import me.dartcv.minix.control.ControlInjectionApplyStatus
import me.dartcv.minix.control.ControlInjectionProfileStatus
import me.dartcv.minix.control.ControlInjectionState
import me.dartcv.minix.control.ControlInt32FieldState
import me.dartcv.minix.control.ControlInt64FieldState
import me.dartcv.minix.control.ControlNativeProbeStatus
import me.dartcv.minix.control.ControlPlayerPositionRequest
import me.dartcv.minix.control.ControlReadOnlyFieldProfileStatus
import me.dartcv.minix.control.ControlReadOnlyFieldReadStatus
import me.dartcv.minix.control.ControlRuntimeState
import me.dartcv.minix.control.ControlSearchIdResult
import me.dartcv.minix.control.ControlSearchIdStatus
import me.dartcv.minix.control.ControlTargetCatalog
import me.dartcv.minix.control.ControlTargetChannel
import me.dartcv.minix.control.hasBlockingRuntimeFailureFor
import me.dartcv.minix.ui.theme.Coral
import me.dartcv.minix.ui.theme.Ink
import me.dartcv.minix.ui.theme.Mint
import me.dartcv.minix.ui.theme.MistBlue
import me.dartcv.minix.ui.theme.PanelWhite
import me.dartcv.minix.ui.theme.Rail
import me.dartcv.minix.ui.theme.Slate

@Composable
fun MinixApp(
    uiState: MainUiState,
    audioPlaying: Boolean,
    actions: MinixActions,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        val useRail = maxWidth >= 600.dp
        if (useRail) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MinixNavigationRail(
                    selected = uiState.destination,
                    onSelected = actions.onDestinationSelected,
                )
                MainPanel(
                    modifier = Modifier.weight(1f),
                    uiState = uiState,
                    audioPlaying = audioPlaying,
                    actions = actions,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MainPanel(
                    modifier = Modifier.weight(1f),
                    uiState = uiState,
                    audioPlaying = audioPlaying,
                    actions = actions,
                )
                MinixNavigationBar(
                    selected = uiState.destination,
                    onSelected = actions.onDestinationSelected,
                )
            }
        }
    }
}

@Composable
private fun MainPanel(
    modifier: Modifier,
    uiState: MainUiState,
    audioPlaying: Boolean,
    actions: MinixActions,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (uiState.destination) {
            AppDestination.HOME -> HomeScreen(
                uiState = uiState,
                onRefresh = actions.onRefresh,
                onStartOverlay = actions.onStartOverlay,
                onStopOverlay = actions.onStopOverlay,
            )

            AppDestination.CONTROLS -> ControlsScreen(
                uiState = uiState,
                actions = actions,
            )

            AppDestination.PRESETS -> PresetsScreen(
                uiState = uiState,
                actions = actions,
            )

            AppDestination.LIBRARY -> LibraryScreen(
                uiState = uiState,
                audioPlaying = audioPlaying,
                actions = actions,
            )
            AppDestination.SETTINGS -> SettingsScreen(
                uiState = uiState,
                actions = actions,
            )
        }
    }
}

@Composable
private fun MinixNavigationRail(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier
            .width(78.dp)
            .fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        header = {
            Surface(
                modifier = Modifier.padding(vertical = 18.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "M",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                    )
                }
            }
        },
    ) {
        AppDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon(),
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun MinixNavigationBar(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon(),
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label, maxLines = 1) },
            )
        }
    }
}

private fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.HOME -> Icons.Outlined.Home
    AppDestination.CONTROLS -> Icons.Outlined.Tune
    AppDestination.PRESETS -> Icons.Outlined.BookmarkBorder
    AppDestination.LIBRARY -> Icons.AutoMirrored.Outlined.LibraryBooks
    AppDestination.SETTINGS -> Icons.Outlined.Settings
}

@Composable
private fun HomeScreen(
    uiState: MainUiState,
    onRefresh: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val active = uiState.overlayState == OverlaySessionState.ACTIVE
    val starting = uiState.overlayState == OverlaySessionState.STARTING
    val primaryLabel = when {
        !uiState.permissions.canDrawOverlays -> "授予悬浮权限"
        active -> "停止悬浮工具"
        starting -> "正在启动"
        uiState.permissions.notificationRequired && !uiState.permissions.notificationGranted -> "允许通知"
        else -> "启动悬浮工具"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "minix · v1.0",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OverlayStatusPill(uiState.overlayState)
        }

        HeroArtwork(
            active = active,
            gridEnabled = uiState.settings.gridEnabled,
            alignmentEnabled = uiState.settings.alignmentEnabled,
            motionPreviewEnabled = uiState.settings.motionPreviewEnabled,
            effectHighlightEnabled = uiState.settings.effectHighlightEnabled,
            backgroundUri = uiState.settings.backgroundUri,
        )

        Text(
            text = "晚上好",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(
            text = "本地工作区已准备好",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
            lineHeight = 42.sp,
        )
        Text(
            text = "当前预设：${uiState.settings.selectedPreset} · 配置仅保存在设备中",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )

        PermissionSummary(uiState)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onRefresh,
                modifier = Modifier.height(56.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新本地状态")
            }
            Button(
                onClick = {
                    if (uiState.settings.hapticsEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    if (active) onStopOverlay() else onStartOverlay()
                },
                enabled = !starting,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Coral else MaterialTheme.colorScheme.primary,
                    contentColor = if (active) Ink else MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(
                        imageVector = if (active) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(primaryLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HeroArtwork(
    active: Boolean,
    gridEnabled: Boolean,
    alignmentEnabled: Boolean,
    motionPreviewEnabled: Boolean,
    effectHighlightEnabled: Boolean,
    backgroundUri: String?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LocalBackgroundImage(backgroundUri)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val min = size.minDimension
                drawCircle(
                    color = Color.White.copy(alpha = 0.48f),
                    radius = min * 0.34f,
                    center = Offset(size.width * 0.72f, size.height * 0.35f),
                )
                drawCircle(
                    color = when {
                        active && effectHighlightEnabled -> Mint.copy(alpha = 0.25f)
                        active -> Slate.copy(alpha = 0.25f)
                        else -> MistBlue.copy(alpha = 0.6f)
                    },
                    radius = min * 0.22f,
                    center = Offset(size.width * 0.38f, size.height * 0.56f),
                )
                if (gridEnabled) {
                    repeat(6) { index ->
                        val x = size.width * (0.12f + index * 0.13f)
                        drawLine(
                            color = Slate.copy(alpha = 0.18f),
                            start = Offset(x, size.height * 0.22f),
                            end = Offset(x, size.height * 0.82f),
                            strokeWidth = 2f,
                        )
                    }
                    repeat(4) { index ->
                        val y = size.height * (0.28f + index * 0.16f)
                        drawLine(
                            color = Slate.copy(alpha = 0.18f),
                            start = Offset(size.width * 0.12f, y),
                            end = Offset(size.width * 0.86f, y),
                            strokeWidth = 2f,
                        )
                    }
                }
                if (alignmentEnabled) {
                    val center = Offset(size.width * 0.68f, size.height * 0.58f)
                    drawCircle(
                        color = Mint.copy(alpha = 0.55f),
                        radius = min * 0.08f,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    )
                    drawLine(
                        color = Mint.copy(alpha = 0.55f),
                        start = Offset(center.x - min * 0.13f, center.y),
                        end = Offset(center.x + min * 0.13f, center.y),
                        strokeWidth = 3f,
                    )
                    drawLine(
                        color = Mint.copy(alpha = 0.55f),
                        start = Offset(center.x, center.y - min * 0.13f),
                        end = Offset(center.x, center.y + min * 0.13f),
                        strokeWidth = 3f,
                    )
                }
                if (motionPreviewEnabled) {
                    val start = Offset(size.width * 0.24f, size.height * 0.72f)
                    val end = Offset(size.width * 0.56f, size.height * 0.36f)
                    drawLine(
                        color = Coral.copy(alpha = 0.7f),
                        start = start,
                        end = end,
                        strokeWidth = 5f,
                    )
                    drawCircle(
                        color = Coral.copy(alpha = 0.8f),
                        radius = 7f,
                        center = end,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "MINIX",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (active) "OVERLAY ACTIVE" else "LOCAL WORKSPACE",
                    color = if (active) Mint else Slate,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun OverlayStatusPill(state: OverlaySessionState) {
    val (label, color, icon) = when (state) {
        OverlaySessionState.ACTIVE -> Triple("运行中", Mint, Icons.Outlined.CheckCircle)
        OverlaySessionState.STARTING -> Triple("启动中", MistBlue, Icons.Outlined.Refresh)
        OverlaySessionState.NEEDS_PERMISSION -> Triple("缺少权限", Coral, Icons.Outlined.Warning)
        OverlaySessionState.STOPPED -> Triple("待启动", Rail, Icons.Outlined.Info)
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.88f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = Ink)
            Text(label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private fun ControlConnectionStatus.displayLabel(): String = when (this) {
    ControlConnectionStatus.IDLE -> "未连接"
    ControlConnectionStatus.REQUESTING -> "绑定中"
    ControlConnectionStatus.CONNECTING -> "连接中"
    ControlConnectionStatus.READY -> "已连接"
    ControlConnectionStatus.UNAVAILABLE -> "不可用"
    ControlConnectionStatus.DENIED -> "身份拒绝"
    ControlConnectionStatus.ERROR -> "异常"
}

private fun ControlAntiFlashStatus.displayLabel(): String = when (this) {
    ControlAntiFlashStatus.IDLE -> "未启动"
    ControlAntiFlashStatus.STARTING -> "启动中"
    ControlAntiFlashStatus.WAITING_FOR_TARGET -> "预检中"
    ControlAntiFlashStatus.PREFLIGHT_FAILED -> "预检失败"
    ControlAntiFlashStatus.RUNNING -> "运行中"
    ControlAntiFlashStatus.STOPPING -> "停止中"
    ControlAntiFlashStatus.STOPPED -> "已停止"
    ControlAntiFlashStatus.TARGET_CHANGED -> "目标已变化"
    ControlAntiFlashStatus.PROFILE_MISMATCH -> "指纹不匹配"
    ControlAntiFlashStatus.READ_FAILED -> "读取失败"
    ControlAntiFlashStatus.WRITE_FAILED -> "写入失败"
    ControlAntiFlashStatus.VERIFY_FAILED -> "回读失败"
    ControlAntiFlashStatus.ROLLBACK_FAILED -> "回滚失败"
    ControlAntiFlashStatus.BACKEND_UNAVAILABLE -> "后端不可用"
}

internal fun antiFlashLaunchButtonLabel(
    armed: Boolean,
    workerRunning: Boolean,
): String = when {
    workerRunning -> "防闪运行中，返回游戏"
    armed -> "防闪已预置，启动游戏"
    else -> "预置防闪并启动游戏"
}

@Composable
private fun PermissionSummary(uiState: MainUiState) {
    val overlayReady = uiState.permissions.canDrawOverlays
    val notificationReady = uiState.permissions.notificationGranted
    val controlReady = uiState.controlState.status == ControlConnectionStatus.READY
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PermissionLine("悬浮窗口", overlayReady)
            PermissionLine("前台通知", notificationReady)
            PermissionLine("同 UID 控制", controlReady, uiState.controlState.status.displayLabel())
        }
    }
}

@Composable
private fun PermissionLine(
    label: String,
    ready: Boolean,
    status: String = if (ready) "已就绪" else "待处理",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        Text(
            text = status,
            color = if (ready) Mint else Coral,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ControlTargetPanel(
    uiState: MainUiState,
    actions: MinixActions,
) {
    val controlState = uiState.controlState
    val ready = controlState.status == ControlConnectionStatus.READY
    val busy = controlState.status == ControlConnectionStatus.REQUESTING ||
        controlState.status == ControlConnectionStatus.CONNECTING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地目标会话", fontWeight = FontWeight.Bold)
                    Text(
                        text = controlState.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = controlState.status.displayLabel(),
                    color = if (ready) Mint else Coral,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            ControlTargetSelector(
                selectedPackage = controlState.targetPackage,
                onSelected = actions.onControlTargetSelected,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = if (ready) actions.onRefreshControlTarget else actions.onConnectControlService,
                    enabled = !busy && (!ready || controlState.targetPackage.isNotBlank()),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (ready) Icons.Outlined.Search else Icons.Outlined.Security,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (ready) "打开或刷新" else "连接服务")
                }
                if (ready) {
                    IconButton(onClick = actions.onScanControlTargets) {
                        Icon(Icons.Outlined.Search, contentDescription = "扫描运行中的渠道包")
                    }
                    FilledTonalButton(onClick = actions.onDisconnectControlService) {
                        Icon(Icons.Outlined.Stop, contentDescription = "断开本地控制服务")
                    }
                }
            }
            Button(
                onClick = actions.onLaunchGameWithAntiFlash,
                enabled = ready && controlState.targetPackage.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    antiFlashLaunchButtonLabel(
                        armed = controlState.antiFlashArmed,
                        workerRunning = controlState.antiFlash.workerRunning,
                    ),
                )
            }
            if (controlState.targetSummary.isNotBlank()) {
                Text(
                    text = controlState.targetSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            if (controlState.nativeProbe.status != ControlNativeProbeStatus.IDLE) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Native 只读后端 · ${controlState.nativeProbe.summary}",
                    color = if (controlState.nativeProbe.isMemoryReady) Mint else Coral,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (controlState.nativeProbe.modules.isNotEmpty()) {
                    Text(
                        text = "读回模块：${controlState.nativeProbe.modules.take(3).joinToString()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (controlState.injection.profileStatus != ControlInjectionProfileStatus.IDLE ||
                controlState.injection.lastApplyStatus != ControlInjectionApplyStatus.IDLE
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "注入门禁 · ${controlState.injection.profileStatus.displayLabel()}",
                    color = if (controlState.injection.isProfileReady) Mint else Coral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = buildString {
                        append(controlState.injection.profileSummary)
                        if (controlState.injection.profileId.isNotBlank()) {
                            append(" · ")
                            append(controlState.injection.profileId)
                        }
                        if (controlState.injection.requiredAbi.isNotBlank()) {
                            append(" · ")
                            append(controlState.injection.requiredAbi)
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (controlState.injection.lastApplyStatus != ControlInjectionApplyStatus.IDLE) {
                    Text(
                        text = "最近执行：${controlState.injection.lastApplyStatus.displayLabel()}" +
                            controlState.injection.message.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (controlState.antiFlashArmed || controlState.antiFlash.status != ControlAntiFlashStatus.IDLE) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "防闪 · ${controlState.antiFlash.status.displayLabel()}",
                    color = if (controlState.antiFlash.workerRunning) Mint else Coral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = buildString {
                        append("循环 ")
                        append(controlState.antiFlash.iterationCount)
                        append(" · 已验证写入 ")
                        append(controlState.antiFlash.successfulWriteCount)
                        controlState.antiFlash.targetPid?.let { pid ->
                            append(" · PID ")
                            append(pid)
                        }
                        controlState.antiFlash.lastFailureIndex?.let { index ->
                            append(" · 失败项 ")
                            append(index)
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (controlState.antiFlash.message.isNotBlank()) {
                    Text(
                        text = controlState.antiFlash.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlTargetSelector(
    selectedPackage: String,
    onSelected: (ControlTargetChannel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ControlTargetCatalog.fromPackageName(selectedPackage)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected?.channelLabel ?: "请选择游戏渠道",
            onValueChange = {},
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            label = { Text("游戏渠道") },
            supportingText = {
                Text(
            text = selected?.packageName ?: "服务连接后自动扫描 9 个渠道包",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ControlTargetCatalog.entries.forEach { target ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(target.channelLabel, fontWeight = FontWeight.Bold)
                            Text(
                                text = target.packageName,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        onSelected(target)
                        expanded = false
                    },
                    trailingIcon = if (target == selected) {
                        {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = "已选择",
                                tint = Mint,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun ControlFeature.icon(): ImageVector = when (this) {
    ControlFeature.AIM -> Icons.Outlined.Sensors
    ControlFeature.DRAW -> Icons.Outlined.GridView
    ControlFeature.FLIGHT -> Icons.Outlined.Map
    ControlFeature.FAKE_FLIGHT -> Icons.Outlined.Map
    ControlFeature.PLAYER_TELEPORT -> Icons.Outlined.Search
    ControlFeature.HITBOX -> Icons.Outlined.Tune
    ControlFeature.ANTI_FLASH -> Icons.Outlined.FlashOff
    ControlFeature.READABLE_DATA -> Icons.Outlined.Info
}

internal val controlFeatureDisplayOrder = listOf(
    ControlFeature.FLIGHT,
    ControlFeature.FAKE_FLIGHT,
    ControlFeature.ANTI_FLASH,
    ControlFeature.READABLE_DATA,
    ControlFeature.AIM,
    ControlFeature.DRAW,
    ControlFeature.HITBOX,
)

@Composable
private fun ControlsScreen(
    uiState: MainUiState,
    actions: MinixActions,
) {
    val controlReady = uiState.controlState.status == ControlConnectionStatus.READY
    val targetReady = controlReady && uiState.controlState.hasVerifiedTargetIdentity
    PageColumn(title = "控制面板", subtitle = "同 UID 控制桥与悬浮面板配置") {
        ControlTargetPanel(uiState = uiState, actions = actions)
        SectionTitle("可用功能")
        controlFeatureDisplayOrder.forEach { feature ->
            if (feature == ControlFeature.AIM) {
                PlayerPositionPanel(uiState = uiState, actions = actions)
                SearchIdPanel(uiState = uiState, actions = actions)
                ReadOnlyFieldsPanel(uiState)
                SectionTitle("待闭合功能")
            }
            val checked = if (feature == ControlFeature.ANTI_FLASH) {
                uiState.controlState.antiFlashArmed
            } else {
                uiState.controlState.features[feature] == true
            }
            ToggleSetting(
                icon = feature.icon(),
                title = controlFeatureTitle(feature),
                subtitle = controlFeatureSubtitle(uiState.controlState, feature),
                checked = checked,
                enabled = controlFeatureControlEnabled(
                    controlState = uiState.controlState,
                    feature = feature,
                ),
                onCheckedChange = { enabled -> actions.onControlFeatureChanged(feature, enabled) },
            )
        }
        SectionTitle("悬浮面板")
        ToggleSetting(
            icon = Icons.Outlined.GridView,
            title = "视觉网格",
            subtitle = "在主视觉与悬浮预览中显示对齐网格",
            checked = uiState.settings.gridEnabled,
            onCheckedChange = actions.onGridChanged,
        )
        ToggleSetting(
            icon = Icons.Outlined.Sensors,
            title = "触觉反馈",
            subtitle = "本地按钮和快捷动作的触觉反馈",
            checked = uiState.settings.hapticsEnabled,
            onCheckedChange = actions.onHapticsChanged,
        )
        SliderSetting(
            icon = Icons.Outlined.Opacity,
            title = "面板透明度",
            valueLabel = "${(uiState.settings.panelOpacity * 100).toInt()}%",
            value = uiState.settings.panelOpacity,
            valueRange = 0.55f..1f,
            onValueChange = actions.onOpacityChanged,
        )
        SliderSetting(
            icon = Icons.Outlined.Palette,
            title = "面板尺寸",
            valueLabel = "${(uiState.settings.panelScale * 100).toInt()}%",
            value = uiState.settings.panelScale,
            valueRange = 0.8f..1.2f,
            onValueChange = actions.onScaleChanged,
        )
        SliderSetting(
            icon = Icons.Outlined.Tune,
            title = "响应灵敏度",
            valueLabel = "${(uiState.settings.sensitivity * 100).toInt()}%",
            value = uiState.settings.sensitivity,
            valueRange = 0.2f..1f,
            onValueChange = actions.onSensitivityChanged,
        )
        ChoiceSetting(
            icon = Icons.Outlined.Sensors,
            title = "响应模式",
            options = me.dartcv.minix.core.model.ResponseMode.entries,
            selected = uiState.settings.responseMode,
            label = { it.label },
            onSelected = actions.onResponseModeChanged,
        )
        ToggleSetting(
            icon = Icons.Outlined.Map,
            title = "对齐辅助",
            subtitle = "在本地预览中显示点位与对齐参考",
            checked = uiState.settings.alignmentEnabled,
            onCheckedChange = actions.onAlignmentChanged,
        )
        ToggleSetting(
            icon = Icons.Outlined.Sensors,
            title = "移动轨迹预览",
            subtitle = "在本地画布中显示移动方向参考",
            checked = uiState.settings.motionPreviewEnabled,
            onCheckedChange = actions.onMotionPreviewChanged,
        )
        ToggleSetting(
            icon = Icons.Outlined.Palette,
            title = "效果高亮",
            subtitle = "用状态色标出本地预览中的启用效果",
            checked = uiState.settings.effectHighlightEnabled,
            onCheckedChange = actions.onEffectHighlightChanged,
        )
    }
}

internal fun controlFeatureTitle(feature: ControlFeature): String = when (feature) {
    ControlFeature.AIM,
    ControlFeature.DRAW,
    ControlFeature.HITBOX,
    -> "${feature.label} · 未实现"

    else -> feature.label
}

internal fun controlFeatureControlEnabled(
    controlState: ControlRuntimeState,
    feature: ControlFeature,
): Boolean {
    val controlReady = controlState.status == ControlConnectionStatus.READY
    if (feature == ControlFeature.ANTI_FLASH) {
        return controlReady && controlState.targetPackage.isNotBlank()
    }
    val targetReady = controlReady && controlState.hasVerifiedTargetIdentity
    if (!targetReady || feature !in controlState.supportedFeatures) return false
    return true
}

internal fun controlFeatureSubtitle(
    controlState: ControlRuntimeState,
    feature: ControlFeature,
): String {
    val controlReady = controlState.status == ControlConnectionStatus.READY
    val targetReady = controlReady && controlState.hasVerifiedTargetIdentity
    val supported = feature in controlState.supportedFeatures
    return when (feature) {
        ControlFeature.AIM -> "40 槽候选与过滤已恢复；最终瞄准 writer、宽度和值尚未闭合"
        ControlFeature.DRAW -> "实体/矩阵/九字段协议已恢复；完整投影、字段映射和循环边界尚未闭合"
        ControlFeature.FAKE_FLIGHT -> when {
            !targetReady -> "连接控制服务并打开目标进程后启用"
            controlState.injection.hasBlockingRuntimeFailureFor(feature) ->
                "上次运行时校验未通过：${controlState.injection.runtimeFailureDetail()}；当前可直接重试"
            supported -> "执行段补丁档案已载入；开启与关闭均校验预期指令并回读"
            else -> "模拟飞行档案未就绪：${controlState.injection.profileSummary}"
        }
        ControlFeature.HITBOX -> "旧 worker 100 槽已闭合，但当前 exact-SHA 全实体集合映射/生命周期未唯一化"
        ControlFeature.ANTI_FLASH -> when {
            !controlReady -> "连接本地控制服务后可预置；游戏启动后自动接管"
            supported -> "双模块指纹已验证，22 ms 循环写入并逐项回读"
            controlState.antiFlashArmed -> controlState.antiFlash.message.ifBlank {
                "防闪已预置；等待游戏进程和双模块精确指纹"
            }
            else -> "可先预置；目标启动后等待 GameApp 与 tprt 精确指纹"
        }

        ControlFeature.READABLE_DATA -> when {
            !targetReady -> "连接控制服务并打开目标进程后启用"
            supported -> "字段档案已载入；下方显示生命状态、击杀数与 GetDataLong(1) 的逐项实读结果"
            else -> "字段档案未就绪：${controlState.readOnlyFields.profileSummary}"
        }

        ControlFeature.FLIGHT -> when {
            !targetReady -> "连接控制服务并打开目标进程后启用"
            controlState.injection.hasBlockingRuntimeFailureFor(feature) ->
                "上次运行时校验未通过：${controlState.injection.runtimeFailureDetail()}；当前可直接重试"
            supported -> "地址档案已载入；首次操作将解析指针链并做写前、回读校验"
            else -> "飞行档案未就绪：${controlState.injection.profileSummary}"
        }

        ControlFeature.PLAYER_TELEPORT -> when {
            !targetReady -> "连接控制服务并打开目标进程后启用"
            controlState.injection.hasBlockingRuntimeFailureFor(feature) ->
                "上次运行时校验未通过：${controlState.injection.runtimeFailureDetail()}；当前可直接重试"
            supported -> "地址档案已载入；应用时解析三轴指针并执行回读与失败回滚"
            else -> "玩家传送档案未就绪：${controlState.injection.profileSummary}"
        }
    }
}

private fun ControlInjectionState.runtimeFailureDetail(): String =
    message.ifBlank { lastApplyStatus.displayLabel() }

@Composable
private fun PlayerPositionPanel(
    uiState: MainUiState,
    actions: MinixActions,
) {
    val controlState = uiState.controlState
    val targetReady = controlState.status == ControlConnectionStatus.READY &&
        controlState.hasVerifiedTargetIdentity
    val supported = ControlFeature.PLAYER_TELEPORT in controlState.supportedFeatures
    val runtimeBlocked = controlState.injection.hasBlockingRuntimeFailureFor(
        ControlFeature.PLAYER_TELEPORT,
    )
    val sessionKey = controlState.targetStartTimeTicks
    var xText by remember(sessionKey) { mutableStateOf("0") }
    var yText by remember(sessionKey) { mutableStateOf("0") }
    var zText by remember(sessionKey) { mutableStateOf("0") }
    val x = xText.toIntOrNull()
    val y = yText.toIntOrNull()
    val z = zText.toIntOrNull()
    val lastResult = controlState.playerPosition

    SectionTitle("玩家传送")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    !targetReady -> "连接控制服务与目标进程后写入三轴坐标"
                    runtimeBlocked ->
                        "上次运行时校验未通过：${controlState.injection.runtimeFailureDetail()}；当前可直接重试"
                    supported -> "地址档案已载入；应用时解析三轴指针、逐轴回读并在失败时回滚"
                    else -> "玩家传送档案未就绪：${controlState.injection.profileSummary}"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PositionAxisField(
                    label = "X",
                    value = xText,
                    onValueChange = { xText = it.positionInput() },
                    modifier = Modifier.weight(1f),
                )
                PositionAxisField(
                    label = "Y",
                    value = yText,
                    onValueChange = { yText = it.positionInput() },
                    modifier = Modifier.weight(1f),
                )
                PositionAxisField(
                    label = "Z",
                    value = zText,
                    onValueChange = { zText = it.positionInput() },
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = targetReady && supported &&
                    x != null && y != null && z != null,
                onClick = {
                    actions.onPlayerPositionApply(
                        ControlPlayerPositionRequest(
                            x = requireNotNull(x),
                            y = requireNotNull(y),
                            z = requireNotNull(z),
                        ),
                    )
                },
            ) {
                Icon(Icons.Outlined.Map, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("应用三轴位置")
            }
            if (lastResult != null) {
                Text(
                    text = buildString {
                        append(lastResult.status.displayLabel())
                        if (lastResult.effectiveX != null) append(" · X=${lastResult.effectiveX}")
                        if (lastResult.effectiveY != null) append(" Y=${lastResult.effectiveY}")
                        if (lastResult.effectiveZ != null) append(" Z=${lastResult.effectiveZ}")
                        append(" · 事务写入 ${lastResult.appliedAxisCount} 轴")
                        if (lastResult.profileId.isNotBlank()) append(" · ${lastResult.profileId}")
                    },
                    color = if (lastResult.isSuccess) Mint else Coral,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                if (lastResult.message.isNotBlank()) {
                    Text(
                        text = lastResult.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchIdPanel(
    uiState: MainUiState,
    actions: MinixActions,
) {
    val controlState = uiState.controlState
    val sessionKey = controlState.targetStartTimeTicks
    var input by remember(sessionKey) { mutableStateOf("") }
    val requestedId = parseSearchIdInput(input)
    val result = controlState.searchIdResult

    SectionTitle("SearchID 扫描")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (controlState.hasVerifiedTargetIdentity && controlState.nativeProbe.isMemoryReady) {
                    "当前版本使用 GameApp 精确指纹和固定 40 槽只读扫描"
                } else {
                    "连接控制服务并等待目标内存探测后扫描"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = { input = it.positionInput() },
                label = { Text("目标 ID（int32）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = searchIdControlEnabled(controlState, requestedId),
                onClick = { actions.onSearchId(requireNotNull(requestedId)) },
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("扫描 40 槽")
            }
            if (result != null) {
                Text(
                    text = searchIdResultLabel(result),
                    color = when (result.status) {
                        ControlSearchIdStatus.MATCH -> Mint
                        ControlSearchIdStatus.NOT_FOUND -> MaterialTheme.colorScheme.primary
                        ControlSearchIdStatus.INVALID -> Coral
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                if (result.message.isNotBlank()) {
                    Text(
                        text = result.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

internal fun parseSearchIdInput(value: String): Long? =
    value.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }

internal fun searchIdControlEnabled(
    controlState: ControlRuntimeState,
    requestedId: Long?,
): Boolean = controlState.status == ControlConnectionStatus.READY &&
    controlState.hasVerifiedTargetIdentity &&
    controlState.nativeProbe.isMemoryReady &&
    requestedId != null

internal fun searchIdResultLabel(result: ControlSearchIdResult): String = when (result.status) {
    ControlSearchIdStatus.MATCH ->
        "已命中 · ID ${result.requestedId} · 槽位 ${(result.slotIndex ?: 0) + 1}/40"
    ControlSearchIdStatus.NOT_FOUND -> "未命中 · ID ${result.requestedId} · 已扫描 40 槽"
    ControlSearchIdStatus.INVALID ->
        "扫描失败 · ${result.invalidReason?.name ?: "INVALID_RESPONSE"}"
}

@Composable
private fun PositionAxisField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun String.positionInput(): String =
    take(11).takeIf { value ->
        value.isEmpty() || value == "-" ||
            value.firstOrNull() == '-' && value.drop(1).all(Char::isDigit) ||
            value.all(Char::isDigit)
    }.orEmpty()

@Composable
private fun ReadOnlyFieldsPanel(uiState: MainUiState) {
    val fields = uiState.readOnlyFields
    SectionTitle("只读字段")
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("字段档案", fontWeight = FontWeight.Bold)
                Text(
                    text = fields.profileSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            Text(
                text = fields.profileStatus.displayLabel(),
                color = if (fields.isProfileReady) Mint else Coral,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        }
        if (fields.profileId.isNotBlank() || fields.targetVersion.isNotBlank()) {
            Text(
                text = listOfNotNull(
                    fields.profileId.takeIf(String::isNotBlank),
                    fields.targetVersion.takeIf(String::isNotBlank)?.let { "目标 $it" },
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReadOnlyFieldRow(
            label = "生命/存活状态",
            valueText = fields.lifeState.displayText(),
            status = fields.lifeState.status,
            message = fields.lifeState.message,
        )
        ReadOnlyFieldRow(
            label = "击杀数",
            valueText = fields.killCount.displayText(),
            status = fields.killCount.status,
            message = fields.killCount.message,
        )
        ReadOnlyFieldRow(
            label = "GetDataLong(1)",
            valueText = fields.dataLongSelector1.displayText(),
            status = fields.dataLongSelector1.status,
            message = fields.dataLongSelector1.message,
        )
    }
}

@Composable
private fun ReadOnlyFieldRow(
    label: String,
    valueText: String,
    status: ControlReadOnlyFieldReadStatus,
    message: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Text(
                text = valueText,
                color = when (status) {
                    ControlReadOnlyFieldReadStatus.OK -> Mint
                    ControlReadOnlyFieldReadStatus.IDLE,
                    ControlReadOnlyFieldReadStatus.FEATURE_DISABLED,
                    -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Coral
                },
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        if (message.isNotBlank() && status != ControlReadOnlyFieldReadStatus.OK) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ControlInt32FieldState.displayText(): String =
    value?.takeIf { isAvailable }?.toString() ?: status.displayLabel()

private fun ControlInt64FieldState.displayText(): String =
    value?.takeIf { isAvailable }?.let { bits ->
        "0x${bits.toULong().toString(16)}"
    } ?: status.displayLabel()

private fun ControlReadOnlyFieldProfileStatus.displayLabel(): String = when (this) {
    ControlReadOnlyFieldProfileStatus.IDLE -> "等待"
    ControlReadOnlyFieldProfileStatus.NO_PROFILE -> "未匹配"
    ControlReadOnlyFieldProfileStatus.INCOMPLETE_EVIDENCE -> "证据未闭合"
    ControlReadOnlyFieldProfileStatus.MODULE_NOT_FOUND -> "模块未就绪"
    ControlReadOnlyFieldProfileStatus.MODULE_AMBIGUOUS -> "模块不唯一"
    ControlReadOnlyFieldProfileStatus.FINGERPRINT_UNAVAILABLE -> "无指纹"
    ControlReadOnlyFieldProfileStatus.FINGERPRINT_MISMATCH -> "版本不匹配"
    ControlReadOnlyFieldProfileStatus.OFFSET_OUT_OF_RANGE -> "偏移异常"
    ControlReadOnlyFieldProfileStatus.READY -> "READY"
}

private fun ControlReadOnlyFieldReadStatus.displayLabel(): String = when (this) {
    ControlReadOnlyFieldReadStatus.IDLE -> "未读取"
    ControlReadOnlyFieldReadStatus.OK -> "已读取"
    ControlReadOnlyFieldReadStatus.SESSION_CLOSED -> "会话关闭"
    ControlReadOnlyFieldReadStatus.TARGET_CHANGED -> "目标变化"
    ControlReadOnlyFieldReadStatus.PROFILE_NOT_READY -> "档案未就绪"
    ControlReadOnlyFieldReadStatus.FEATURE_DISABLED -> "读取未启用"
    ControlReadOnlyFieldReadStatus.FIELD_NOT_AVAILABLE -> "字段不可用"
    ControlReadOnlyFieldReadStatus.READ_FAILED -> "读取失败"
}

private fun ControlInjectionProfileStatus.displayLabel(): String = when (this) {
    ControlInjectionProfileStatus.IDLE -> "等待"
    ControlInjectionProfileStatus.NO_PROFILE -> "未匹配"
    ControlInjectionProfileStatus.SCHEMA_UNSUPPORTED -> "档案版本不兼容"
    ControlInjectionProfileStatus.INCOMPLETE_EVIDENCE -> "证据未闭合"
    ControlInjectionProfileStatus.ABI_MISMATCH -> "ABI 不匹配"
    ControlInjectionProfileStatus.MODULE_NOT_FOUND -> "模块未就绪"
    ControlInjectionProfileStatus.MODULE_AMBIGUOUS -> "模块不唯一"
    ControlInjectionProfileStatus.FINGERPRINT_UNAVAILABLE -> "无模块指纹"
    ControlInjectionProfileStatus.FINGERPRINT_MISMATCH -> "模块版本不匹配"
    ControlInjectionProfileStatus.PATCH_OUT_OF_RANGE -> "标量偏移异常"
    ControlInjectionProfileStatus.INVALID_RESPONSE -> "响应异常"
    ControlInjectionProfileStatus.READY -> "READY"
}

private fun ControlInjectionApplyStatus.displayLabel(): String = when (this) {
    ControlInjectionApplyStatus.IDLE -> "未执行"
    ControlInjectionApplyStatus.APPLIED -> "已应用"
    ControlInjectionApplyStatus.ALREADY_APPLIED -> "已是目标状态"
    ControlInjectionApplyStatus.SESSION_CLOSED -> "会话关闭"
    ControlInjectionApplyStatus.TARGET_CHANGED -> "目标变化"
    ControlInjectionApplyStatus.PROFILE_NOT_READY -> "档案未就绪"
    ControlInjectionApplyStatus.FEATURE_NOT_DEFINED -> "功能未定义"
    ControlInjectionApplyStatus.PRECONDITION_READ_FAILED -> "前置读失败"
    ControlInjectionApplyStatus.EXPECTED_VALUE_MISMATCH -> "expected value 不匹配"
    ControlInjectionApplyStatus.BACKEND_UNAVAILABLE -> "后端未就绪"
    ControlInjectionApplyStatus.WRITE_FAILED -> "写入失败"
    ControlInjectionApplyStatus.VERIFY_FAILED -> "回读验证失败"
    ControlInjectionApplyStatus.ROLLBACK_FAILED -> "回滚失败"
    ControlInjectionApplyStatus.INVALID_RESPONSE -> "响应异常"
}

@Composable
private fun PresetsScreen(
    uiState: MainUiState,
    actions: MinixActions,
) {
    val haptic = LocalHapticFeedback.current
    var showSaveDialog by remember { mutableStateOf(false) }
    PageColumn(title = "本地预设", subtitle = "设备内保存的外观、布局和快捷设置") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存当前")
            }
            IconButton(onClick = actions.onImportData) {
                Icon(Icons.Outlined.FileUpload, contentDescription = "导入本地数据")
            }
            IconButton(onClick = actions.onExportData) {
                Icon(Icons.Outlined.FileDownload, contentDescription = "导出本地数据")
            }
        }
        uiState.presets.forEach { preset ->
            PresetCard(
                name = preset.name,
                detail = preset.description,
                icon = if (preset.gridEnabled) Icons.Outlined.GridView else Icons.Outlined.Tune,
                selected = uiState.settings.selectedPreset == preset.name,
                onClick = {
                    if (uiState.settings.hapticsEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    actions.onPresetSelected(preset)
                },
                onDelete = if (preset.isBuiltIn) null else ({ actions.onDeletePreset(preset.id) }),
            )
        }
    }
    if (showSaveDialog) {
        NameDialog(
            title = "保存当前预设",
            initialValue = "我的预设",
            confirmLabel = "保存",
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                actions.onSavePreset(name)
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun LibraryScreen(
    uiState: MainUiState,
    audioPlaying: Boolean,
    actions: MinixActions,
) {
    var showAddPointDialog by remember { mutableStateOf(false) }
    var editingPoint by remember { mutableStateOf<me.dartcv.minix.core.model.MapPoint?>(null) }
    var entryQuery by remember { mutableStateOf("") }
    PageColumn(title = "本地库", subtitle = "设备内的活动记录、媒体与离线资料。") {
        SectionTitle("媒体")
        MediaSetting(
            icon = Icons.Outlined.Image,
            title = "主页背景",
            detail = if (uiState.settings.backgroundUri == null) "未选择" else "已选择本地图片",
            onPick = actions.onPickBackground,
            onClear = if (uiState.settings.backgroundUri == null) null else actions.onClearBackground,
        )
        MediaSetting(
            icon = Icons.Outlined.MusicNote,
            title = "本地音频",
            detail = when {
                uiState.settings.audioUri == null -> "未选择"
                audioPlaying -> "正在播放"
                else -> "已选择本地音频"
            },
            onPick = actions.onPickAudio,
            onClear = if (uiState.settings.audioUri == null) null else actions.onClearAudio,
            onPlay = if (uiState.settings.audioUri == null) null else actions.onToggleAudio,
            playing = audioPlaying,
        )

        SectionTitle("传感器预览")
        FeaturePreviewCard(
            icon = Icons.Outlined.Sensors,
            title = "姿态读数",
            detail = "Pitch ${uiState.runtime.sensorPreview.pitch}° · " +
                "Roll ${uiState.runtime.sensorPreview.roll}° · " +
                "Yaw ${uiState.runtime.sensorPreview.yaw}°",
        )

        SectionTitle("本地条目")
        OutlinedTextField(
            value = entryQuery,
            onValueChange = { entryQuery = it.take(32) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("搜索名称或分组") },
        )
        DemoEntries.all
            .filter { entry ->
                entryQuery.isBlank() ||
                    entry.name.contains(entryQuery, ignoreCase = true) ||
                    entry.group.contains(entryQuery, ignoreCase = true)
            }
            .forEach { entry ->
                LocalEntryRow(
                    entry = entry,
                    favorite = entry.id in uiState.favoriteEntryIds,
                    onToggleFavorite = { actions.onToggleFavoriteEntry(entry.id) },
                )
            }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("点位与布局")
            IconButton(onClick = { showAddPointDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "新增点位")
            }
        }
        uiState.mapPoints.forEach { point ->
            MapPointRow(
                point = point,
                onRename = { editingPoint = point },
                onDelete = { actions.onDeleteMapPoint(point.id) },
            )
        }

        SectionTitle("活动记录")
        uiState.runtime.activityLog.forEach { entry ->
            Text(
                text = entry,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }

    if (showAddPointDialog) {
        NameDialog(
            title = "新增点位",
            initialValue = "新点位",
            confirmLabel = "新增",
            onDismiss = { showAddPointDialog = false },
            onConfirm = { name ->
                actions.onAddMapPoint(name)
                showAddPointDialog = false
            },
        )
    }
    editingPoint?.let { point ->
        NameDialog(
            title = "重命名点位",
            initialValue = point.name,
            confirmLabel = "保存",
            onDismiss = { editingPoint = null },
            onConfirm = { name ->
                actions.onRenameMapPoint(point.id, name)
                editingPoint = null
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    uiState: MainUiState,
    actions: MinixActions,
) {
    val controlStatus = uiState.controlState.status
    val controlBusy = controlStatus == ControlConnectionStatus.REQUESTING ||
        controlStatus == ControlConnectionStatus.CONNECTING
    PageColumn(title = "设置与隐私", subtitle = "设备内的权限、外观与数据管理") {
        ActionSetting(
            icon = Icons.Outlined.Security,
            title = "权限状态",
            detail = "悬浮窗口：${if (uiState.permissions.canDrawOverlays) "已授权" else "未授权"} · " +
                "通知：${if (uiState.permissions.notificationGranted) "已授权" else "未授权"}",
            actionLabel = if (uiState.permissions.canDrawOverlays) "启动" else "授权",
            onClick = actions.onStartOverlay,
        )
        ActionSetting(
            icon = Icons.Outlined.Security,
            title = "同 UID 控制服务",
            detail = uiState.controlState.message,
            actionLabel = when {
                controlBusy -> "处理中"
                controlStatus == ControlConnectionStatus.READY -> "断开"
                else -> "连接服务"
            },
            enabled = !controlBusy,
            onClick = if (controlStatus == ControlConnectionStatus.READY) {
                actions.onDisconnectControlService
            } else {
                actions.onConnectControlService
            },
        )
        FeaturePreviewCard(
            icon = Icons.Outlined.Info,
            title = "最近操作",
            detail = uiState.runtime.activityLog.firstOrNull() ?: "暂无记录",
        )
        ChoiceSetting(
            icon = Icons.Outlined.DarkMode,
            title = "外观模式",
            options = me.dartcv.minix.core.model.ThemeMode.entries,
            selected = uiState.settings.themeMode,
            label = { it.label },
            onSelected = actions.onThemeModeChanged,
        )
        ChoiceSetting(
            icon = Icons.Outlined.Palette,
            title = "强调色",
            options = me.dartcv.minix.core.model.AccentOption.entries,
            selected = uiState.settings.accentOption,
            label = { it.label },
            onSelected = actions.onAccentChanged,
        )
        ActionSetting(
            icon = Icons.Outlined.FileUpload,
            title = "导入本地数据",
            detail = "校验版本、字段与范围后一次性写入；错误文件不会覆盖当前配置。",
            actionLabel = "导入",
            onClick = actions.onImportData,
        )
        ActionSetting(
            icon = Icons.Outlined.FileDownload,
            title = "导出本地数据",
            detail = "通过系统文件选择器导出 JSON，可再次导入。",
            actionLabel = "导出",
            onClick = actions.onExportData,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Coral)
                Column(modifier = Modifier.weight(1f)) {
                    Text("重置本地配置", fontWeight = FontWeight.Bold)
                    Text(
                        "恢复开关、透明度、尺寸和悬浮位置的默认值。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                FilledTonalButton(onClick = actions.onResetSettings) {
                    Text("重置")
                }
            }
        }
    }
}

@Composable
private fun PageColumn(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun ToggleSetting(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingSurface(
        modifier = Modifier.clickable(
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) },
        ),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SliderSetting(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(valueLabel, color = Mint, fontWeight = FontWeight.Bold)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
        }
    }
}

@Composable
private fun SettingSurface(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PresetCard(
    name: String,
    detail: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = if (selected) BorderStroke(1.dp, Mint) else null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) Mint else MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (selected) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "已选择", tint = Mint)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除预设", tint = Coral)
                }
            }
        }
    }
}

@Composable
private fun FeaturePreviewCard(
    icon: ImageVector,
    title: String,
    detail: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceSetting(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelected(option) },
                        label = { Text(label(option), maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Black,
        fontSize = 16.sp,
    )
}

@Composable
private fun MediaSetting(
    icon: ImageVector,
    title: String,
    detail: String,
    onPick: () -> Unit,
    onClear: (() -> Unit)?,
    onPlay: (() -> Unit)? = null,
    playing: Boolean = false,
) {
    SettingSurface {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlay != null) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "暂停音频" else "播放音频",
                )
            }
        }
        IconButton(onClick = onPick) {
            Icon(Icons.Outlined.FileUpload, contentDescription = "选择文件")
        }
        if (onClear != null) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "清除文件", tint = Coral)
            }
        }
    }
}

@Composable
private fun LocalEntryRow(
    entry: LocalEntry,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    SettingSurface {
        Icon(Icons.AutoMirrored.Outlined.LibraryBooks, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, fontWeight = FontWeight.Bold)
            Text(
                text = "${entry.group} · ${entry.detail}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MapPointRow(
    point: MapPoint,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    SettingSurface {
        Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(point.name, fontWeight = FontWeight.Bold)
            Text(
                text = "${point.group} · x ${(point.x * 100).toInt()}% · y ${(point.y * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = "重命名点位")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除点位", tint = Coral)
        }
    }
}

@Composable
private fun ActionSetting(
    icon: ImageVector,
    title: String,
    detail: String,
    actionLabel: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingSurface {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(onClick = onClick, enabled = enabled) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(64) },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun LocalBackgroundImage(uriValue: String?) {
    val context = LocalContext.current
    var image by remember(uriValue) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriValue) {
        image = if (uriValue.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { decodeSampledImage(context, uriValue.toUri()) }.getOrNull()
            }
        }
    }
    image?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.3f),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun decodeSampledImage(context: Context, uri: Uri): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1600) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    return bitmap.asImageBitmap()
}
