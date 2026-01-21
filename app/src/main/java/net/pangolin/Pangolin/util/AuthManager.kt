package net.pangolin.Pangolin.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthError : Exception() {
    object Unauthenticated : AuthError()
    object NoOrganizationSelected : AuthError()
    object DeviceAuthTimeout : AuthError()
    object DeviceAuthCancelled : AuthError()
    data class NetworkError(val originalError: Throwable) : AuthError()
    data class APIError(val originalError: Throwable) : AuthError()

    override val message: String?
        get() = when (this) {
            is Unauthenticated -> "Not authenticated"
            is NoOrganizationSelected -> "No organization selected"
            is DeviceAuthTimeout -> "Device authentication timed out"
            is DeviceAuthCancelled -> "Device authentication was cancelled"
            is NetworkError -> "Network error: ${originalError.message}"
            is APIError -> "API error: ${originalError.message}"
        }
}



class AuthManager(
    private val context: Context,
    val apiClient: APIClient,
    val configManager: ConfigManager,
    val accountManager: AccountManager,
    val secretManager: SecretManager,
    var tunnelManager: TunnelManager? = null,
) {
    private val tag = "AuthManager"

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _currentOrg = MutableStateFlow<Organization?>(null)
    val currentOrg: StateFlow<Organization?> = _currentOrg.asStateFlow()

    private val _organizations = MutableStateFlow<List<Organization>>(emptyList())
    val organizations: StateFlow<List<Organization>> = _organizations.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _deviceAuthCode = MutableStateFlow<String?>(null)
    val deviceAuthCode: StateFlow<String?> = _deviceAuthCode.asStateFlow()

    private val _deviceAuthLoginURL = MutableStateFlow<String?>(null)
    val deviceAuthLoginURL: StateFlow<String?> = _deviceAuthLoginURL.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _isServerDown = MutableStateFlow(false)
    val isServerDown: StateFlow<Boolean> = _isServerDown.asStateFlow()

    private var deviceAuthJob: Job? = null

    suspend fun initialize() {
        _isInitializing.value = true

        try {
            val activeAccount = accountManager.activeAccount

            if (activeAccount == null) {
                Log.i(tag, "No active account found")
                _isAuthenticated.value = false
                return
            }

            val token = secretManager.getSecret("session-token-${activeAccount.userId}")

            if (token == null) {
                Log.w(tag, "No session token found for user ${activeAccount.userId}")
                _isAuthenticated.value = false
                return
            }

            apiClient.updateBaseURL(activeAccount.hostname)
            apiClient.updateSessionToken(token)

            // Health check before fetching data
            val isHealthy = apiClient.checkHealth()
            if (!isHealthy) {
                Log.w(tag, "Server appears to be down")
                _isServerDown.value = true
                _errorMessage.value = "The server appears to be down."
                _isAuthenticated.value = true
                return
            }

            _isServerDown.value = false
            _errorMessage.value = null

            val user = apiClient.getUser()
            _currentUser.value = user
            _isAuthenticated.value = true

            // Fetch server info
            try {
                val serverInfo = apiClient.getServerInfo()
                _serverInfo.value = serverInfo
            } catch (e: Exception) {
                Log.w(tag, "Failed to fetch server info: ${e.message}")
            }

            // Update account with username and name
            accountManager.updateAccountUserInfo(user.userId, user.username, user.name)

            refreshOrganizations()

            val orgId = ensureOrgIsSelected()
            val orgResponse = apiClient.getOrg(orgId)
            _currentOrg.value = Organization(
                orgId = orgResponse.org.orgId,
                name = orgResponse.org.name
            )

            // Clear error message on successful initialization
            _errorMessage.value = null

            Log.i(tag, "Successfully initialized with user: ${user.email}")
        } catch (e: Exception) {
            Log.e(tag, "Initialization failed: ${e.message}", e)
            _isAuthenticated.value = false
            _errorMessage.value = e.message
        } finally {
            _isInitializing.value = false
        }
    }

    suspend fun loginWithDeviceAuth(hostnameOverride: String? = null) {
        try {
            _deviceAuthCode.value = null
            _deviceAuthLoginURL.value = null
            _errorMessage.value = null

            val hostname = hostnameOverride ?: apiClient.baseURL
            apiClient.updateBaseURL(hostname)

            Log.i(tag, "Starting device authentication with hostname: $hostname")

            val deviceName = android.os.Build.MODEL
            val appName = "Pangolin Android"

            val startResponse = apiClient.startDeviceAuth(appName, deviceName, hostnameOverride)

            val code = startResponse.code
            val loginURL = "$hostname/auth/device-web-auth/$code"

            _deviceAuthCode.value = code
            _deviceAuthLoginURL.value = loginURL

            Log.i(tag, "Device auth started. Code: $code, URL: $loginURL")

            deviceAuthJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val startTime = System.currentTimeMillis()
                    val expirationTime = startTime + (startResponse.expiresInSeconds * 1000)

                    while (isActive && System.currentTimeMillis() < expirationTime) {
                        try {
                            delay(2000)

                            val (pollResponse, token) = apiClient.pollDeviceAuth(code, hostnameOverride)

                            if (pollResponse.verified) {
                                Log.i(tag, "Device auth verified!")

                                if (token.isNullOrEmpty()) {
                                    throw AuthError.APIError(
                                        Exception("Device auth verified but no token received")
                                    )
                                }

                                apiClient.updateSessionToken(token)

                                val user = apiClient.getUser()

                                withContext(Dispatchers.Main) {
                                    handleSuccessfulAuth(user, hostname, token)
                                }

                                _deviceAuthCode.value = null
                                _deviceAuthLoginURL.value = null
                                return@launch
                            }
                        } catch (e: APIError.HttpError) {
                            if (e.status == 404) {
                                Log.d(tag, "Device auth not yet verified, continuing to poll...")
                                continue
                            } else {
                                Log.e(tag, "HTTP error during device auth polling: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    _errorMessage.value = e.message
                                    _deviceAuthCode.value = null
                                    _deviceAuthLoginURL.value = null
                                }
                                return@launch
                            }
                        } catch (e: APIError.NetworkError) {
                            Log.w(tag, "Network error during device auth polling, continuing: ${e.message}")
                            // Continue polling on network errors
                            continue
                        } catch (e: APIError) {
                            Log.e(tag, "API error during device auth polling: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                _errorMessage.value = e.message
                                _deviceAuthCode.value = null
                                _deviceAuthLoginURL.value = null
                            }
                            return@launch
                        } catch (e: CancellationException) {
                            Log.i(tag, "Device auth cancelled")
                            throw e
                        } catch (e: Exception) {
                            Log.e(tag, "Unexpected error during device auth polling: ${e.message}", e)
                            // Continue polling on unexpected errors
                            continue
                        }
                    }

                    if (System.currentTimeMillis() >= expirationTime) {
                        Log.w(tag, "Device auth timed out")
                        withContext(Dispatchers.Main) {
                            _errorMessage.value = "Device authentication timed out"
                            _deviceAuthCode.value = null
                            _deviceAuthLoginURL.value = null
                        }
                    }
                } catch (e: CancellationException) {
                    Log.i(tag, "Device auth job cancelled")
                    // Don't rethrow, just let the job end
                } catch (e: Exception) {
                    Log.e(tag, "Fatal error in device auth job: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Authentication failed: ${e.message}"
                        _deviceAuthCode.value = null
                        _deviceAuthLoginURL.value = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Device auth failed: ${e.message}", e)
            _errorMessage.value = e.message
            _deviceAuthCode.value = null
            _deviceAuthLoginURL.value = null
            throw e
        }
    }

    fun cancelDeviceAuth() {
        deviceAuthJob?.cancel()
        deviceAuthJob = null
        _deviceAuthCode.value = null
        _deviceAuthLoginURL.value = null
        Log.i(tag, "Device auth cancelled")
    }

    /**
     * Set the device auth code externally (used by DeviceAuthService)
     */
    fun setDeviceAuthCode(code: String?) {
        _deviceAuthCode.value = code
        if (code != null) {
            _deviceAuthLoginURL.value = "${apiClient.baseURL}/auth/device-web-auth/$code"
        } else {
            _deviceAuthLoginURL.value = null
        }
    }

    /**
     * Handle successful authentication from external source (used by DeviceAuthService)
     */
    suspend fun handleSuccessfulAuth(user: User, hostname: String, token: String) {
        _currentUser.value = user

        secretManager.saveSecret("session-token-${user.userId}", token)

        ensureOlmCredentials(user.userId)

        // Fetch server info
        try {
            val serverInfo = apiClient.getServerInfo()
            _serverInfo.value = serverInfo
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch server info: ${e.message}")
        }

        refreshOrganizations()

        val orgId = ensureOrgIsSelected()

        val account = Account(
            userId = user.userId,
            hostname = hostname,
            email = user.email,
            orgId = orgId,
            username = user.username,
            name = user.name
        )

        accountManager.addAccount(account, makeActive = true)

        val orgResponse = apiClient.getOrg(orgId)
        _currentOrg.value = Organization(
            orgId = orgResponse.org.orgId,
            name = orgResponse.org.name
        )

        // Clear error message on successful authentication
        _errorMessage.value = null

        Log.i(tag, "Successfully authenticated as ${user.email}")
        
        // Set authenticated flag last, after account is saved to disk
        _isAuthenticated.value = true
    }

    fun updateServerStatus(isHealthy: Boolean) {
        if (!isHealthy) {
            _isServerDown.value = true
            if (_errorMessage.value == null) {
                _errorMessage.value = "The server appears to be down."
            }
        } else {
            _isServerDown.value = false
            // Clear server-down error message when server becomes healthy
            if (_errorMessage.value == "The server appears to be down.") {
                _errorMessage.value = null
            }
        }
    }

    // MARK: - API Client Sync
    /**
     * Synchronize the APIClient with the current active account's token and hostname.
     * This should be called when the activity resumes to ensure the APIClient is
     * properly configured, especially after returning from another activity that
     * may have added or switched accounts.
     */
    fun syncApiClientForActiveAccount() {
        val activeAccount = accountManager.activeAccount
        if (activeAccount == null) {
            Log.d(tag, "syncApiClientForActiveAccount: No active account")
            return
        }

        val token = secretManager.getSecret("session-token-${activeAccount.userId}")
        if (token != null) {
            apiClient.updateBaseURL(activeAccount.hostname)
            apiClient.updateSessionToken(token)
            Log.d(tag, "syncApiClientForActiveAccount: Updated APIClient for user ${activeAccount.userId}")
        } else {
            Log.w(tag, "syncApiClientForActiveAccount: No token found for user ${activeAccount.userId}")
        }
    }

    private suspend fun ensureOrgIsSelected(): String {
        val user = _currentUser.value ?: throw AuthError.Unauthenticated

        val activeAccount = accountManager.activeAccount
        if (activeAccount != null && activeAccount.userId == user.userId && activeAccount.orgId.isNotEmpty()) {
            return activeAccount.orgId
        }

        val orgsResponse = apiClient.listUserOrgs(user.userId)

        if (orgsResponse.orgs.isEmpty()) {
            throw AuthError.NoOrganizationSelected
        }

        val firstOrg = orgsResponse.orgs[0]
        accountManager.setUserOrganization(user.userId, firstOrg.orgId)

        return firstOrg.orgId
    }

    suspend fun refreshOrganizations() {
        try {
            val user = _currentUser.value ?: return

            val response = apiClient.listUserOrgs(user.userId)
            _organizations.value = response.orgs

            val activeAccount = accountManager.activeAccount
            if (activeAccount != null) {
                val currentOrgId = activeAccount.orgId
                val currentOrgStillExists = response.orgs.any { it.orgId == currentOrgId }

                if (!currentOrgStillExists && response.orgs.isNotEmpty()) {
                    Log.w(tag, "Current org $currentOrgId no longer exists, switching to first available")
                    selectOrganization(response.orgs[0])
                }
            }

            Log.i(tag, "Refreshed organizations: ${response.orgs.size} found")
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh organizations: ${e.message}", e)
            _errorMessage.value = "Failed to refresh organizations: ${e.message}"
        }
    }

    suspend fun switchAccount(userId: String) {
        try {
            val account = accountManager.accounts[userId]
            if (account == null) {
                Log.e(tag, "Account $userId not found")
                return
            }

            val token = secretManager.getSecret("session-token-$userId")
            if (token == null) {
                Log.e(tag, "No session token for user $userId")
                accountManager.removeAccount(userId)
                return
            }

            // Step 1: Switch account locally first
            accountManager.setActiveUser(userId)
            apiClient.updateSessionToken(token)
            apiClient.updateBaseURL(account.hostname)

            // Step 2: Clear user data immediately
            _currentUser.value = null
            _currentOrg.value = null
            _organizations.value = emptyList()
            _serverInfo.value = null

            // Step 3: Set authenticated to show UI
            _isAuthenticated.value = true

            // Step 4: Reset server status
            _isServerDown.value = false
            _errorMessage.value = null

            // Step 5: Validate with server
            // Health check before fetching data
            val isHealthy = apiClient.checkHealth()
            if (!isHealthy) {
                Log.w(tag, "Server appears to be down")
                _isServerDown.value = true
                _errorMessage.value = "The server appears to be down."
                return
            }

            val user = try {
                apiClient.getUser()
            } catch (e: Exception) {
                Log.e(tag, "Failed to get user info: ${e.message}", e)
                _errorMessage.value = "Failed to get user info: ${e.message}"
                return
            }

            _currentUser.value = user

            // Fetch server info
            try {
                val serverInfo = apiClient.getServerInfo()
                _serverInfo.value = serverInfo
            } catch (e: Exception) {
                Log.w(tag, "Failed to fetch server info: ${e.message}")
            }

            // Update account with username and name
            accountManager.updateAccountUserInfo(user.userId, user.username, user.name)

            refreshOrganizations()

            if (account.orgId.isNotEmpty()) {
                val hasAccess = checkOrgAccess(account.orgId)
                if (hasAccess) {
                    val orgResponse = apiClient.getOrg(account.orgId)
                    _currentOrg.value = Organization(
                        orgId = orgResponse.org.orgId,
                        name = orgResponse.org.name
                    )

                } else {
                    val orgId = ensureOrgIsSelected()
                    val orgResponse = apiClient.getOrg(orgId)
                    _currentOrg.value = Organization(
                        orgId = orgResponse.org.orgId,
                        name = orgResponse.org.name
                    )

                }
            } else {
                val orgId = ensureOrgIsSelected()
                val orgResponse = apiClient.getOrg(orgId)
                _currentOrg.value = Organization(
                    orgId = orgResponse.org.orgId,
                    name = orgResponse.org.name
                )

            }

            // Clear error message on successful completion
            _errorMessage.value = null

            Log.i(tag, "Switched to account: ${user.email}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to switch account: ${e.message}", e)
            _errorMessage.value = "Failed to switch account: ${e.message}"
            throw e
        }
    }

    suspend fun selectOrganization(organization: Organization) {
        try {
            val user = _currentUser.value ?: throw AuthError.Unauthenticated

            val hasAccess = checkOrgAccess(organization.orgId)
            if (!hasAccess) {
                throw AuthError.APIError(Exception("You do not have access to this organization"))
            }

            Log.i(tag, "=== ORG SWITCH: Switching user ${user.userId} to org ${organization.orgId} (${organization.name}) ===")
            accountManager.setUserOrganization(user.userId, organization.orgId)
            _currentOrg.value = organization
            
            // Verify the switch was successful
            val updatedAccount = accountManager.activeAccount
            Log.i(tag, "=== ORG SWITCH COMPLETE: Active account now has orgId=${updatedAccount?.orgId} ===")

            Log.i(tag, "Selected organization: ${organization.name}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to select organization: ${e.message}", e)
            _errorMessage.value = e.message
            throw e
        }
    }

    private suspend fun checkOrgAccess(orgId: String): Boolean {
        return try {
            val user = _currentUser.value ?: return false
            val response = apiClient.checkOrgUserAccess(orgId, user.userId)
            response.allowed
        } catch (e: Exception) {
            Log.e(tag, "Failed to check org access: ${e.message}", e)
            false
        }
    }

    suspend fun ensureOlmCredentials(userId: String) {
        // Ensure APIClient has the correct token for this user
        // This is critical when called from a different context (e.g., TunnelManager)
        // where the APIClient may not have been updated with the current user's token
        val token = secretManager.getSecret("session-token-$userId")
        if (token != null) {
            val account = accountManager.accounts[userId]
            if (account != null) {
                apiClient.updateBaseURL(account.hostname)
            }
            apiClient.updateSessionToken(token)
            Log.d(tag, "ensureOlmCredentials: Updated APIClient token for user $userId")
        } else {
            Log.w(tag, "ensureOlmCredentials: No token found for user $userId")
        }

        // Check if OLM credentials already exist locally
        if (secretManager.hasOlmCredentials(userId)) {
            // Verify OLM exists on server by getting the OLM directly
            val olmIdString = secretManager.getOlmId(userId)
            if (olmIdString != null) {
                try {
                    val olm = apiClient.getUserOlm(userId, olmIdString)

                    // Verify the olmId and userId match
                    if (olm.olmId == olmIdString && olm.userId == userId) {
                        Log.d(tag, "OLM credentials verified successfully")
                        return
                    } else {
                        Log.e(tag, "OLM mismatch - returned olmId: ${olm.olmId}, userId: ${olm.userId}, stored olmId: $olmIdString")
                        // Clear invalid credentials
                        secretManager.deleteOlmCredentials(userId)
                    }
                } catch (e: Exception) {
                    // If getting OLM fails, the OLM might not exist
                    Log.e(tag, "Failed to verify OLM credentials: ${e.message}", e)
                    // Clear invalid credentials so we can try to create new ones
                    secretManager.deleteOlmCredentials(userId)
                }
            } else {
                // No olmId found, clear credentials
                Log.e(tag, "Cannot verify OLM - olmId not found")
                secretManager.deleteOlmCredentials(userId)
            }
        }

        // First, try to recover the OLM with the calculated platform fingerprint
        // hash, to prevent a duplicate device from being created in the OLM and
        // client lists.
        try {
            val c = AndroidFingerprintCollector(context.applicationContext)
            val platformFingerprint = c.computePlatformFingerprint()

            val recoveredOlm = apiClient.recoverOlmWithFingerprint(userId, platformFingerprint)
            Log.i(tag, "Recovered OLM credentials using platform fingerprint")
            secretManager.saveOlmCredentials(userId, recoveredOlm.olmId, recoveredOlm.secret)
            return
        } catch (_: Exception) {
            Log.i(tag, "ensureOlmCredentials: credentials not recovered, creating new ones")
        }

        // If credentials don't exist or were cleared, create new ones
        if (!secretManager.hasOlmCredentials(userId)) {
            try {
                // Use the actual device name (user's device model) for OLM
                val deviceName = android.os.Build.MODEL
                val olmResponse = apiClient.createOlm(userId, deviceName)
                
                // Save OLM credentials
                val saved = secretManager.saveOlmCredentials(userId, olmResponse.olmId, olmResponse.secret)
                
                if (!saved) {
                    Log.e(tag, "Failed to save OLM credentials")
                    // TODO: Show error dialog to user
                } else {
                    Log.i(tag, "Created new OLM credentials for user $userId")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to create OLM credentials: ${e.message}", e)
                // TODO: Show error dialog to user
            }
        }
    }

    suspend fun logout(): Boolean {
        // Use activeAccount from AccountManager instead of _currentUser
        // because _currentUser can be null when server is down
        val activeAccount = accountManager.activeAccount
        Log.i(tag, "=== LOGOUT STARTED ===")
        Log.i(tag, "Active account being logged out: ${activeAccount?.userId} (${activeAccount?.email})")
        Log.i(tag, "All accounts before logout: ${accountManager.accounts.keys}")
        
        if (activeAccount != null) {
            // Try to logout from server, but don't fail if it doesn't work
            try {
                apiClient.logout()
                Log.i(tag, "Successfully logged out from server")
            } catch (e: Exception) {
                Log.w(tag, "Failed to logout from server (server may be down): ${e.message}")
                // Continue with local cleanup even if server logout fails
            }
            
            // Always clean up local state, even if server logout failed
            secretManager.deleteSecret("session-token-${activeAccount.userId}")
            Log.i(tag, "Deleted session token for: ${activeAccount.userId}")
            
            accountManager.removeAccount(activeAccount.userId)
            Log.i(tag, "Removed account from AccountManager: ${activeAccount.userId}")
        }
        
        // Pick the next available logged-in account
        val remainingAccounts = accountManager.accounts
        Log.i(tag, "Remaining accounts after removal: ${remainingAccounts.keys}")
        
        if (remainingAccounts.isNotEmpty()) {
            val nextAccount = remainingAccounts.values.first()
            Log.i(tag, "Switching to next available account: ${nextAccount.userId} (${nextAccount.email})")
            try {
                switchAccount(nextAccount.userId)
                Log.i(tag, "=== LOGOUT COMPLETE - Switched to next account ===")
                return true
            } catch (e: Exception) {
                Log.e(tag, "Failed to switch to next account: ${e.message}", e)
                // Clear everything if we can't switch to the next account
                _currentUser.value = null
                _isAuthenticated.value = false
                _currentOrg.value = null
                _organizations.value = emptyList()
                apiClient.updateSessionToken(null)
                Log.i(tag, "=== LOGOUT COMPLETE - Failed to switch, cleared all state ===")
                return false
            }
        } else {
            // No more accounts available, clear everything
            _currentUser.value = null
            _isAuthenticated.value = false
            _currentOrg.value = null
            _organizations.value = emptyList()
            apiClient.updateSessionToken(null)
            Log.i(tag, "=== LOGOUT COMPLETE - No more accounts available ===")
            return false
        }
    }
}
