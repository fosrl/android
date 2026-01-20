package net.pangolin.Pangolin.util

import android.app.admin.DevicePolicyManager
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
        val serial = getOrCreatePersistentId()

        return Fingerprint(
            username = "",
            hostname = Build.DEVICE ?: "",
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "",
            kernelVersion = getKernelVersion(),
            arch = arch,
            deviceModel = model,
            serialNumber = serial,
            platformFingerprint = computePlatformFingerprint(serial)
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
        return DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ==
            context.getSystemService(DevicePolicyManager::class.java)
                ?.storageEncryptionStatus
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
        return prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit { putString("device_id", it) }
            }
    }

    fun computePlatformFingerprint(persistentId: String): String {
        val raw = "android|$persistentId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())

        return digest.joinToString("") { "%02x".format(it) }
    }
}
