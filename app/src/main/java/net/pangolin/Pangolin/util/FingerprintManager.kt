package net.pangolin.Pangolin.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.PangolinApplication
import net.pangolin.Pangolin.util.SocketManager

class FingerprintManager(
    private val context: Context,
    private val socketManager: SocketManager,
    private val collector: AndroidFingerprintCollector
) : PangolinApplication.StandbyListener {
    private val tag = "FingerprintManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    
    // Flag to track if polling is paused (e.g., due to low power mode)
    private var isPaused = false

    fun start(intervalSeconds: Long = 30) {
        if (job?.isActive == true) {
            Log.d(tag, "Fingerprint polling already active, ignoring start request")
            return
        }

        Log.d(tag, "Starting fingerprint polling (interval: ${intervalSeconds}s)")
        
        // Register for standby notifications
        (context.applicationContext as? PangolinApplication)?.registerStandbyListener(this)

        job = scope.launch {
            while (isActive) {
                // Skip polling if paused
                if (isPaused) {
                    delay(intervalSeconds * 1000)
                    continue
                }
                
                runUpdateMetadata()
                delay(intervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        Log.d(tag, "Stopping fingerprint polling")
        
        // Unregister from standby notifications
        (context.applicationContext as? PangolinApplication)?.unregisterStandbyListener(this)
        
        job?.cancel()
        job = null
        isPaused = false
    }
    
    /**
     * Pause polling temporarily without stopping it completely.
     * This should be called when entering low power mode.
     */
    private fun pausePolling() {
        if (job?.isActive != true) {
            Log.d(tag, "Polling not active, ignoring pause request")
            return
        }
        
        if (isPaused) {
            Log.d(tag, "Polling already paused, ignoring pause request")
            return
        }
        
        Log.d(tag, "Pausing fingerprint polling (low power mode)")
        isPaused = true
    }
    
    /**
     * Resume polling after being paused.
     * This should be called when exiting low power mode.
     */
    private fun resumePolling() {
        if (job?.isActive != true) {
            Log.d(tag, "Polling not active, ignoring resume request")
            return
        }
        
        if (!isPaused) {
            Log.d(tag, "Polling not paused, ignoring resume request")
            return
        }
        
        Log.d(tag, "Resuming fingerprint polling (normal power mode)")
        isPaused = false
    }

    private suspend fun runUpdateMetadata() {
        try {
            val fingerprint = collector.gatherFingerprintInfo()
            val postures = collector.gatherPostureChecks()

            socketManager.updateMetadata(
                fingerprint = fingerprint,
                postures = postures
            )
        } catch (e: Exception) {
            Log.w(tag, "Failed to push fingerprint/postures", e)
        }
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
