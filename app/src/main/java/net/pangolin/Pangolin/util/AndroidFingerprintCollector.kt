package net.pangolin.Pangolin.util

import android.app.admin.DevicePolicyManager
import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import android.os.Build
import net.pangolin.Pangolin.util.Fingerprint
import net.pangolin.Pangolin.util.Postures
import java.security.MessageDigest
import java.util.UUID
import android.provider.Settings
import java.io.File
import androidx.core.content.edit

class AndroidFingerprintCollector(
    private val context: Context
) {
    fun gatherFingerprintInfo(): Fingerprint {
        val arch = System.getProperty("os.arch") ?: "unknown"
        val model = Build.MODEL ?: "unknown"

        return Fingerprint(
            username = "",
            // hostname = Build.DEVICE ?: "",
            hostname = "",
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "",
            kernelVersion = getKernelVersion(),
            arch = arch,
            deviceModel = model,
            serialNumber = "",
            platformFingerprint = computePlatformFingerprint()
        )
    }

    fun gatherPostureChecks(): Postures {
        return Postures(
            autoUpdatesEnabled = isAutoUpdateEnabled(context),
            biometricsEnabled = isBiometricsAvailable(),
            diskEncrypted = isDiskEncrypted(),
            firewallEnabled = false, // No system firewall concept on Android
            tpmAvailable = hasStrongBox()
        )
    }

    private fun getKernelVersion(): String {
        return try {
            File("/proc/version").readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isBiometricsAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun isDiskEncrypted(): Boolean {
        val status = context.getSystemService(DevicePolicyManager::class.java)
            ?.storageEncryptionStatus
        return status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
            status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
    }

    private fun hasStrongBox(): Boolean {
        return context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    private fun isAutoUpdateEnabled(context: Context): Boolean {
        val resolver = context.contentResolver

        val possibleKeys = listOf(
            "auto_update_system",
            "auto_update",
            "ota_disable_automatic_update"
        )

        for (key in possibleKeys) {
            try {
                val value = Settings.Global.getInt(resolver, key)
                return value == 1
            } catch (_: Exception) {
                // ignore
            }
        }

        return false
    }

    private fun getOrCreatePersistentId(): String {
        val prefs = context.getSharedPreferences("fingerprint", Context.MODE_PRIVATE)
        
        // First, check if we already have a stored ID (preserves existing fingerprints)
        prefs.getString("device_id", null)?.let { 
            Log.d(TAG, "Using existing stored device ID")
            return it 
        }
        
        // If not, try to get ANDROID_ID (persists across uninstall/reinstall)
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        Log.d(TAG, "ANDROID_ID available: ${!androidId.isNullOrEmpty()}")
        
        // Use ANDROID_ID if available, otherwise generate a UUID
        val persistentId = if (!androidId.isNullOrEmpty()) {
            Log.d(TAG, "Using ANDROID_ID as persistent ID")
            androidId
        } else {
            Log.d(TAG, "ANDROID_ID unavailable, generating new UUID")
            UUID.randomUUID().toString()
        }
        
        // Store the ID for future use
        Log.d(TAG, "Storing persistent ID in SharedPreferences")
        prefs.edit { putString("device_id", persistentId) }
        return persistentId
    }

    companion object {
        private const val TAG = "AndroidFingerprintCollector"
    }

    fun computePlatformFingerprint(persistentId: String = getOrCreatePersistentId()): String {
        val raw = "android|$persistentId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())

        return digest.joinToString("") { "%02x".format(it) }
    }
}
