package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTargetCatalogTest {
    @Test
    fun runtimeStateDefaultsToOfficialChannelSoLaunchIsAvailableBeforeProcessStarts() {
        val state = RootRuntimeState()

        assertEquals(RootTargetChannel.OFFICIAL.packageName, state.targetPackage)
        assertTrue(state.targetSummary.contains(RootTargetChannel.OFFICIAL.packageName))
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
            RootTargetCatalog.entries.map(RootTargetChannel::packageName),
        )
        assertEquals(9, RootTargetCatalog.entries.map(RootTargetChannel::packageName).toSet().size)
        assertTrue(RootTargetCatalog.entries.all { RootProtocol.isValidPackageName(it.packageName) })
    }

    @Test
    fun packageLookupReturnsTheTypedChannel() {
        assertSame(
            RootTargetChannel.TENCENT,
            RootTargetCatalog.fromPackageName("com.tencent.tmgp.minitech.miniworld"),
        )
        assertEquals("233服", RootTargetChannel.CHANNEL_233.channelLabel)
        assertEquals("快手服", RootTargetChannel.KUAISHOU.channelLabel)
    }
}
