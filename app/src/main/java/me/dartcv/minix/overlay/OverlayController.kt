package me.dartcv.minix.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.dartcv.minix.core.model.OverlaySessionState

object OverlayController {
    private val _state = MutableStateFlow(OverlaySessionState.STOPPED)
    val state: StateFlow<OverlaySessionState> = _state.asStateFlow()

    fun start(context: Context) {
        _state.value = OverlaySessionState.STARTING
        val intent = Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: IllegalStateException) {
            _state.value = if (Settings.canDrawOverlays(context)) {
                OverlaySessionState.STOPPED
            } else {
                OverlaySessionState.NEEDS_PERMISSION
            }
        } catch (_: SecurityException) {
            _state.value = if (Settings.canDrawOverlays(context)) {
                OverlaySessionState.STOPPED
            } else {
                OverlaySessionState.NEEDS_PERMISSION
            }
        }
    }

    fun stop(context: Context) {
        val stopped = context.stopService(Intent(context, OverlayService::class.java))
        if (!stopped) {
            _state.value = OverlaySessionState.STOPPED
        }
    }

    internal fun update(state: OverlaySessionState) {
        _state.value = state
    }
}
