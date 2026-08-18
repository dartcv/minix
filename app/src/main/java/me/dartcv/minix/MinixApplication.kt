package me.dartcv.minix

import android.app.Application
import me.dartcv.minix.root.RootController

class MinixApplication : Application() {
    private val controlControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RootController(this)
    }

    /**
     * Shared-UID Binder control client used by the UI and overlay.
     *
     * The implementation type intentionally stays in the historical `root`
     * package so the existing Binder/AIDL descriptor remains unchanged.
     */
    val controlController: RootController by controlControllerDelegate

    /**
     * Source-compatibility alias for integrations built before the control
     * service terminology migration. New code should use [controlController].
     */
    @Deprecated(
        message = "Use controlController; the root naming is retained only for Binder compatibility",
        replaceWith = ReplaceWith("controlController"),
        level = DeprecationLevel.WARNING,
    )
    val rootController: RootController
        get() = controlController
}
