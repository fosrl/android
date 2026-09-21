package net.pangolin.Pangolin

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import net.pangolin.Pangolin.util.TunnelManager
import net.pangolin.Pangolin.util.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VpnNotificationTest {
    @Test
    fun notificationSettingStaysUsableWhileConnectionSettingsAreLocked() {
        val context = ApplicationProvider.getApplicationContext<PangolinApplication>()
        assumeTrue("Use an isolated installation without accounts", context.runtime.accountManager.accounts.isEmpty())
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("persistentVpnNotification").commit()
        // Feed the actual settings observer a connected state without requiring an account.
        val field = TunnelManager::class.java.getDeclaredField("_tunnelState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(context.runtime.tunnelManager) as MutableStateFlow<TunnelState>
        val previous = state.value
        try {
            state.value = TunnelState(isServiceRunning = true)
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                        as SettingsActivity.SettingsFragment
                    val notification = fragment.findPreference<SwitchPreferenceCompat>("persistentVpnNotification")!!
                    assertTrue(notification.isEnabled)
                    assertFalse(notification.isChecked)
                    assertFalse(fragment.findPreference<Preference>("overrideDns")!!.isEnabled)
                    notification.isChecked = true
                    assertTrue(PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("persistentVpnNotification", false))
                }
            }
        } finally {
            state.value = previous
            PreferenceManager.getDefaultSharedPreferences(context).edit().remove("persistentVpnNotification").commit()
        }
    }

    @Test
    fun defaultOffAndLiveTogglePreserveVpnBindingAndRestoreRecoveryNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("VPN consent is required", VpnService.prepare(context) == null)
        assumeTrue("Grant notification permission to test actual visibility",
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Use an isolated installation without accounts",
            (context as PangolinApplication).runtime.accountManager.accounts.isEmpty())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove("persistentVpnNotification").commit()
        assertFalse(prefs.getBoolean("persistentVpnNotification", false))
        val intent = Intent(context, GoBackend.VpnService::class.java)
            .setAction("net.pangolin.Pangolin.PacketTunnel.action.MANUAL_START")
        val notifications = context.getSystemService(NotificationManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun notificationVisible() = notifications.activeNotifications.any { it.id == 1001 }
        fun vpnPresent() = connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

        ContextCompat.startForegroundService(context, intent)
        val service = runningService()
        val backend = GoBackend(context)
        try {
            waitUntil("Startup notification") { notificationVisible() }
            // A real local TUN causes Android to bind the production VPN service. No
            // routes, DNS, credentials, server or peer are involved in this fixture.
            service.getBuilder().setSession("notification-test")
                .addAddress("192.0.2.1", 32).establish()!!.use { tun ->
                    waitUntil("Android VPN binding") { vpnPresent() }
                    backend.updateForegroundNotification(true)
                    waitUntil("Default-off notification removed") { !notificationVisible() }
                    assertTrue(tun.fileDescriptor.valid())
                    assertTrue(vpnPresent())

                    prefs.edit().putBoolean("persistentVpnNotification", true).commit()
                    waitUntil("Opt-in applied without reconnect") { notificationVisible() }
                    assertSame(service, runningService())
                    assertTrue(vpnPresent())

                    prefs.edit().putBoolean("persistentVpnNotification", false).commit()
                    waitUntil("Opt-out applied without reconnect") { !notificationVisible() }
                    Thread.sleep(6_000L)
                    assertSame(service, runningService())
                    assertTrue(tun.fileDescriptor.valid())
                    assertTrue(vpnPresent())

                    backend.updateForegroundNotification(false)
                    waitUntil("Recovery notification restored") { notificationVisible() }
                    backend.updateForegroundNotification(true)
                    waitUntil("Ready notification removed again") { !notificationVisible() }
                }
            // Closing the TUN makes Android unbind: quiet mode must no longer demote
            // the started service while it waits for recovery.
            waitUntil("Binding loss restores foreground notification") { notificationVisible() }
        } finally {
            prefs.edit().remove("persistentVpnNotification").commit()
            context.stopService(intent)
        }
    }

    // Access only for the TUN fixture; production has no testing-only service API.
    private fun runningService(): GoBackend.VpnService {
        val field = GoBackend::class.java.getDeclaredField("vpnService").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(null) as CompletableFuture<GoBackend.VpnService>).get(5, TimeUnit.SECONDS)
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50L)
        check(condition()) { description }
    }
}
