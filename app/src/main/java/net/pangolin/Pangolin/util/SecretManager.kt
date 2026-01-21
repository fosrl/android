package net.pangolin.Pangolin.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

class SecretManager private constructor(context: Context) {
    private val tag = "SecretManager"
    private val prefsFileName = "pangolin_secrets"
    
    private val sharedPreferences: SharedPreferences = createEncryptedPrefs(context)
    
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedSharedPreferences(context)
        } catch (e: Exception) {
            Log.e(tag, "Failed to create EncryptedSharedPreferences, attempting recovery", e)
            // This can happen if the app was uninstalled/reinstalled and the master key
            // was destroyed but the encrypted prefs file persisted.
            // Clear the corrupted files and try again.
            clearCorruptedPrefs(context)
            buildEncryptedSharedPreferences(context)
        }
    }
    
    private fun buildEncryptedSharedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        return EncryptedSharedPreferences.create(
            context,
            prefsFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private fun clearCorruptedPrefs(context: Context) {
        Log.w(tag, "Clearing corrupted EncryptedSharedPreferences files")
        
        // Delete the encrypted shared preferences file
        val prefsFile = File(context.filesDir.parent, "shared_prefs/$prefsFileName.xml")
        if (prefsFile.exists()) {
            val deleted = prefsFile.delete()
            Log.d(tag, "Deleted prefs file: $deleted")
        }
        
        // Also need to delete the Tink keyset files that EncryptedSharedPreferences uses
        val keysetPrefsFile = File(context.filesDir.parent, "shared_prefs/__androidx_security_crypto_encrypted_prefs_key_keyset__.xml")
        if (keysetPrefsFile.exists()) {
            val deleted = keysetPrefsFile.delete()
            Log.d(tag, "Deleted key keyset file: $deleted")
        }
        
        val valueKeysetPrefsFile = File(context.filesDir.parent, "shared_prefs/__androidx_security_crypto_encrypted_prefs_value_keyset__.xml")
        if (valueKeysetPrefsFile.exists()) {
            val deleted = valueKeysetPrefsFile.delete()
            Log.d(tag, "Deleted value keyset file: $deleted")
        }
        
        // Clear the master key from Android Keystore if it exists but is corrupted
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                Log.d(tag, "Deleted corrupted master key from Keystore")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to clear master key from Keystore", e)
        }
    }

    fun saveSecret(key: String, value: String): Boolean {
        return sharedPreferences.edit().putString(key, value).commit()
    }

    fun getSecret(key: String): String? {
        return sharedPreferences.getString(key, null)
    }

    fun deleteSecret(key: String): Boolean {
        return sharedPreferences.edit().remove(key).commit()
    }

    // MARK: - OLM Credentials

    fun getOlmId(userId: String): String? {
        return getSecret("olm-id-$userId")
    }

    fun getOlmSecret(userId: String): String? {
        return getSecret("olm-secret-$userId")
    }

    fun saveOlmCredentials(userId: String, olmId: String, secret: String): Boolean {
        val idSaved = saveSecret("olm-id-$userId", olmId)
        val secretSaved = saveSecret("olm-secret-$userId", secret)
        return idSaved && secretSaved
    }

    fun hasOlmCredentials(userId: String): Boolean {
        return getOlmId(userId) != null && getOlmSecret(userId) != null
    }

    fun deleteOlmCredentials(userId: String): Boolean {
        val idDeleted = deleteSecret("olm-id-$userId")
        val secretDeleted = deleteSecret("olm-secret-$userId")
        return idDeleted && secretDeleted
    }

    // MARK: - Session Token

    fun getSessionToken(userId: String): String? {
        return getSecret("session-token-$userId")
    }

    companion object {
        @Volatile
        private var instance: SecretManager? = null

        fun getInstance(context: Context): SecretManager {
            return instance ?: synchronized(this) {
                instance ?: SecretManager(context.applicationContext).also { instance = it }
            }
        }
    }
}