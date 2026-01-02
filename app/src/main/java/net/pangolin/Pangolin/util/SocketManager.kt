package net.pangolin.Pangolin.util

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

sealed class SocketError : Exception() {
    class SocketDoesNotExist : SocketError()
    data class ConnectionFailed(val originalError: Throwable) : SocketError()
    class InvalidResponse : SocketError()
    data class HttpError(val status: Int, val body: String?) : SocketError()
    data class DecodingError(val originalError: Throwable) : SocketError()

    override val message: String?
        get() = when (this) {
            is SocketDoesNotExist -> "Socket does not exist (is the tunnel running?)"
            is ConnectionFailed -> "Failed to connect to socket: ${originalError.message}"
            is InvalidResponse -> "Invalid response from server"
            is HttpError -> body ?: "HTTP error $status"
            is DecodingError -> "Failed to decode response: ${originalError.message}"
        }
}

class SocketManager(
    private val socketPath: String,
    private val timeoutMs: Int = 5000
) {
    private val tag = "SocketManager"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun isRunning(): Boolean = withContext(Dispatchers.IO) {
        if (!File(socketPath).exists()) return@withContext false
        try {
            getStatus()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getStatus(): SocketStatusResponse = performRequest("GET", "/status")

    suspend fun exit(): SocketExitResponse = performRequest("POST", "/exit")

    suspend fun switchOrg(orgId: String): SocketSwitchOrgResponse {
        val body = json.encodeToString(SocketSwitchOrgRequest(orgId))
        return performRequest("POST", "/switch-org", body)
    }

    private suspend inline fun <reified T> performRequest(
        method: String,
        path: String,
        body: String? = null
    ): T = withContext(Dispatchers.IO) {
        if (!File(socketPath).exists()) {
            throw SocketError.SocketDoesNotExist()
        }

        val requestBuilder = StringBuilder()
        requestBuilder.append("$method $path HTTP/1.1\r\n")
        requestBuilder.append("Host: localhost\r\n")
        requestBuilder.append("Connection: close\r\n")

        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
        if (bodyBytes != null) {
            requestBuilder.append("Content-Type: application/json\r\n")
            requestBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        requestBuilder.append("\r\n")

        val headerBytes = requestBuilder.toString().toByteArray(StandardCharsets.UTF_8)
        val fullRequest = if (bodyBytes != null) {
            headerBytes + bodyBytes
        } else {
            headerBytes
        }

        val responseData = connectAndSend(fullRequest)
        val (statusCode, responseBody) = parseHTTPResponse(responseData)

        if (statusCode != 200) {
            throw SocketError.HttpError(statusCode, responseBody)
        }

        try {
            json.decodeFromString<T>(responseBody)
        } catch (e: Exception) {
            Log.e(tag, "JSON decode error: ${e.message}")
            Log.e(tag, "Failed to decode body: $responseBody")
            throw SocketError.DecodingError(e)
        }
    }

    private fun connectAndSend(requestData: ByteArray): ByteArray {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = timeoutMs

            socket.outputStream.write(requestData)
            socket.outputStream.flush()
            socket.shutdownOutput()

            val inputStream = socket.inputStream
            val buffer = ByteArray(4096)
            val outputStream = ByteArrayOutputStream()
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            return outputStream.toByteArray()
        } catch (e: Exception) {
            throw SocketError.ConnectionFailed(e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun parseHTTPResponse(data: ByteArray): Pair<Int, String> {
        val responseString = String(data, StandardCharsets.UTF_8)
        val delimiter = if (responseString.contains("\r\n\r\n")) "\r\n\r\n" else "\n\n"
        val parts = responseString.split(delimiter, limit = 2)

        if (parts.size < 2) {
            throw SocketError.InvalidResponse()
        }

        val headers = parts[0]
        val body = parts[1]

        val statusLine = headers.lines().firstOrNull() ?: throw SocketError.InvalidResponse()
        val statusParts = statusLine.split(" ")
        if (statusParts.size < 2) {
            throw SocketError.InvalidResponse()
        }

        val statusCode = statusParts[1].toIntOrNull() ?: throw SocketError.InvalidResponse()
        return Pair(statusCode, body)
    }
}
