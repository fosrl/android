package net.pangolin.Pangolin.util

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// MARK: - Configuration

@Serializable
data class Config(
    val dnsOverrideEnabled: Boolean? = null,
    val dnsTunnelEnabled: Boolean? = null,
    val primaryDNSServer: String? = null,
    val secondaryDNSServer: String? = null,
    val logCollectionEnabled: Boolean? = null
)

// MARK: - Account Types

@Serializable
data class Account(
    val userId: String,
    val hostname: String,
    val email: String,
    var orgId: String,
    var username: String? = null,
    var name: String? = null
)

@Serializable
data class AccountStore(
    var activeUserId: String = "",
    var accounts: Map<String, Account> = emptyMap()
)

// MARK: - API Response Types

@Serializable
data class APIResponse<T>(
    val data: T? = null,
    val success: Boolean? = null,
    val error: Boolean? = null,
    val message: String? = null,
    val status: Int? = null,
    val stack: String? = null
)

@Serializable
class EmptyResponse

// MARK: - Authentication

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val code: String? = null
)

@Serializable
data class LoginResponse(
    val codeRequested: Boolean? = null,
    val emailVerificationRequired: Boolean? = null,
    val useSecurityKey: Boolean? = null,
    val twoFactorSetupRequired: Boolean? = null
)

@Serializable
data class DeviceAuthStartRequest(
    val applicationName: String,
    val deviceName: String? = null
)

@Serializable
data class DeviceAuthStartResponse(
    val code: String,
    val expiresInSeconds: Long
)

@Serializable
data class DeviceAuthPollResponse(
    val verified: Boolean,
    val message: String? = null,
    val token: String? = null
)

// MARK: - Server Info

@Serializable
data class ServerInfo(
    val version: String,
    val supporterStatusValid: Boolean,
    val build: String, // "oss" | "enterprise" | "saas"
    val enterpriseLicenseValid: Boolean,
    val enterpriseLicenseType: String? = null
)

// MARK: - User

@Serializable
data class User(
    val userId: String,
    val email: String,
    val username: String? = null,
    val name: String? = null,
    val type: String? = null,
    val twoFactorEnabled: Boolean? = null,
    val emailVerified: Boolean? = null,
    val serverAdmin: Boolean? = null,
    val idpName: String? = null,
    val idpId: Int? = null
)

// MARK: - Organizations

@Serializable
data class Organization(
    val orgId: String,
    val name: String,
    val isOwner: Boolean? = null
)

@Serializable
data class Org(
    val orgId: String,
    val name: String
)

@Serializable
data class GetOrgResponse(
    val org: Org
)

@Serializable
data class ListUserOrgsResponse(
    val orgs: List<Organization>,
    val pagination: Pagination? = null
)

// MARK: - Organization Access Policy

@Serializable
data class MaxSessionLengthPolicy(
    val compliant: Boolean,
    val maxSessionLengthHours: Int,
    val sessionAgeHours: Double
)

@Serializable
data class PasswordAgePolicy(
    val compliant: Boolean,
    val maxPasswordAgeDays: Int,
    val passwordAgeDays: Double
)

@Serializable
data class OrgAccessPolicies(
    val requiredTwoFactor: Boolean? = null,
    val maxSessionLength: MaxSessionLengthPolicy? = null,
    val passwordAge: PasswordAgePolicy? = null
)

@Serializable
data class CheckOrgUserAccessResponse(
    val allowed: Boolean,
    val error: String? = null,
    val policies: OrgAccessPolicies? = null
)

// MARK: - Client

@Serializable
data class GetClientResponse(
    val siteIds: List<Int>,
    val clientId: Int,
    val orgId: String,
    val exitNodeId: Int? = null,
    val userId: String? = null,
    val name: String,
    val pubKey: String? = null,
    val olmId: String? = null,
    val subnet: String,
    val megabytesIn: Int? = null,
    val megabytesOut: Int? = null,
    val lastBandwidthUpdate: String? = null,
    val lastPing: Int? = null,
    val type: String,
    val online: Boolean,
    val lastHolePunch: Int? = null
)

@Serializable
data class Pagination(
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null
)

// MARK: - OLM

@Serializable
data class Olm(
    val olmId: String,
    val userId: String,
    val name: String? = null,
    val secret: String? = null
)

@Serializable
data class CreateOlmRequest(
    val name: String
)

@Serializable
data class CreateOlmResponse(
    val olmId: String,
    val secret: String
)

// MARK: - Tunnel Status

@Serializable
enum class TunnelStatus {
    @SerialName("Disconnected") DISCONNECTED,
    @SerialName("Connecting...") CONNECTING,
    @SerialName("Registering...") REGISTERING,
    @SerialName("Connected") CONNECTED,
    @SerialName("Reconnecting...") RECONNECTING,
    @SerialName("Disconnecting...") DISCONNECTING,
    @SerialName("Invalid") INVALID,
    @SerialName("Error") ERROR;

    val displayText: String
        get() = when (this) {
            DISCONNECTED -> "Disconnected"
            CONNECTING -> "Connecting..."
            REGISTERING -> "Registering..."
            CONNECTED -> "Connected"
            RECONNECTING -> "Reconnecting..."
            DISCONNECTING -> "Disconnecting..."
            INVALID -> "Invalid"
            ERROR -> "Error"
        }
}

// MARK: - Socket API

// MARK: - OLM Error

@Serializable
data class OlmError(
    val code: String,
    val message: String
)

@Serializable
data class SocketStatusResponse(
    val status: String? = null,
    val connected: Boolean,
    val terminated: Boolean,
    val tunnelIP: String? = null,
    val version: String? = null,
    val agent: String? = null,
    val peers: Map<String, SocketPeer>? = null,
    val registered: Boolean? = null,
    val orgId: String? = null,
    val networkSettings: NetworkSettings? = null,
    val error: OlmError? = null
)

@Serializable
data class SocketPeer(
    val siteId: Int? = null,
    val name: String? = null,
    val connected: Boolean? = null,
    val rtt: Long? = null,  // nanoseconds
    val lastSeen: String? = null,
    val endpoint: String? = null,
    val isRelay: Boolean? = null
)

@Serializable
data class NetworkSettings(
    @SerialName("tunnel_remote_address") val tunnelRemoteAddress: String? = null,
    val mtu: Int? = null,
    @SerialName("dns_servers") val dnsServers: List<String>? = null,
    @SerialName("ipv4_addresses") val ipv4Addresses: List<String>? = null,
    @SerialName("ipv4_subnet_masks") val ipv4SubnetMasks: List<String>? = null,
    @SerialName("ipv4_included_routes") val ipv4IncludedRoutes: List<IPv4Route>? = null,
    @SerialName("ipv4_excluded_routes") val ipv4ExcludedRoutes: List<IPv4Route>? = null,
    @SerialName("ipv6_addresses") val ipv6Addresses: List<String>? = null,
    @SerialName("ipv6_network_prefixes") val ipv6NetworkPrefixes: List<String>? = null,
    @SerialName("ipv6_included_routes") val ipv6IncludedRoutes: List<IPv6Route>? = null,
    @SerialName("ipv6_excluded_routes") val ipv6ExcludedRoutes: List<IPv6Route>? = null
)

@Serializable
data class IPv4Route(
    @SerialName("destination_address") val destinationAddress: String,
    @SerialName("subnet_mask") val subnetMask: String? = null,
    @SerialName("gateway_address") val gatewayAddress: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null
)

@Serializable
data class IPv6Route(
    @SerialName("destination_address") val destinationAddress: String,
    @SerialName("network_prefix_length") val networkPrefixLength: Int? = null,
    @SerialName("gateway_address") val gatewayAddress: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null
)

@Serializable
data class SocketExitResponse(
    val status: String
)

@Serializable
data class SocketSwitchOrgRequest(
    val orgId: String
)

@Serializable
data class SocketSwitchOrgResponse(
    val status: String
)

// MARK: - Display Name Helpers

/**
 * Returns a display name for a User with precedence: email > name > username > "User"
 */
fun userDisplayName(user: User): String {
    return when {
        user.email.isNotEmpty() -> user.email
        !user.name.isNullOrEmpty() -> user.name!!
        !user.username.isNullOrEmpty() -> user.username!!
        else -> "User"
    }
}

/**
 * Returns a display name for an Account with precedence: email > name > username > "Account"
 */
fun accountDisplayName(account: Account): String {
    return when {
        account.email.isNotEmpty() -> account.email
        !account.name.isNullOrEmpty() -> account.name!!
        !account.username.isNullOrEmpty() -> account.username!!
        else -> "Account"
    }
}

@Serializable
data class UpdateMetadataRequest(
    val fingerprint: Fingerprint,
    val postures: Postures
)

@Serializable
data class UpdateMetadataResponse(
    val status: String,
)

@Serializable
data class Fingerprint(
    val username: String,
    val hostname: String,
    val platform: String,
    val osVersion: String,
    val kernelVersion: String,
    val arch: String,
    val deviceModel: String,
    val serialNumber: String,
    val platformFingerprint: String
)

@Serializable
data class Postures(
    val autoUpdatesEnabled: Boolean,
    val biometricsEnabled: Boolean,
    val diskEncrypted: Boolean,
    val firewallEnabled: Boolean,
    val tpmAvailable: Boolean
)
