package net.pangolin.Pangolin.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pangolin.Pangolin.PangolinApplication
import java.io.File

/**
 * Manager for polling tunnel status from the socket manager.
 * Polls every 3 seconds when active and provides status updates via StateFlow.
 */
class StatusPollingManager(
    private val context: Context,
    private val socketPath: String,
    private val pollingIntervalMs: Long = 3000L
) : PangolinApplication.StandbyListener {
    private val tag = "StatusPollingManager"
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private var socketManager: SocketManager? = null
    private var pollingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // StateFlow for observing status updates
    private val _statusFlow = MutableStateFlow<SocketStatusResponse?>(null)
    val statusFlow: StateFlow<SocketStatusResponse?> = _statusFlow.asStateFlow()
    
    // StateFlow for formatted JSON string
    private val _statusJsonFlow = MutableStateFlow<String>("No status available")
    val statusJsonFlow: StateFlow<String> = _statusJsonFlow.asStateFlow()
    
    // StateFlow for error messages
    private val _errorFlow = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _errorFlow.asStateFlow()
    
    // SharedFlow for OLM errors that need to be shown to the user (emits only on new/changed errors)
    private val _olmErrorFlow = MutableSharedFlow<OlmError>(replay = 0)
    val olmErrorFlow: SharedFlow<OlmError> = _olmErrorFlow.asSharedFlow()
    
    // Track the last alerted error code to avoid duplicate alerts
    private var lastAlertedErrorCode: String? = null
    
    // Flag to track if polling is active
    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()
    
    // Flag to track if polling is paused (e.g., due to low power mode)
    private var isPaused = false
    
    /**
     * Start polling the socket for status updates.
     * This should be called when the tunnel is turned on.
     */
    fun startPolling() {
        if (_isPolling.value) {
            Log.d(tag, "Polling already active, ignoring start request")
            return
        }
        
        Log.d(tag, "Starting status polling (interval: ${pollingIntervalMs}ms)")
        _isPolling.value = true
        _errorFlow.value = null
        
        // Register for standby notifications
        (context.applicationContext as? PangolinApplication)?.registerStandbyListener(this)
        
        // Initialize socket manager
        socketManager = SocketManager(socketPath)
        
        // Start the polling coroutine
        pollingJob = coroutineScope.launch {
            while (isActive && _isPolling.value) {
                // Skip polling if paused
                if (isPaused) {
                    delay(pollingIntervalMs)
                    continue
                }
                
                try {
                    // Check if socket exists before attempting to poll
                    if (!File(socketPath).exists()) {
                        Log.w(tag, "Socket file does not exist at: $socketPath")
                        _errorFlow.value = "Socket not available (tunnel may not be running)"
                        delay(pollingIntervalMs)
                        continue
                    }
                    
                    // Fetch status from socket
                    val status = socketManager?.getStatus()
                    
                    if (status != null) {
                        // Check for OLM errors FIRST and emit before updating statusFlow
                        // This ensures the error dialog is shown before TunnelManager disconnects
                        val olmError = status.error
                        if (olmError != null) {
                            // Only emit if error code is different from last alerted
                            if (olmError.code != lastAlertedErrorCode) {
                                Log.d(tag, "New OLM error detected: code=${olmError.code}, message=${olmError.message}")
                                lastAlertedErrorCode = olmError.code
                                _olmErrorFlow.emit(olmError)
                            }
                        } else {
                            // Error cleared - reset tracking so same error can alert again if it returns
                            if (lastAlertedErrorCode != null) {
                                Log.d(tag, "OLM error cleared, resetting last alerted code")
                                lastAlertedErrorCode = null
                            }
                        }
                        
                        // Update status flow (TunnelManager collects this and may disconnect)
                        _statusFlow.value = status
                        
                        // Format as pretty JSON
                        val formattedJson = try {
                            json.encodeToString(status)
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to format status as JSON", e)
                            "Error formatting status: ${e.message}"
                        }
                        _statusJsonFlow.value = formattedJson
                        
                        // Clear any previous errors
                        _errorFlow.value = null
                        
                        Log.d(tag, "Status updated: connected=${status.connected}, tunnelIP=${status.tunnelIP}")
                    }
                } catch (e: SocketError.SocketDoesNotExist) {
                    Log.w(tag, "Socket does not exist")
                    _errorFlow.value = "Socket not available"
                } catch (e: SocketError.ConnectionFailed) {
                    Log.w(tag, "Failed to connect to socket: ${e.message}")
                    _errorFlow.value = "Connection failed: ${e.message}"
                } catch (e: Exception) {
                    Log.e(tag, "Error polling status", e)
                    _errorFlow.value = "Error: ${e.message}"
                }
                
                // Wait before next poll
                delay(pollingIntervalMs)
            }
        }
    }
    
    /**
     * Pause polling temporarily without stopping it completely.
     * This should be called when entering low power mode.
     */
    fun pausePolling() {
        if (!_isPolling.value) {
            Log.d(tag, "Polling not active, ignoring pause request")
            return
        }
        
        if (isPaused) {
            Log.d(tag, "Polling already paused, ignoring pause request")
            return
        }
        
        Log.d(tag, "Pausing status polling (low power mode)")
        isPaused = true
    }
    
    /**
     * Resume polling after being paused.
     * This should be called when exiting low power mode.
     */
    fun resumePolling() {
        if (!_isPolling.value) {
            Log.d(tag, "Polling not active, ignoring resume request")
            return
        }
        
        if (!isPaused) {
            Log.d(tag, "Polling not paused, ignoring resume request")
            return
        }
        
        Log.d(tag, "Resuming status polling (normal power mode)")
        isPaused = false
    }
    
    /**
     * Stop polling the socket for status updates.
     * This should be called when the tunnel is turned off.
     */
    fun stopPolling() {
        if (!_isPolling.value) {
            Log.d(tag, "Polling not active, ignoring stop request")
            return
        }
        
        Log.d(tag, "Stopping status polling")
        _isPolling.value = false
        isPaused = false
        
        // Unregister from standby notifications
        (context.applicationContext as? PangolinApplication)?.unregisterStandbyListener(this)
        
        // Cancel the polling job
        pollingJob?.cancel()
        pollingJob = null
        
        // Clean up socket manager
        socketManager = null
        
        // Reset state
        _statusFlow.value = null
        _statusJsonFlow.value = "Tunnel disconnected"
        _errorFlow.value = null
        lastAlertedErrorCode = null
    }
    
    /**
     * Get the current status synchronously.
     * Returns null if no status has been fetched yet.
     */
    fun getCurrentStatus(): SocketStatusResponse? {
        return _statusFlow.value
    }
    
    /**
     * Get the current status as a formatted JSON string.
     */
    fun getCurrentStatusJson(): String {
        return _statusJsonFlow.value
    }
    
    /**
     * Manually trigger a status fetch (outside of the polling interval).
     * This is useful for immediate updates.
     */
    suspend fun fetchStatusNow() {
        if (socketManager == null) {
            socketManager = SocketManager(socketPath)
        }
        
        try {
            val status = socketManager?.getStatus()
            if (status != null) {
                _statusFlow.value = status
                val formattedJson = json.encodeToString(status)
                _statusJsonFlow.value = formattedJson
                _errorFlow.value = null
                Log.d(tag, "Manual status fetch successful")
            }
        } catch (e: Exception) {
            Log.e(tag, "Manual status fetch failed", e)
            _errorFlow.value = "Error: ${e.message}"
            throw e
        }
    }
    
    /**
     * Clean up resources when the manager is no longer needed.
     */
    fun cleanup() {
        Log.d(tag, "Cleaning up StatusPollingManager")
        stopPolling()
        coroutineScope.cancel()
    }
    
    // StandbyListener implementation
    override fun onEnterStandby() {
        Log.d(tag, "Entering standby - pausing polling")
        pausePolling()
    }
    
    override fun onExitStandby() {
        Log.d(tag, "Exiting standby - resuming polling")
        resumePolling()
    }
}