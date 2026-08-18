package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTargetCatalogTest {
    @Test
    fun runtimeStateDefaultsToOfficialChannelSoLaunchIsAvailableBeforeProcessStarts() {
        val state = ControlRuntimeState()

        assertEquals(ControlTargetChannel.OFFICIAL.packageName, state.targetPackage)
        assertTrue(state.targetSummary.contains(ControlTargetChannel.OFFICIAL.packageName))
    }

    @Test
    fun recoveredCatalogKeepsAllNineChannelsInProbeOrder() {
        assertEquals(
            listOf(
                "com.minitech.miniworld",
                "com.minitech.miniworld.vivo",
                "com.minitech.miniworld.nearme.gamecenter",
                "com.minitech.miniworld.TMobile.mi",
                "com.minitech.miniworld.m4399",
                "com.minitech.miniworld.uc",
                "com.tencent.tmgp.minitech.miniworld",
                "com.minitech.miniworld.kuaishou",
                "com.minitech.miniworld.meta",
            ),
            ControlTargetCatalog.entries.map(ControlTargetChannel::packageName),
        )
        assertEquals(9, ControlTargetCatalog.entries.map(ControlTargetChannel::packageName).toSet().size)
        assertTrue(ControlTargetCatalog.entries.all { ControlProtocol.isValidPackageName(it.packageName) })
    }

    @Test
    fun packageLookupReturnsTheTypedChannel() {
        assertSame(
            ControlTargetChannel.TENCENT,
            ControlTargetCatalog.fromPackageName("com.tencent.tmgp.minitech.miniworld"),
        )
        assertEquals("233服", ControlTargetChannel.CHANNEL_233.channelLabel)
        assertEquals("快手服", ControlTargetChannel.KUAISHOU.channelLabel)
    }
}
