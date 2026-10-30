package net.pangolin.Pangolin.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ConfigManager private constructor(private val context: Context) {
    private val tag = "ConfigManager"
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _orgConfigs = MutableStateFlow(loadOrgConfigs())
    val orgConfigs: StateFlow<Map<String, OrgConfig>> = _orgConfigs.asStateFlow()

    // Set by TunnelManager.refreshClientSettings.
    private val _activeOrg = MutableStateFlow<ActiveOrg?>(null)
    val activeOrg: StateFlow<ActiveOrg?> = _activeOrg.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "overrideDns", "tunnelDns", "primaryDNSServer", "secondaryDNSServer", "logCollectionEnabled", "mtu", "persistentVpnNotification", "exitNodeTakesPrecedence" -> {
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
                primaryDNSServer = prefs.getString("primaryDNSServer", null),
                secondaryDNSServer = prefs.getString("secondaryDNSServer", null),
                logCollectionEnabled = prefs.getBoolean("logCollectionEnabled", false),
                mtu = prefs.getString("mtu", null)?.toIntOrNull(),
                persistentVpnNotification = prefs.getBoolean("persistentVpnNotification", false),
                exitNodeTakesPrecedence = prefs.getBoolean("exitNodeTakesPrecedence", false)
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
                putString("primaryDNSServer", config.primaryDNSServer)
                putString("secondaryDNSServer", config.secondaryDNSServer)
                putBoolean("logCollectionEnabled", config.logCollectionEnabled ?: false)
                putBoolean("persistentVpnNotification", config.persistentVpnNotification)
                putBoolean("exitNodeTakesPrecedence", config.exitNodeTakesPrecedence ?: false)
                if (config.mtu != null) putString("mtu", config.mtu.toString()) else remove("mtu")
                apply()
            }
            _config.value = config
            true
        } catch (e: Exception) {
            Log.e(tag, "Error saving config: ${e.message}", e)
            false
        }
    }

    fun globalBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    fun globalString(key: String, defValue: String?): String? = prefs.getString(key, defValue)

    fun putGlobalBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun putGlobalString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    fun setActiveOrg(org: ActiveOrg?) {
        _activeOrg.value = org
    }

    fun orgConfig(orgId: String): OrgConfig? = _orgConfigs.value[orgId]

    /** The settings for orgId: server, then its section of pangolin.json, then global. */
    fun effectiveConfig(orgId: String?, server: ClientSettings?): Config {
        return effectiveConfig(_config.value, orgId?.let { orgConfig(it) }, server)
    }

    /** The settings shown for and used by the active org, or the global ones without one. */
    fun activeEffectiveConfig(): Config {
        val org = _activeOrg.value
        return effectiveConfig(org?.id, org?.serverSettings)
    }

    /** Whether the active org has its own settings rather than using the global ones. */
    val isOrgMode: Boolean
        get() = _activeOrg.value?.let { orgConfig(it.id)?.isEmpty == false } ?: false

    /**
     * Where a change goes without asking: an org with its own settings keeps its changes, and
     * with no org only the global settings exist. Null means the user has to choose.
     */
    val knownScope: SettingsScope?
        get() = when {
            _activeOrg.value == null -> SettingsScope.GLOBAL
            isOrgMode -> SettingsScope.ORG
            else -> null
        }

    /** Saves a change to the active org's section of pangolin.json. */
    fun updateActiveOrgConfig(transform: (OrgConfig) -> OrgConfig): Boolean {
        val orgId = _activeOrg.value?.id ?: return false
        val configs = _orgConfigs.value.toMutableMap()
        val updated = transform(configs[orgId] ?: OrgConfig())
        if (updated.isEmpty) configs.remove(orgId) else configs[orgId] = updated
        return saveOrgConfigs(configs)
    }

    /** Removes the active org's own settings so it uses the global settings again. */
    fun resetActiveOrgConfig(): Boolean {
        val orgId = _activeOrg.value?.id ?: return false
        return saveOrgConfigs(_orgConfigs.value - orgId)
    }

    private fun orgConfigFile(): File = File(File(context.filesDir, "Pangolin"), "pangolin.json")

    private fun loadOrgConfigs(): Map<String, OrgConfig> {
        val file = orgConfigFile()
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString<PangolinFile>(file.readText()).orgs
        } catch (e: Exception) {
            Log.e(tag, "Error loading pangolin.json: ${e.message}", e)
            emptyMap()
        }
    }

    private fun saveOrgConfigs(configs: Map<String, OrgConfig>): Boolean {
        return try {
            val file = orgConfigFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(PangolinFile(configs)))
            _orgConfigs.value = configs
            true
        } catch (e: Exception) {
            Log.e(tag, "Error saving pangolin.json: ${e.message}", e)
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
