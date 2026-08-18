package me.dartcv.minix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import me.dartcv.minix.control.ControlConnectionStatus
import me.dartcv.minix.control.ControlFeature
import me.dartcv.minix.control.ControlReadOnlyFieldReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlFeatureRuntimeInstrumentedTest {
    @Test
    fun currentTargetExposesRecoveredFeaturesAndReadableFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as MinixApplication).controlController

        controller.requestAndConnect()
        controller.openOrRefreshTarget()
        val opened = controller.state.value

        controller.setFeatureEnabled(ControlFeature.READABLE_DATA, true)
        controller.refreshTargetState()
        val readable = controller.state.value

        File(context.filesDir, RESULT_FILE).writeText(
            buildString {
                appendLine("status=${readable.status}")
                appendLine("message=${readable.message}")
                appendLine("targetPackage=${readable.targetPackage}")
                appendLine("targetPid=${readable.targetPid}")
                appendLine("targetUid=${readable.targetUid}")
                appendLine("serviceUid=${readable.uid}")
                appendLine("targetStartTimeTicks=${readable.targetStartTimeTicks}")
                appendLine(
                    "supportedFeatures=" + readable.supportedFeatures
                        .sortedBy(ControlFeature::wireId)
                        .joinToString(",", transform = ControlFeature::wireId),
                )
                appendLine("injectionProfile=${readable.injection.profileStatus}")
                appendLine("injectionProfileId=${readable.injection.profileId}")
                appendLine("fieldProfile=${readable.readOnlyFields.profileStatus}")
                appendLine("fieldProfileId=${readable.readOnlyFields.profileId}")
                appendLine("lifeStatus=${readable.readOnlyFields.lifeState.status}")
                appendLine("lifeValue=${readable.readOnlyFields.lifeState.value}")
                appendLine("killStatus=${readable.readOnlyFields.killCount.status}")
                appendLine("killValue=${readable.readOnlyFields.killCount.value}")
                appendLine("dataLongStatus=${readable.readOnlyFields.dataLongSelector1.status}")
                appendLine("dataLongValue=${readable.readOnlyFields.dataLongSelector1.value}")
                appendLine("dataLongMessage=${readable.readOnlyFields.dataLongSelector1.message}")
            },
        )

        assertEquals(ControlConnectionStatus.READY, opened.status)
        assertTrue("target identity was not verified: ${opened.targetSummary}", opened.hasVerifiedTargetIdentity)
        assertTrue(ControlFeature.FLIGHT in readable.supportedFeatures)
        assertTrue(ControlFeature.FAKE_FLIGHT in readable.supportedFeatures)
        assertTrue(ControlFeature.PLAYER_TELEPORT in readable.supportedFeatures)
        assertTrue(ControlFeature.READABLE_DATA in readable.supportedFeatures)
        assertEquals(
            ControlReadOnlyFieldReadStatus.OK,
            readable.readOnlyFields.dataLongSelector1.status,
        )
        assertTrue(readable.readOnlyFields.dataLongSelector1.value != null)
    }

    private companion object {
        const val RESULT_FILE = "control-feature-runtime-probe.txt"
    }
}
