package net.pangolin.Pangolin.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.MainActivity
import net.pangolin.Pangolin.R

/**
 * Fragment that displays the tunnel status as formatted JSON.
 * The JSON is updated automatically by collecting from the StatusPollingManager's StateFlow.
 */
class StatusJsonFragment : Fragment() {
    
    private var disconnectedCard: View? = null
    private var jsonStatusText: TextView? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_status_json, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        disconnectedCard = view.findViewById(R.id.disconnected_card_include)
        jsonStatusText = view.findViewById(R.id.jsonStatusText)
        
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
            // Collect status JSON updates
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.statusJsonFlow.collect { jsonString ->
                    if (jsonString.isNotEmpty() && !jsonString.startsWith("No status")) {
                        // Show JSON content, hide disconnected card
                        disconnectedCard?.visibility = View.GONE
                        jsonStatusText?.visibility = View.VISIBLE
                        jsonStatusText?.text = jsonString
                    } else {
                        // Show disconnected card, hide JSON content
                        showDisconnected()
                    }
                }
            }
            
            // Also collect error updates
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.errorFlow.collect { error ->
                    if (error != null) {
                        // Show disconnected card instead of error
                        showDisconnected()
                    }
                }
            }
        } else {
            showDisconnected()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        disconnectedCard = null
        jsonStatusText = null
    }
    
    /**
     * Show the disconnected card and hide JSON content.
     */
    private fun showDisconnected() {
        disconnectedCard?.visibility = View.VISIBLE
        jsonStatusText?.visibility = View.GONE
    }
    
    companion object {
        fun newInstance(): StatusJsonFragment {
            return StatusJsonFragment()
        }
    }
}

/**
 * Interface that activities should implement to provide access to the StatusPollingManager.
 */
interface StatusPollingProvider {
    fun getStatusPollingManager(): net.pangolin.Pangolin.util.StatusPollingManager?
}