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
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.MainActivity
import net.pangolin.Pangolin.R
import net.pangolin.Pangolin.util.PANGOLIN_SERVER_PEER_KEY
import net.pangolin.Pangolin.util.SocketStatusResponse
import net.pangolin.Pangolin.util.gatewayLabel
import net.pangolin.Pangolin.util.peerDetails
import net.pangolin.Pangolin.util.relativeTime

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
    private var gatewayValue: TextView? = null
    private var peersContainer: LinearLayout? = null
    private var noPeersMessage: TextView? = null

    // Latest status, and the peer whose details dialog is open (if any) so it follows live updates
    private var lastStatus: SocketStatusResponse? = null
    private var selectedPeerKey: String? = null
    private var peerDialog: AlertDialog? = null
    private var peerDialogContent: LinearLayout? = null

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
        gatewayValue = view.findViewById(R.id.gatewayValue)
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
        peerDialog?.dismiss()
        peerDialog = null
        peerDialogContent = null
        selectedPeerKey = null
        lastStatus = null
        disconnectedCard = null
        connectionStatusHeader = null
        appInfoCard = null
        sitesHeader = null
        agentValue = null
        versionValue = null
        statusValue = null
        statusIndicator = null
        organizationValue = null
        gatewayValue = null
        peersContainer = null
        noPeersMessage = null
    }

    /**
     * Update the UI with the current status.
     */
    private fun updateUI(status: SocketStatusResponse) {
        lastStatus = status

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

        // Update gateway (exit node)
        gatewayValue?.text = gatewayLabel(status)

        // Update peers
        updatePeers(status)
        bindPeerDialog()
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
     * Update the peers section with peer cards. The exit node (the client's own
     * connection to the Pangolin server, used for site resources hosted on the exit
     * node) is shown first as a "Pangolin Server" card, pinned above the regular peers.
     */
    private fun updatePeers(status: SocketStatusResponse) {
        peersContainer?.removeAllViews()

        val peers = status.peers
        if (status.exitNode == null && peers.isNullOrEmpty()) {
            noPeersMessage?.visibility = View.VISIBLE
            return
        }

        noPeersMessage?.visibility = View.GONE

        status.exitNode?.let { exitNode ->
            val exitNodeCard = createPeerCard(
                PANGOLIN_SERVER_PEER_KEY, "Pangolin Server", exitNode.endpoint, exitNode.connected ?: false
            )
            peersContainer?.addView(exitNodeCard)
        }

        // Create a card for each peer
        peers?.forEach { (peerId, peer) ->
            val peerCard = createPeerCard(peerId, peer.name ?: peerId, peer.endpoint, peer.connected ?: false)
            peersContainer?.addView(peerCard)
        }
    }

    /**
     * Create a card view for a single peer (or the synthetic exit node row).
     */
    private fun createPeerCard(key: String, name: String, endpoint: String?, connected: Boolean): View {
        val inflater = LayoutInflater.from(requireContext())
        val cardView = inflater.inflate(R.layout.item_peer_card, peersContainer, false)

        val peerName = cardView.findViewById<TextView>(R.id.peerName)
        val peerStatus = cardView.findViewById<TextView>(R.id.peerStatus)
        val peerStatusIndicator = cardView.findViewById<View>(R.id.peerStatusIndicator)
        val peerEndpoint = cardView.findViewById<TextView>(R.id.peerEndpoint)

        // Set peer name
        peerName.text = name

        // Set status
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
        peerEndpoint.text = endpoint ?: "No endpoint"

        // Tapping a peer shows its details
        cardView.isClickable = true
        cardView.isFocusable = true
        cardView.setOnClickListener { showPeerDetails(key) }

        return cardView
    }

    /**
     * Show the details of a peer (or the Pangolin Server row) in a dialog. It follows live status
     * updates while open, like the Windows app's site sheet.
     */
    private fun showPeerDetails(key: String) {
        peerDialog?.dismiss()

        val density = resources.displayMetrics.density
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * density).toInt()
            val vertical = (12 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
        }

        selectedPeerKey = key
        peerDialogContent = content
        peerDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Site")
            .setView(content)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                selectedPeerKey = null
                peerDialog = null
                peerDialogContent = null
            }
            .show()

        bindPeerDialog()
    }

    /**
     * Fill the open peer dialog (if any) from the latest status.
     */
    private fun bindPeerDialog() {
        val key = selectedPeerKey ?: return
        val dialog = peerDialog ?: return
        val content = peerDialogContent ?: return

        content.removeAllViews()

        val details = lastStatus?.let { peerDetails(it, key) }
        if (details == null) {
            dialog.setTitle("Site")
            content.addView(detailValueText("This site is no longer in the status response."))
            return
        }

        dialog.setTitle(details.name)
        addDetailRow(content, "Site", details.name)
        addDetailRow(content, "Status", if (details.connected) "Connected" else "Disconnected", details.connected)
        addDetailRow(content, "Connection", details.connection ?: "—")
        addDetailRow(content, "Endpoint", details.endpoint?.takeIf { it.isNotEmpty() } ?: "—", monospace = true)
        addDetailRow(content, "Last Seen", relativeTime(details.lastSeen))
        addDetailRow(
            content,
            "Exit Node",
            when (details.gateway) {
                true -> "Yes"
                false -> "No"
                null -> "—"
            }
        )
    }

    private fun detailValueText(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextIsSelectable(true)
        }
    }

    /**
     * Add a "label ... value" row. When [connected] is given, a status dot (green or gray) is
     * shown before the value.
     */
    private fun addDetailRow(
        container: LinearLayout,
        label: String,
        value: String,
        connected: Boolean? = null,
        monospace: Boolean = false
    ) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padding = (8 * density).toInt()
            setPadding(0, padding, 0, padding)
        }

        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })

        // Spacer pushes the value to the right edge
        row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f))

        if (connected != null) {
            val dot = View(requireContext())
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(Color.parseColor(if (connected) "#4CAF50" else "#9E9E9E"))
            dot.background = drawable
            val size = (8 * density).toInt()
            row.addView(dot, LinearLayout.LayoutParams(size, size).apply { marginEnd = (6 * density).toInt() })
        }

        row.addView(detailValueText(value).apply {
            gravity = android.view.Gravity.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            if (monospace) typeface = android.graphics.Typeface.MONOSPACE
        })

        container.addView(row)
    }

    /**
     * Show a message when no status is available.
     */
    private fun showNoStatus() {
        lastStatus = null
        bindPeerDialog()

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
