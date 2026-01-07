package net.pangolin.Pangolin.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.R
import net.pangolin.Pangolin.util.SocketPeer
import net.pangolin.Pangolin.util.SocketStatusResponse

/**
 * Fragment that displays the tunnel status in a native UI with cards.
 * Shows application info in a top section and individual peer cards below.
 * The status is updated automatically by collecting from the StatusPollingManager's StateFlow.
 */
class StatusFormattedFragment : Fragment() {
    
    private var agentValue: TextView? = null
    private var versionValue: TextView? = null
    private var statusValue: TextView? = null
    private var statusIndicator: View? = null
    private var organizationValue: TextView? = null
    private var peersContainer: LinearLayout? = null
    private var noPeersMessage: TextView? = null
    private var errorMessage: TextView? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_status_formatted, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        agentValue = view.findViewById(R.id.agentValue)
        versionValue = view.findViewById(R.id.versionValue)
        statusValue = view.findViewById(R.id.statusValue)
        statusIndicator = view.findViewById(R.id.statusIndicator)
        organizationValue = view.findViewById(R.id.organizationValue)
        peersContainer = view.findViewById(R.id.peersContainer)
        noPeersMessage = view.findViewById(R.id.noPeersMessage)
        errorMessage = view.findViewById(R.id.errorMessage)
        
        // Get the StatusPollingManager from the activity
        val statusPollingManager = (activity as? StatusPollingProvider)?.getStatusPollingManager()
        
        if (statusPollingManager != null) {
            // Collect status updates and update UI
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.statusFlow.collect { status ->
                    if (status != null) {
                        updateUI(status)
                    } else {
                        showNoStatus()
                    }
                }
            }
            
            // Also collect error updates
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.errorFlow.collect { error ->
                    if (error != null) {
                        showError(error)
                    } else {
                        hideError()
                    }
                }
            }
        } else {
            showNoStatus()
            showError("StatusPollingManager not available. Ensure the tunnel is running.")
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        agentValue = null
        versionValue = null
        statusValue = null
        statusIndicator = null
        organizationValue = null
        peersContainer = null
        noPeersMessage = null
        errorMessage = null
    }
    
    /**
     * Update the UI with the current status.
     */
    private fun updateUI(status: SocketStatusResponse) {
        // Update application info
        agentValue?.text = status.agent ?: "—"
        versionValue?.text = status.version ?: "—"
        
        // Update status with indicator
        val statusText = if (status.connected) "Connected" else "Disconnected"
        statusValue?.text = statusText
        updateStatusIndicator(status.connected)
        
        // Update organization
        organizationValue?.text = status.orgId ?: "—"
        
        // Update peers
        updatePeers(status.peers)
    }
    
    /**
     * Update the status indicator color based on connection state.
     */
    private fun updateStatusIndicator(connected: Boolean) {
        statusIndicator?.let { indicator ->
            val color = if (connected) {
                Color.parseColor("#4CAF50") // Green
            } else {
                Color.parseColor("#F44336") // Red
            }
            
            val drawable = indicator.background as? GradientDrawable
            if (drawable != null) {
                drawable.setColor(color)
            } else {
                // Create new drawable if needed
                val newDrawable = GradientDrawable()
                newDrawable.shape = GradientDrawable.OVAL
                newDrawable.setColor(color)
                indicator.background = newDrawable
            }
        }
    }
    
    /**
     * Update the peers section with peer cards.
     */
    private fun updatePeers(peers: Map<String, SocketPeer>?) {
        peersContainer?.removeAllViews()
        
        if (peers.isNullOrEmpty()) {
            noPeersMessage?.visibility = View.VISIBLE
            return
        }
        
        noPeersMessage?.visibility = View.GONE
        
        // Create a card for each peer
        peers.forEach { (peerId, peer) ->
            val peerCard = createPeerCard(peerId, peer)
            peersContainer?.addView(peerCard)
        }
    }
    
    /**
     * Create a card view for a single peer.
     */
    private fun createPeerCard(peerId: String, peer: SocketPeer): View {
        val inflater = LayoutInflater.from(requireContext())
        val cardView = inflater.inflate(R.layout.item_peer_card, peersContainer, false)
        
        val peerName = cardView.findViewById<TextView>(R.id.peerName)
        val peerStatus = cardView.findViewById<TextView>(R.id.peerStatus)
        val peerStatusIndicator = cardView.findViewById<View>(R.id.peerStatusIndicator)
        val peerEndpoint = cardView.findViewById<TextView>(R.id.peerEndpoint)
        
        // Set peer name
        peerName.text = peer.name ?: peerId
        
        // Set status
        val connected = peer.connected ?: false
        peerStatus.text = if (connected) "Connected" else "Disconnected"
        
        // Set status indicator color
        val color = if (connected) {
            Color.parseColor("#4CAF50") // Green
        } else {
            Color.parseColor("#9E9E9E") // Gray
        }
        
        val drawable = peerStatusIndicator.background as? GradientDrawable
        if (drawable != null) {
            drawable.setColor(color)
        } else {
            val newDrawable = GradientDrawable()
            newDrawable.shape = GradientDrawable.OVAL
            newDrawable.setColor(color)
            peerStatusIndicator.background = newDrawable
        }
        
        // Set endpoint
        peerEndpoint.text = peer.endpoint ?: "No endpoint"
        
        return cardView
    }
    
    /**
     * Show a message when no status is available.
     */
    private fun showNoStatus() {
        agentValue?.text = "—"
        versionValue?.text = "—"
        statusValue?.text = "—"
        organizationValue?.text = "—"
        peersContainer?.removeAllViews()
        noPeersMessage?.visibility = View.VISIBLE
        noPeersMessage?.text = "No status available"
    }
    
    /**
     * Show an error message.
     */
    private fun showError(error: String) {
        errorMessage?.text = "⚠️ $error"
        errorMessage?.visibility = View.VISIBLE
    }
    
    /**
     * Hide the error message.
     */
    private fun hideError() {
        errorMessage?.visibility = View.GONE
    }
    
    companion object {
        fun newInstance(): StatusFormattedFragment {
            return StatusFormattedFragment()
        }
    }
}