package net.pangolin.Pangolin.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import net.pangolin.Pangolin.PacketTunnel.InitConfig
import net.pangolin.Pangolin.PacketTunnel.Tunnel
import net.pangolin.Pangolin.PacketTunnel.TunnelConfig
import java.io.File

/**
 * Manages VPN tunnel state, connection, and lifecycle across the app.
 * This is a singleton that persists tunnel state across activity changes.
 */
class TunnelManager private constructor(
    private val context: Context,
    private val authManager: AuthManager,
    private val accountManager: AccountManager,
    private val secretManager: SecretManager,
    private val configManager: ConfigManager
) {
    private val tag = "TunnelManager"
    
    // Coroutine scope for tunnel operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Go backend instance
    private var goBackend: GoBackend? = null
    
    // Tunnel instance - must be reused for disconnect to work
    private var tunnel: Tunnel? = null
    
    // Socket polling
    private var statusPollingManager: StatusPollingManager? = null
    private var pollingJob: Job? = null
    
    // Tunnel state
    private val _tunnelState = MutableStateFlow(TunnelState())
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()
    
    // Connection status from socket
    private val _connectionStatus = MutableStateFlow<SocketStatusResponse?>(null)
    val connectionStatus: StateFlow<SocketStatusResponse?> = _connectionStatus.asStateFlow()
    
    init {
        goBackend = GoBackend(context)
        val socketPath = File(context.filesDir, "pangolin.sock").absolutePath
        statusPollingManager = StatusPollingManager(socketPath)
        
        // Observe status updates
        scope.launch {
            statusPollingManager?.statusFlow?.collect { status ->
                if (status != null) {
                    _connectionStatus.value = status
                    updateConnectionStatusFromSocket(status)
                }
            }
        }
    }
    
    /**
     * Update internal state based on socket status response
     */
    private fun updateConnectionStatusFromSocket(status: SocketStatusResponse) {
        val currentState = _tunnelState.value
        
        // Only update if service is running
        if (!currentState.isServiceRunning) {
            return
        }
        
        val isConnected = status.connected && status.registered == true
        val isRegistered = status.registered == true
        
        _tunnelState.value = currentState.copy(
            isSocketConnected = status.connected,
            isRegistered = isRegistered,
            isConnecting = !isConnected && status.connected,
            statusMessage = determineStatusMessage(status),
            errorMessage = if (status.terminated) "Connection terminated" else null
        )
    }
    
    /**
     * Determine human-readable status message from socket response
     */
    private fun determineStatusMessage(status: SocketStatusResponse): String {
        return when {
            status.terminated -> "Disconnected"
            !status.connected -> "Connecting..."
            status.registered != true -> "Registering..."
            status.connected && status.registered == true -> "Connected"
            else -> "Unknown"
        }
    }
    
    /**
     * Connect to VPN tunnel
     */
    suspend fun connect() {
        Log.i(tag, "Starting tunnel connection")
        
        updateState(_tunnelState.value.copy(
            isConnecting = true,
            isServiceRunning = false,
            isSocketConnected = false,
            isRegistered = false,
            statusMessage = "Starting VPN service...",
            errorMessage = null
        ))
        
        try {
            // Get current user and credentials
            val activeAccount = accountManager.activeAccount
            if (activeAccount == null) {
                throw Exception("No active account")
            }
            
            val userId = activeAccount.userId
            val orgId = activeAccount.orgId
            
            Log.i(tag, "=== CONNECT: Starting connection for user=$userId, org=$orgId ===")
            Log.i(tag, "Active account details: userId=${activeAccount.userId}, orgId=${activeAccount.orgId}")
            
            if (orgId.isEmpty()) {
                throw Exception("No organization selected")
            }
            
            // Get user session token
            val userToken = secretManager.getSessionToken(userId)
            if (userToken == null) {
                throw Exception("No session token found")
            }
            
            // Ensure OLM credentials exist
            authManager.ensureOlmCredentials(userId)
            
            // Get OLM credentials
            val olmId = secretManager.getOlmId(userId)
            val olmSecret = secretManager.getOlmSecret(userId)
            
            if (olmId == null || olmSecret == null) {
                throw Exception("Failed to retrieve OLM credentials")
            }
            
            Log.i(tag, "Using OLM credentials for user $userId, org $orgId, olmId=$olmId")
            Log.i(tag, "About to build TunnelConfig with orgId=$orgId")
            
            // Get configuration
            val config = configManager.config.value
            val primaryDNS = config.primaryDNSServer ?: "1.1.1.1"
            val secondaryDNS = config.secondaryDNSServer
            val overrideDns = config.dnsOverrideEnabled ?: false
            val tunnelDns = config.dnsTunnelEnabled ?: false
            
            Log.d(tag, "DNS Configuration - overrideDns: $overrideDns, tunnelDns: $tunnelDns, primaryDNS: $primaryDNS, secondaryDNS: $secondaryDNS")
            
            // Start tunnel
            withContext(Dispatchers.IO) {
                val initConfig = InitConfig.Builder()
                    .setEnableAPI(true)
                    .setLogLevel("debug")
                    .setAgent("android")
                    .setVersion("1.0.0")
                    .setSocketPath(File(context.filesDir, "pangolin.sock").absolutePath)
                    .build()
                
                val upstreamDns = mutableListOf<String>()
                upstreamDns.add("$primaryDNS:53")
                if (secondaryDNS != null) {
                    upstreamDns.add("$secondaryDNS:53")
                }
                
                val tunnelConfig = TunnelConfig.Builder()
                    .setEndpoint("https://app.pangolin.net")
                    .setId(olmId)
                    .setSecret(olmSecret)
                    .setUserToken(userToken)
                    .setOrgId(orgId)
                    .setMtu(1280)
                    .setDns(primaryDNS)
                    .setUpstreamDNS(upstreamDns)
                    .setPingIntervalSeconds(10)
                    .setPingTimeoutSeconds(30)
                    .setHolepunch(true)
                    .setOverrideDNS(overrideDns)
                    .setTunnelDNS(tunnelDns)
                    .build()
                
                Log.d(tag, "=== TUNNEL CONFIG: Starting tunnel with OLM ID: $olmId, Org ID: $orgId ===")
                Log.d(tag, "Full tunnel config - endpoint: https://app.pangolin.net, mtu: 1280, dns: $primaryDNS")
                // Create tunnel instance if not already created
                if (tunnel == null) {
                    tunnel = createTunnel()
                }
                goBackend?.setState(tunnel!!, Tunnel.State.UP, tunnelConfig, initConfig)
            }
            
            updateState(_tunnelState.value.copy(
                isServiceRunning = true,
                isConnecting = true,
                statusMessage = "VPN service started, connecting..."
            ))
            
            // Start socket polling
            startSocketPolling()
            
        } catch (e: Exception) {
            Log.e(tag, "Failed to start tunnel", e)
            updateState(_tunnelState.value.copy(
                isServiceRunning = false,
                isConnecting = false,
                isSocketConnected = false,
                isRegistered = false,
                statusMessage = "Connection failed",
                errorMessage = e.message ?: "Unknown error"
            ))
        }
    }
    
    /**
     * Disconnect from VPN tunnel
     */
    suspend fun disconnect() {
        Log.i(tag, "Stopping tunnel connection")
        
        updateState(_tunnelState.value.copy(
            statusMessage = "Disconnecting...",
            isConnecting = false
        ))
        
        try {
            stopSocketPolling()
            
            withContext(Dispatchers.IO) {
                // Use the same tunnel instance that was used for connect
                if (tunnel != null) {
                    goBackend?.setState(tunnel!!, Tunnel.State.DOWN, null, null)
                } else {
                    Log.w(tag, "No tunnel instance to disconnect")
                }
            }
            
            updateState(TunnelState(
                isServiceRunning = false,
                isConnecting = false,
                isSocketConnected = false,
                isRegistered = false,
                statusMessage = "Disconnected"
            ))
            
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop tunnel", e)
            updateState(_tunnelState.value.copy(
                statusMessage = "Disconnection failed",
                errorMessage = e.message ?: "Unknown error"
            ))
        }
    }
    
    /**
     * Switch to a different organization
     */
    suspend fun switchOrg(orgId: String) {
        Log.i(tag, "Switching to organization: $orgId")
        
        try {
            val socketPath = File(context.filesDir, "pangolin.sock").absolutePath
            val socketManager = SocketManager(socketPath)
            val response = socketManager.switchOrg(orgId)
            Log.i(tag, "Organization switched: ${response.status}")
            
            // Update account manager
            val activeAccount = accountManager.activeAccount
            if (activeAccount != null) {
                accountManager.setUserOrganization(activeAccount.userId, orgId)
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Failed to switch organization", e)
        }
    }
    
    /**
     * Start polling socket for status updates
     */
    private fun startSocketPolling() {
        if (pollingJob?.isActive == true) {
            Log.d(tag, "Socket polling already active")
            return
        }
        
        statusPollingManager?.startPolling()
        
        pollingJob = scope.launch {
            while (isActive) {
                delay(1000) // Check every second
                
                val currentState = _tunnelState.value
                if (!currentState.isServiceRunning) {
                    // Service stopped, stop polling
                    break
                }
            }
        }
        
        Log.d(tag, "Socket polling started")
    }
    
    /**
     * Stop polling socket for status updates
     */
    private fun stopSocketPolling() {
        statusPollingManager?.stopPolling()
        pollingJob?.cancel()
        pollingJob = null
        Log.d(tag, "Socket polling stopped")
    }
    
    /**
     * Update tunnel state
     */
    private fun updateState(newState: TunnelState) {
        _tunnelState.value = newState
    }
    
    /**
     * Create a Tunnel instance for Go backend
     */
    private fun createTunnel(): Tunnel {
        return object : Tunnel {
            override fun getName(): String = "pangolin"
            
            override fun onStateChange(newState: Tunnel.State) {
                Log.d(tag, "Tunnel state changed to: $newState")
                val isServiceUp = goBackend?.getState(this) == Tunnel.State.UP
                
                if (!isServiceUp) {
                    updateState(_tunnelState.value.copy(
                        isServiceRunning = false,
                        isConnecting = false,
                        isSocketConnected = false,
                        isRegistered = false,
                        statusMessage = "Disconnected"
                    ))
                    stopSocketPolling()
                }
            }
        }
    }
    
    /**
     * Get current backend state
     */
    fun getCurrentState(): Tunnel.State? {
        return if (tunnel != null) {
            goBackend?.getState(tunnel!!)
        } else {
            Tunnel.State.DOWN
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopSocketPolling()
        scope.cancel()
    }
    
    companion object {
        @Volatile
        private var instance: TunnelManager? = null
        
        fun getInstance(
            context: Context,
            authManager: AuthManager,
            accountManager: AccountManager,
            secretManager: SecretManager,
            configManager: ConfigManager
        ): TunnelManager {
            return instance ?: synchronized(this) {
                instance ?: TunnelManager(
                    context.applicationContext,
                    authManager,
                    accountManager,
                    secretManager,
                    configManager
                ).also { instance = it }
            }
        }
        
        fun getInstance(): TunnelManager? {
            return instance
        }
    }
}

/**
 * Represents the current state of the VPN tunnel
 */
data class TunnelState(
    val isServiceRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val isSocketConnected: Boolean = false,
    val isRegistered: Boolean = false,
    val statusMessage: String = "Disconnected",
    val errorMessage: String? = null
) {
    val isFullyConnected: Boolean
        get() = isServiceRunning && isSocketConnected && isRegistered && !isConnecting
}