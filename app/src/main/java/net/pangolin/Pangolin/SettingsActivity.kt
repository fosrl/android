
package net.pangolin.Pangolin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.ViewGroup
import android.text.method.DigitsKeyListener
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
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.DnsServerAddress
import net.pangolin.Pangolin.util.ScopedPreferenceDataStore
import net.pangolin.Pangolin.util.SettingsScope
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
        private val configManager by lazy { ConfigManager.getInstance(requireContext()) }
        private val scopedStore by lazy { ScopedPreferenceDataStore(configManager) }
        private val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(requireContext(), R.string.vpn_notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<androidx.preference.SwitchPreferenceCompat>("persistentVpnNotification")
                ?.setOnPreferenceChangeListener { _, value ->
                    if (value == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    true
                }
            
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
            
            setupOrgSettings()

            // Show "System DNS" as the summary when a DNS field is left empty
            val systemDnsSummaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                if (pref.text.isNullOrEmpty()) "System DNS" else pref.text
            }
            findPreference<EditTextPreference>("primaryDNSServer")?.summaryProvider = systemDnsSummaryProvider
            findPreference<EditTextPreference>("secondaryDNSServer")?.summaryProvider = systemDnsSummaryProvider

            // Setup DNS settings dependencies
            setupDnsSettingsDependencies()
            
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
        
        override fun onResume() {
            super.onResume()
            // The server's settings are re-fetched each time the screen opens.
            lifecycleScope.launch {
                TunnelManager.getInstance()?.refreshClientSettings()
                showScopedValues()
            }
        }

        /**
         * Routes the settings that can differ per org through [scopedStore], and adds the org
         * mode message at the top and the switch back to the global settings at the bottom.
         */
        private fun setupOrgSettings() {
            val scopedKeys = ScopedPreferenceDataStore.BOOLEAN_KEYS + ScopedPreferenceDataStore.STRING_KEYS
            for (key in scopedKeys) {
                findPreference<Preference>(key)?.preferenceDataStore = scopedStore
            }

            preferenceScreen?.addPreference(Preference(requireContext()).apply {
                key = "org_mode_info"
                isSelectable = false
                isVisible = false
                isPersistent = false
                isIconSpaceReserved = false
                order = -2
            })
            preferenceScreen?.addPreference(Preference(requireContext()).apply {
                key = "org_reset"
                isVisible = false
                isPersistent = false
                isIconSpaceReserved = false
                order = Int.MAX_VALUE
                setOnPreferenceClickListener {
                    configManager.resetActiveOrgConfig()
                    showScopedValues()
                    true
                }
            })

            // Switches have no listener of their own beyond this one
            findPreference<Preference>(ScopedPreferenceDataStore.KEY_EXIT_NODE_PRECEDENCE)
                ?.setOnPreferenceChangeListener { preference, newValue -> confirmScope(preference, newValue) }
            for (key in ScopedPreferenceDataStore.STRING_KEYS) {
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { preference, newValue -> confirmScope(preference, newValue) }
            }

            showScopedValues()
        }

        /**
         * Picks where a change is saved. Settings apply as soon as they change, so this is asked
         * here rather than on a save button. Returns true to let the change through; false when
         * it's waiting on the user, in which case [onApplied] runs once it has been applied.
         */
        private fun confirmScope(preference: Preference, newValue: Any, onApplied: () -> Unit = {}): Boolean {
            val known = configManager.knownScope
            if (known != null) {
                scopedStore.scope = known
                view?.post { showOrgMode() }
                return true
            }

            val org = configManager.activeOrg.value ?: return true
            val choices = arrayOf<CharSequence>("All Organizations", "Only ${org.name}")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Apply This Change To")
                .setItems(choices) { _, which ->
                    scopedStore.scope = if (which == 0) SettingsScope.GLOBAL else SettingsScope.ORG
                    when (preference) {
                        is androidx.preference.SwitchPreferenceCompat -> preference.isChecked = newValue as Boolean
                        is EditTextPreference -> preference.text = newValue as String
                    }
                    onApplied()
                    showOrgMode()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }

        /** Shows what the active org uses, without that counting as a change. */
        private fun showScopedValues() {
            scopedStore.suppressWrites = true
            for (key in ScopedPreferenceDataStore.BOOLEAN_KEYS) {
                findPreference<androidx.preference.SwitchPreferenceCompat>(key)?.let {
                    it.isChecked = scopedStore.getBoolean(key, it.isChecked)
                }
            }
            for (key in ScopedPreferenceDataStore.STRING_KEYS) {
                findPreference<EditTextPreference>(key)?.let {
                    it.text = scopedStore.getString(key, it.text)
                }
            }
            updatePreferencesEnabled()
            updateDnsSettings()
            scopedStore.suppressWrites = false

            showOrgMode()
        }

        private fun showOrgMode() {
            val org = configManager.activeOrg.value
            findPreference<Preference>("org_mode_info")?.apply {
                isVisible = org != null
                title = org?.let {
                    if (configManager.isOrgMode) "${it.name} is using its own settings"
                    else "${it.name} is using your global settings"
                }
                summary = if (org?.serverSettings != null) "Settings set by your organization can't be changed." else null
            }
            findPreference<Preference>("org_reset")?.apply {
                isVisible = configManager.isOrgMode
                title = "Use Global Settings for ${org?.name}"
            }
        }

        private fun setupDnsSettingsDependencies() {
            val overrideDns = findPreference<androidx.preference.SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<androidx.preference.SwitchPreferenceCompat>("tunnelDns")

            // Set up listeners
            overrideDns?.setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue as Boolean
                val applied = {
                    if (!enabled) {
                        tunnelDns?.isChecked = false
                    }
                    // Delay update to allow preference change to complete
                    view?.postDelayed({ updateDnsSettings() }, 100)
                    Unit
                }
                val allowed = confirmScope(preference, newValue, applied)
                if (allowed) applied()
                allowed
            }
            
            tunnelDns?.setOnPreferenceChangeListener { preference, newValue ->
                val applied = {
                    // Delay update to allow preference change to complete
                    view?.postDelayed({ updateDnsSettings() }, 100)
                    Unit
                }
                val allowed = confirmScope(preference, newValue, applied)
                if (allowed) applied()
                allowed
            }
            
            // Initial update
            updateDnsSettings()
        }

        // Update DNS settings based on current state
        private fun updateDnsSettings() {
            val overrideDns = findPreference<androidx.preference.SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<androidx.preference.SwitchPreferenceCompat>("tunnelDns")
            val primaryDns = findPreference<EditTextPreference>("primaryDNSServer")
            val secondaryDns = findPreference<EditTextPreference>("secondaryDNSServer")
            val isDnsOverrideEnabled = overrideDns?.isChecked ?: true

            // If DNS override is off, force tunnel DNS off and disable it
            if (!isDnsOverrideEnabled) {
                if (tunnelDns?.isEnabled == true && tunnelDns.key !in scopedStore.managedKeys()) {
                    tunnelDns.isChecked = false
                }
                tunnelDns?.isEnabled = false
            } else {
                // Only re-enable if tunnel is not active
                tunnelDns?.isEnabled = !isTunnelActive && tunnelDns?.key !in scopedStore.managedKeys()
            }

            // Show/hide DNS input boxes based on DNS override setting
            primaryDns?.isVisible = isDnsOverrideEnabled
            secondaryDns?.isVisible = isDnsOverrideEnabled
        }
        
        private fun updateLockInfo() {
            val infoPreference = findPreference<Preference>("tunnel_lock_info")
            infoPreference?.apply {
                isVisible = isTunnelActive
                title = "Tunnel active"
                summary = "Connection settings cannot be changed while the tunnel is active. Please disconnect first."
            }
        }
        
        private fun updatePreferencesEnabled() {
            preferenceScreen?.let { screen ->
                setPreferencesEnabledRecursive(screen, !isTunnelActive)
            }
            
            // Re-apply DNS dependencies after updating enabled state
            val overrideDns = findPreference<androidx.preference.SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<androidx.preference.SwitchPreferenceCompat>("tunnelDns")
            
            if (!isTunnelActive) {
                val isDnsOverrideEnabled = overrideDns?.isChecked ?: true
                if (!isDnsOverrideEnabled) {
                    tunnelDns?.isEnabled = false
                }
            }
        }
        
        private fun setPreferencesEnabledRecursive(preferenceGroup: androidx.preference.PreferenceGroup, enabled: Boolean) {
            for (i in 0 until preferenceGroup.preferenceCount) {
                val preference = preferenceGroup.getPreference(i)

                // Presentation only: keep this switch usable while connected.
                if (preference.key == "persistentVpnNotification") continue
                
                // Skip the info/link preference at the top
                if (preference.key == null && preference.title?.toString()?.contains("docs") == true) {
                    continue
                }
                
                preference.isEnabled = enabled && preference.key !in scopedStore.managedKeys()
                
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

                // If DNS fields, restrict to the characters of an IPv4/IPv6 address and validate
                val isDnsField = preference.key == "primaryDNSServer" || preference.key == "secondaryDNSServer"
                val isMtuField = preference.key == "mtu"
                if (isDnsField) {
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789abcdefABCDEF.:")
                    // DigitsKeyListener asks for the number pad, which has no ':' or hex letters for IPv6
                    editText.setRawInputType(
                        android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    )
                    textInputLayout.placeholderText = "System DNS"
                } else if (isMtuField) {
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789")
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                
                textInputLayout.addView(editText)

                val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setView(textInputLayout)
                    .setPositiveButton("OK", null) // we will override to keep the dialog open on invalid
                    .setNegativeButton("Cancel", null)

                if (isDnsField) {
                    // Clears the field and reverts to using the system DNS
                    dialogBuilder.setNeutralButton("Default", null)
                }

                val dialog = dialogBuilder.create()

                dialog.setOnShowListener {
                    val okButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    okButton.setOnClickListener {
                        val newValue = editText.text?.toString()?.trim().orEmpty()
                        val isValid = when {
                            isDnsField -> {
                                if (newValue.isEmpty()) {
                                    true
                                } else {
                                    DnsServerAddress.isValid(newValue)
                                }
                            }
                            isMtuField -> {
                                val mtuVal = newValue.toIntOrNull()
                                mtuVal != null && mtuVal in 576..65535
                            }
                            else -> true
                        }

                        if (!isValid) {
                            // Only the error is set: setting helperText afterwards replaces
                            // the error caption, so it would flash and disappear
                            textInputLayout.error = when {
                                isMtuField -> "Please enter a valid MTU value between 576 and 65535"
                                else -> "Please enter a valid IP address, like 1.1.1.1 or 2001:4860:4860::8888"
                            }
                        } else {
                            textInputLayout.error = null
                            dialog.dismiss()
                            if (preference.callChangeListener(newValue)) {
                                preference.text = newValue
                            }
                        }
                    }

                    if (isDnsField) {
                        val neutralButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                        neutralButton?.setOnClickListener {
                            editText.setText("")
                            textInputLayout.error = null
                            if (preference.callChangeListener("")) {
                                preference.text = ""
                            }
                            dialog.dismiss()
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
