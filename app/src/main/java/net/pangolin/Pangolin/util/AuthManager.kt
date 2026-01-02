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

interface TunnelManager {
    suspend fun switchOrganization(orgId: String)
}

class AuthManager(
    private val context: Context,
    val apiClient: APIClient,
    val configManager: ConfigManager,
    val accountManager: AccountManager,
    val secretManager: SecretManager,
    var tunnelManager: TunnelManager? = null
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

            val user = apiClient.getUser()
            _currentUser.value = user
            _isAuthenticated.value = true

            refreshOrganizations()

            val orgId = ensureOrgIsSelected()
            val orgResponse = apiClient.getOrg(orgId)
            _currentOrg.value = Organization(
                orgId = orgResponse.org.orgId,
                name = orgResponse.org.name
            )

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
                            }
                            throw AuthError.APIError(e)
                        }
                    } catch (e: CancellationException) {
                        Log.i(tag, "Device auth cancelled")
                        throw AuthError.DeviceAuthCancelled
                    } catch (e: Exception) {
                        Log.e(tag, "Error during device auth polling: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            _errorMessage.value = e.message
                        }
                        throw e
                    }
                }

                if (System.currentTimeMillis() >= expirationTime) {
                    Log.w(tag, "Device auth timed out")
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Device authentication timed out"
                        _deviceAuthCode.value = null
                        _deviceAuthLoginURL.value = null
                    }
                    throw AuthError.DeviceAuthTimeout
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

    private suspend fun handleSuccessfulAuth(user: User, hostname: String, token: String) {
        _currentUser.value = user
        _isAuthenticated.value = true

        secretManager.saveSecret("session-token-${user.userId}", token)

        ensureOlmCredentials(user.userId)

        refreshOrganizations()

        val orgId = ensureOrgIsSelected()

        val account = Account(
            userId = user.userId,
            hostname = hostname,
            email = user.email,
            orgId = orgId
        )

        accountManager.addAccount(account, makeActive = true)

        val orgResponse = apiClient.getOrg(orgId)
        _currentOrg.value = Organization(
            orgId = orgResponse.org.orgId,
            name = orgResponse.org.name
        )

        Log.i(tag, "Successfully authenticated as ${user.email}")
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

            apiClient.updateBaseURL(account.hostname)
            apiClient.updateSessionToken(token)

            val user = try {
                apiClient.getUser()
            } catch (e: Exception) {
                Log.e(tag, "Failed to get user info: ${e.message}", e)
                accountManager.removeAccount(userId)
                secretManager.deleteSecret("session-token-$userId")
                throw e
            }

            _currentUser.value = user
            _isAuthenticated.value = true

            accountManager.setActiveUser(userId)

            refreshOrganizations()

            if (account.orgId.isNotEmpty()) {
                val hasAccess = checkOrgAccess(account.orgId)
                if (hasAccess) {
                    val orgResponse = apiClient.getOrg(account.orgId)
                    _currentOrg.value = Organization(
                        orgId = orgResponse.org.orgId,
                        name = orgResponse.org.name
                    )
                    tunnelManager?.switchOrganization(account.orgId)
                } else {
                    val orgId = ensureOrgIsSelected()
                    val orgResponse = apiClient.getOrg(orgId)
                    _currentOrg.value = Organization(
                        orgId = orgResponse.org.orgId,
                        name = orgResponse.org.name
                    )
                    tunnelManager?.switchOrganization(orgId)
                }
            } else {
                val orgId = ensureOrgIsSelected()
                val orgResponse = apiClient.getOrg(orgId)
                _currentOrg.value = Organization(
                    orgId = orgResponse.org.orgId,
                    name = orgResponse.org.name
                )
                tunnelManager?.switchOrganization(orgId)
            }

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

            accountManager.setUserOrganization(user.userId, organization.orgId)
            _currentOrg.value = organization

            tunnelManager?.switchOrganization(organization.orgId)

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

    private suspend fun ensureOlmCredentials(userId: String) {
        if (!secretManager.hasOlmCredentials(userId)) {
            try {
                val deviceName = android.os.Build.MODEL
                val response = apiClient.createOlm(userId, deviceName)
                secretManager.saveOlmCredentials(userId, response.olmId, response.secret)
                Log.i(tag, "Created new OLM credentials for user $userId")
            } catch (e: Exception) {
                Log.e(tag, "Failed to create OLM credentials: ${e.message}", e)
            }
        }
    }

    suspend fun logout() {
        try {
            val user = _currentUser.value
            if (user != null) {
                apiClient.logout()
                secretManager.deleteSecret("session-token-${user.userId}")
                accountManager.removeAccount(user.userId)
            }
        } catch (e: Exception) {
            Log.e(tag, "Logout failed: ${e.message}", e)
        } finally {
            _currentUser.value = null
            _isAuthenticated.value = false
            _currentOrg.value = null
            _organizations.value = emptyList()
            apiClient.updateSessionToken(null)
            Log.i(tag, "Logged out successfully")
        }
    }
}
