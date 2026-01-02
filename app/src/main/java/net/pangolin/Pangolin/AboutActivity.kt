package net.pangolin.Pangolin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import net.pangolin.Pangolin.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.app_name, R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_main -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                R.id.nav_about -> {
                    // Already here
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        binding.navView.setCheckedItem(R.id.nav_about)

        // Set version info
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            binding.content.tvVersion.text = "Version $version"
        } catch (e: Exception) {
            binding.content.tvVersion.text = "Version 1.0.0"
        }

        // Click listeners for links
        binding.content.tvDocs.setOnClickListener {
            openUrl("https://docs.pangolin.net")
        }

        binding.content.tvHowItWorks.setOnClickListener {
            openUrl("https://docs.pangolin.net/about/how-pangolin-works")
        }

        binding.content.tvTos.setOnClickListener {
            openUrl("https://pangolin.net/terms-of-service.html")
        }

        binding.content.tvPrivacy.setOnClickListener {
            openUrl("https://pangolin.net/privacy-policy.html")
        }

        binding.fab.hide()

        // Modern back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
