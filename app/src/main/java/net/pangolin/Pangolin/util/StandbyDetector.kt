package net.pangolin.Pangolin.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Detects when the app/device enters standby or low power modes and notifies listeners.
 * This helps optimize battery usage by pausing unnecessary background operations.
 */
class StandbyDetector(
    private val context: Context,
    private val listener: StandbyListener
) : DefaultLifecycleObserver {
    
    private val tag = "StandbyDetector"
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var isAppInBackground = false
    private var isDeviceInteractive = true
    private var isRegistered = false
    
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(tag, "Screen turned off")
                    isDeviceInteractive = false
                    checkAndNotifyStandbyState()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(tag, "Screen turned on")
                    isDeviceInteractive = true
                    checkAndNotifyStandbyState()
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val isInDozeMode = powerManager.isDeviceIdleMode
                    Log.d(tag, "Doze mode changed: $isInDozeMode")
                    checkAndNotifyStandbyState()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val isPowerSaveMode = powerManager.isPowerSaveMode
                    Log.d(tag, "Power save mode changed: $isPowerSaveMode")
                    checkAndNotifyStandbyState()
                }
            }
        }
    }
    
    /**
     * Start detecting standby mode changes.
     */
    fun start() {
        if (isRegistered) {
            Log.d(tag, "Already registered, ignoring start request")
            return
        }
        
        Log.d(tag, "Starting standby detection")
        
        // Register for lifecycle callbacks to detect when app goes to background
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Register broadcast receivers for power events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        
        context.registerReceiver(screenStateReceiver, filter)
        isRegistered = true
        
        // Check initial state
        checkAndNotifyStandbyState()
    }
    
    /**
     * Stop detecting standby mode changes.
     */
    fun stop() {
        if (!isRegistered) {
            Log.d(tag, "Not registered, ignoring stop request")
            return
        }
        
        Log.d(tag, "Stopping standby detection")
        
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "Receiver was not registered", e)
        }
        
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        isRegistered = false
    }
    
    /**
     * Called when app moves to foreground.
     */
    override fun onStart(owner: LifecycleOwner) {
        Log.d(tag, "App moved to foreground")
        isAppInBackground = false
        checkAndNotifyStandbyState()
    }
    
    /**
     * Called when app moves to background.
     */
    override fun onStop(owner: LifecycleOwner) {
        Log.d(tag, "App moved to background")
        isAppInBackground = true
        checkAndNotifyStandbyState()
    }
    
    /**
     * Check if device is currently in standby mode and notify listener if state changed.
     */
    private fun checkAndNotifyStandbyState() {
        val isInStandby = isInStandbyMode()
        Log.d(tag, "Standby state check: inStandby=$isInStandby, " +
                "appInBackground=$isAppInBackground, " +
                "deviceInteractive=$isDeviceInteractive, " +
                "dozeMode=${powerManager.isDeviceIdleMode}, " +
                "powerSave=${powerManager.isPowerSaveMode}")
        
        if (isInStandby) {
            listener.onEnterStandby()
        } else {
            listener.onExitStandby()
        }
    }
    
    /**
     * Determine if the device is in standby mode.
     * Standby is considered when:
     * - App is in background AND screen is off, OR
     * - Device is in doze mode, OR
     * - Power save mode is active AND screen is off
     */
    private fun isInStandbyMode(): Boolean {
        return when {
            // Device is in doze mode (most aggressive power saving)
            powerManager.isDeviceIdleMode -> true
            
            // App is in background and screen is off
            isAppInBackground && !isDeviceInteractive -> true
            
            // Power save mode is active and screen is off
            powerManager.isPowerSaveMode && !isDeviceInteractive -> true
            
            // Otherwise, not in standby
            else -> false
        }
    }
    
    /**
     * Listener interface for standby state changes.
     */
    interface StandbyListener {
        /**
         * Called when device enters standby mode.
         */
        fun onEnterStandby()
        
        /**
         * Called when device exits standby mode.
         */
        fun onExitStandby()
    }
}