package net.pangolin.Pangolin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import net.pangolin.Pangolin.databinding.ActivityMainBinding
import net.pangolin.Pangolin.databinding.ContentMainBinding
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.AuthManager
import net.pangolin.Pangolin.util.AccountManager
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.SecretManager
import net.pangolin.Pangolin.util.TunnelManager
import net.pangolin.Pangolin.util.TunnelState

class MainActivity : BaseNavigationActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var contentBinding: ContentMainBinding

    // Authentication managers
    private lateinit var apiClient: APIClient
    private lateinit var authManager: AuthManager
    private lateinit var accountManager: AccountManager
    private lateinit var configManager: ConfigManager
    private lateinit var secretManager: SecretManager
    
    // Tunnel manager
    private lateinit var tunnelManager: TunnelManager

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, start the tunnel
            lifecycleScope.launch {
                tunnelManager.connect()
            }
        } else {
            Log.e("MainActivity", "VPN permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get version name
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }

        // Initialize authentication managers first (before navigation setup)
        secretManager = SecretManager.getInstance(applicationContext)
        accountManager = AccountManager.getInstance(applicationContext)
        configManager = ConfigManager.getInstance(applicationContext)
        apiClient = APIClient("https://app.pangolin.net", versionName = versionName)
        authManager = AuthManager(
            context = applicationContext,
            apiClient = apiClient,
            configManager = configManager,
            accountManager = accountManager,
            secretManager = secretManager
        )

        // Check if there are any accounts - if not, go to LoginActivity
        val accounts = accountManager.accounts
        // // log the accounts for debugging
        Log.d("MainActivity", "Existing accounts: $accounts")
        if (accounts.isEmpty()) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)

        // Initialize TunnelManager singleton
        tunnelManager = TunnelManager.getInstance(
            context = applicationContext,
            authManager = authManager,
            accountManager = accountManager,
            secretManager = secretManager,
            configManager = configManager
        )

        // Bind content layout
        contentBinding = ContentMainBinding.bind(binding.content.root)

        // Show loading overlay initially
        contentBinding.loadingOverlay.visibility = android.view.View.VISIBLE
        contentBinding.mainContent.visibility = android.view.View.GONE

        // Setup toggle switch listener with helper function
        fun setupToggleListener() {
            contentBinding.toggleConnect.setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch {
                    val currentState = tunnelManager.tunnelState.value
                    
                    // Check if the action is allowed based on current state
                    if (isChecked && !currentState.canEnable) {
                        // Trying to enable but not in a state that allows it - revert toggle
                        contentBinding.toggleConnect.setOnCheckedChangeListener(null)
                        contentBinding.toggleConnect.isChecked = false
                        setupToggleListener()
                        return@launch
                    } else if (!isChecked && !currentState.canDisable) {
                        // Trying to disable but not in a state that allows it - revert toggle
                        contentBinding.toggleConnect.setOnCheckedChangeListener(null)
                        contentBinding.toggleConnect.isChecked = true
                        setupToggleListener()
                        return@launch
                    }
                    
                    // Action is allowed, proceed
                    if (isChecked) {
                        connectTunnel()
                    } else {
                        tunnelManager.disconnect()
                    }
                }
            }
        }
        setupToggleListener()

//        // Setup login button click listener
//        contentBinding.btnLogin.setOnClickListener {
//            val intent = Intent(this, LoginActivity::class.java)
//            startActivity(intent)
//        }

        // Setup status card click listener to toggle the switch
        contentBinding.statusCard.setOnClickListener {
            lifecycleScope.launch {
                val currentState = tunnelManager.tunnelState.value
                val currentToggleState = contentBinding.toggleConnect.isChecked
                
                // Only allow toggle if the resulting action would be allowed
                if (!currentToggleState && currentState.canEnable) {
                    // Currently off, want to turn on, and can enable
                    contentBinding.toggleConnect.isChecked = true
                } else if (currentToggleState && currentState.canDisable) {
                    // Currently on, want to turn off, and can disable
                    contentBinding.toggleConnect.isChecked = false
                }
                // Otherwise, ignore the click (rapid toggle prevention)
            }
        }

        // Setup account card click listener
        contentBinding.accountButtonLayout.setOnClickListener {
            showAccountManagementDialog()
        }

        // Setup organization card click listener
        contentBinding.organizationButtonLayout.setOnClickListener {
            showOrganizationPickerDialog()
        }

        // Setup links card click listeners
        contentBinding.linkDashboard.setOnClickListener {
            val activeAccount = accountManager.activeAccount
            if (activeAccount != null) {
                val dashboardUrl = "${activeAccount.hostname}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
                startActivity(intent)
            }
        }

        contentBinding.linkHowPangolinWorks.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.pangolin.net/about/how-pangolin-works"))
            startActivity(intent)
        }

        contentBinding.linkDocumentation.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.pangolin.net/"))
            startActivity(intent)
        }

        // Setup status details card click listener
        contentBinding.statusDetailsButton.setOnClickListener {
            val intent = Intent(this, StatusActivity::class.java)
            startActivity(intent)
        }

        // Observe tunnel state changes
        lifecycleScope.launch {
            tunnelManager.tunnelState.collect { state ->
                updateTunnelState(state)
            }
        }

        // Set theme-aware logo
        setThemeAwareLogo()

        // Initialize UI state
        updateAccountOrgCard()

        // Initialize auth manager and check authentication state
        lifecycleScope.launch {
            try {
                authManager.initialize()
                // Hide loading overlay and show content once initialization is complete
                contentBinding.loadingOverlay.visibility = android.view.View.GONE
                contentBinding.mainContent.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing auth manager", e)
                // Hide loading overlay even on error
                contentBinding.loadingOverlay.visibility = android.view.View.GONE
                contentBinding.mainContent.visibility = android.view.View.VISIBLE
            }
        }

        // Observe authentication state to update UI
        lifecycleScope.launch {
            authManager.isAuthenticated.collect { isAuthenticated ->
//                updateLoginButtonText(isAuthenticated)
                updateAccountOrgCard()
            }
        }

        // Observe current user changes
        lifecycleScope.launch {
            authManager.currentUser.collect {
                updateAccountOrgCard()
            }
        }

        // Observe current organization changes
        lifecycleScope.launch {
            authManager.currentOrg.collect {
                updateAccountOrgCard()
            }
        }

        // Observe OLM errors and show alert dialog
        lifecycleScope.launch {
            tunnelManager.olmErrorFlow?.collectLatest { olmError ->
                Log.w("MainActivity", "OLM error received: code=${olmError.code}, message=${olmError.message}")
                showOlmErrorDialog(olmError.code, olmError.message)
            }
        }
    }

    private fun showOlmErrorDialog(code: String, message: String) {
        runOnUiThread {
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_error)
            val errorColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, android.graphics.Color.RED)
            icon?.setTint(errorColor)

            MaterialAlertDialogBuilder(this)
                .setTitle("Connection Error")
                .setIcon(icon)
                .setMessage(message)
                .setPositiveButton("Dismiss", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Check if there are any accounts - if not, go to LoginActivity
        val accounts = accountManager.accounts
        // // log the accounts for debugging
        Log.d("MainActivity", "Existing accounts on resume: $accounts")
        if (accounts.isEmpty()) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        
        // Sync APIClient with active account token - critical after returning from sign-in
        // where a different APIClient instance may have been used
        authManager.syncApiClientForActiveAccount()
        
        // Update authentication state
        updateAccountOrgCard()
    }

    private fun setThemeAwareLogo() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        
        contentBinding.logoImage.setImageResource(
            if (isDarkMode) R.drawable.word_mark_white else R.drawable.word_mark_black
        )
    }

//    private fun updateLoginButtonText(isAuthenticated: Boolean) {
//        contentBinding.btnLogin.text = if (isAuthenticated) {
//            val userEmail = authManager.currentUser.value?.email ?: "Account"
//            "Signed in as $userEmail"
//        } else {
//            "Sign In"
//        }
//    }

    private fun updateAccountOrgCard() {
        val activeAccount = accountManager.activeAccount
        val currentUser = authManager.currentUser.value
        val currentOrg = authManager.currentOrg.value

        if (activeAccount != null && currentUser != null) {
            // Show the account/org card
            contentBinding.accountOrgCard.visibility = View.VISIBLE
            contentBinding.tvAccountEmail.text = currentUser.email

            // Show organization section if we have an org
            if (currentOrg != null) {
                contentBinding.organizationSection.visibility = View.VISIBLE
                contentBinding.tvOrganizationName.text = currentOrg.name
            } else {
                contentBinding.organizationSection.visibility = View.GONE
            }
        } else {
            // Hide the card if not authenticated
            contentBinding.accountOrgCard.visibility = View.GONE
        }
    }

    private fun showAccountManagementDialog() {
        val accounts = accountManager.accounts.values.toList()
        val currentUserId = accountManager.activeUserId

        // Create array of account emails for the dialog
        val accountEmails = accounts.map { it.email }.toTypedArray()
        
        // Find the currently selected account index
        val currentIndex = accounts.indexOfFirst { it.userId == currentUserId }
        val checkedItem = if (currentIndex >= 0) currentIndex else -1

        // Create and tint the icon with pangolin_primary color
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_person)
        icon?.setTint(ContextCompat.getColor(this, R.color.pangolin_primary))

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Account")
            .setIcon(icon)
            .setSingleChoiceItems(accountEmails, checkedItem) { dialog, which ->
                val selectedUserId = accounts[which].userId
                if (selectedUserId != currentUserId) {
                    dialog.dismiss()
                    lifecycleScope.launch {
                        try {
                            authManager.switchAccount(selectedUserId)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error switching account", e)
                            runOnUiThread {
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("Error")
                                    .setMessage("Failed to switch account: ${e.message}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                } else {
                    dialog.dismiss()
                }
            }
            .setPositiveButton("Add Account") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton("Logout") { _, _ ->
                showLogoutConfirmation()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showLogoutConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val hasRemainingAccounts = authManager.logout()
                        if (!hasRemainingAccounts) {
                            // No more accounts, navigate to LoginActivity
                            val intent = Intent(this@MainActivity, LoginActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error during logout", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOrganizationPickerDialog() {
        val organizations = authManager.organizations.value
        val currentOrgId = authManager.currentOrg.value?.orgId

        if (organizations.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No Organizations")
                .setMessage("You don't have access to any organizations.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Create array of organization names for the dialog
        val organizationNames = organizations.map { it.name }.toTypedArray()
        
        // Find the currently selected organization index
        val currentIndex = organizations.indexOfFirst { it.orgId == currentOrgId }
        val checkedItem = if (currentIndex >= 0) currentIndex else -1

        // Create and tint the icon with pangolin_primary color
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_business)
        icon?.setTint(ContextCompat.getColor(this, R.color.pangolin_primary))

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Organization")
            .setIcon(icon)
            .setSingleChoiceItems(organizationNames, checkedItem) { dialog, which ->
                val selectedOrg = organizations[which]
                if (selectedOrg.orgId != currentOrgId) {
                    Log.i("MainActivity", "=== UI: User selected org ${selectedOrg.name} (${selectedOrg.orgId}) ===")
                    Log.i("MainActivity", "Previous org was: $currentOrgId")
                    lifecycleScope.launch {
                        try {
                            authManager.selectOrganization(selectedOrg)
                            Log.i("MainActivity", "=== UI: Org switch completed successfully ===")
                            // Log the account state after switch
                            val activeAccount = accountManager.activeAccount
                            Log.i("MainActivity", "Active account after switch: userId=${activeAccount?.userId}, orgId=${activeAccount?.orgId}")
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error switching organization", e)
                            runOnUiThread {
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("Error")
                                    .setMessage("Failed to switch organization: ${e.message}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                } else {
                    Log.i("MainActivity", "=== UI: User selected same org, no change needed ===")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_main
    }

    private fun connectTunnel() {
        Log.i("MainActivity", "=== UI: connectTunnel() called ===")
        val activeAccount = accountManager.activeAccount
        Log.i("MainActivity", "Active account before connect: userId=${activeAccount?.userId}, orgId=${activeAccount?.orgId}")
        
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Permission already granted
            lifecycleScope.launch {
                tunnelManager.connect()
            }
        }
    }

    private fun updateTunnelState(newState: TunnelState) {
        runOnUiThread {
            // Determine the status text based on the connection state
            val statusText = when {
                newState.errorMessage != null -> "Error"
                newState.isFullyConnected -> "Connected"
                newState.isRegistered -> "Connected"
                newState.isSocketConnected && !newState.isRegistered -> "Registering"
                newState.isServiceRunning && !newState.isSocketConnected -> "Connecting"
                newState.isConnecting -> "Connecting"
                else -> "Disconnected"
            }
            
            // Update status text
            contentBinding.tvStatus.text = statusText

            // Update status dot drawable based on connection state
            val dotDrawable = when {
                newState.errorMessage != null -> R.drawable.status_dot_red
                newState.isFullyConnected -> R.drawable.status_dot_green
                newState.isRegistered -> R.drawable.status_dot_green
                newState.isSocketConnected && !newState.isRegistered -> R.drawable.status_dot_orange
                newState.isServiceRunning && !newState.isSocketConnected -> R.drawable.status_dot_orange
                newState.isConnecting -> R.drawable.status_dot_orange
                else -> R.drawable.status_dot_gray
            }
            
            contentBinding.statusDot.setBackgroundResource(dotDrawable)

            // Update error message
            if (newState.errorMessage != null) {
                contentBinding.tvError.text = "Error: ${newState.errorMessage}"
                contentBinding.tvError.visibility = View.VISIBLE
            } else {
                contentBinding.tvError.visibility = View.GONE
            }

            // Update progress indicator - show when connecting or when service is up but not fully registered
            val showProgress = newState.isConnecting || (newState.isServiceRunning && !newState.isFullyConnected)
            contentBinding.progressIndicator.visibility =
                if (showProgress) View.VISIBLE else View.GONE

            // Show/hide status details card - only show when connected
            contentBinding.statusDetailsCard.visibility = 
                if (newState.isFullyConnected || newState.isRegistered) View.VISIBLE else View.GONE

            // Update toggle switch state
            // Enable switch only if we can perform an action (enable or disable)
            contentBinding.toggleConnect.isEnabled = newState.canEnable || newState.canDisable
            
            // Update toggle state without triggering listener
            contentBinding.toggleConnect.setOnCheckedChangeListener(null)
            contentBinding.toggleConnect.isChecked = newState.isServiceRunning || newState.isConnecting
            contentBinding.toggleConnect.setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch {
                    val currentState = tunnelManager.tunnelState.value
                    if (isChecked && currentState.canEnable) {
                        connectTunnel()
                    } else if (!isChecked && currentState.canDisable) {
                        tunnelManager.disconnect()
                    } else {
                        // Revert toggle if action not allowed
                        contentBinding.toggleConnect.setOnCheckedChangeListener(null)
                        contentBinding.toggleConnect.isChecked = !isChecked
                        // Re-attach listener after revert
                        contentBinding.toggleConnect.setOnCheckedChangeListener { _, checked ->
                            lifecycleScope.launch {
                                val state = tunnelManager.tunnelState.value
                                if (checked && state.canEnable) {
                                    connectTunnel()
                                } else if (!checked && state.canDisable) {
                                    tunnelManager.disconnect()
                                }
                            }
                        }
                    }
                }
            }


        }
    }
}
