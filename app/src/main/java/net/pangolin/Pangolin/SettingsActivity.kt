package net.pangolin.Pangolin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.DigitsKeyListener
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.databinding.SettingsActivityBinding
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.DnsServerAddress
import net.pangolin.Pangolin.util.ScopedPreferenceDataStore
import net.pangolin.Pangolin.util.TunnelManager

/**
 * Settings: a list of General (app-wide) and Connection (can differ per org), each opening its
 * own screen within this activity, like the iOS app.
 */
class SettingsActivity : BaseNavigationActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

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
                .replace(R.id.settings, SettingsRootFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener { updateToolbar() }
        updateToolbar()
    }

    /** Opens General or Connection from the Settings list. */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val className = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, className)
        fragment.arguments = pref.extras
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, fragment)
            // The entry's name is the screen's title.
            .addToBackStack(pref.title?.toString())
            .commit()
        return true
    }

    /** Titles the toolbar after the open screen, with a back arrow on General and Connection. */
    private fun updateToolbar() {
        val count = supportFragmentManager.backStackEntryCount
        setShowsBackArrow(count > 0)
        supportActionBar?.title = if (count > 0) {
            supportFragmentManager.getBackStackEntryAt(count - 1).name ?: "Settings"
        } else {
            "Settings"
        }
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_settings
    }

    /** The Settings list. */
    class SettingsRootFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_root, rootKey)
        }
    }

    /**
     * App-wide settings. Unlike [ConnectionSettingsFragment]'s, these are the same for every
     * organization and account.
     */
    class GeneralSettingsFragment : PreferenceFragmentCompat() {
        private val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(requireContext(), R.string.vpn_notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.general_settings, rootKey)

            findPreference<SwitchPreferenceCompat>("persistentVpnNotification")
                ?.setOnPreferenceChangeListener { _, value ->
                    if (value == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    true
                }
        }
    }

    /**
     * The tunnel settings, which can differ per org. Changes are saved for all organizations
     * until the current org gets its own settings with the banner's button; after that they're
     * saved only for it.
     */
    class ConnectionSettingsFragment : PreferenceFragmentCompat() {
        private companion object {
            // How often the org's settings are re-fetched while this screen is visible.
            const val SERVER_SETTINGS_POLL_INTERVAL_MS = 10_000L

            const val KEY_TUNNEL_LOCK_INFO = "tunnel_lock_info"
            const val KEY_ORG_BANNERS = "org_banners"
            const val KEY_ORG_MODE_BANNER = "org_mode_banner"
            const val KEY_ORG_MODE_BUTTON = "org_mode_button"
            const val KEY_MANAGED_BANNER = "managed_banner"

            /** Rows that only inform; they stay readable while the tunnel is up. */
            val INFO_KEYS = setOf(KEY_TUNNEL_LOCK_INFO, KEY_ORG_BANNERS, KEY_ORG_MODE_BANNER, KEY_MANAGED_BANNER)
        }

        private var isTunnelActive = false
        private val configManager by lazy { ConfigManager.getInstance(requireContext()) }
        private val scopedStore by lazy { ScopedPreferenceDataStore(configManager) }

        /** The settings' titles without a lock, by key. */
        private val baseTitles = mutableMapOf<String, CharSequence>()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.connection_settings, rootKey)

            // Shown at the top while the tunnel is up, since nothing here can change then.
            preferenceScreen?.addPreference(Preference(requireContext()).apply {
                key = KEY_TUNNEL_LOCK_INFO
                isSelectable = false
                isVisible = false
                isPersistent = false
                layoutResource = android.R.layout.preference_category
                order = -20
            })

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

            observeServerSettings()
        }

        /**
         * Keeps the screen in step with what the org's server manages. The settings are fetched
         * when the screen appears, then re-fetched every [SERVER_SETTINGS_POLL_INTERVAL_MS]
         * while it stays visible, and every change is shown as it arrives. Android can't push
         * these, and nothing runs once the screen is no longer started. A running tunnel still
         * only picks the new values up when it reconnects.
         */
        private fun observeServerSettings() {
            val tunnelManager = TunnelManager.getInstance() ?: return
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        configManager.activeOrg.collect { showScopedValues() }
                    }
                    launch {
                        while (true) {
                            tunnelManager.refreshClientSettings()
                            delay(SERVER_SETTINGS_POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }

        /**
         * Routes the settings that can differ per org through [scopedStore], and adds the banners
         * at the top: whether the org uses the settings for all organizations or its own, with a
         * button to switch, and how many settings its administrator manages.
         */
        private fun setupOrgSettings() {
            val scopedKeys = ScopedPreferenceDataStore.BOOLEAN_KEYS + ScopedPreferenceDataStore.STRING_KEYS
            for (key in scopedKeys) {
                findPreference<Preference>(key)?.preferenceDataStore = scopedStore
            }

            val banners = PreferenceCategory(requireContext()).apply {
                key = KEY_ORG_BANNERS
                layoutResource = R.layout.preference_category_material_card
                isIconSpaceReserved = false
                isVisible = false
                order = -10
            }
            preferenceScreen?.addPreference(banners)
            banners.addPreference(Preference(requireContext()).apply {
                key = KEY_ORG_MODE_BANNER
                layoutResource = R.layout.preference_banner
                isSelectable = false
                isPersistent = false
            })
            // On a phone there's no room beside the text, so the action is a row of its own.
            banners.addPreference(Preference(requireContext()).apply {
                key = KEY_ORG_MODE_BUTTON
                layoutResource = R.layout.preference_button
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    if (configManager.isOrgMode) {
                        confirmReset()
                    } else {
                        configManager.giveActiveOrgOwnSettings()
                        showScopedValues()
                    }
                    true
                }
            })
            banners.addPreference(Preference(requireContext()).apply {
                key = KEY_MANAGED_BANNER
                layoutResource = R.layout.preference_banner
                isSelectable = false
                isPersistent = false
                setIcon(R.drawable.ic_lock)
            })

            showScopedValues()
        }

        /** Asks before the org goes back to the settings for all organizations. */
        private fun confirmReset() {
            val name = configManager.activeOrg.value?.name ?: return
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset $name to settings for all organizations?")
                .setMessage(
                    "$name will use your settings for all organizations again. " +
                        "Settings you changed for $name will be removed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    configManager.resetActiveOrgConfig()
                    showScopedValues()
                }
                .show()
        }

        /** Shows what the active org uses, without that counting as a change. */
        private fun showScopedValues() {
            scopedStore.suppressWrites = true
            for (key in ScopedPreferenceDataStore.BOOLEAN_KEYS) {
                findPreference<SwitchPreferenceCompat>(key)?.let {
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

        /** Updates the banners and the locks on the settings the org's administrator manages. */
        private fun showOrgMode() {
            val org = configManager.activeOrg.value
            findPreference<PreferenceCategory>(KEY_ORG_BANNERS)?.isVisible = org != null
            if (org != null) {
                val name = org.name
                val ownSettings = configManager.isOrgMode
                findPreference<Preference>(KEY_ORG_MODE_BANNER)?.apply {
                    setIcon(if (ownSettings) R.drawable.ic_business else R.drawable.ic_public)
                    title = if (ownSettings) "$name has its own settings" else "$name uses your settings for all organizations"
                    summary = if (ownSettings) {
                        "Changes are saved only for $name. Other organizations use your settings for all organizations."
                    } else {
                        "Changes apply to every organization. Own settings for $name start as a copy of these."
                    }
                }
                findPreference<Preference>(KEY_ORG_MODE_BUTTON)?.title =
                    if (ownSettings) "Reset to Settings for All Organizations…" else "Use Own Settings for $name"

                val count = managedCount()
                findPreference<Preference>(KEY_MANAGED_BANNER)?.apply {
                    isVisible = count > 0
                    title = "Managed by your administrator in $name"
                    summary = "Your administrator set ${if (count == 1) "1 of these settings" else "$count of these settings"} " +
                        "for your role. To change ${if (count == 1) "it" else "them"}, contact your administrator."
                }
            }
            showManagedLocks()
        }

        /**
         * How many of the shown settings the org's server manages. The two upstream DNS servers
         * count as one, and only while they're shown (DNS override is on).
         */
        private fun managedCount(): Int {
            val dnsServersShown = findPreference<SwitchPreferenceCompat>(ScopedPreferenceDataStore.KEY_OVERRIDE_DNS)
                ?.isChecked ?: true
            return scopedStore.managedKeys()
                .map { if (it == ScopedPreferenceDataStore.KEY_SECONDARY_DNS) ScopedPreferenceDataStore.KEY_PRIMARY_DNS else it }
                .filter { dnsServersShown || it != ScopedPreferenceDataStore.KEY_PRIMARY_DNS }
                .toSet()
                .size
        }

        /** Puts a lock after the names of the settings the org's administrator manages. */
        private fun showManagedLocks() {
            val managed = scopedStore.managedKeys()
            for (key in ScopedPreferenceDataStore.BOOLEAN_KEYS + ScopedPreferenceDataStore.STRING_KEYS) {
                val preference = findPreference<Preference>(key) ?: continue
                val base = baseTitles.getOrPut(key) { preference.title ?: "" }
                preference.title = if (key in managed) lockedTitle(base) else base
            }
        }

        private fun lockedTitle(title: CharSequence): CharSequence {
            val context = requireContext()
            val size = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
            ).toInt()
            val lock = AppCompatResources.getDrawable(context, R.drawable.ic_lock)?.mutate() ?: return title
            DrawableCompat.setTint(
                lock,
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
            )
            lock.setBounds(0, 0, size, size)
            val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DynamicDrawableSpan.ALIGN_CENTER
            } else {
                DynamicDrawableSpan.ALIGN_BASELINE
            }
            return SpannableStringBuilder(title).apply {
                append("  ")
                setSpan(ImageSpan(lock, alignment), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun setupDnsSettingsDependencies() {
            val overrideDns = findPreference<SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<SwitchPreferenceCompat>("tunnelDns")

            overrideDns?.setOnPreferenceChangeListener { _, newValue ->
                if (!(newValue as Boolean)) {
                    tunnelDns?.isChecked = false
                }
                // Delay update to allow preference change to complete
                view?.postDelayed({
                    updateDnsSettings()
                    // Whether the DNS servers are shown changes the managed count.
                    showOrgMode()
                }, 100)
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

        // Update DNS settings based on current state
        private fun updateDnsSettings() {
            val overrideDns = findPreference<SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<SwitchPreferenceCompat>("tunnelDns")
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
            findPreference<Preference>(KEY_TUNNEL_LOCK_INFO)?.apply {
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
            val overrideDns = findPreference<SwitchPreferenceCompat>("overrideDns")
            val tunnelDns = findPreference<SwitchPreferenceCompat>("tunnelDns")

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

                // The banners only inform, so they stay readable; their children are handled below.
                if (preference.key !in INFO_KEYS) {
                    // Skip the info/link preference at the top
                    if (preference.key == null && preference.title?.toString()?.contains("docs") == true) {
                        continue
                    }

                    preference.isEnabled = enabled && preference.key !in scopedStore.managedKeys()
                }

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
