package me.dartcv.minix.overlay

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.dartcv.minix.MainActivity
import me.dartcv.minix.R
import me.dartcv.minix.core.model.AppSettings
import me.dartcv.minix.core.model.OverlaySessionState
import me.dartcv.minix.core.storage.SettingsRepository
import me.dartcv.minix.ui.theme.MinixTheme

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stopping = AtomicBoolean(false)
    private var terminalState = OverlaySessionState.STOPPED

    private lateinit var appOpsManager: AppOpsManager
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewTreeOwner: OverlayViewTreeOwner
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var watchingOverlayPermission = false

    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (
            operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW &&
            packageName == this.packageName
        ) {
            serviceScope.launch {
                if (!Settings.canDrawOverlays(this@OverlayService)) {
                    stopOverlay(OverlaySessionState.NEEDS_PERMISSION)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appOpsManager = getSystemService(AppOpsManager::class.java)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(this)
        viewTreeOwner = OverlayViewTreeOwner()
        try {
            appOpsManager.startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageName,
                overlayPermissionListener,
            )
            watchingOverlayPermission = true
        } catch (_: SecurityException) {
            // Window operations still re-check the permission before updating.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopOverlay(OverlaySessionState.NEEDS_PERMISSION)
            return START_NOT_STICKY
        }

        try {
            startInForeground()
        } catch (_: IllegalStateException) {
            stopOverlay()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopOverlay()
            return START_NOT_STICKY
        }
        if (overlayView == null) {
            serviceScope.launch {
                showOverlay(settingsRepository.settings.first())
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(removedTaskIntent: Intent?) {
        stopOverlay()
        super.onTaskRemoved(removedTaskIntent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        serviceScope.launch {
            val settings = settingsRepository.settings.first()
            updatePanelBounds(settings.overlayExpanded, settings.panelScale)
        }
    }

    override fun onDestroy() {
        if (watchingOverlayPermission) {
            appOpsManager.stopWatchingMode(overlayPermissionListener)
            watchingOverlayPermission = false
        }
        removeOverlayView()
        serviceScope.cancel()
        viewTreeOwner.markDestroyed()
        OverlayController.update(terminalState)
        super.onDestroy()
    }

    private fun showOverlay(initialSettings: AppSettings) {
        if (overlayView != null || stopping.get()) return

        val params = createLayoutParams(initialSettings)
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(viewTreeOwner)
            setViewTreeViewModelStoreOwner(viewTreeOwner)
            setViewTreeSavedStateRegistryOwner(viewTreeOwner)
            setContent {
                val settings = settingsRepository.settings.collectAsState(initial = initialSettings).value
                MinixTheme(
                    themeMode = settings.themeMode,
                    accentOption = settings.accentOption,
                ) {
                    LaunchedEffect(settings.overlayExpanded, settings.panelScale) {
                        updatePanelBounds(settings.overlayExpanded, settings.panelScale)
                    }
                    OverlayPanel(
                        settings = settings,
                        onDrag = ::moveBy,
                        onDragEnd = ::finishDrag,
                        onExpandedChange = { expanded ->
                            serviceScope.launch {
                                settingsRepository.setOverlayExpanded(expanded)
                            }
                        },
                        onStop = { stopOverlay() },
                        onGridChanged = { enabled ->
                            serviceScope.launch {
                                settingsRepository.setGridEnabled(enabled)
                            }
                        },
                        onMotionPreviewChanged = { enabled ->
                            serviceScope.launch {
                                settingsRepository.setMotionPreviewEnabled(enabled)
                            }
                        },
                        onEffectHighlightChanged = { enabled ->
                            serviceScope.launch {
                                settingsRepository.setEffectHighlightEnabled(enabled)
                            }
                        },
                        onOpacityChanged = { value ->
                            serviceScope.launch {
                                settingsRepository.setPanelOpacity(value)
                            }
                        },
                    )
                }
            }
        }

        try {
            clampToDisplay(params)
            windowManager.addView(view, params)
            overlayView = view
            layoutParams = params
            viewTreeOwner.markVisible()
            OverlayController.update(OverlaySessionState.ACTIVE)
        } catch (_: SecurityException) {
            stopOverlay(OverlaySessionState.NEEDS_PERMISSION)
        } catch (_: WindowManager.BadTokenException) {
            stopOverlay()
        }
    }

    private fun createLayoutParams(settings: AppSettings): WindowManager.LayoutParams {
        val width = dp(if (settings.overlayExpanded) 304f * settings.panelScale else 72f)
        val height = dp(if (settings.overlayExpanded) 390f * settings.panelScale else 72f)
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX
            y = settings.overlayY
        }
    }

    private fun updatePanelBounds(expanded: Boolean, scale: Float) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.width = dp(if (expanded) 304f * scale else 72f)
        params.height = dp(if (expanded) 390f * scale else 72f)
        clampToDisplay(params)
        try {
            windowManager.updateViewLayout(view, params)
            persistOverlayPosition()
        } catch (_: IllegalArgumentException) {
            stopOverlay()
        } catch (_: SecurityException) {
            stopOverlay(OverlaySessionState.NEEDS_PERMISSION)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampToDisplay(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            stopOverlay()
        } catch (_: SecurityException) {
            stopOverlay(OverlaySessionState.NEEDS_PERMISSION)
        }
    }

    private fun clampToDisplay(params: WindowManager.LayoutParams) {
        val constrained = constrainOverlayLayout(
            layout = OverlayLayout(
                x = params.x,
                y = params.y,
                width = params.width,
                height = params.height,
            ),
            bounds = safeDisplayBounds(),
        )
        params.x = constrained.x
        params.y = constrained.y
        params.width = constrained.width
        params.height = constrained.height
    }

    private fun safeDisplayBounds(): OverlayBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return OverlayBounds(
                left = bounds.left + insets.left,
                top = bounds.top + insets.top,
                right = bounds.right - insets.right,
                bottom = bounds.bottom - insets.bottom,
            )
        }

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getMetrics)
        return OverlayBounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun persistOverlayPosition() {
        val params = layoutParams ?: return
        val x = params.x
        val y = params.y
        serviceScope.launch {
            settingsRepository.updateOverlayPosition(x, y)
        }
    }

    private fun finishDrag() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val snapped = snapOverlayLayout(
            layout = OverlayLayout(params.x, params.y, params.width, params.height),
            bounds = safeDisplayBounds(),
            snapDistance = dp(20f),
        )
        params.x = snapped.x
        params.y = snapped.y
        params.width = snapped.width
        params.height = snapped.height
        try {
            windowManager.updateViewLayout(view, params)
            persistOverlayPosition()
        } catch (_: IllegalArgumentException) {
            stopOverlay()
        } catch (_: SecurityException) {
            stopOverlay(OverlaySessionState.NEEDS_PERMISSION)
        }
    }

    private fun startInForeground() {
        createNotificationChannel()
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_minix_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                R.drawable.ic_minix_notification,
                getString(R.string.overlay_notification_stop),
                stopPendingIntent,
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopOverlay(finalState: OverlaySessionState = OverlaySessionState.STOPPED) {
        if (!stopping.compareAndSet(false, true)) {
            if (finalState == OverlaySessionState.NEEDS_PERMISSION) {
                terminalState = finalState
            }
            OverlayController.update(terminalState)
            return
        }
        terminalState = finalState
        removeOverlayView()
        stopForeground(STOP_FOREGROUND_REMOVE)
        OverlayController.update(terminalState)
        stopSelf()
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        overlayView = null
        layoutParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // The view is already detached.
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "me.dartcv.minix.overlay.START"
        const val ACTION_STOP = "me.dartcv.minix.overlay.STOP"
        private const val CHANNEL_ID = "minix_overlay"
        private const val NOTIFICATION_ID = 4102
    }
}
