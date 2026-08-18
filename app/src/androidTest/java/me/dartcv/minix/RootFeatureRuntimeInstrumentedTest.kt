package me.dartcv.minix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import me.dartcv.minix.root.RootConnectionStatus
import me.dartcv.minix.root.RootFeature
import me.dartcv.minix.root.RootReadOnlyFieldReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootFeatureRuntimeInstrumentedTest {
    @Test
    fun currentTargetExposesRecoveredFeaturesAndReadableFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as MinixApplication).controlController

        controller.requestAndConnect()
        controller.openOrRefreshTarget()
        val opened = controller.state.value

        controller.setFeatureEnabled(RootFeature.READABLE_DATA, true)
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
                        .sortedBy(RootFeature::wireId)
                        .joinToString(",", transform = RootFeature::wireId),
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

        assertEquals(RootConnectionStatus.READY, opened.status)
        assertTrue("target identity was not verified: ${opened.targetSummary}", opened.hasVerifiedTargetIdentity)
        assertTrue(RootFeature.FLIGHT in readable.supportedFeatures)
        assertTrue(RootFeature.FAKE_FLIGHT in readable.supportedFeatures)
        assertTrue(RootFeature.PLAYER_TELEPORT in readable.supportedFeatures)
        assertTrue(RootFeature.READABLE_DATA in readable.supportedFeatures)
        assertEquals(
            RootReadOnlyFieldReadStatus.OK,
            readable.readOnlyFields.dataLongSelector1.status,
        )
        assertTrue(readable.readOnlyFields.dataLongSelector1.value != null)
    }

    private companion object {
        const val RESULT_FILE = "root-feature-runtime-probe.txt"
    }
}
