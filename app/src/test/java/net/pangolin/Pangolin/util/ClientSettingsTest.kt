package net.pangolin.Pangolin.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSettingsTest {
    private val local = Config(
        dnsOverrideEnabled = true,
        dnsTunnelEnabled = false,
        primaryDNSServer = "9.9.9.9",
        secondaryDNSServer = "8.8.8.8",
        mtu = 1400,
        exitNodeTakesPrecedence = false
    )

    @Test
    fun unmanagedSettingsKeepTheUsersOwnValues() {
        val server = ClientSettings()
        assertEquals(local, server.applyTo(local))
        assertTrue(server.managedKeys.isEmpty())
    }

    @Test
    fun managedSettingsReplaceTheUsersValues() {
        val server = ClientSettings(overrideDns = false, mtu = 1280, exitNodeTakesPrecedence = true)
        val merged = server.applyTo(local)
        assertEquals(false, merged.dnsOverrideEnabled)
        assertEquals(1280, merged.mtu)
        assertEquals(true, merged.exitNodeTakesPrecedence)
        assertEquals("9.9.9.9", merged.primaryDNSServer)
        assertEquals(setOf("overrideDns", "mtu", "exitNodeTakesPrecedence"), server.managedKeys)
    }

    @Test
    fun upstreamDnsServersAreManagedTogether() {
        // Managing only the primary also clears the user's secondary, as on the other clients.
        val server = ClientSettings(primaryDns = "1.1.1.1")
        val merged = server.applyTo(local)
        assertEquals("1.1.1.1", merged.primaryDNSServer)
        assertEquals("", merged.secondaryDNSServer)
        assertEquals(setOf("primaryDNSServer", "secondaryDNSServer"), server.managedKeys)
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
}
