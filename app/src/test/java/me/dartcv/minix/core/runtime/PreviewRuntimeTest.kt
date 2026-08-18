package me.dartcv.minix.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRuntimeTest {
    @Test
    fun recordPrependsMessagesToTheActivityLog() {
        val runtime = PreviewRuntime()

        runtime.record("first")
        runtime.record("second")

        assertEquals("second", runtime.state.value.activityLog[0])
        assertEquals("first", runtime.state.value.activityLog[1])
    }

    @Test
    fun refreshRecordsAVisibleLocalRefreshEvent() {
        val runtime = PreviewRuntime()
        val beforeRefresh = runtime.state.value.refreshedAt

        runtime.refresh()

        assertEquals("本地状态已刷新", runtime.state.value.activityLog.first())
        assertTrue(runtime.state.value.refreshedAt >= beforeRefresh)
    }

    @Test
    fun activityLogKeepsTheSixMostRecentEntries() {
        val runtime = PreviewRuntime()

        (1..7).forEach { runtime.record("event-$it") }

        assertEquals(
            listOf("event-7", "event-6", "event-5", "event-4", "event-3", "event-2"),
            runtime.state.value.activityLog,
        )
    }
}
