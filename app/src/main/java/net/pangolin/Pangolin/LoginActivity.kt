package net.pangolin.Pangolin

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import net.pangolin.Pangolin.databinding.ActivityLoginBinding
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.AccountManager
import net.pangolin.Pangolin.util.AuthManager
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.SecretManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var accountManager: AccountManager
    private lateinit var authManager: AuthManager
    private var showingSelfHostedInput = false

    companion object {
        const val EXTRA_HOSTNAME = "extra_hostname"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize account manager
        accountManager = AccountManager.getInstance(applicationContext)
        
        // Initialize auth manager to check for auto-start flag
        val secretManager = SecretManager.getInstance(applicationContext)
        val configManager = ConfigManager.getInstance(applicationContext)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
        val apiClient = APIClient("https://app.pangolin.net", versionName = versionName)
        authManager = AuthManager(
            context = applicationContext,
            apiClient = apiClient,
            configManager = configManager,
            accountManager = accountManager,
            secretManager = secretManager
        )

        // Set theme-aware logo
        setThemeAwareLogo()

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

        // Setup terms and privacy links
        val spannedText = Html.fromHtml(getString(R.string.terms_and_privacy_text), Html.FROM_HTML_MODE_LEGACY)
        binding.termsText.text = removeUnderlineFromLinks(spannedText)
        binding.termsText.movementMethod = LinkMovementMethod.getInstance()

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
        
        // Check if we should auto-start device auth (for re-authentication)
        // This should happen after UI setup but before user interaction
        if (authManager.startDeviceAuthImmediately.value) {
            // Clear the flag
            authManager.setStartDeviceAuthImmediately(false)
            
            // Get hostname from active account
            val activeAccount = accountManager.activeAccount
            if (activeAccount != null) {
                // Auto-start with existing hostname
                val intent = Intent(this, SignInCodeActivity::class.java)
                intent.putExtra(SignInCodeActivity.EXTRA_HOSTNAME, activeAccount.hostname)
                intent.putExtra("AUTO_START_DEVICE_AUTH", true)
                startActivity(intent)
                // Don't finish - let user go back if needed
            } else {
                // Shouldn't happen, but fallback to normal flow
                android.util.Log.w("LoginActivity", "Auto-start requested but no active account found")
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

    private fun removeUnderlineFromLinks(spanned: Spanned): SpannableString {
        val spannable = SpannableString(spanned)
        val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        for (urlSpan in urlSpans) {
            val start = spannable.getSpanStart(urlSpan)
            val end = spannable.getSpanEnd(urlSpan)
            spannable.removeSpan(urlSpan)
            spannable.setSpan(
                object : URLSpan(urlSpan.url) {
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                    }
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun setThemeAwareLogo() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        
        binding.logoImage.setImageResource(
            if (isDarkMode) R.drawable.word_mark_white else R.drawable.word_mark_black
        )
    }
}