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
                isServiceRunning = false,
                isSocketConnected = false,
                isRegistered = false,
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
        
        // Observe status updates from socket polling
        observeStatusUpdates()

        // Bind content layout
        contentBinding = ContentMainBinding.bind(binding.content.root)

        // Setup button click listener
        contentBinding.btnConnect.setOnClickListener {
            if (tunnelState.isServiceRunning) {
                disconnectTunnel()
            } else {
                connectTunnel()
            }
        }

        // Initialize UI state
        updateTunnelState(tunnelState)
    }
    
    override fun onResume() {
        super.onResume()
        // Check current tunnel state when returning to activity
        checkTunnelState()
    }
    
    private fun checkTunnelState() {
        lifecycleScope.launch {
            try {
                val backend = goBackend ?: return@launch
                val currentState = backend.getState(tunnel)
                val isServiceUp = currentState == Tunnel.State.UP
                
                if (isServiceUp) {
                    // Service is running, start polling if not already
                    val isCurrentlyPolling = statusPollingManager?.isPolling?.value ?: false
                    if (!isCurrentlyPolling) {
                        statusPollingManager?.startPolling()
                    }
                    
                    // Try to get current socket status
                    val currentStatus = statusPollingManager?.getCurrentStatus()
                    updateTunnelState(tunnelState.copy(
                        isServiceRunning = true,
                        isSocketConnected = currentStatus?.connected ?: false,
                        isRegistered = currentStatus?.registered ?: false,
                        statusMessage = determineStatusMessage(true, currentStatus?.connected ?: false, currentStatus?.registered ?: false)
                    ))
                } else {
                    updateTunnelState(tunnelState.copy(
                        isServiceRunning = false,
                        isSocketConnected = false,
                        isRegistered = false,
                        statusMessage = "Disconnected"
                    ))
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking tunnel state", e)
            }
        }
    }
    
    private fun observeStatusUpdates() {
        lifecycleScope.launch {
            statusPollingManager?.statusFlow?.collect { status ->
                if (status != null && tunnelState.isServiceRunning) {
                    updateTunnelState(tunnelState.copy(
                        isSocketConnected = status.connected,
                        isRegistered = status.registered ?: false,
                        statusMessage = determineStatusMessage(
                            tunnelState.isServiceRunning,
                            status.connected,
                            status.registered ?: false
                        )
                    ))
                }
            }
        }
    }
    
    private fun determineStatusMessage(serviceRunning: Boolean, socketConnected: Boolean, registered: Boolean): String {
        return when {
            !serviceRunning -> "Disconnected"
            !socketConnected -> "Connecting to server..."
            !registered -> "Connected, registering..."
            else -> "Connected & Registered"
        }
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
            isServiceRunning = false,
            isSocketConnected = false,
            isRegistered = false,
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

            // Update VPN Service status
            contentBinding.tvServiceStatus.text = if (newState.isServiceRunning) {
                "🟢 Running"
            } else {
                "⚫ Stopped"
            }
            contentBinding.tvServiceStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (newState.isServiceRunning) android.R.color.holo_green_dark else android.R.color.darker_gray
                )
            )

            // Update Socket Connected status
            contentBinding.tvSocketStatus.text = if (newState.isSocketConnected) {
                "🟢 Yes"
            } else if (newState.isServiceRunning) {
                "🟡 Connecting..."
            } else {
                "⚫ No"
            }
            contentBinding.tvSocketStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        newState.isSocketConnected -> android.R.color.holo_green_dark
                        newState.isServiceRunning -> android.R.color.holo_orange_dark
                        else -> android.R.color.darker_gray
                    }
                )
            )

            // Update Registered status
            contentBinding.tvRegisteredStatus.text = if (newState.isRegistered) {
                "🟢 Yes"
            } else if (newState.isSocketConnected) {
                "🟡 Registering..."
            } else {
                "⚫ No"
            }
            contentBinding.tvRegisteredStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        newState.isRegistered -> android.R.color.holo_green_dark
                        newState.isSocketConnected -> android.R.color.holo_orange_dark
                        else -> android.R.color.darker_gray
                    }
                )
            )

            // Update error message
            if (newState.errorMessage != null) {
                contentBinding.tvError.text = "Error: ${newState.errorMessage}"
                contentBinding.tvError.visibility = View.VISIBLE
            } else {
                contentBinding.tvError.visibility = View.GONE
            }

            // Update progress indicator - show when connecting or when service is up but not fully registered
            val showProgress = newState.isConnecting || (newState.isServiceRunning && !newState.isFullyConnected)
            contentBinding.progressIndicator.visibility =
                if (showProgress) View.VISIBLE else View.GONE

            // Update button
            contentBinding.btnConnect.isEnabled = !newState.isConnecting
            contentBinding.btnConnect.text = when {
                newState.isConnecting -> "Connecting..."
                newState.isServiceRunning -> "Disconnect"
                else -> "Connect"
            }

            // Update button color
            if (newState.isServiceRunning) {
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

            // Update card background color based on connection state
            val cardColorAttr = when {
                newState.errorMessage != null -> com.google.android.material.R.attr.colorErrorContainer
                newState.isFullyConnected -> com.google.android.material.R.attr.colorPrimaryContainer
                newState.isServiceRunning -> com.google.android.material.R.attr.colorSecondaryContainer
                newState.isConnecting -> com.google.android.material.R.attr.colorSecondaryContainer
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
            val isServiceUp = newState == Tunnel.State.UP
            
            if (isServiceUp) {
                Log.d("MainActivity", "Tunnel service UP, starting status polling")
                statusPollingManager?.startPolling()
                
                updateTunnelState(tunnelState.copy(
                    isServiceRunning = true,
                    isConnecting = false,
                    isSocketConnected = false,
                    isRegistered = false,
                    statusMessage = "Connecting to server..."
                ))
            } else {
                Log.d("MainActivity", "Tunnel service DOWN, stopping status polling")
                statusPollingManager?.stopPolling()
                
                updateTunnelState(tunnelState.copy(
                    isServiceRunning = false,
                    isConnecting = false,
                    isSocketConnected = false,
                    isRegistered = false,
                    statusMessage = "Disconnected"
                ))
            }
        }
    }
}

data class TunnelState(
    val isServiceRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isSocketConnected: Boolean = false,
    val isRegistered: Boolean = false,
    val statusMessage: String = "Disconnected",
    val errorMessage: String? = null
) {
    val isFullyConnected: Boolean
        get() = isServiceRunning && isSocketConnected && isRegistered
}

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
        isServiceRunning = false,
        isSocketConnected = false,
        isRegistered = false,
        statusMessage = "Starting VPN service..."
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
            isServiceRunning = true,
            isConnecting = false,
            isSocketConnected = false,
            isRegistered = false,
            statusMessage = "VPN service started, connecting..."
        ))
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to start tunnel", e)
        onStateChange(TunnelState(
            isServiceRunning = false,
            isConnecting = false,
            isSocketConnected = false,
            isRegistered = false,
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
        isServiceRunning = true,
        isConnecting = true,
        isSocketConnected = false,
        isRegistered = false,
        statusMessage = "Disconnecting..."
    ))

    try {
        withContext(Dispatchers.IO) {
            goBackend.setState(tunnel, Tunnel.State.DOWN, null, null)
        }

        onStateChange(TunnelState(
            isServiceRunning = false,
            isSocketConnected = false,
            isRegistered = false,
            statusMessage = "Disconnected"
        ))
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to stop tunnel", e)
        onStateChange(TunnelState(
            isServiceRunning = false,
            isSocketConnected = false,
            isRegistered = false,
            statusMessage = "Disconnected",
            errorMessage = "Error while disconnecting: ${e.message}"
        ))
    }
}
