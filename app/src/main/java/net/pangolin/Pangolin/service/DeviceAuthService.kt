package net.pangolin.Pangolin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.pangolin.Pangolin.R
import net.pangolin.Pangolin.SignInCodeActivity
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.APIError

class DeviceAuthService : Service() {

    companion object {
        private const val TAG = "DeviceAuthService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "device_auth_channel"
        
        const val ACTION_START_POLLING = "net.pangolin.Pangolin.action.START_POLLING"
        const val ACTION_STOP_POLLING = "net.pangolin.Pangolin.action.STOP_POLLING"
        
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_HOSTNAME = "extra_hostname"
        const val EXTRA_EXPIRES_IN_SECONDS = "extra_expires_in_seconds"
    }

    private val binder = LocalBinder()
    private var pollingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var apiClient: APIClient? = null
    
    // State flows for communicating results back to the activity
    private val _pollResult = MutableStateFlow<PollResult?>(null)
    val pollResult: StateFlow<PollResult?> = _pollResult.asStateFlow()
    
    sealed class PollResult {
        data class Success(val token: String, val hostname: String) : PollResult()
        data class Error(val message: String) : PollResult()
        object Timeout : PollResult()
        object Cancelled : PollResult()
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): DeviceAuthService = this@DeviceAuthService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "DeviceAuthService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_POLLING -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                val hostname = intent.getStringExtra(EXTRA_HOSTNAME)
                val expiresInSeconds = intent.getLongExtra(EXTRA_EXPIRES_IN_SECONDS, 300)
                
                if (code != null && hostname != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startPolling(code, hostname, expiresInSeconds)
                } else {
                    Log.e(TAG, "Missing required extras for polling")
                    stopSelf()
                }
            }
            ACTION_STOP_POLLING -> {
                stopPolling()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sign In Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when sign-in is in progress"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // Create intent to bring user back to SignInCodeActivity
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SignInCodeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Signing in...")
            .setContentText("Waiting for browser authentication")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun startPolling(code: String, hostname: String, expiresInSeconds: Long) {
        // Cancel any existing polling
        pollingJob?.cancel()
        _pollResult.value = null
        
        // Initialize API client
        apiClient = APIClient(hostname)
        
        pollingJob = serviceScope.launch {
            val startTime = System.currentTimeMillis()
            val expirationTime = startTime + (expiresInSeconds * 1000)
            
            Log.d(TAG, "Starting polling for code: $code, hostname: $hostname, expires in: ${expiresInSeconds}s")
            
            while (isActive && System.currentTimeMillis() < expirationTime) {
                try {
                    delay(2000)
                    
                    Log.d(TAG, "Polling for device auth verification...")
                    val (pollResponse, token) = apiClient!!.pollDeviceAuth(code, hostname)
                    
                    if (pollResponse.verified) {
                        Log.i(TAG, "Device auth verified!")
                        
                        if (token.isNullOrEmpty()) {
                            _pollResult.value = PollResult.Error("Device auth verified but no token received")
                            stopSelf()
                            return@launch
                        }
                        
                        _pollResult.value = PollResult.Success(token, hostname)
                        stopSelf()
                        return@launch
                    }
                } catch (e: APIError.HttpError) {
                    if (e.status == 404) {
                        Log.d(TAG, "Device auth not yet verified, continuing to poll...")
                        continue
                    } else {
                        Log.e(TAG, "HTTP error during polling: ${e.message}", e)
                        _pollResult.value = PollResult.Error(e.message ?: "HTTP error during authentication")
                        stopSelf()
                        return@launch
                    }
                } catch (e: APIError.NetworkError) {
                    Log.w(TAG, "Network error during polling, continuing: ${e.message}")
                    // Continue polling on network errors - this is the key improvement
                    continue
                } catch (e: CancellationException) {
                    Log.i(TAG, "Polling cancelled")
                    _pollResult.value = PollResult.Cancelled
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during polling: ${e.message}", e)
                    // Continue polling on unexpected errors
                    continue
                }
            }
            
            if (System.currentTimeMillis() >= expirationTime) {
                Log.w(TAG, "Device auth timed out")
                _pollResult.value = PollResult.Timeout
                stopSelf()
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _pollResult.value = PollResult.Cancelled
        Log.d(TAG, "Polling stopped")
    }

    fun resetResult() {
        _pollResult.value = null
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "DeviceAuthService destroyed")
    }
}