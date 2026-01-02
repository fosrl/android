package net.pangolin.Pangolin

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import net.pangolin.Pangolin.databinding.SettingsActivityBinding

class SettingsActivity : BaseNavigationActivity() {

    private lateinit var binding: SettingsActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_settings
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
        }
    }
}