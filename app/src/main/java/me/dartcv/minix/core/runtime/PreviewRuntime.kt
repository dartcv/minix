package me.dartcv.minix.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.dartcv.minix.core.model.RuntimeState

class PreviewRuntime {
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    fun refresh() {
        updateLog("本地状态已刷新")
    }

    fun record(message: String) {
        updateLog(message)
    }

    private fun updateLog(message: String) {
        val previousSensor = _state.value.sensorPreview
        _state.value = _state.value.copy(
            refreshedAt = System.currentTimeMillis(),
            sensorPreview = previousSensor.copy(
                pitch = ((previousSensor.pitch + 4.3f) % 8f) - 4f,
                roll = ((previousSensor.roll + 11.8f) % 8f) - 4f,
                yaw = (previousSensor.yaw + 2.4f) % 360f,
            ),
            activityLog = listOf(message) + _state.value.activityLog.take(5),
        )
    }
}
