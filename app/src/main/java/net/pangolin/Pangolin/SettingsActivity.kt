
package net.pangolin.Pangolin

import android.os.Bundle
import android.view.ViewGroup
import android.text.method.DigitsKeyListener
import android.util.Patterns
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.databinding.SettingsActivityBinding
import net.pangolin.Pangolin.util.TunnelManager

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
        private var isTunnelActive = false
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            
            // Add info preference at the top to show lock status
            val infoPreference = Preference(requireContext()).apply {
                key = "tunnel_lock_info"
                isSelectable = false
                isVisible = false
                layoutResource = android.R.layout.preference_category
            }
            preferenceScreen?.addPreference(infoPreference)
            preferenceScreen?.getPreference(0)?.let { first ->
                preferenceScreen?.removePreference(infoPreference)
                preferenceScreen?.addPreference(infoPreference)
                // Move to top
                infoPreference.order = -1
            }
            
            // Observe tunnel state and disable settings when tunnel is active
            lifecycleScope.launch {
                val tunnelManager = TunnelManager.getInstance()
                if (tunnelManager != null) {
                    tunnelManager.tunnelState.collectLatest { state ->
                        isTunnelActive = state.isServiceRunning || state.isConnecting
                        updatePreferencesEnabled()
                        updateLockInfo()
                    }
                }
            }
        }
        
        private fun updateLockInfo() {
            val infoPreference = findPreference<Preference>("tunnel_lock_info")
            infoPreference?.apply {
                isVisible = isTunnelActive
                title = "Tunnel active"
                summary = "Settings cannot be changed while the tunnel is active. Please disconnect first."
            }
        }
        
        private fun updatePreferencesEnabled() {
            preferenceScreen?.let { screen ->
                setPreferencesEnabledRecursive(screen, !isTunnelActive)
            }
        }
        
        private fun setPreferencesEnabledRecursive(preferenceGroup: androidx.preference.PreferenceGroup, enabled: Boolean) {
            for (i in 0 until preferenceGroup.preferenceCount) {
                val preference = preferenceGroup.getPreference(i)
                
                // Skip the info/link preference at the top
                if (preference.key == null && preference.title?.toString()?.contains("docs") == true) {
                    continue
                }
                
                preference.isEnabled = enabled
                
                if (preference is androidx.preference.PreferenceGroup) {
                    setPreferencesEnabledRecursive(preference, enabled)
                }
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            // Don't allow opening dialogs when tunnel is active
            if (isTunnelActive) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Settings Locked")
                    .setMessage("Settings cannot be changed while the tunnel is active. Please disconnect first.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            
            if (preference is EditTextPreference) {
                // Create Material3 text input
                val textInputLayout = TextInputLayout(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    hint = preference.title
                    setPadding(
                        resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal),
                        resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical),
                        resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal),
                        0
                    )
                }
                
                val editText = TextInputEditText(textInputLayout.context).apply {
                    setText(preference.text)
                }

                // If DNS fields, restrict to numeric + decimal input (to allow dots) and validate
                val isDnsField = preference.key == "primaryDNSServer" || preference.key == "secondaryDNSServer"
                if (isDnsField) {
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.:")
                }
                
                textInputLayout.addView(editText)

                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setView(textInputLayout)
                    .setPositiveButton("OK", null) // we will override to keep the dialog open on invalid
                    .setNegativeButton("Cancel", null)
                    .create()

                dialog.setOnShowListener {
                    val okButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    okButton.setOnClickListener {
                        val newValue = editText.text?.toString()?.trim().orEmpty()
                        val isSecondary = preference.key == "secondaryDNSServer"
                        val isValid = if (isDnsField) {
                            if (isSecondary && newValue.isEmpty()) {
                                true // allow empty secondary DNS
                            } else {
                                Patterns.IP_ADDRESS.matcher(newValue).matches()
                            }
                        } else {
                            true
                        }

                        if (!isValid) {
                            textInputLayout.error = "Please enter a valid IP address"
                            textInputLayout.helperText = "Examples: 1.1.1.1, 8.8.4.4, or IPv6 like 2001:4860:4860::8888"
                        } else {
                            textInputLayout.error = null
                            textInputLayout.helperText = null
                            if (preference.callChangeListener(newValue)) {
                                preference.text = newValue
                                dialog.dismiss()
                            }
                        }
                    }
                }

                dialog.show()
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }
    }
}