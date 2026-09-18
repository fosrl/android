package net.pangolin.Pangolin

import net.pangolin.Pangolin.util.ReadinessEpoch
import net.pangolin.Pangolin.util.TunnelState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelReadinessTest {
    @Test
    fun controlPlaneRegistrationAloneIsNotReady() {
        val controlPlaneOnly = TunnelState(
            isServiceRunning = true,
            isSocketConnected = true,
            isRegistered = true,
            isConnecting = false,
        )

        assertFalse(controlPlaneOnly.isFullyConnected)
    }

    @Test
    fun readinessRequiresNetworkSettingsAndAConnectedPrivatePeer() {
        val noPeer = readyState().copy(hasConnectedPeer = false)
        val noSettings = readyState().copy(isNetworkSettingsApplied = false)

        assertFalse(noPeer.isFullyConnected)
        assertFalse(noSettings.isFullyConnected)
        assertTrue(readyState().isFullyConnected)
    }

    @Test
    fun readinessEpochRejectsConflatedOrNonReadyReset() {
        val epoch = ReadinessEpoch()
        epoch.recordTransition(previousReady = false, nextReady = true)
        val readyEpoch = epoch.snapshot()

        epoch.recordTransition(previousReady = true, nextReady = false)
        epoch.recordTransition(previousReady = false, nextReady = true)

        var reset = false
        assertFalse(epoch.runIfUnchanged(readyEpoch, isReady = { true }) { reset = true })
        assertFalse(reset)

        val recoveredEpoch = epoch.snapshot()
        assertFalse(epoch.runIfUnchanged(recoveredEpoch, isReady = { false }) { reset = true })
        assertTrue(epoch.runIfUnchanged(recoveredEpoch, isReady = { true }) { reset = true })
        assertTrue(reset)
    }

    private fun readyState() = TunnelState(
        isServiceRunning = true,
        isSocketConnected = true,
        isRegistered = true,
        isNetworkSettingsApplied = true,
        hasConnectedPeer = true,
        isConnecting = false,
    )
}
