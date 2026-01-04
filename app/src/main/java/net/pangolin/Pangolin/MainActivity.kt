package net.pangolin.Pangolin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import net.pangolin.Pangolin.PacketTunnel.InitConfig
import net.pangolin.Pangolin.PacketTunnel.Tunnel
import net.pangolin.Pangolin.PacketTunnel.TunnelConfig
import net.pangolin.Pangolin.databinding.ActivityMainBinding
import net.pangolin.Pangolin.databinding.ContentMainBinding
import net.pangolin.Pangolin.util.StatusPollingManager
import java.io.File

class MainActivity : BaseNavigationActivity() {
    private var goBackend: GoBackend? = null
    private lateinit var binding: ActivityMainBinding
    private lateinit var contentBinding: ContentMainBinding

    private var tunnelState = TunnelState()
    private var statusPollingManager: StatusPollingManager? = null

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, start the tunnel
            startTunnel()
        } else {
            updateTunnelState(tunnelState.copy(
                isConnecting = false,
                errorMessage = "VPN permission denied"
            ))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)

        goBackend = GoBackend(applicationContext)

        // Initialize StatusPollingManager
        val socketPath = File(applicationContext.filesDir, "pangolin.sock").absolutePath
        statusPollingManager = StatusPollingManager(socketPath)

        // Bind content layout
        contentBinding = ContentMainBinding.bind(binding.content.root)

        // Setup button click listener
        contentBinding.btnConnect.setOnClickListener {
            if (tunnelState.isConnected) {
                disconnectTunnel()
            } else {
                connectTunnel()
            }
        }

        // Initialize UI state
        updateTunnelState(tunnelState)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the polling manager
        statusPollingManager?.cleanup()
        statusPollingManager = null
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_main
    }

    private fun connectTunnel() {
        updateTunnelState(tunnelState.copy(
            isConnecting = true,
            statusMessage = "Requesting permission...",
            errorMessage = null
        ))

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Permission already granted
            startTunnel()
        }
    }

    private fun startTunnel() {
        lifecycleScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val primaryDNSServer = prefs.getString("primaryDNSServer", "1.1.1.1")
            val secondaryDNSServer = prefs.getString("secondaryDNSServer", null)
            val overrideDns = prefs.getBoolean("overrideDns", false)
            val tunnelDns = prefs.getBoolean("tunnelDns", false)

            startTunnelWithConfig(
                context = this@MainActivity,
                goBackend = goBackend!!,
                tunnel = tunnel,
                endpoint = "https://app.pangolin.net",
                id = "pcncclwlvde9mg5",
                secret = "t6xvv3yn0i8ypqrdc2r01bqcyic0ygiwo6lff1han6shfmlt",
                mtu = 1280,
                pingInterval = 10,
                pingTimeout = 30,
                holepunch = true,
                logLevel = "debug",
                dns = primaryDNSServer ?: "8.8.8.8",
                upstreamDnsPrimary = primaryDNSServer,
                upstreamDnsSecondary = secondaryDNSServer,
                overrideDns = overrideDns,
                tunnelDns = tunnelDns,
                onStateChange = { updateTunnelState(it) }
            )
        }
    }

    private fun disconnectTunnel() {
        lifecycleScope.launch {
            stopTunnelWithConfig(
                goBackend = goBackend!!,
                tunnel = tunnel,
                onStateChange = { updateTunnelState(it) }
            )
        }
    }

    private fun updateTunnelState(newState: TunnelState) {
        runOnUiThread {
            tunnelState = newState

            // Update status text
            contentBinding.tvStatus.text = "Status: ${newState.statusMessage}"

            // Update error message
            if (newState.errorMessage != null) {
                contentBinding.tvError.text = "Error: ${newState.errorMessage}"
                contentBinding.tvError.visibility = View.VISIBLE
            } else {
                contentBinding.tvError.visibility = View.GONE
            }

            // Update progress indicator
            contentBinding.progressIndicator.visibility =
                if (newState.isConnecting) View.VISIBLE else View.GONE

            // Update button
            contentBinding.btnConnect.isEnabled = !newState.isConnecting
            contentBinding.btnConnect.text = when {
                newState.isConnecting -> "Connecting..."
                newState.isConnected -> "Disconnect"
                else -> "Connect"
            }

            // Update button color
            if (newState.isConnected) {
                contentBinding.btnConnect.setBackgroundColor(
                    MaterialColors.getColor(
                        contentBinding.btnConnect,
                        com.google.android.material.R.attr.colorError
                    )
                )
            } else {
                contentBinding.btnConnect.setBackgroundColor(
                    MaterialColors.getColor(
                        contentBinding.btnConnect,
                        com.google.android.material.R.attr.colorPrimary
                    )
                )
            }

            // Update card background color
            val cardColorAttr = when {
                newState.isConnected -> com.google.android.material.R.attr.colorPrimaryContainer
                newState.isConnecting -> com.google.android.material.R.attr.colorSecondaryContainer
                newState.errorMessage != null -> com.google.android.material.R.attr.colorErrorContainer
                else -> com.google.android.material.R.attr.colorSurfaceVariant
            }

            contentBinding.statusCard.setCardBackgroundColor(
                MaterialColors.getColor(contentBinding.statusCard, cardColorAttr)
            )
        }
    }

    // Simple tunnel implementation
    private val tunnel = object : Tunnel {
        override fun getName(): String = "pangolin"
        override fun onStateChange(newState: Tunnel.State) {
            val isConnected = newState == Tunnel.State.UP
            updateTunnelState(tunnelState.copy(
                isConnected = isConnected,
                isConnecting = false,
                statusMessage = if (isConnected) "Connected" else "Disconnected"
            ))
            
            // Start or stop status polling based on tunnel state
            if (isConnected) {
                Log.d("MainActivity", "Tunnel connected, starting status polling")
                statusPollingManager?.startPolling()
            } else {
                Log.d("MainActivity", "Tunnel disconnected, stopping status polling")
                statusPollingManager?.stopPolling()
            }
        }
    }
}

data class TunnelState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val statusMessage: String = "Disconnected",
    val errorMessage: String? = null
)

private suspend fun startTunnelWithConfig(
    context: Context,
    goBackend: GoBackend,
    tunnel: Tunnel,
    endpoint: String,
    id: String,
    secret: String,
    mtu: Int,
    pingInterval: Int,
    pingTimeout: Int,
    holepunch: Boolean,
    logLevel: String,
    dns: String? = null,
    upstreamDnsPrimary: String? = null,
    upstreamDnsSecondary: String? = null,
    overrideDns: Boolean = false,
    tunnelDns: Boolean = false,
    onStateChange: (TunnelState) -> Unit
) {
    onStateChange(TunnelState(
        isConnecting = true,
        statusMessage = "Connecting..."
    ))

    try {
        withContext(Dispatchers.IO) {
            // Build init config
            val initConfig = InitConfig.Builder()
                .setEnableAPI(false)
                .setLogLevel(logLevel)
                .setAgent("android")
                .setVersion("1.0.0-test")
                .setSocketPath(File(context.filesDir, "pangolin.sock").absolutePath)
                .setEnableAPI(true)
                .build()

            val upstreamDns = mutableListOf<String>()
            if (upstreamDnsPrimary != null) {
                upstreamDns.add("$upstreamDnsPrimary:53")
            }
            if (upstreamDnsSecondary != null) {
                upstreamDns.add("$upstreamDnsSecondary:53")
            }

            // Build tunnel config
            val tunnelConfig = TunnelConfig.Builder()
                .setEndpoint(endpoint)
                .setId(id)
                .setSecret(secret)
                .setMtu(mtu)
                .setDns(dns ?: "1.1.1.1")
                .setUpstreamDNS(upstreamDns)
                .setPingIntervalSeconds(pingInterval)
                .setPingTimeoutSeconds(pingTimeout)
                .setHolepunch(holepunch)
                .setOverrideDNS(overrideDns)
                .setTunnelDNS(tunnelDns)
                .build()

            Log.d("MainActivity", "Starting tunnel with config: $tunnelConfig")
            Log.d("MainActivity", "Init config: $initConfig")

            goBackend.setState(tunnel, Tunnel.State.UP, tunnelConfig, initConfig)
        }

        onStateChange(TunnelState(
            isConnected = true,
            statusMessage = "Connected"
        ))
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to start tunnel", e)
        onStateChange(TunnelState(
            isConnected = false,
            isConnecting = false,
            statusMessage = "Connection failed",
            errorMessage = e.message ?: "Unknown error"
        ))
    }
}

private suspend fun stopTunnelWithConfig(
    goBackend: GoBackend,
    tunnel: Tunnel,
    onStateChange: (TunnelState) -> Unit
) {
    onStateChange(TunnelState(
        isConnected = true,
        isConnecting = true,
        statusMessage = "Disconnecting..."
    ))

    try {
        withContext(Dispatchers.IO) {
            goBackend.setState(tunnel, Tunnel.State.DOWN, null, null)
        }

        onStateChange(TunnelState(
            isConnected = false,
            statusMessage = "Disconnected"
        ))
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to stop tunnel", e)
        onStateChange(TunnelState(
            isConnected = false,
            statusMessage = "Disconnected",
            errorMessage = "Error while disconnecting: ${e.message}"
        ))
    }
}
