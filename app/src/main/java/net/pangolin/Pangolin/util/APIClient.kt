package net.pangolin.Pangolin.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class APIError : Exception() {
    object InvalidURL : APIError()
    object InvalidResponse : APIError()
    data class HttpError(val status: Int, val body: String?) : APIError()
    data class NetworkError(val originalError: Throwable) : APIError()
    data class DecodingError(val originalError: Throwable) : APIError()

    override val message: String?
        get() = when (this) {
            is InvalidURL -> "Invalid URL"
            is InvalidResponse -> "Invalid response from server"
            is HttpError -> {
                if (!body.isNullOrEmpty()) body else {
                    when (status) {
                        401, 403 -> "Unauthorized"
                        404 -> "Not found"
                        429 -> "Rate limit exceeded"
                        500 -> "Internal server error"
                        else -> "HTTP error $status"
                    }
                }
            }
            is NetworkError -> originalError.localizedMessage
            is DecodingError -> "Failed to decode response: ${originalError.localizedMessage}"
        }
}

class APIClient(
    private var _baseURL: String,
    private var sessionToken: String? = null
) {
    private val tag = "APIClient"
    private val sessionCookieName = "p_session_token"
    private val csrfTokenValue = "x-csrf-protection"
    private val agentName = "pangolin-android"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val baseURL: String
        get() = _baseURL

    init {
        this._baseURL = normalizeBaseURL(_baseURL)
        Log.i(tag, "APIClient initialized with baseURL: ${this._baseURL}")
    }

    fun updateBaseURL(newBaseURL: String) {
        this._baseURL = normalizeBaseURL(newBaseURL)
    }

    fun updateSessionToken(token: String?) {
        this.sessionToken = token
    }

    private fun normalizeBaseURL(url: String): String {
        var normalized = url.trim()
        if (normalized.isEmpty()) {
            return "https://app.pangolin.net"
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.removeSuffix("/")
    }

    private fun apiURL(path: String, hostnameOverride: String? = null): HttpUrl? {
        val fullPath = if (path.startsWith("/")) path else "/$path"
        val apiPath = "/api/v1$fullPath"
        val hostname = hostnameOverride ?: _baseURL
        val normalizedHostname = normalizeBaseURL(hostname)
        val fullURL = normalizedHostname + apiPath
        return fullURL.toHttpUrlOrNull()
    }

    private suspend fun makeRequest(
        method: String,
        path: String,
        body: String? = null,
        hostnameOverride: String? = null
    ): Response = withContext(Dispatchers.IO) {
        val url = apiURL(path, hostnameOverride) ?: throw APIError.InvalidURL

        val requestBuilder = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", agentName)
            .addHeader("X-CSRF-Token", csrfTokenValue)

        sessionToken?.let { token ->
            requestBuilder.addHeader("Cookie", "$sessionCookieName=$token")
        }

        try {
            Log.d(tag, "Making request to: $url")
            val response = client.newCall(requestBuilder.build()).execute()
            response
        } catch (e: IOException) {
            Log.e(tag, "Network error: ${e.message}")
            throw APIError.NetworkError(e)
        }
    }

    private inline fun <reified T> parseResponse(response: Response): T {
        val bodyString = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            var errorMessage: String? = null
            try {
                val errorResponse = json.decodeFromString<APIResponse<EmptyResponse>>(bodyString)
                errorMessage = errorResponse.message
            } catch (e: Exception) {
                // Ignore decoding error for error response
            }
            throw APIError.HttpError(response.code, errorMessage)
        }

        if (bodyString.isEmpty() || bodyString == "{}") {
            if (T::class == EmptyResponse::class) {
                return EmptyResponse() as T
            }
            throw APIError.InvalidResponse
        }

        return try {
            val apiResponse = json.decodeFromString<APIResponse<T>>(bodyString)

            if (apiResponse.success == false || apiResponse.error == true) {
                val message = apiResponse.message ?: "Request failed"
                val status = apiResponse.status ?: response.code
                throw APIError.HttpError(status, message)
            }

            apiResponse.data ?: run {
                if (T::class == EmptyResponse::class) {
                    EmptyResponse() as T
                } else {
                    throw APIError.InvalidResponse
                }
            }
        } catch (e: Exception) {
            if (e is APIError) throw e
            Log.e(tag, "Decoding error: ${e.message}")
            throw APIError.DecodingError(e)
        }
    }

    private fun extractCookie(response: Response, name: String): String? {
        val cookies = response.headers("Set-Cookie")
        for (cookie in cookies) {
            val parts = cookie.split(";")
            for (part in parts) {
                val kv = part.trim().split("=")
                if (kv.size == 2 && kv[0] == name) {
                    return kv[1]
                }
            }
        }
        return null
    }

    // MARK: - Authentication

    suspend fun login(email: String, password: String, code: String?): Pair<LoginResponse, String> {
        val requestBody = json.encodeToString(LoginRequest(email, password, code))
        val response = makeRequest("POST", "/auth/login", requestBody)

        val loginResponse: LoginResponse = parseResponse(response)

        val token = extractCookie(response, sessionCookieName)
            ?: extractCookie(response, "p_session")
            ?: throw APIError.InvalidResponse

        return Pair(loginResponse, token)
    }

    suspend fun startDeviceAuth(applicationName: String, deviceName: String?, hostnameOverride: String? = null): DeviceAuthStartResponse {
        val requestBody = json.encodeToString(DeviceAuthStartRequest(applicationName, deviceName))
        val response = makeRequest("POST", "/auth/device-web-auth/start", requestBody, hostnameOverride)
        return parseResponse(response)
    }

    suspend fun pollDeviceAuth(code: String, hostnameOverride: String? = null): Pair<DeviceAuthPollResponse, String?> {
        val response = makeRequest("GET", "/auth/device-web-auth/poll/$code", hostnameOverride = hostnameOverride)
        val pollResponse: DeviceAuthPollResponse = parseResponse(response)

        val token = if (pollResponse.verified && pollResponse.token != null) {
            pollResponse.token
        } else {
            extractCookie(response, sessionCookieName) ?: extractCookie(response, "p_session")
        }

        return Pair(pollResponse, token)
    }

    suspend fun logout() {
        val response = makeRequest("POST", "/auth/logout", "")
        parseResponse<EmptyResponse>(response)
    }

    // MARK: - User

    suspend fun getUser(): User {
        val response = makeRequest("GET", "/user")
        return parseResponse(response)
    }

    suspend fun listUserOrgs(userId: String): ListUserOrgsResponse {
        val response = makeRequest("GET", "/user/$userId/orgs")
        return parseResponse(response)
    }

    suspend fun createOlm(userId: String, name: String): CreateOlmResponse {
        val requestBody = json.encodeToString(CreateOlmRequest(name))
        val response = makeRequest("PUT", "/user/$userId/olm", requestBody)
        return parseResponse(response)
    }

    suspend fun getUserOlm(userId: String, olmId: String): Olm {
        val response = makeRequest("GET", "/user/$userId/olm/$olmId")
        return parseResponse(response)
    }

    // MARK: - Organization

    suspend fun getOrg(orgId: String): GetOrgResponse {
        val response = makeRequest("GET", "/org/$orgId")
        return parseResponse(response)
    }

    suspend fun checkOrgUserAccess(orgId: String, userId: String): CheckOrgUserAccessResponse {
        val response = makeRequest("GET", "/org/$orgId/user/$userId/check")
        return parseResponse(response)
    }

    // MARK: - Client

    suspend fun getClient(clientId: Int): GetClientResponse {
        val response = makeRequest("GET", "/client/$clientId")
        return parseResponse(response)
    }

    // MARK: - Connection Test

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val url = _baseURL.toHttpUrlOrNull() ?: return@withContext false
        val request = Request.Builder()
            .url(url)
            .method("HEAD", null)
            .header("User-Agent", agentName)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 404
            }
        } catch (e: Exception) {
            false
        }
    }
}
