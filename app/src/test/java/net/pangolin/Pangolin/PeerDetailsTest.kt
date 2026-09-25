package net.pangolin.Pangolin

import net.pangolin.Pangolin.util.ExitNode
import net.pangolin.Pangolin.util.PANGOLIN_SERVER_PEER_KEY
import net.pangolin.Pangolin.util.SocketPeer
import net.pangolin.Pangolin.util.SocketStatusResponse
import net.pangolin.Pangolin.util.connectionLabel
import net.pangolin.Pangolin.util.gatewayLabel
import net.pangolin.Pangolin.util.parseRfc3339Millis
import net.pangolin.Pangolin.util.peerDetails
import net.pangolin.Pangolin.util.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDetailsTest {
    private fun status(
        peers: Map<String, SocketPeer>? = null,
        exitNode: ExitNode? = null,
        gatewayActive: Boolean? = null,
        gatewaySiteIds: List<Int>? = null
    ) = SocketStatusResponse(
        connected = true,
        terminated = false,
        peers = peers,
        exitNode = exitNode,
        gatewayActive = gatewayActive,
        gatewaySiteIds = gatewaySiteIds
    )

    @Test
    fun gatewayLabelMatchesCliAndWindows() {
        assertEquals("Off", gatewayLabel(status()))
        assertEquals("Off", gatewayLabel(SocketStatusResponse(connected = true, terminated = false, gatewayActive = false, gatewaySiteResourceId = 12)))
        assertEquals(
            "Active (resource 12)",
            gatewayLabel(SocketStatusResponse(connected = true, terminated = false, gatewayActive = true, gatewaySiteResourceId = 12))
        )
        assertEquals("Active", gatewayLabel(SocketStatusResponse(connected = true, terminated = false, gatewayActive = true)))
    }

    @Test
    fun connectionLabelPrefersLocalThenRelay() {
        assertEquals("Local", connectionLabel(isLocal = true, isRelay = true))
        assertEquals("Relay", connectionLabel(isLocal = false, isRelay = true))
        assertEquals("Direct", connectionLabel(isLocal = false, isRelay = false))
    }

    @Test
    fun peerDetailsMarksGatewaySites() {
        val s = status(
            peers = mapOf(
                "1" to SocketPeer(siteId = 1, name = "a", connected = true, isRelay = true),
                "2" to SocketPeer(siteId = 2, name = "b", connected = false)
            ),
            gatewayActive = true,
            gatewaySiteIds = listOf(1)
        )
        val a = peerDetails(s, "1")!!
        assertEquals("a", a.name)
        assertEquals("Relay", a.connection)
        assertEquals(true, a.gateway)
        assertEquals(false, peerDetails(s, "2")!!.gateway)
    }

    @Test
    fun gatewaySitesAreIgnoredWhenGatewayInactive() {
        val s = status(
            peers = mapOf("1" to SocketPeer(siteId = 1, name = "a")),
            gatewayActive = false,
            gatewaySiteIds = listOf(1)
        )
        assertEquals(false, peerDetails(s, "1")!!.gateway)
    }

    @Test
    fun pangolinServerRowHasNoConnectionOrGateway() {
        val s = status(exitNode = ExitNode(connected = true, endpoint = "1.2.3.4:51820"))
        val d = peerDetails(s, PANGOLIN_SERVER_PEER_KEY)!!
        assertEquals("Pangolin Server", d.name)
        assertTrue(d.connected)
        assertNull(d.connection)
        assertNull(d.gateway)
    }

    @Test
    fun missingRowsReturnNull() {
        assertNull(peerDetails(status(), "7"))
        assertNull(peerDetails(status(), PANGOLIN_SERVER_PEER_KEY))
    }

    @Test
    fun parsesGoTimestamps() {
        val utc = parseRfc3339Millis("2026-09-24T10:00:00Z")!!
        assertEquals(utc, parseRfc3339Millis("2026-09-24T10:00:00.123456789Z"))
        // 03:00 at -07:00 is 10:00 UTC
        assertEquals(utc, parseRfc3339Millis("2026-09-24T03:00:00-07:00"))
        assertEquals(utc, parseRfc3339Millis("2026-09-24T12:30:00+02:30"))
        assertNull(parseRfc3339Millis("not a time"))
        assertNull(parseRfc3339Millis(null))
    }

    @Test
    fun relativeTimeBuckets() {
        val now = parseRfc3339Millis("2026-09-24T10:00:00Z")!!
        fun ago(seconds: Long) = relativeTime(
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(now - seconds * 1000)),
            now
        )
        assertEquals("12s ago", ago(12))
        assertEquals("5m ago", ago(5 * 60))
        assertEquals("3h ago", ago(3 * 3600))
        assertEquals("2d ago", ago(2 * 86400))
    }

    @Test
    fun relativeTimeUnknownValues() {
        assertEquals("—", relativeTime(null))
        assertEquals("—", relativeTime(""))
        assertEquals("—", relativeTime("0001-01-01T00:00:00Z"))
        assertFalse(relativeTime("2026-09-24T10:00:00Z", 0L).isEmpty())
    }
}
