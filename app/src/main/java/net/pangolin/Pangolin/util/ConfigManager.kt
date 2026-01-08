package net.pangolin.Pangolin.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigManager private constructor(context: Context) {
    private val tag = "ConfigManager"
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()
    
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "overrideDns", "tunnelDns", "primaryDNSServer", "secondaryDNSServer" -> {
                Log.d(tag, "Preference changed: $key, reloading config")
                _config.value = loadConfig()
            }
        }
    }
    
    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun loadConfig(): Config {
        return try {
            Config(
                dnsOverrideEnabled = prefs.getBoolean("overrideDns", false),
                dnsTunnelEnabled = prefs.getBoolean("tunnelDns", false),
                primaryDNSServer = prefs.getString("primaryDNSServer", "1.1.1.1"),
                secondaryDNSServer = prefs.getString("secondaryDNSServer", null)
            )
        } catch (e: Exception) {
            Log.e(tag, "Error loading config: ${e.message}", e)
            Config()
        }
    }

    fun save(config: Config): Boolean {
        return try {
            prefs.edit().apply {
                putBoolean("overrideDns", config.dnsOverrideEnabled ?: false)
                putBoolean("tunnelDns", config.dnsTunnelEnabled ?: false)
                putString("primaryDNSServer", config.primaryDNSServer ?: "1.1.1.1")
                putString("secondaryDNSServer", config.secondaryDNSServer)
                apply()
            }
            _config.value = config
            true
        } catch (e: Exception) {
            Log.e(tag, "Error saving config: ${e.message}", e)
            false
        }
    }

    fun updateConfig(block: (Config) -> Config) {
        val newConfig = block(_config.value)
        save(newConfig)
    }
    
    fun cleanup() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    companion object {
        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

}