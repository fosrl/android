package net.pangolin.Pangolin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import net.pangolin.Pangolin.util.HealthCheckResult
import kotlinx.coroutines.launch
import net.pangolin.Pangolin.databinding.ActivitySignInCodeBinding
import net.pangolin.Pangolin.util.APIClient
import net.pangolin.Pangolin.util.AuthManager
import net.pangolin.Pangolin.util.AccountManager
import net.pangolin.Pangolin.util.ConfigManager
import net.pangolin.Pangolin.util.SecretManager

class SignInCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInCodeBinding
    private val tag = "SignInCodeActivity"

    private lateinit var apiClient: APIClient
    private lateinit var authManager: AuthManager
    private lateinit var accountManager: AccountManager
    private lateinit var configManager: ConfigManager
    private lateinit var secretManager: SecretManager

    private var hostname: String = "https://app.pangolin.net"
    private var hasAutoOpenedBrowser = false

    companion object {
        const val EXTRA_HOSTNAME = "extra_hostname"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySignInCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "Sign In"

        // Get hostname from intent
        hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: "https://app.pangolin.net"

        // Initialize managers
        secretManager = SecretManager.getInstance(applicationContext)
        accountManager = AccountManager.getInstance(applicationContext)
        configManager = ConfigManager.getInstance(applicationContext)
        apiClient = APIClient(hostname)
        authManager = AuthManager(
            context = applicationContext,
            apiClient = apiClient,
            configManager = configManager,
            accountManager = accountManager,
            secretManager = secretManager
        )

        // Setup navigation icon click
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        // Set theme-aware logo
        setThemeAwareLogo()

        // Setup copy code button
        binding.copyCodeButton.setOnClickListener {
            copyCodeToClipboard()
        }

        // Setup open login page button
        binding.openLoginButton.setOnClickListener {
            openLoginPage()
        }

        // Update URL text
        val codeWithoutHyphen = ""
        binding.urlText.text = "Or visit: $hostname/auth/login/device"

        // Show loading indicator initially
        binding.instructionText.text = "Generating sign in code..."

        // Observe device auth code
        lifecycleScope.launch {
            authManager.deviceAuthCode.collect { code ->
                if (code != null) {
                    // Hide loading indicator and update instruction text
                    binding.instructionText.text = "Enter this code on the login page"
                    
                    displayCode(code)
                    binding.codeContainer.visibility = View.VISIBLE
                    binding.copyCodeButton.visibility = View.VISIBLE
                    binding.openLoginButton.visibility = View.VISIBLE
                    binding.urlText.visibility = View.VISIBLE

                    // Auto-open browser when code is generated
                    if (!hasAutoOpenedBrowser) {
                        hasAutoOpenedBrowser = true
                        autoOpenBrowser(code)
                    }
                } else {
                    // Hide UI elements when no code
                    binding.codeContainer.visibility = View.GONE
                    binding.copyCodeButton.visibility = View.GONE
                    binding.openLoginButton.visibility = View.GONE
                    binding.urlText.visibility = View.GONE
                }
            }
        }

        // Observe authentication state
        lifecycleScope.launch {
            authManager.isAuthenticated.collect { isAuthenticated ->
                if (isAuthenticated) {
                    // Successfully authenticated
                    showSuccess()
                }
            }
        }

        // Observe error messages
        lifecycleScope.launch {
            authManager.errorMessage.collect { errorMessage ->
                if (errorMessage != null) {
                    Toast.makeText(this@SignInCodeActivity, errorMessage, Toast.LENGTH_LONG).show()

                    // If we get an error and no code is showing, go back
                    if (authManager.deviceAuthCode.value == null) {
                        // Delay to let user see the toast
                        kotlinx.coroutines.delay(2000)
                        finish()
                    }
                }
            }
        }

        // Start the device auth flow
        startDeviceAuth()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Handle deep link callback from browser
        intent?.data?.let { uri ->
            if (uri.scheme == "pangolin") {
                Log.i(tag, "Received deep link callback: $uri")
                Toast.makeText(this, "Completing sign in...", Toast.LENGTH_SHORT).show()
                // The polling in authManager will automatically detect the successful auth
                // and trigger the isAuthenticated flow
            }
        }
    }

    private fun startDeviceAuth() {
        lifecycleScope.launch {
            try {
                Log.i(tag, "Starting device auth flow with hostname: $hostname")
                authManager.loginWithDeviceAuth(hostname)
            } catch (e: Exception) {
                Log.e(tag, "Device auth failed: ${e.message}", e)
                // Don't finish immediately, let user see the error
                // The error will be shown via the errorMessage observer
            }
        }
    }

    private fun displayCode(code: String) {
        // Split code into individual characters (remove hyphen)
        val codeChars = code.replace("-", "").toCharArray()

        val codeViews = listOf(
            binding.codeChar1,
            binding.codeChar2,
            binding.codeChar3,
            binding.codeChar4,
            binding.codeChar5,
            binding.codeChar6,
            binding.codeChar7,
            binding.codeChar8
        )

        codeChars.forEachIndexed { index, char ->
            if (index < codeViews.size) {
                codeViews[index].text = char.toString()
                codeViews[index].visibility = View.VISIBLE
            }
        }

        // Show/hide the dash separator
        binding.codeDash.visibility = if (codeChars.size > 4) View.VISIBLE else View.GONE
    }

    private fun copyCodeToClipboard() {
        val code = authManager.deviceAuthCode.value
        if (code != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Sign In Code", code)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLoginPage() {
    	autoOpenBrowser(authManager.deviceAuthCode.value ?: return)
    }

    private fun autoOpenBrowser(code: String) {
        // Remove hyphen from code (e.g., "XXXX-XXXX" -> "XXXXXXXX")
        val codeWithoutHyphen = code.replace("-", "")
        val autoOpenURL = "$hostname/auth/login/device?code=$codeWithoutHyphen"

        Log.i(tag, "Auto-opening browser with URL: $autoOpenURL")

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(autoOpenURL))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to auto-open browser: ${e.message}", e)
        }
    }

    private fun showSuccess() {
        Toast.makeText(this, "Authentication Successful!", Toast.LENGTH_LONG).show()

        // Start MainActivity and clear the back stack
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any ongoing device auth polling
        authManager.cancelDeviceAuth()
    }

    override fun onBackPressed() {
        authManager.cancelDeviceAuth()
        super.onBackPressed()
    }

    private fun setThemeAwareLogo() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        
        binding.logoImage.setImageResource(
            if (isDarkMode) R.drawable.word_mark_white else R.drawable.word_mark_black
        )
    }
}
