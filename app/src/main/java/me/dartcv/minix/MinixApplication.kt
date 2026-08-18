package me.dartcv.minix

import android.app.Application
import me.dartcv.minix.control.ControlController

class MinixApplication : Application() {
    private val controlControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ControlController(this)
    }

    /** Shared-UID Binder control client used by the UI and overlay. */
    val controlController: ControlController by controlControllerDelegate
}
