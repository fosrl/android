package net.pangolin.Pangolin.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecretManager private constructor(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "pangolin_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
