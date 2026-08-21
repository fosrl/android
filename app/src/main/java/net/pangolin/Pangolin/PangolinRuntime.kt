package net.pangolin.Pangolin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.AccountManager
import net.pangolin.Pangolin.util.AndroidFingerprintCollector
import net.pangolin.Pangolin.util.AuthManager
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.FingerprintManager
import net.pangolin.Pangolin.util.SecretManager
import net.pangolin.Pangolin.util.SocketManager
import net.pangolin.Pangolin.util.TunnelManager
import net.pangolin.Pangolin.util.TunnelStartResult
import net.pangolin.Pangolin.util.TunnelState
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L
private const val MAX_RECONNECT_RETRIES = 5
private const val STABLE_CONNECTION_RESET_MS = 30_000L
private const val STALLED_CONTROL_PLANE_TIMEOUT_MS = 30_000L

enum class AlwaysOnReconnectAction {
    CANCEL,
    WAIT,
    CONNECT,
}

internal object AlwaysOnReconnectPolicy {
    fun action(
        alwaysOnRequested: Boolean,
        state: TunnelState,
        hasStoredState: Boolean,
        sessionExpired: Boolean,
    ): AlwaysOnReconnectAction = when {
        !alwaysOnRequested || !hasStoredState || sessionExpired -> AlwaysOnReconnectAction.CANCEL
        state.isServiceRunning || state.isConnecting -> AlwaysOnReconnectAction.WAIT
        else -> AlwaysOnReconnectAction.CONNECT
    }

    fun canUserDisconnect(alwaysOnRequested: Boolean): Boolean = !alwaysOnRequested

    fun reconcileOwnership(latched: Boolean, platformAlwaysOn: Boolean?): Boolean =
        platformAlwaysOn ?: latched
}

enum class ReadinessWatchdogAction {
    CANCEL,
    KEEP_ARMED,
    RECOVER,
}

internal object AlwaysOnRecoveryPolicy {
    fun shouldArm(alwaysOnRequested: Boolean, state: TunnelState): Boolean =
        alwaysOnRequested && state.isServiceRunning && !state.isFullyConnected

    fun deadlineAction(
        alwaysOnRequested: Boolean,
        state: TunnelState,
        hasUnderlyingNetwork: Boolean,
    ): ReadinessWatchdogAction = when {
        !shouldArm(alwaysOnRequested, state) -> ReadinessWatchdogAction.CANCEL
        !hasUnderlyingNetwork -> ReadinessWatchdogAction.KEEP_ARMED
        else -> ReadinessWatchdogAction.RECOVER
    }
}

internal class ReconnectBackoff(
    private val initialDelayMs: Long = INITIAL_RECONNECT_DELAY_MS,
    private val maximumDelayMs: Long = MAX_RECONNECT_DELAY_MS,
    private val maximumRetries: Int = MAX_RECONNECT_RETRIES,
) {
    private var retryCount = 0

    @Synchronized
    fun nextDelayMs(): Long? {
        if (retryCount >= maximumRetries) return null
        var delayMs = initialDelayMs
        repeat(retryCount) {
            delayMs = (delayMs * 2).coerceAtMost(maximumDelayMs)
        }
        return delayMs
    }

    @Synchronized
    fun recordRetryAttempt() {
        if (retryCount < maximumRetries) retryCount += 1
    }

    @Synchronized
    fun isExhausted(): Boolean = retryCount >= maximumRetries

    @Synchronized
    fun reset() {
        retryCount = 0
    }
}

/**
 * Process-wide manager graph and Android Always-On coordinator.
 *
 * Android creates the Application before it creates VpnService, including after boot or
 * process recreation. Keeping this graph outside Activity lets the platform resume an
 * existing encrypted account without opening the UI or creating a competing tunnel manager.
 */
class PangolinRuntime(private val context: Context) {
    private val tag = "PangolinRuntime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconnectBackoff = ReconnectBackoff()
    private val reconnectLock = Any()
    private val watchdogLock = Any()

    @Volatile
    private var alwaysOnRequested = false

    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var stableConnectionJob: Job? = null
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val usableUnderlyingNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val underlyingNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onUnderlyingNetworkChanged(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (isUsableUnderlyingNetwork(capabilities)) {
                if (usableUnderlyingNetworks.add(network)) wakeRecoveryForNetwork()
            } else {
                usableUnderlyingNetworks.remove(network)
            }
        }

        override fun onLost(network: Network) {
            usableUnderlyingNetworks.remove(network)
        }
    }

    val secretManager: SecretManager = SecretManager.getInstance(context)
    val accountManager: AccountManager = AccountManager.getInstance(context)
    val configManager: ConfigManager = ConfigManager.getInstance(context)
    val socketManager = SocketManager(File(context.filesDir, "pangolin.sock").absolutePath)
    val fingerprintManager = FingerprintManager(
        context,
        socketManager,
        AndroidFingerprintCollector(context),
    )
    val apiClient = APIClient(
        "https://app.pangolin.net",
        versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown",
    )
    val authManager = AuthManager(
        context = context,
        apiClient = apiClient,
        configManager = configManager,
        accountManager = accountManager,
        secretManager = secretManager,
    )
    val tunnelManager = TunnelManager.getInstance(
        context = context,
        authManager = authManager,
        accountManager = accountManager,
        secretManager = secretManager,
        configManager = configManager,
        socketManager = socketManager,
        fingerprintManager = fingerprintManager,
    )

    init {
        authManager.tunnelManager = tunnelManager
        authManager.requestUserDisconnect = { disconnectFromUser() }
        GoBackend.setAlwaysOnCallback(object : GoBackend.AlwaysOnCallback {
            override fun alwaysOnTriggered() {
                Log.i(tag, "Android Always-On requested tunnel startup")
                val newlyOwned = !alwaysOnRequested
                alwaysOnRequested = true
                if (newlyOwned) reconnectBackoff.reset()
                handleTunnelState(tunnelManager.tunnelState.value, immediate = newlyOwned)
            }

            override fun alwaysOnStopped() {
                Log.i(tag, "Android Always-On ownership ended")
                alwaysOnRequested = false
                cancelRecovery()
            }
        })

        scope.launch {
            tunnelManager.tunnelState.collectLatest { state ->
                handleTunnelState(state, immediate = false)
            }
        }
        scope.launch {
            accountManager.store.drop(1).collectLatest {
                if (alwaysOnRequested) {
                    reconnectBackoff.reset()
                    handleTunnelState(tunnelManager.tunnelState.value, immediate = true)
                }
            }
        }
        scope.launch {
            authManager.sessionExpired.drop(1).collectLatest { expired ->
                if (!alwaysOnRequested) return@collectLatest
                if (expired) {
                    cancelRecovery()
                } else {
                    reconnectBackoff.reset()
                    handleTunnelState(tunnelManager.tunnelState.value, immediate = true)
                }
            }
        }

        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager?.registerNetworkCallback(request, underlyingNetworkCallback)
        }.onFailure { error ->
            Log.w(tag, "Unable to register underlying-network callback", error)
        }
    }

    suspend fun disconnectFromUser(): Boolean {
        alwaysOnRequested = AlwaysOnReconnectPolicy.reconcileOwnership(
            alwaysOnRequested,
            tunnelManager.platformAlwaysOnState(),
        )
        if (!AlwaysOnReconnectPolicy.canUserDisconnect(alwaysOnRequested)) return false
        tunnelManager.disconnect()
        return true
    }

    private fun handleTunnelState(state: TunnelState, immediate: Boolean) {
        if (!alwaysOnRequested) return
        if (!state.isFullyConnected) cancelStableBackoffReset()

        val action = AlwaysOnReconnectPolicy.action(
            alwaysOnRequested = true,
            state = state,
            hasStoredState = hasStoredAlwaysOnState(),
            sessionExpired = authManager.sessionExpired.value,
        )
        when (action) {
            AlwaysOnReconnectAction.CANCEL -> cancelRecovery()
            AlwaysOnReconnectAction.CONNECT -> scheduleReconnect(immediate)
            AlwaysOnReconnectAction.WAIT -> {
                if (state.isFullyConnected) {
                    cancelWatchdog()
                    scheduleStableBackoffReset()
                } else if (AlwaysOnRecoveryPolicy.shouldArm(true, state)) {
                    armWatchdog()
                }
            }
        }
    }

    private fun scheduleReconnect(immediate: Boolean) {
        synchronized(reconnectLock) {
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                var firstAttempt = immediate
                while (alwaysOnRequested) {
                    val action = AlwaysOnReconnectPolicy.action(
                        alwaysOnRequested = alwaysOnRequested,
                        state = tunnelManager.tunnelState.value,
                        hasStoredState = hasStoredAlwaysOnState(),
                        sessionExpired = authManager.sessionExpired.value,
                    )
                    if (action != AlwaysOnReconnectAction.CONNECT) return@launch

                    if (!hasUnderlyingNetwork()) {
                        Log.d(tag, "Always-On recovery waiting for an underlying network")
                        return@launch
                    }

                    if (!firstAttempt) {
                        val retryDelayMs = reconnectBackoff.nextDelayMs() ?: run {
                            Log.w(tag, "Always-On reconnect budget exhausted")
                            return@launch
                        }
                        delay(retryDelayMs)
                        if (!hasUnderlyingNetwork()) return@launch
                        reconnectBackoff.recordRetryAttempt()
                    }
                    firstAttempt = false

                    when (tunnelManager.connectFromStoredAccount()) {
                        TunnelStartResult.STARTED,
                        TunnelStartResult.TERMINAL_FAILURE -> return@launch
                        TunnelStartResult.RETRYABLE_FAILURE -> Unit
                    }
                }
            }
        }
    }

    private fun armWatchdog() {
        synchronized(watchdogLock) {
            if (watchdogJob?.isActive == true) return
            watchdogJob = scope.launch {
                while (alwaysOnRequested) {
                    delay(STALLED_CONTROL_PLANE_TIMEOUT_MS)
                    when (
                        AlwaysOnRecoveryPolicy.deadlineAction(
                            alwaysOnRequested,
                            tunnelManager.tunnelState.value,
                            hasUnderlyingNetwork(),
                        )
                    ) {
                        ReadinessWatchdogAction.CANCEL -> return@launch
                        ReadinessWatchdogAction.KEEP_ARMED -> continue
                        ReadinessWatchdogAction.RECOVER -> {
                            Log.w(tag, "Always-On control plane stalled; rebuilding tunnel")
                            tunnelManager.disconnect()
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private fun scheduleStableBackoffReset() {
        if (stableConnectionJob?.isActive == true) return
        stableConnectionJob = scope.launch {
            var observedEpoch = tunnelManager.readinessEpochSnapshot()
            while (alwaysOnRequested) {
                delay(STABLE_CONNECTION_RESET_MS)
                if (!tunnelManager.tunnelState.value.isFullyConnected) return@launch

                if (tunnelManager.runIfReadinessStable(observedEpoch) {
                        reconnectBackoff.reset()
                    }
                ) return@launch

                // READY changed away and back between observations. Start a fresh full
                // stability interval even if StateFlow conflated the intermediate value.
                observedEpoch = tunnelManager.readinessEpochSnapshot()
            }
        }
    }

    private fun hasStoredAlwaysOnState(): Boolean {
        val account = accountManager.activeAccount ?: return false
        if (account.hostname.isBlank() || account.orgId.isBlank()) return false
        return !secretManager.getSessionToken(account.userId).isNullOrBlank() &&
            !secretManager.getOlmId(account.userId).isNullOrBlank() &&
            !secretManager.getOlmSecret(account.userId).isNullOrBlank()
    }

    private fun hasUnderlyingNetwork(): Boolean {
        return usableUnderlyingNetworks.isNotEmpty()
    }

    private fun onUnderlyingNetworkChanged(network: Network) {
        val capabilities = runCatching {
            connectivityManager?.getNetworkCapabilities(network)
        }.onFailure { error ->
            Log.w(tag, "Unable to inspect underlying network", error)
        }.getOrNull() ?: return
        if (isUsableUnderlyingNetwork(capabilities)) {
            if (usableUnderlyingNetworks.add(network)) wakeRecoveryForNetwork()
        }
    }

    private fun wakeRecoveryForNetwork() {
        if (!alwaysOnRequested) return
        val state = tunnelManager.tunnelState.value
        if (AlwaysOnReconnectPolicy.action(
                true,
                state,
                hasStoredAlwaysOnState(),
                authManager.sessionExpired.value,
            ) == AlwaysOnReconnectAction.CONNECT
        ) {
            reconnectBackoff.reset()
            synchronized(reconnectLock) {
                reconnectJob?.cancel()
                reconnectJob = null
            }
            scheduleReconnect(immediate = true)
        } else if (AlwaysOnRecoveryPolicy.shouldArm(true, state)) {
            cancelWatchdog()
            armWatchdog()
        }
    }

    private fun isUsableUnderlyingNetwork(capabilities: NetworkCapabilities): Boolean {
        val supportedTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val validated = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            supportedTransport && validated
    }

    private fun cancelRecovery() {
        synchronized(reconnectLock) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        cancelWatchdog()
        cancelStableBackoffReset()
        reconnectBackoff.reset()
    }

    private fun cancelWatchdog() {
        synchronized(watchdogLock) {
            watchdogJob?.cancel()
            watchdogJob = null
        }
    }

    private fun cancelStableBackoffReset() {
        stableConnectionJob?.cancel()
        stableConnectionJob = null
    }
}
