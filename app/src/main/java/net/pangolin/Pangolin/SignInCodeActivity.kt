package net.pangolin.Pangolin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var currentCode: String? = null
    private var expiresInSeconds: Long = 300

    // Chrome Custom Tabs
    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsConnection: CustomTabsServiceConnection? = null

    companion object {
        const val EXTRA_HOSTNAME = "extra_hostname"
        private const val CUSTOM_TAB_PACKAGE_NAME = "com.android.chrome"
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

        // Setup Chrome Custom Tabs connection
        setupCustomTabs()

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
        binding.urlText.text = "Or visit: $hostname/auth/login/device"

        // Show loading indicator initially
        binding.instructionText.text = "Generating sign in code..."

        // Observe device auth code from AuthManager (for initial code generation)
        lifecycleScope.launch {
            authManager.deviceAuthCode.collect { code ->
                if (code != null) {
                    currentCode = code
                    // Hide loading indicator and update instruction text
                    binding.instructionText.text = "Enter this code on the login page"
                    
                    displayCode(code)
                    binding.codeContainer.visibility = View.VISIBLE
                    binding.copyCodeButton.visibility = View.VISIBLE
                    binding.openLoginButton.visibility = View.VISIBLE
                    binding.urlText.visibility = View.VISIBLE

                    // Auto-open browser with the code
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
                        delay(2000)
                        finish()
                    }
                }
            }
        }

        // Check if this is a deep link callback
        if (intent?.data?.scheme == "pangolin") {
            handleDeepLinkCallback()
        } else {
            // Start device auth flow
            startDeviceAuth()
        }
    }

    private fun setupCustomTabs() {
        customTabsConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                customTabsClient = client
                client.warmup(0)
                customTabsSession = client.newSession(object : CustomTabsCallback() {
                    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                        Log.d(tag, "Custom Tab navigation event: $navigationEvent")
                    }
                })
                
                // Pre-fetch the login page for faster loading
                val codeWithoutHyphen = currentCode?.replace("-", "") ?: ""
                if (codeWithoutHyphen.isNotEmpty()) {
                    val loginUrl = "$hostname/auth/login/device?code=$codeWithoutHyphen"
                    customTabsSession?.mayLaunchUrl(Uri.parse(loginUrl), null, null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                customTabsClient = null
                customTabsSession = null
            }
        }

        // Try to bind to Chrome Custom Tabs service
        val packageName = getCustomTabsPackage()
        if (packageName != null) {
            CustomTabsClient.bindCustomTabsService(this, packageName, customTabsConnection!!)
        }
    }

    private fun getCustomTabsPackage(): String? {
        val activityIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val resolvedActivityList = packageManager.queryIntentActivities(activityIntent, 0)
        
        val packagesSupportingCustomTabs = mutableListOf<String>()
        
        for (info in resolvedActivityList) {
            val serviceIntent = Intent()
            serviceIntent.action = "android.support.customtabs.action.CustomTabsService"
            serviceIntent.setPackage(info.activityInfo.packageName)
            
            if (packageManager.resolveService(serviceIntent, 0) != null) {
                packagesSupportingCustomTabs.add(info.activityInfo.packageName)
            }
        }
        
        return when {
            packagesSupportingCustomTabs.isEmpty() -> null
            packagesSupportingCustomTabs.contains(CUSTOM_TAB_PACKAGE_NAME) -> CUSTOM_TAB_PACKAGE_NAME
            else -> packagesSupportingCustomTabs[0]
        }
    }

    private fun handleDeepLinkCallback() {
        Log.i(tag, "Received deep link callback - polling for auth token")
        Toast.makeText(this, "Completing sign in...", Toast.LENGTH_SHORT).show()
        
        // Poll once to get the token since the code should now be verified
        val code = currentCode
        if (code != null) {
            pollForToken(code)
        } else {
            // If we don't have the code in memory, we need to wait for it
            // This shouldn't normally happen, but handle it gracefully
            lifecycleScope.launch {
                // Wait for the code to be available
                authManager.deviceAuthCode.collect { deviceCode ->
                    if (deviceCode != null && currentCode == null) {
                        currentCode = deviceCode
                        pollForToken(deviceCode)
                    }
                }
            }
        }
    }

    private fun pollForToken(code: String) {
        lifecycleScope.launch {
            try {
                Log.i(tag, "Polling for auth token with code: $code")
                
                val (pollResponse, token) = withContext(Dispatchers.IO) {
                    apiClient.pollDeviceAuth(code, hostname)
                }
                
                if (pollResponse.verified && !token.isNullOrEmpty()) {
                    Log.i(tag, "Poll successful - got auth token")
                    handlePollSuccess(token, hostname)
                } else {
                    Log.w(tag, "Poll returned but code not yet verified, will retry...")
                    // Code might not be verified yet, retry after a short delay
                    delay(1000)
                    pollForToken(code)
                }
            } catch (e: Exception) {
                Log.e(tag, "Poll failed: ${e.message}", e)
                Toast.makeText(this@SignInCodeActivity, "Failed to complete sign in: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun handlePollSuccess(token: String, hostname: String) {
        Log.i(tag, "Poll success - handling authentication")
        
        try {
            // Update API client with the token
            apiClient.updateSessionToken(token)
            
            // Run network calls on IO dispatcher
            val user = withContext(Dispatchers.IO) {
                apiClient.getUser()
            }
            
            // Let AuthManager handle the successful auth (this also does network calls)
            withContext(Dispatchers.IO) {
                authManager.handleSuccessfulAuth(user, hostname, token)
            }
            
            // Show success (this will navigate to MainActivity) - must be on main thread
            showSuccess()
        } catch (e: Exception) {
            Log.e(tag, "Failed to complete authentication: ${e.message}", e)
            Toast.makeText(this, "Authentication failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Handle deep link callback from browser
        intent?.data?.let { uri ->
            if (uri.scheme == "pangolin") {
                Log.i(tag, "Received deep link callback via onNewIntent: $uri")
                handleDeepLinkCallback()
            }
        }
    }

    private fun startDeviceAuth() {
        lifecycleScope.launch {
            try {
                Log.i(tag, "Starting device auth flow with hostname: $hostname")
                // This will generate the code and set up initial state
                val deviceName = android.os.Build.MODEL
                val appName = "Pangolin Android"
                
                val startResponse = apiClient.startDeviceAuth(appName, deviceName, hostname)
                expiresInSeconds = startResponse.expiresInSeconds
                
                // Update AuthManager's device auth code state
                authManager.setDeviceAuthCode(startResponse.code)
                
            } catch (e: Exception) {
                Log.e(tag, "Device auth failed: ${e.message}", e)
                Toast.makeText(this@SignInCodeActivity, "Failed to start sign in: ${e.message}", Toast.LENGTH_LONG).show()
                delay(2000)
                finish()
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
        val code = currentCode
        if (code != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Sign In Code", code)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLoginPage() {
        currentCode?.let { autoOpenBrowser(it) }
    }

    private fun autoOpenBrowser(code: String) {
        // Remove hyphen from code (e.g., "XXXX-XXXX" -> "XXXXXXXX")
        val codeWithoutHyphen = code.replace("-", "")
        val autoOpenURL = "$hostname/auth/login/device?code=$codeWithoutHyphen"

        Log.i(tag, "Opening in-app browser with URL: $autoOpenURL")

        try {
            launchCustomTab(Uri.parse(autoOpenURL))
        } catch (e: Exception) {
            Log.e(tag, "Failed to open in-app browser: ${e.message}", e)
            // Fallback to system browser if Custom Tabs fails
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(autoOpenURL))
                startActivity(intent)
            } catch (fallbackError: Exception) {
                Log.e(tag, "Failed to open system browser: ${fallbackError.message}", fallbackError)
                Toast.makeText(this, "Unable to open browser. Please visit:\n$autoOpenURL", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchCustomTab(uri: Uri) {
        // Get theme colors for Custom Tab
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        
        val toolbarColor = if (isDarkMode) {
            ContextCompat.getColor(this, android.R.color.black)
        } else {
            ContextCompat.getColor(this, android.R.color.white)
        }

        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColor)
            .setNavigationBarColor(toolbarColor)
            .build()

        val customTabsIntentBuilder = CustomTabsIntent.Builder(customTabsSession)
            .setDefaultColorSchemeParams(colorSchemeParams)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)

        // Set color scheme based on current theme
        if (isDarkMode) {
            customTabsIntentBuilder.setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
        } else {
            customTabsIntentBuilder.setColorScheme(CustomTabsIntent.COLOR_SCHEME_LIGHT)
        }

        val customTabsIntent = customTabsIntentBuilder.build()

        // These flags enable third-party cookies and ensure proper sign-in flow support
        // Important for OAuth flows like "Sign in with Google"
        customTabsIntent.intent.putExtra(
            "android.support.customtabs.extra.ENABLE_INSTANT_APPS",
            false
        )
        
        // Launch the Custom Tab
        customTabsIntent.launchUrl(this, uri)
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
        
        // Cancel any ongoing device auth polling in AuthManager
        authManager.cancelDeviceAuth()
        
        // Unbind Custom Tabs service
        customTabsConnection?.let {
            try {
                unbindService(it)
            } catch (e: Exception) {
                Log.w(tag, "Error unbinding Custom Tabs service: ${e.message}")
            }
        }
        customTabsConnection = null
        customTabsClient = null
        customTabsSession = null
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