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
    fun rootAndSuPathsAreDisabledAndMarkedDeprecated() {
        val policy = TransportPolicy.current

        assertFalse(policy.rootPrivilegeRequired)
        assertFalse(policy.suShellEntryPointEnabled)
        assertFalse(policy.rootEntryPointEnabled)
        assertTrue(policy.rootSchemeDeprecated)
    }

    @Test
    fun historicalRootNamespaceIsCompatibilityOnlyAndUsesExactMatching() {
        val policy = TransportPolicy.current

        assertEquals("me.dartcv.minix.root", policy.historicalCompatibilityNamespace)
        assertTrue(policy.isHistoricalCompatibilityNamespace("me.dartcv.minix.root"))
        assertFalse(policy.isHistoricalCompatibilityNamespace("me.dartcv.minix.root.extra"))
        assertFalse(policy.isHistoricalCompatibilityNamespace("me.dartcv.minix.control"))
    }
}
