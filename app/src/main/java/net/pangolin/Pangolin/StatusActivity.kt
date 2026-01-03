package net.pangolin.Pangolin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import net.pangolin.Pangolin.databinding.ActivityStatusBinding

class StatusActivity : BaseNavigationActivity() {

    private lateinit var binding: ActivityStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_status
    }
}