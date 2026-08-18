package me.dartcv.minix.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProtocolTest {
    @Test
    fun protocolVersionIncludesSearchIdBridge() {
        assertEquals(10, RootProtocol.VERSION)
    }

    @Test
    fun packageValidationRejectsShellTextAndAcceptsAndroidPackageNames() {
        assertTrue(RootProtocol.isValidPackageName("com.example.target"))
        assertTrue(RootProtocol.processMatchesPackage("com.example.target:worker", "com.example.target"))
        assertFalse(RootProtocol.isValidPackageName("com.example.target;id"))
        assertFalse(RootProtocol.isValidPackageName("/proc/1"))
        assertFalse(RootProtocol.processMatchesPackage("com.example.targeted", "com.example.target"))
    }

    @Test
    fun featureWireIdsAreStableAndUnknownValuesAreRejected() {
        assertEquals(RootFeature.AIM, RootFeature.fromWireId("aim"))
        assertEquals(RootFeature.FAKE_FLIGHT, RootFeature.fromWireId("fake_flight"))
        assertEquals(RootFeature.ANTI_FLASH, RootFeature.fromWireId("anti_flash"))
        assertEquals(RootFeature.READABLE_DATA, RootFeature.fromWireId("readable_data"))
        assertNull(RootFeature.fromWireId("arbitrary_shell"))
        assertEquals(8, RootFeature.entries.size)
    }
}
