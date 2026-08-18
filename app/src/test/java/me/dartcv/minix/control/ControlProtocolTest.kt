package me.dartcv.minix.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProtocolTest {
    @Test
    fun protocolVersionIncludesSearchIdBridge() {
        assertEquals(11, ControlProtocol.VERSION)
    }

    @Test
    fun packageValidationRejectsShellTextAndAcceptsAndroidPackageNames() {
        assertTrue(ControlProtocol.isValidPackageName("com.example.target"))
        assertTrue(ControlProtocol.processMatchesPackage("com.example.target:worker", "com.example.target"))
        assertFalse(ControlProtocol.isValidPackageName("com.example.target;id"))
        assertFalse(ControlProtocol.isValidPackageName("/proc/1"))
        assertFalse(ControlProtocol.processMatchesPackage("com.example.targeted", "com.example.target"))
    }

    @Test
    fun featureWireIdsAreStableAndUnknownValuesAreRejected() {
        assertEquals(ControlFeature.AIM, ControlFeature.fromWireId("aim"))
        assertEquals(ControlFeature.FAKE_FLIGHT, ControlFeature.fromWireId("fake_flight"))
        assertEquals(ControlFeature.ANTI_FLASH, ControlFeature.fromWireId("anti_flash"))
        assertEquals(ControlFeature.READABLE_DATA, ControlFeature.fromWireId("readable_data"))
        assertNull(ControlFeature.fromWireId("arbitrary_shell"))
        assertEquals(8, ControlFeature.entries.size)
    }
}
