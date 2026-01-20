package net.pangolin.Pangolin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import net.pangolin.Pangolin.databinding.ActivityStatusBinding
import net.pangolin.Pangolin.databinding.ContentStatusBinding
import net.pangolin.Pangolin.ui.StatusFormattedFragment
import net.pangolin.Pangolin.ui.StatusJsonFragment
import net.pangolin.Pangolin.ui.StatusPollingProvider
import net.pangolin.Pangolin.util.StatusPollingManager
import java.io.File

class StatusActivity : BaseNavigationActivity(), StatusPollingProvider {

    private lateinit var binding: ActivityStatusBinding
    private lateinit var contentBinding: ContentStatusBinding
    private var statusPollingManager: StatusPollingManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)
        
        // Bind content layout
        contentBinding = ContentStatusBinding.bind(binding.content.root)

        val socketManager =
            (application as PangolinApplication).socketManager
        

        statusPollingManager = StatusPollingManager(this, socketManager)
        
        // Setup ViewPager with fragments
        setupViewPager()
    }
    
    override fun onResume() {
        super.onResume()
        // Start polling when activity is visible
        // StatusPollingManager will handle standby detection internally
        statusPollingManager?.startPolling()
    }
    
    override fun onPause() {
        super.onPause()
        // Stop polling when activity is not visible to save resources
        statusPollingManager?.stopPolling()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up the polling manager
        statusPollingManager?.cleanup()
        statusPollingManager = null
    }
    
    private fun setupViewPager() {
        val adapter = StatusPagerAdapter(this)
        contentBinding.viewPager.adapter = adapter
        
        // Connect TabLayout with ViewPager2
        TabLayoutMediator(contentBinding.tabLayout, contentBinding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Formatted"
                1 -> "JSON"
                else -> "Unknown"
            }
        }.attach()
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_status
    }
    
    override fun getStatusPollingManager(): StatusPollingManager? {
        return statusPollingManager
    }
    
    /**
     * ViewPager adapter for status fragments
     */
    private class StatusPagerAdapter(activity: StatusActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StatusFormattedFragment.newInstance()
                1 -> StatusJsonFragment.newInstance()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}