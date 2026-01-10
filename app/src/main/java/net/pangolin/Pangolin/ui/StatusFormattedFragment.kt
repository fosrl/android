package net.pangolin.Pangolin.ui

import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.MainActivity
import net.pangolin.Pangolin.R
import net.pangolin.Pangolin.util.SocketPeer
import net.pangolin.Pangolin.util.SocketStatusResponse

/**
 * Fragment that displays the tunnel status in a native UI with cards.
 * Shows application info in a top section and individual peer cards below.
 * The status is updated automatically by collecting from the StatusPollingManager's StateFlow.
 */
class StatusFormattedFragment : Fragment() {

    private var disconnectedCard: View? = null
    private var connectionStatusHeader: TextView? = null
    private var appInfoCard: MaterialCardView? = null
    private var sitesHeader: TextView? = null
    private var agentValue: TextView? = null
    private var versionValue: TextView? = null
    private var statusValue: TextView? = null
    private var statusIndicator: View? = null
    private var organizationValue: TextView? = null
    private var peersContainer: LinearLayout? = null
    private var noPeersMessage: TextView? = null

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
        disconnectedCard = view.findViewById(R.id.disconnected_card_include)
        connectionStatusHeader = view.findViewById(R.id.connection_status_header)
        appInfoCard = view.findViewById(R.id.appInfoCard)
        sitesHeader = view.findViewById(R.id.sites_header)
        agentValue = view.findViewById(R.id.agentValue)
        versionValue = view.findViewById(R.id.versionValue)
        statusValue = view.findViewById(R.id.statusValue)
        statusIndicator = view.findViewById(R.id.statusIndicator)
        organizationValue = view.findViewById(R.id.organizationValue)
        peersContainer = view.findViewById(R.id.peersContainer)
        noPeersMessage = view.findViewById(R.id.noPeersMessage)
        
        // Setup disconnected card click listener
        disconnectedCard?.findViewById<View>(R.id.disconnected_button)?.setOnClickListener {
            // Navigate to MainActivity
            val intent = Intent(requireContext(), MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
        
        // Set gray indicator for disconnected card
        disconnectedCard?.findViewById<View>(R.id.disconnected_indicator)?.let { indicator ->
            val drawable = indicator.background as? GradientDrawable
            if (drawable != null) {
                drawable.setColor(Color.parseColor("#9E9E9E")) // Gray
            } else {
                val newDrawable = GradientDrawable()
                newDrawable.shape = GradientDrawable.OVAL
                newDrawable.setColor(Color.parseColor("#9E9E9E"))
                indicator.background = newDrawable
            }
        }

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
                        Log.e("StatusFormattedFragment", "Error fetching status: $error")
                    }
                }
            }
        } else {
            showNoStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disconnectedCard = null
        connectionStatusHeader = null
        appInfoCard = null
        sitesHeader = null
        agentValue = null
        versionValue = null
        statusValue = null
        statusIndicator = null
        organizationValue = null
        peersContainer = null
        noPeersMessage = null
    }

    /**
     * Update the UI with the current status.
     */
    private fun updateUI(status: SocketStatusResponse) {
        // Hide disconnected card and show status content
        disconnectedCard?.visibility = View.GONE
        connectionStatusHeader?.visibility = View.VISIBLE
        appInfoCard?.visibility = View.VISIBLE
        sitesHeader?.visibility = View.VISIBLE
        
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
        // Show disconnected card and hide status content
        disconnectedCard?.visibility = View.VISIBLE
        connectionStatusHeader?.visibility = View.GONE
        appInfoCard?.visibility = View.GONE
        sitesHeader?.visibility = View.GONE
        peersContainer?.removeAllViews()
        noPeersMessage?.visibility = View.GONE
    }

    companion object {
        fun newInstance(): StatusFormattedFragment {
            return StatusFormattedFragment()
        }
    }
}
