package net.pangolin.Pangolin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
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

        // Initialize authentication managers first (before navigation setup)
        secretManager = SecretManager(applicationContext)
        accountManager = AccountManager(applicationContext)
        configManager = ConfigManager(applicationContext)
        apiClient = APIClient("https://app.pangolin.net")
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

        // Setup button click listener
        contentBinding.btnConnect.setOnClickListener {
            lifecycleScope.launch {
                val currentState = tunnelManager.tunnelState.value
                if (currentState.isServiceRunning) {
                    tunnelManager.disconnect()
                } else {
                    connectTunnel()
                }
            }
        }

//        // Setup login button click listener
//        contentBinding.btnLogin.setOnClickListener {
//            val intent = Intent(this, LoginActivity::class.java)
//            startActivity(intent)
//        }

        // Setup account card click listener
        contentBinding.accountButtonLayout.setOnClickListener {
            showAccountManagementDialog()
        }

        // Setup organization card click listener
        contentBinding.organizationButtonLayout.setOnClickListener {
            showOrganizationPickerDialog()
        }

        // Observe tunnel state changes
        lifecycleScope.launch {
            tunnelManager.tunnelState.collect { state ->
                updateTunnelState(state)
            }
        }

        // Initialize UI state
        updateAccountOrgCard()

        // Initialize auth manager and check authentication state
        lifecycleScope.launch {
            try {
                authManager.initialize()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing auth manager", e)
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
        
        // Update authentication state
        updateAccountOrgCard()
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

        val options = mutableListOf<String>()
        val accountUserIds = mutableListOf<String>()

        // Add existing accounts
        accounts.forEach { account ->
            val label = if (account.userId == currentUserId) {
                "${account.email} ✓"
            } else {
                account.email
            }
            options.add(label)
            accountUserIds.add(account.userId)
        }

        // Add management options
        options.add("Add Account")
        options.add("Logout")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Account")
            .setItems(options.toTypedArray()) { dialog, which ->
                when {
                    which < accounts.size -> {
                        // Switch to selected account
                        val selectedUserId = accountUserIds[which]
                        if (selectedUserId != currentUserId) {
                            lifecycleScope.launch {
                                try {
                                    authManager.switchAccount(selectedUserId)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Error switching account", e)
                                    runOnUiThread {
                                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                            .setTitle("Error")
                                            .setMessage("Failed to switch account: ${e.message}")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }
                                }
                            }
                        }
                    }
                    which == accounts.size -> {
                        // Add Account
                        val intent = Intent(this, LoginActivity::class.java)
                        startActivity(intent)
                    }
                    which == accounts.size + 1 -> {
                        // Logout
                        showLogoutConfirmation()
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showLogoutConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
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
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No Organizations")
                .setMessage("You don't have access to any organizations.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val options = organizations.map { org ->
            if (org.orgId == currentOrgId) {
                "${org.name} ✓"
            } else {
                org.name
            }
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Organization")
            .setItems(options) { dialog, which ->
                val selectedOrg = organizations[which]
                if (selectedOrg.orgId != currentOrgId) {
                    lifecycleScope.launch {
                        try {
                            authManager.selectOrganization(selectedOrg)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error switching organization", e)
                            runOnUiThread {
                                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Error")
                                    .setMessage("Failed to switch organization: ${e.message}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_main
    }

    private fun connectTunnel() {
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
            // Update status text
            contentBinding.tvStatus.text = "Status: ${newState.statusMessage}"

            // Update VPN Service status
            contentBinding.tvServiceStatus.text = if (newState.isServiceRunning) {
                "Running"
            } else {
                "Stopped"
            }
            contentBinding.tvServiceStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (newState.isServiceRunning) android.R.color.holo_green_dark else android.R.color.darker_gray
                )
            )

            // Update Socket Connected status
            contentBinding.tvSocketStatus.text = if (newState.isSocketConnected) {
                "Yes"
            } else if (newState.isServiceRunning) {
                "Connecting..."
            } else {
                "No"
            }
            contentBinding.tvSocketStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        newState.isSocketConnected -> android.R.color.holo_green_dark
                        newState.isServiceRunning -> android.R.color.holo_orange_dark
                        else -> android.R.color.darker_gray
                    }
                )
            )

            // Update Registered status
            contentBinding.tvRegisteredStatus.text = if (newState.isRegistered) {
                "Yes"
            } else if (newState.isSocketConnected) {
                "Registering..."
            } else {
                "No"
            }
            contentBinding.tvRegisteredStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        newState.isRegistered -> android.R.color.holo_green_dark
                        newState.isSocketConnected -> android.R.color.holo_orange_dark
                        else -> android.R.color.darker_gray
                    }
                )
            )

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

            // Update button
            contentBinding.btnConnect.isEnabled = !newState.isConnecting
            contentBinding.btnConnect.text = when {
                newState.isConnecting -> "Connecting..."
                newState.isServiceRunning -> "Disconnect"
                else -> "Connect"
            }

            // Update button color
            if (newState.isServiceRunning) {
                contentBinding.btnConnect.setBackgroundColor(
                    MaterialColors.getColor(
                        contentBinding.btnConnect,
                        com.google.android.material.R.attr.colorError
                    )
                )
            } else {
                contentBinding.btnConnect.setBackgroundColor(
                    MaterialColors.getColor(
                        contentBinding.btnConnect,
                        com.google.android.material.R.attr.colorPrimary
                    )
                )
            }

            // Update card background color based on connection state
            val cardColorAttr = when {
                newState.errorMessage != null -> com.google.android.material.R.attr.colorErrorContainer
                newState.isFullyConnected -> com.google.android.material.R.attr.colorPrimaryContainer
                newState.isServiceRunning -> com.google.android.material.R.attr.colorSecondaryContainer
                newState.isConnecting -> com.google.android.material.R.attr.colorSecondaryContainer
                else -> com.google.android.material.R.attr.colorSurfaceVariant
            }

            contentBinding.statusCard.setCardBackgroundColor(
                MaterialColors.getColor(contentBinding.statusCard, cardColorAttr)
            )
        }
    }
}
