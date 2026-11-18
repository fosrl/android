package net.pangolin.Pangolin.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSettingsTest {
    private val global = Config(
        dnsOverrideEnabled = true,
        dnsTunnelEnabled = false,
        primaryDNSServer = "1.1.1.1",
        mtu = 1400
    )

    @Test
    fun orgOverridesGlobalAndServerOverridesOrg() {
        val org = OrgConfig(mtu = 1300, dnsOverrideEnabled = false)

        assertEquals(1400, effectiveConfig(global, null, null).mtu)
        assertEquals(1300, effectiveConfig(global, org, null).mtu)
        assertEquals(1200, effectiveConfig(global, org, ClientSettings(mtu = 1200)).mtu)
        assertEquals(false, effectiveConfig(global, org, null).dnsOverrideEnabled)
        assertEquals(true, effectiveConfig(global, null, ClientSettings(overrideDns = true)).dnsOverrideEnabled)
    }

    @Test
    fun orgCanClearAGlobalDnsServer() {
        assertEquals("", effectiveConfig(global, OrgConfig(primaryDNSServer = ""), null).primaryDNSServer)
    }

    @Test
    fun serverManagesDnsServersAsAPair() {
        val config = effectiveConfig(global, null, ClientSettings(secondaryDns = "9.9.9.9"))

        // Managing only the secondary also clears the user's primary, as on the other clients.
        assertEquals("", config.primaryDNSServer)
        assertEquals("9.9.9.9", config.secondaryDNSServer)
    }

    @Test
    fun orgSectionIsOverriddenByServerOnlyForManagedFields() {
        val org = OrgConfig(mtu = 1300, dnsTunnelEnabled = true)
        val config = effectiveConfig(global, org, ClientSettings(mtu = 1200))

        assertEquals(1200, config.mtu)
        assertEquals(true, config.dnsTunnelEnabled)
    }

    @Test
    fun matchDomainsFollowTheSamePrecedence() {
        val org = OrgConfig(matchDomains = listOf("*.org.internal"))

        assertNull(effectiveConfig(global, null, null).matchDomains)
        assertEquals(listOf("*.org.internal"), effectiveConfig(global, org, null).matchDomains)
        assertEquals(
            listOf("*.corp.internal"),
            effectiveConfig(global, org, ClientSettings(matchDomains = listOf("*.corp.internal"))).matchDomains
        )
    }

    @Test
    fun decodesTheServerResponse() {
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<MyClientSettingsResponse>(
            """{"settings":{"mtu":1300,"tunnelDns":true,"somethingNew":1}}"""
        )
        assertEquals(1300, parsed.settings?.mtu)
        assertEquals(true, parsed.settings?.tunnelDns)
        assertNull(json.decodeFromString<MyClientSettingsResponse>("""{"settings":null}""").settings)
    }

    @Test
    fun serverSettingsKeepAnOrgEntryAlive() {
        assertTrue(Account.OrgSettings().isEmpty)
        assertEquals(false, Account.OrgSettings(serverSettings = ClientSettings(mtu = 1280)).isEmpty)
    }

    @Test
    fun exitNodePrecedence() {
        val org = OrgConfig(exitNodeResourceId = 30)
        val default = ClientSettings(exitNodeSiteResourceId = 10)
        val locked = ClientSettings(exitNodeSiteResourceId = 10, exitNodeLocked = true)

        assertNull(effectiveExitNode(null, null, null))
        assertEquals(10, effectiveExitNode(default, null, null))
        assertEquals(30, effectiveExitNode(default, null, org))
        assertEquals(20, effectiveExitNode(default, 20, org))
        assertEquals(10, effectiveExitNode(locked, 20, org))
    }
}
