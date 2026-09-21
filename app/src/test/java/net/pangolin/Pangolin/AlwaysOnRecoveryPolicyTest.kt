package net.pangolin.Pangolin

import net.pangolin.Pangolin.util.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnRecoveryPolicyTest {
    private val stalledState = TunnelState(
        isServiceRunning = true,
        isConnecting = true,
        isSocketConnected = true,
        isRegistered = false,
    )

    @Test
    fun reconnectsOnlyWithOwnershipAndUsableStoredState() {
        assertEquals(
            AlwaysOnReconnectAction.CONNECT,
            AlwaysOnReconnectPolicy.action(
                alwaysOnRequested = true,
                state = TunnelState(),
                hasStoredState = true,
                sessionExpired = false,
            ),
        )
        assertEquals(
            AlwaysOnReconnectAction.WAIT,
            AlwaysOnReconnectPolicy.action(true, stalledState, true, false),
        )
        assertEquals(
            AlwaysOnReconnectAction.CANCEL,
            AlwaysOnReconnectPolicy.action(false, TunnelState(), true, false),
        )
        assertEquals(
            AlwaysOnReconnectAction.CANCEL,
            AlwaysOnReconnectPolicy.action(true, TunnelState(), false, false),
        )
        assertEquals(
            AlwaysOnReconnectAction.CANCEL,
            AlwaysOnReconnectPolicy.action(true, TunnelState(), true, true),
        )
    }

    @Test
    fun stalledControlPlaneRecoversOnlyWhenUnderlyingNetworkExists() {
        assertTrue(AlwaysOnRecoveryPolicy.shouldArm(true, stalledState))
        assertEquals(
            ReadinessWatchdogAction.KEEP_ARMED,
            AlwaysOnRecoveryPolicy.deadlineAction(true, stalledState, false),
        )
        assertEquals(
            ReadinessWatchdogAction.RECOVER,
            AlwaysOnRecoveryPolicy.deadlineAction(true, stalledState, true),
        )
    }

    @Test
    fun readyOrUnownedTunnelNeverTriggersRecovery() {
        val ready = TunnelState(
            isServiceRunning = true,
            isSocketConnected = true,
            isRegistered = true,
            isNetworkSettingsApplied = true,
            hasConnectedPeer = true,
            isConnecting = false,
        )

        assertFalse(AlwaysOnRecoveryPolicy.shouldArm(true, ready))
        assertEquals(
            ReadinessWatchdogAction.CANCEL,
            AlwaysOnRecoveryPolicy.deadlineAction(true, ready, true),
        )
        assertEquals(
            ReadinessWatchdogAction.CANCEL,
            AlwaysOnRecoveryPolicy.deadlineAction(false, stalledState, true),
        )
    }

    @Test
    fun userDisconnectIsRejectedWhileAndroidOwnsAlwaysOn() {
        assertFalse(AlwaysOnReconnectPolicy.canUserDisconnect(alwaysOnRequested = true))
        assertTrue(AlwaysOnReconnectPolicy.canUserDisconnect(alwaysOnRequested = false))
    }

    @Test
    fun livePlatformOwnershipReconcilesBothEnableAndDisable() {
        assertTrue(AlwaysOnReconnectPolicy.reconcileOwnership(latched = false, platformAlwaysOn = true))
        assertFalse(AlwaysOnReconnectPolicy.reconcileOwnership(latched = true, platformAlwaysOn = false))
        assertTrue(AlwaysOnReconnectPolicy.reconcileOwnership(latched = true, platformAlwaysOn = null))
    }
}
