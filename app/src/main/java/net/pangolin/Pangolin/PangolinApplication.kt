package net.pangolin.Pangolin

import android.app.Application
import android.util.Log
import net.pangolin.Pangolin.util.StandbyDetector

/**
 * Application class for Pangolin.
 * Manages app-wide resources and lifecycle events, including standby detection
 * to optimize battery usage by pausing background operations when appropriate.
 */
class PangolinApplication : Application(), StandbyDetector.StandbyListener {
    
    private val tag = "PangolinApplication"
    private var standbyDetector: StandbyDetector? = null
    
    // List of listeners that want to be notified of standby changes
    private val standbyListeners = mutableListOf<StandbyListener>()
    
    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Pangolin application starting")
        
        // Initialize standby detector
        standbyDetector = StandbyDetector(this, this)
        standbyDetector?.start()
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.d(tag, "Pangolin application terminating")
        standbyDetector?.stop()
        standbyDetector = null
    }
    
    override fun onEnterStandby() {
        Log.i(tag, "Device entered standby mode - pausing background operations")
        synchronized(standbyListeners) {
            standbyListeners.forEach { it.onEnterStandby() }
        }
    }
    
    override fun onExitStandby() {
        Log.i(tag, "Device exited standby mode - resuming background operations")
        synchronized(standbyListeners) {
            standbyListeners.forEach { it.onExitStandby() }
        }
    }
    
    /**
     * Register a listener to be notified of standby state changes.
     */
    fun registerStandbyListener(listener: StandbyListener) {
        synchronized(standbyListeners) {
            if (!standbyListeners.contains(listener)) {
                standbyListeners.add(listener)
                Log.d(tag, "Registered standby listener: ${listener.javaClass.simpleName}")
            }
        }
    }
    
    /**
     * Unregister a listener from standby state changes.
     */
    fun unregisterStandbyListener(listener: StandbyListener) {
        synchronized(standbyListeners) {
            standbyListeners.remove(listener)
            Log.d(tag, "Unregistered standby listener: ${listener.javaClass.simpleName}")
        }
    }
    
    /**
     * Interface for components that want to be notified of standby state changes.
     */
    interface StandbyListener {
        fun onEnterStandby()
        fun onExitStandby()
    }
}