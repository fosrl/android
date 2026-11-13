
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.databinding.SettingsActivityBinding
import net.pangolin.Pangolin.util.ClientSettings
import net.pangolin.Pangolin.util.DnsServerAddress
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
        private companion object {
            // How often the org's settings are re-fetched while this screen is visible.
            const val SERVER_SETTINGS_POLL_INTERVAL_MS = 10_000L

            // The preferences an org's server can manage (see ClientSettings.managedKeys).
            val MANAGEABLE_KEYS = listOf(
                "overrideDns", "tunnelDns", "primaryDNSServer", "secondaryDNSServer", "mtu", "exitNodeTakesPrecedence"
            )
        }

        private var isTunnelActive = false

        // The preference keys the org's server currently manages. Those preferences show the
        // server's value and are read-only until the server stops managing them.
        private var managedKeys: Set<String> = emptySet()
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

            observeServerSettings()
        }

        /**
         * Keeps the screen in step with what the org's server manages. While the screen is
         * visible the settings are re-fetched every [SERVER_SETTINGS_POLL_INTERVAL_MS], and
         * every change is applied to the preferences as it arrives. Android can't push these,
         * and nothing runs once the screen is no longer started.
         */
        private fun observeServerSettings() {
            val runtime = (requireActivity().application as PangolinApplication).runtime
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        runtime.configManager.serverSettings.collect { applyServerSettings(it) }
                    }
                    launch {
                        while (true) {
                            runtime.tunnelManager.refreshClientSettings()
                            delay(SERVER_SETTINGS_POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }

        /** Shows [server]'s values on the preferences it manages and locks them. */
        private fun applyServerSettings(server: ClientSettings?) {
            val newManaged = server?.managedKeys.orEmpty()
            for (key in MANAGEABLE_KEYS) {
                val pref = findPreference<Preference>(key) ?: continue
                when {
                    key in newManaged -> {
                        // Not persisted, so the user's own value is still stored underneath.
                        pref.isPersistent = false
                        showServerValue(pref, server!!)
                    }
                    key in managedKeys -> {
                        pref.isPersistent = true
                        showLocalValue(pref)
                    }
                }
            }
            managedKeys = newManaged
            updatePreferencesEnabled()
            updateDnsSettings()
            updateLockInfo()
        }

        private fun showServerValue(pref: Preference, server: ClientSettings) {
            when (pref.key) {
                "overrideDns" -> (pref as? androidx.preference.SwitchPreferenceCompat)?.isChecked = server.overrideDns == true
                "tunnelDns" -> (pref as? androidx.preference.SwitchPreferenceCompat)?.isChecked = server.tunnelDns == true
                "exitNodeTakesPrecedence" ->
                    (pref as? androidx.preference.SwitchPreferenceCompat)?.isChecked = server.exitNodeTakesPrecedence == true
                "primaryDNSServer" -> (pref as? EditTextPreference)?.text = server.primaryDns.orEmpty()
                "secondaryDNSServer" -> (pref as? EditTextPreference)?.text = server.secondaryDns.orEmpty()
                "mtu" -> (pref as? EditTextPreference)?.text = server.mtu?.toString()
            }
        }

        /** Puts back the user's own value after the server stops managing [pref]. */
        private fun showLocalValue(pref: Preference) {
            val prefs = pref.sharedPreferences ?: return
            when (pref.key) {
                "overrideDns" -> (pref as? androidx.preference.SwitchPreferenceCompat)?.isChecked = prefs.getBoolean("overrideDns", true)
                "tunnelDns", "exitNodeTakesPrecedence" ->
                    (pref as? androidx.preference.SwitchPreferenceCompat)?.isChecked = prefs.getBoolean(pref.key, false)
                "primaryDNSServer", "secondaryDNSServer" ->
                    (pref as? EditTextPreference)?.text = prefs.getString(pref.key, null)
                "mtu" -> (pref as? EditTextPreference)?.text = prefs.getString("mtu", "1280")
            }
        }
        
        /** Applies the DNS preferences' dependencies on each other and on what the server manages. */
        private fun updateDnsSettings() {
            val overrideDns = findPreference<androidx.preference.SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<androidx.preference.SwitchPreferenceCompat>("tunnelDns")
            val primaryDns = findPreference<EditTextPreference>("primaryDNSServer")
            val secondaryDns = findPreference<EditTextPreference>("secondaryDNSServer")
            val isDnsOverrideEnabled = overrideDns?.isChecked ?: true

            // If DNS override is off, force tunnel DNS off and disable it
            if (!isDnsOverrideEnabled) {
                // A value the server manages is only shown, so leave it as the server set it.
                if ("tunnelDns" !in managedKeys) tunnelDns?.isChecked = false
                tunnelDns?.isEnabled = false
            } else {
                // Only re-enable if tunnel is not active and the server doesn't manage it
                tunnelDns?.isEnabled = !isTunnelActive && "tunnelDns" !in managedKeys
            }

            // Show/hide DNS input boxes based on DNS override setting
            primaryDns?.isVisible = isDnsOverrideEnabled
            secondaryDns?.isVisible = isDnsOverrideEnabled
        }

        private fun setupDnsSettingsDependencies() {
            val overrideDns = findPreference<androidx.preference.SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<androidx.preference.SwitchPreferenceCompat>("tunnelDns")

            // Set up listeners
            overrideDns?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (!enabled) {
                    tunnelDns?.isChecked = false
                }
                // Delay update to allow preference change to complete
                view?.postDelayed({ updateDnsSettings() }, 100)
                true
            }
            
            tunnelDns?.setOnPreferenceChangeListener { _, _ ->
                // Delay update to allow preference change to complete
                view?.postDelayed({ updateDnsSettings() }, 100)
                true
            }
            
            // Initial update
            updateDnsSettings()
        }
        
        private fun updateLockInfo() {
            val infoPreference = findPreference<Preference>("tunnel_lock_info")
            val isManaged = managedKeys.isNotEmpty()
            infoPreference?.apply {
                isVisible = isTunnelActive || isManaged
                title = when {
                    isTunnelActive -> "Tunnel active"
                    else -> "Some settings are managed by your organization"
                }
                summary = buildString {
                    if (isTunnelActive) {
                        append("Connection settings cannot be changed while the tunnel is active. Please disconnect first.")
                    }
                    if (isManaged) {
                        if (isNotEmpty()) append(' ')
                        append("Settings set by your organization can't be changed. Changes take effect the next time the tunnel connects.")
                    }
                }
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
                
                // Settings the server manages stay read-only whatever the tunnel is doing.
                preference.isEnabled = enabled && preference.key?.let { it in managedKeys } != true
                
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
                            if (preference.callChangeListener(newValue)) {
                                preference.text = newValue
                                dialog.dismiss()
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
