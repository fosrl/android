package net.pangolin.Pangolin

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import net.pangolin.Pangolin.databinding.ActivityLoginBinding
import net.pangolin.Pangolin.util.AccountManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var accountManager: AccountManager
    private var showingSelfHostedInput = false

    companion object {
        const val EXTRA_HOSTNAME = "extra_hostname"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize account manager
        accountManager = AccountManager(applicationContext)

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "Sign In"
        
        // Check if there are any accounts - hide back button if none exist
        val hasAccounts = accountManager.accounts.isNotEmpty()
        supportActionBar?.setDisplayHomeAsUpEnabled(hasAccounts)
        
        // Setup navigation icon click only if there are accounts
        if (hasAccounts) {
            binding.toolbar.setNavigationOnClickListener {
                onBackPressed()
            }
        }

        // Setup cloud option click
        binding.cloudOptionCard.setOnClickListener {
            startSignInCodeActivity("https://app.pangolin.net")
        }

        // Setup self-hosted option click
        binding.selfHostedOptionCard.setOnClickListener {
            showSelfHostedInput()
        }

        // Setup back button
        binding.backToSelectionButton.setOnClickListener {
            showHostingSelection()
        }

        // Setup continue button
        binding.continueButton.setOnClickListener {
            val serverUrl = binding.serverUrlInput.text.toString().trim()
            if (serverUrl.isNotEmpty()) {
                val normalizedUrl = normalizeUrl(serverUrl)
                startSignInCodeActivity(normalizedUrl)
            } else {
                binding.serverUrlInputLayout.error = "Please enter a server URL"
            }
        }

        // Clear error when user types
        binding.serverUrlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.serverUrlInputLayout.error = null
            }
        }
    }

    private fun showHostingSelection() {
        showingSelfHostedInput = false
        binding.hostingSelectionContainer.visibility = View.VISIBLE
        binding.selfHostedInputContainer.visibility = View.GONE
        binding.serverUrlInput.text?.clear()
        binding.serverUrlInputLayout.error = null
    }

    private fun showSelfHostedInput() {
        showingSelfHostedInput = true
        binding.hostingSelectionContainer.visibility = View.GONE
        binding.selfHostedInputContainer.visibility = View.VISIBLE
    }

    private fun startSignInCodeActivity(hostname: String) {
        val intent = Intent(this, SignInCodeActivity::class.java)
        intent.putExtra(SignInCodeActivity.EXTRA_HOSTNAME, hostname)
        startActivity(intent)
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        
        // Add https:// if no protocol specified
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        
        // Remove trailing slashes
        normalized = normalized.trimEnd('/')
        
        return normalized
    }

    override fun onBackPressed() {
        if (showingSelfHostedInput) {
            showHostingSelection()
        } else {
            // Only allow back if there are accounts
            if (accountManager.accounts.isNotEmpty()) {
                super.onBackPressed()
            }
        }
    }
}