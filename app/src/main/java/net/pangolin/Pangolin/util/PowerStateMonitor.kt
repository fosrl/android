package net.pangolin.Pangolin.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import net.pangolin.Pangolin.PacketTunnel.GoBackend

/**
 * Monitors Android power states including Doze mode and app idle states.
 * Logs state changes to the Go backend logger.
 */
class PowerStateMonitor(private val context: Context) {
    private val tag = "PowerStateMonitor"
    private var powerManager: PowerManager? = null
    private var isReceiverRegistered = false
    
    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    handleDozeModeChange()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    handlePowerSaveModeChange()
                }
            }
        }
    }
    
    init {
        powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
    }
    
    /**
     * Start monitoring power states
     */
    fun startMonitoring() {
        if (isReceiverRegistered) {
            Log.d(tag, "Power state monitoring already active")
            return
        }
        
        try {
            val filter = IntentFilter().apply {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            
            context.registerReceiver(powerStateReceiver, filter)
            isReceiverRegistered = true
            
            // Log initial state
            logCurrentPowerState()
            
            Log.i(tag, "Power state monitoring started")
            logToGo("Power state monitoring started")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start power state monitoring", e)
        }
    }
    
    /**
     * Stop monitoring power states
     */
    fun stopMonitoring() {
        if (!isReceiverRegistered) {
            return
        }
        
        try {
            context.unregisterReceiver(powerStateReceiver)
            isReceiverRegistered = false
            
            Log.i(tag, "Power state monitoring stopped")
            logToGo("Power state monitoring stopped")
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop power state monitoring", e)
        }
    }
    
    /**
     * Handle doze mode state changes
     */
    private fun handleDozeModeChange() {
        val isInDozeMode = isDeviceInDozeMode()
        val message = if (isInDozeMode) {
            "Device ENTERED Doze mode"
        } else {
            "Device EXITED Doze mode"
        }
        
        Log.i(tag, message)
        logToGo(message)
    }
    
    /**
     * Handle power save mode changes
     */
    private fun handlePowerSaveModeChange() {
        val isInPowerSaveMode = powerManager?.isPowerSaveMode ?: false
        val message = if (isInPowerSaveMode) {
            "Device ENTERED Power Save mode"
        } else {
            "Device EXITED Power Save mode"
        }
        
        Log.i(tag, message)
        logToGo(message)
    }
    
    /**
     * Check if device is currently in doze mode
     */
    private fun isDeviceInDozeMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isDeviceIdleMode ?: false
        } else {
            false
        }
    }
    
    /**
     * Check if device is in light doze mode (Android N+)
     * Note: This feature is simplified for compatibility
     */
    private fun isDeviceInLightDozeMode(): Boolean {
        // Simplified - just return false for now to avoid API compatibility issues
        // Full implementation would require more complex version checking
        return false
    }
    
    /**
     * Check if app is in standby (idle) mode
     */
    private fun isAppInStandby(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
        } else {
            false
        }
    }
    
    /**
     * Log current power state
     */
    private fun logCurrentPowerState() {
        val dozeModeStatus = if (isDeviceInDozeMode()) "YES" else "NO"
        val lightDozeModeStatus = if (isDeviceInLightDozeMode()) "YES" else "NO"
        val powerSaveStatus = if (powerManager?.isPowerSaveMode == true) "YES" else "NO"
        val ignoringBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName)?.let {
                if (it) "YES" else "NO"
            } ?: "UNKNOWN"
        } else {
            "N/A"
        }
        
        val status = buildString {
            append("Power State: ")
            append("Doze=$dozeModeStatus, ")
            append("LightDoze=$lightDozeModeStatus, ")
            append("PowerSave=$powerSaveStatus, ")
            append("IgnoringBatteryOpt=$ignoringBatteryOpt")
        }
        
        Log.i(tag, status)
        logToGo(status)
    }
    
    /**
     * Get current power state as a readable string
     */
    fun getCurrentPowerStateString(): String {
        val states = mutableListOf<String>()
        
        if (isDeviceInDozeMode()) {
            states.add("Doze Mode")
        }
        
        if (isDeviceInLightDozeMode()) {
            states.add("Light Doze")
        }
        
        if (powerManager?.isPowerSaveMode == true) {
            states.add("Power Save")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false) {
                states.add("Battery Restricted")
            }
        }
        
        return if (states.isEmpty()) {
            "Normal"
        } else {
            states.joinToString(", ")
        }
    }
    
    /**
     * Log message to Go backend logger
     */
    private fun logToGo(message: String) {
        try {
            GoBackend.logFromAndroid("[PowerState] $message")
        } catch (e: Exception) {
            Log.e(tag, "Failed to log to Go backend", e)
        }
    }
}