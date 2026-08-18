package me.dartcv.minix.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.dartcv.minix.core.model.AppSettings
import me.dartcv.minix.ui.theme.Coral
import me.dartcv.minix.ui.theme.Ink
import me.dartcv.minix.ui.theme.Mint

@Composable
fun OverlayPanel(
    settings: AppSettings,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onGridChanged: (Boolean) -> Unit,
    onMotionPreviewChanged: (Boolean) -> Unit,
    onEffectHighlightChanged: (Boolean) -> Unit,
    onOpacityChanged: (Float) -> Unit,
) {
    val expanded = settings.overlayExpanded
    if (!expanded) {
        Surface(
            modifier = Modifier
                .size(68.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable { onExpandedChange(true) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 10.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "M",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .alpha(settings.panelOpacity),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                        ) { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DragHandle, contentDescription = "拖动悬浮面板")
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("MINIX", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${settings.selectedPreset} · 本地预览",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = { onExpandedChange(false) }) {
                    Icon(Icons.Outlined.ExpandLess, contentDescription = "收起")
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Coral)
                }
            }

            OverlaySettingRow(
                title = "视觉网格",
                icon = {
                    Icon(Icons.Outlined.GridView, contentDescription = null, tint = Mint)
                },
                control = {
                    Switch(checked = settings.gridEnabled, onCheckedChange = onGridChanged)
                },
            )

            OverlaySettingRow(
                title = "移动轨迹",
                icon = {
                    Icon(Icons.Outlined.Sensors, contentDescription = null, tint = Mint)
                },
                control = {
                    Switch(
                        checked = settings.motionPreviewEnabled,
                        onCheckedChange = onMotionPreviewChanged,
                    )
                },
            )

            OverlaySettingRow(
                title = "效果高亮",
                icon = {
                    Icon(Icons.Outlined.Palette, contentDescription = null, tint = Mint)
                },
                control = {
                    Switch(
                        checked = settings.effectHighlightEnabled,
                        onCheckedChange = onEffectHighlightChanged,
                    )
                },
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Opacity, contentDescription = null, tint = Mint)
                    Spacer(Modifier.width(10.dp))
                    Text("透明度", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(
                        "${(settings.panelOpacity * 100).toInt()}%",
                        color = Mint,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = settings.panelOpacity,
                    onValueChange = onOpacityChanged,
                    valueRange = 0.55f..1f,
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = Ink,
                        )
                        Text(
                            if (settings.gridEnabled) "网格预览已启用" else "简洁预览模式",
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlaySettingRow(
    title: String,
    icon: @Composable () -> Unit,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        control()
    }
}
