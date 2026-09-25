package net.pangolin.Pangolin.util

import java.util.Calendar
import java.util.TimeZone

/** Key used for the synthetic "Pangolin Server" row in the peer details lookup. */
const val PANGOLIN_SERVER_PEER_KEY = "__pangolin_server__"

/**
 * Everything the peer details dialog shows for one row of the Status screen.
 * [connection] and [gateway] are null for the Pangolin Server row, which has neither.
 */
data class PeerDetails(
    val name: String,
    val connected: Boolean,
    val connection: String?,
    val endpoint: String?,
    val lastSeen: String?,
    val gateway: Boolean?
)

/**
 * Looks up the details for a Status row: [key] is either [PANGOLIN_SERVER_PEER_KEY] or a key of
 * [SocketStatusResponse.peers]. Returns null when it is no longer in the status response.
 */
fun peerDetails(status: SocketStatusResponse, key: String): PeerDetails? {
    if (key == PANGOLIN_SERVER_PEER_KEY) {
        val exitNode = status.exitNode ?: return null
        return PeerDetails(
            name = "Pangolin Server",
            connected = exitNode.connected ?: false,
            connection = null,
            endpoint = exitNode.endpoint,
            lastSeen = exitNode.lastSeen,
            gateway = null
        )
    }

    val peer = status.peers?.get(key) ?: return null
    val siteId = peer.siteId ?: key.toIntOrNull()
    val isGateway = status.gatewayActive == true &&
        siteId != null &&
        status.gatewaySiteIds.orEmpty().contains(siteId)
    return PeerDetails(
        name = peer.name ?: key,
        connected = peer.connected ?: false,
        connection = connectionLabel(peer.isLocal == true, peer.isRelay == true),
        endpoint = peer.endpoint,
        lastSeen = peer.lastSeen,
        gateway = isGateway
    )
}

/**
 * Summarizes the exit node (gateway) the same way the CLI and Windows status do: "Off", or
 * "Active (resource N)" with the gateway site resource's ID.
 */
fun gatewayLabel(status: SocketStatusResponse): String {
    if (status.gatewayActive != true) return "Off"
    val resourceId = status.gatewaySiteResourceId ?: 0
    return if (resourceId != 0) "Active (resource $resourceId)" else "Active"
}

/** "Local", "Relay" or "Direct". Local and relay are mutually exclusive; neither means direct. */
fun connectionLabel(isLocal: Boolean, isRelay: Boolean): String = when {
    isLocal -> "Local"
    isRelay -> "Relay"
    else -> "Direct"
}

private val rfc3339 = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$""")

/**
 * Parses an RFC 3339 timestamp (as Go writes time.Time, e.g. "2026-09-24T10:00:00.123456789-07:00")
 * to epoch milliseconds, or null if it isn't one. Done by hand because java.time needs API 26 and
 * this app supports 24.
 */
fun parseRfc3339Millis(value: String?): Long? {
    val m = rfc3339.matchEntire(value ?: return null) ?: return null
    val (year, month, day, hour, minute, second, zone) = m.destructured

    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())

    val offsetMillis = if (zone == "Z") {
        0L
    } else {
        val sign = if (zone[0] == '-') -1 else 1
        val hours = zone.substring(1, 3).toLong()
        val minutes = zone.substring(4, 6).toLong()
        sign * (hours * 60 + minutes) * 60_000L
    }
    return cal.timeInMillis - offsetMillis
}

/**
 * "12s ago", "5m ago", "3h ago", "2d ago", matching the Windows and macOS apps. "—" when the
 * time is unknown; Go's zero time (year 1) counts as unknown.
 */
fun relativeTime(lastSeen: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val millis = parseRfc3339Millis(lastSeen) ?: return "—"
    if (millis < 946_684_800_000L) return "—" // before 2000: Go's zero time, never actually seen

    val seconds = maxOf(0L, (nowMillis - millis) / 1000)
    if (seconds < 60) return "${seconds}s ago"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    return "${hours / 24}d ago"
}
