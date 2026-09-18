package net.pangolin.Pangolin.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDisconnectGateTest {
    @Test
    fun inactiveTunnelDoesNotRequestDisconnect() = runBlocking {
        var called = false

        UserDisconnectGate.requireDisconnectIfNeeded(tunnelActive = false) {
            called = true
            true
        }

        assertFalse(called)
    }

    @Test
    fun activeAlwaysOnTunnelRejectsUserMutation() = runBlocking {
        var rejected = false

        try {
            UserDisconnectGate.requireDisconnectIfNeeded(tunnelActive = true) { false }
        } catch (_: AuthError.AlwaysOnActive) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun activeOrdinaryTunnelAllowsMutationAfterDisconnect() = runBlocking {
        var called = false

        UserDisconnectGate.requireDisconnectIfNeeded(tunnelActive = true) {
            called = true
            true
        }

        assertTrue(called)
    }
}
