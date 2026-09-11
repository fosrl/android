package net.pangolin.Pangolin

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnServiceLifecycleTest {
    @Test
    fun manualServiceStartsWithoutAnActivityAndCreatesItsNotificationChannel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "VPN consent is required before Android permits a systemExempted VPN service",
            VpnService.prepare(context) == null,
        )
        val intent = Intent(context, GoBackend.VpnService::class.java).setAction(
            "net.pangolin.Pangolin.PacketTunnel.action.MANUAL_START",
        )

        ContextCompat.startForegroundService(context, intent)
        try {
            val backend = GoBackend(context)
            waitUntil { runCatching { !backend.isAlwaysOn }.getOrDefault(false) }

            // Android itself enforces the foreground-service deadline. Keeping the service
            // alive beyond that window proves onCreate promoted it successfully; the channel
            // assertion verifies the production notification setup without relying on the
            // instrumentation-only activeNotifications view.
            Thread.sleep(6_000L)
            assertFalse(backend.isAlwaysOn)

            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel("pangolin_vpn")
            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        } finally {
            context.stopService(intent)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L)
        }
        check(condition()) { "Condition was not met within 5 seconds" }
    }
}
