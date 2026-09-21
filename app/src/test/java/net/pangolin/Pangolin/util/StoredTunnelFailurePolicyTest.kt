package net.pangolin.Pangolin.util

import net.pangolin.Pangolin.PacketTunnel.BackendException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredTunnelFailurePolicyTest {
    @Test
    fun authorizationAndMissingConfigAreTerminal() {
        assertTrue(StoredTunnelFailurePolicy.isTerminal(BackendException.Reason.VPN_NOT_AUTHORIZED))
        assertTrue(StoredTunnelFailurePolicy.isTerminal(BackendException.Reason.TUNNEL_MISSING_CONFIG))
    }

    @Test
    fun serviceNativeAndDnsFailuresRemainEligibleForBoundedRetry() {
        assertFalse(StoredTunnelFailurePolicy.isTerminal(BackendException.Reason.UNABLE_TO_START_VPN))
        assertFalse(StoredTunnelFailurePolicy.isTerminal(BackendException.Reason.TUN_CREATION_ERROR))
        assertFalse(StoredTunnelFailurePolicy.isTerminal(BackendException.Reason.GO_ACTIVATION_ERROR_CODE))
        assertFalse(StoredTunnelFailurePolicy.isTerminal(BackendException.Reason.DNS_RESOLUTION_FAILURE))
    }
}
