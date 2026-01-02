package net.pangolin.Pangolin.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConfigManager(context: Context) {
    private val tag = "ConfigManager"
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "pangolin_config",
        Context.MODE_PRIVATE
    )
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    private fun loadConfig(): Config {
        val configJson = prefs.getString(CONFIG_KEY, null)
        return if (configJson != null) {
            try {
                json.decodeFromString(configJson)
            } catch (e: Exception) {
                Log.e(tag, "Error loading config: ${e.message}", e)
                Config()
            }
        } else {
            Config()
        }
    }

    fun save(config: Config): Boolean {
        return try {
            val configJson = json.encodeToString(config)
            prefs.edit().putString(CONFIG_KEY, configJson).apply()
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

    companion object {
        private const val CONFIG_KEY = "config"
    }
}