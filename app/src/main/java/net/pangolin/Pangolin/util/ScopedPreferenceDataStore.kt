package net.pangolin.Pangolin.util

import androidx.preference.PreferenceDataStore

/**
 * Backs the settings that can differ per org. Reads return what the active org actually uses, so
 * the settings screen shows effective values; writes go to the global settings or the org's
 * section of pangolin.json depending on [scope].
 */
class ScopedPreferenceDataStore(private val configManager: ConfigManager) : PreferenceDataStore() {
    var scope = SettingsScope.GLOBAL

    /** Set while refreshing what's displayed, which must not count as a change. */
    var suppressWrites = false

    private val activeOrgConfig: OrgConfig?
        get() = configManager.activeOrg.value?.let { configManager.orgConfig(it.id) }

    private val serverSettings: ClientSettings?
        get() = configManager.activeOrg.value?.serverSettings

    private val managesDns: Boolean
        get() = serverSettings?.managesUpstreamDns == true

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val server = serverSettings
        val org = activeOrgConfig
        val value = when (key) {
            KEY_OVERRIDE_DNS -> server?.overrideDns ?: org?.dnsOverrideEnabled
            KEY_TUNNEL_DNS -> server?.tunnelDns ?: org?.dnsTunnelEnabled
            KEY_EXIT_NODE_PRECEDENCE -> server?.exitNodeTakesPrecedence ?: org?.exitNodeTakesPrecedence
            else -> null
        }
        return value ?: configManager.globalBoolean(key, defValue)
    }

    override fun getString(key: String, defValue: String?): String? {
        val server = serverSettings
        val org = activeOrgConfig
        return when (key) {
            KEY_PRIMARY_DNS -> if (managesDns) server?.primaryDns.orEmpty() else org?.primaryDNSServer
            KEY_SECONDARY_DNS -> if (managesDns) server?.secondaryDns.orEmpty() else org?.secondaryDNSServer
            KEY_MTU -> (server?.mtu ?: org?.mtu)?.toString()
            else -> null
        } ?: configManager.globalString(key, defValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        if (suppressWrites) return
        if (scope == SettingsScope.GLOBAL) {
            configManager.putGlobalBoolean(key, value)
            return
        }
        configManager.updateActiveOrgConfig {
            when (key) {
                KEY_OVERRIDE_DNS -> it.copy(dnsOverrideEnabled = value)
                KEY_TUNNEL_DNS -> it.copy(dnsTunnelEnabled = value)
                KEY_EXIT_NODE_PRECEDENCE -> it.copy(exitNodeTakesPrecedence = value)
                else -> it
            }
        }
    }

    override fun putString(key: String, value: String?) {
        if (suppressWrites) return
        if (scope == SettingsScope.GLOBAL) {
            configManager.putGlobalString(key, value)
            return
        }
        configManager.updateActiveOrgConfig {
            when (key) {
                // An empty string lets an org clear a server the global settings define.
                KEY_PRIMARY_DNS -> it.copy(primaryDNSServer = value.orEmpty())
                KEY_SECONDARY_DNS -> it.copy(secondaryDNSServer = value.orEmpty())
                KEY_MTU -> it.copy(mtu = value?.toIntOrNull())
                else -> it
            }
        }
    }

    /** The settings keys this store's server settings manage. */
    fun managedKeys(): Set<String> {
        val server = serverSettings ?: return emptySet()
        return buildSet {
            if (server.overrideDns != null) add(KEY_OVERRIDE_DNS)
            if (server.tunnelDns != null) add(KEY_TUNNEL_DNS)
            if (server.exitNodeTakesPrecedence != null) add(KEY_EXIT_NODE_PRECEDENCE)
            if (server.managesUpstreamDns) {
                add(KEY_PRIMARY_DNS)
                add(KEY_SECONDARY_DNS)
            }
            if (server.mtu != null) add(KEY_MTU)
        }
    }

    companion object {
        const val KEY_OVERRIDE_DNS = "overrideDns"
        const val KEY_TUNNEL_DNS = "tunnelDns"
        const val KEY_EXIT_NODE_PRECEDENCE = "exitNodeTakesPrecedence"
        const val KEY_PRIMARY_DNS = "primaryDNSServer"
        const val KEY_SECONDARY_DNS = "secondaryDNSServer"
        const val KEY_MTU = "mtu"

        val BOOLEAN_KEYS = listOf(KEY_OVERRIDE_DNS, KEY_TUNNEL_DNS, KEY_EXIT_NODE_PRECEDENCE)
        val STRING_KEYS = listOf(KEY_PRIMARY_DNS, KEY_SECONDARY_DNS, KEY_MTU)
    }
}
