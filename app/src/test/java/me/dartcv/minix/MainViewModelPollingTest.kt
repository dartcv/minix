package me.dartcv.minix

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.dartcv.minix.root.RootAntiFlashState
import me.dartcv.minix.root.RootAntiFlashStatus
import me.dartcv.minix.root.RootConnectionStatus
import me.dartcv.minix.root.RootRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelPollingTest {
    @Test
    fun armingInterruptsSlowPollingAndRunsStartupMaintenanceImmediately() = runTest {
        val states = MutableStateFlow(
            RootRuntimeState(
                status = RootConnectionStatus.READY,
                uid = 123,
                targetPid = 456,
                targetUid = 123,
                targetStartTimeTicks = "789",
            ),
        )
        var refreshCalls = 0
        var antiFlashCalls = 0
        val polling = async {
            states.runControlPolling(
                maintainAntiFlash = { antiFlashCalls += 1 },
                refreshTargetState = { refreshCalls += 1 },
            )
        }
        runCurrent()
        assertEquals(1, refreshCalls)

        advanceTimeBy(500)
        states.value = states.value.copy(
            antiFlashArmed = true,
            antiFlash = RootAntiFlashState(
                status = RootAntiFlashStatus.WAITING_FOR_TARGET,
                requestedEnabled = true,
            ),
        )
        runCurrent()

        assertEquals(1, antiFlashCalls)
        polling.cancel()
    }
}
