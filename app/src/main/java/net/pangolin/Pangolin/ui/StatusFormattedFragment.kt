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
        
        // Application information
        sb.append("APPLICATION INFO\n")
        
        if (status.version != null) {
            sb.append("Version: ${status.version}\n")
        }
        
        if (status.agent != null) {
            sb.append("Agent: ${status.agent}\n")
        }
        
        if (status.orgId != null) {
            sb.append("Organization ID: ${status.orgId}\n")
        } 
        
        // Peers information
        if (!status.peers.isNullOrEmpty()) {
            sb.append("Sites\n")
            
            status.peers.forEach { (peerId, peer) ->
                val peerIcon = if (peer.connected == true) "🟢" else "🔴"
                sb.append("$peerIcon Peer: ${peer.name ?: peerId}\n")
                
                if (peer.siteId != null) {
                    sb.append("   Site: ${peer.name}\n")
                }
                
                if (peer.connected != null) {
                    sb.append("   Connected: ${peer.connected}\n")
                }
                
                if (peer.endpoint != null) {
                    sb.append("   Endpoint: ${peer.endpoint}\n")
                }
                
                if (peer.isRelay == true) {
                    sb.append("   Relay connection\n")
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