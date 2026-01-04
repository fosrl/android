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

/**
 * Fragment that displays the tunnel status as formatted JSON.
 * The JSON is updated automatically by collecting from the StatusPollingManager's StateFlow.
 */
class StatusJsonFragment : Fragment() {
    
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
        
        jsonStatusText = view.findViewById(R.id.jsonStatusText)
        
        // Get the StatusPollingManager from the activity
        val statusPollingManager = (activity as? StatusPollingProvider)?.getStatusPollingManager()
        
        if (statusPollingManager != null) {
            // Collect status JSON updates
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.statusJsonFlow.collect { jsonString ->
                    jsonStatusText?.text = jsonString
                }
            }
            
            // Also collect error updates
            viewLifecycleOwner.lifecycleScope.launch {
                statusPollingManager.errorFlow.collect { error ->
                    if (error != null) {
                        // Append error to the JSON display
                        val currentText = statusPollingManager.getCurrentStatusJson()
                        jsonStatusText?.text = "$currentText\n\n[ERROR]: $error"
                    }
                }
            }
        } else {
            jsonStatusText?.text = "StatusPollingManager not available.\nEnsure the tunnel is running and the activity implements StatusPollingProvider."
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        jsonStatusText = null
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