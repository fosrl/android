package net.pangolin.Pangolin.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.R
import net.pangolin.Pangolin.util.SocketStatusResponse
import java.text.DecimalFormat

/**
 * Fragment that displays the tunnel status in a human-readable formatted view.
 * The status is updated automatically by collecting from the StatusPollingManager's StateFlow.
 */
class StatusFormattedFragment : Fragment() {
    
    private var formattedStatusText: TextView? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_status_formatted, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        formattedStatusText = view.findViewById(R.id.formattedStatusText)
        
        // Get the StatusPollingManager from the activity
        val statusPollingManager = (activity as? StatusPollingProvider)?.getStatusPollingManager()
        
        if (statusPollingManager != null) {
            // Collect status updates and format them
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.statusFlow.collect { status ->
                    if (status != null) {
                        formattedStatusText?.text = formatStatus(status)
                    } else {
                        formattedStatusText?.text = "No status available"
                    }
                }
            }
            
            // Also collect error updates
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.errorFlow.collect { error ->
                    if (error != null) {
                        val currentStatus = statusPollingManager.getCurrentStatus()
                        val statusText = if (currentStatus != null) {
                            formatStatus(currentStatus)
                        } else {
                            "No status available"
                        }
                        formattedStatusText?.text = "$statusText\n\n⚠️ Error: $error"
                    }
                }
            }
        } else {
            formattedStatusText?.text = "StatusPollingManager not available.\nEnsure the tunnel is running and the activity implements StatusPollingProvider."
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        formattedStatusText = null
    }
    
    /**
     * Format the socket status response into a human-readable string.
     */
    private fun formatStatus(status: SocketStatusResponse): String {
        val sb = StringBuilder()
        
        // Connection status
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("📡 CONNECTION STATUS\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
        
        val connectionIcon = if (status.connected) "✅" else "❌"
        sb.append("$connectionIcon Connected: ${status.connected}\n")
        sb.append("🔄 Status: ${status.status ?: "Unknown"}\n")
        sb.append("🏁 Terminated: ${status.terminated}\n")
        
        if (status.registered != null) {
            val registeredIcon = if (status.registered) "✅" else "❌"
            sb.append("$registeredIcon Registered: ${status.registered}\n")
        }
        
        // Network information
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("🌐 NETWORK INFORMATION\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
        
        if (status.tunnelIP != null) {
            sb.append("📍 Tunnel IP: ${status.tunnelIP}\n")
        } else {
            sb.append("📍 Tunnel IP: Not assigned\n")
        }
        
        if (status.orgId != null) {
            sb.append("🏢 Organization ID: ${status.orgId}\n")
        }
        
        // Network settings
        if (status.networkSettings != null) {
            val ns = status.networkSettings
            
            if (ns.mtu != null) {
                sb.append("📦 MTU: ${ns.mtu}\n")
            }
            
            if (!ns.dnsServers.isNullOrEmpty()) {
                sb.append("🔍 DNS Servers:\n")
                ns.dnsServers.forEach { dns ->
                    sb.append("   • $dns\n")
                }
            }
            
            if (!ns.ipv4Addresses.isNullOrEmpty()) {
                sb.append("🌍 IPv4 Addresses:\n")
                ns.ipv4Addresses.forEach { addr ->
                    sb.append("   • $addr\n")
                }
            }
            
            if (!ns.ipv6Addresses.isNullOrEmpty()) {
                sb.append("🌏 IPv6 Addresses:\n")
                ns.ipv6Addresses.forEach { addr ->
                    sb.append("   • $addr\n")
                }
            }
        }
        
        // Application information
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("ℹ️  APPLICATION INFO\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
        
        if (status.version != null) {
            sb.append("📌 Version: ${status.version}\n")
        }
        
        if (status.agent != null) {
            sb.append("🤖 Agent: ${status.agent}\n")
        }
        
        // Peers information
        if (!status.peers.isNullOrEmpty()) {
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("👥 PEERS (${status.peers.size})\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            
            status.peers.forEach { (peerId, peer) ->
                val peerIcon = if (peer.connected == true) "🟢" else "🔴"
                sb.append("$peerIcon Peer: ${peer.name ?: peerId}\n")
                
                if (peer.siteId != null) {
                    sb.append("   Site ID: ${peer.siteId}\n")
                }
                
                if (peer.connected != null) {
                    sb.append("   Connected: ${peer.connected}\n")
                }
                
                if (peer.rtt != null) {
                    val rttMs = peer.rtt / 1_000_000.0 // Convert nanoseconds to milliseconds
                    val df = DecimalFormat("#.##")
                    sb.append("   RTT: ${df.format(rttMs)} ms\n")
                }
                
                if (peer.endpoint != null) {
                    sb.append("   Endpoint: ${peer.endpoint}\n")
                }
                
                if (peer.isRelay == true) {
                    sb.append("   🔄 Relay connection\n")
                }
                
                if (peer.lastSeen != null) {
                    sb.append("   Last seen: ${peer.lastSeen}\n")
                }
                
                sb.append("\n")
            }
        } else {
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("👥 PEERS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            sb.append("No peers connected\n")
        }
        
        return sb.toString()
    }
    
    companion object {
        fun newInstance(): StatusFormattedFragment {
            return StatusFormattedFragment()
        }
    }
}