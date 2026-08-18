package me.dartcv.minix.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPolicyTest {
    @Test
    fun currentChannelIsCertificateSharedUidBinder() {
        val policy = TransportPolicy.current

        assertEquals("CERTIFICATE_SHARED_UID_BINDER", TransportPolicy.CHANNEL_ID)
        assertEquals(TransportChannel.CERTIFICATE_SHARED_UID_BINDER, policy.channel)
        assertEquals(TransportPolicy.CHANNEL_ID, policy.channel.wireName)
        assertTrue(policy.certificateBound)
        assertTrue(policy.sharedUidRequired)
    }

    @Test
    fun serviceBoundaryIsExplicitAndNonExported() {
        val policy = TransportPolicy.current

        assertFalse(policy.serviceExported)
        assertEquals(":control", policy.serviceProcessName)
        assertEquals("me.dartcv.minix.control.IControlBridge", policy.aidlDescriptor)
    }
}
