package net.pangolin.Pangolin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pangolin.Pangolin.PacketTunnel.GoBackend
import net.pangolin.Pangolin.PacketTunnel.InitConfig
import net.pangolin.Pangolin.PacketTunnel.Tunnel
import net.pangolin.Pangolin.PacketTunnel.TunnelConfig
import net.pangolin.Pangolin.databinding.ActivityMainBinding
import net.pangolin.Pangolin.ui.theme.PangolinTheme
import java.io.File

class MainActivity : BaseNavigationActivity() {
    private var goBackend: GoBackend? = null
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup navigation using base class
        setupNavigation(binding.drawerLayout, binding.navView, binding.toolbar)

        goBackend = GoBackend(applicationContext)

        // Setup Compose content in the main_content FrameLayout
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PangolinTheme {
                    TunnelControlScreen(goBackend = goBackend!!)
                }
            }
        }
        binding.mainContent.addView(composeView)
    }

    override fun getSelectedNavItemId(): Int {
        return R.id.nav_main
    }
}

data class TunnelState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val statusMessage: String = "Disconnected",
    val errorMessage: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelControlScreen(
    goBackend: GoBackend,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Config state with defaults from config.go
    var endpoint by remember { mutableStateOf("https://app.pangolin.net") }
    var id by remember { mutableStateOf("pcncclwlvde9mg5") }
    var secret by remember { mutableStateOf("t6xvv3yn0i8ypqrdc2r01bqcyic0ygiwo6lff1han6shfmlt") }
    var mtu by remember { mutableStateOf("1280") }
    var dns by remember { mutableStateOf("8.8.8.8") }
    var pingInterval by remember { mutableStateOf("10") }
    var pingTimeout by remember { mutableStateOf("30") }
    var holepunchEnabled by remember { mutableStateOf(true) }
    var logLevel by remember { mutableStateOf("debug") }

    // Tunnel state
    var tunnelState by remember { mutableStateOf(TunnelState()) }

    // Simple tunnel implementation
    val tunnel = remember {
        object : Tunnel {
            override fun getName(): String = "pangolin"
            override fun onStateChange(newState: Tunnel.State) {
                tunnelState = tunnelState.copy(
                    isConnected = newState == Tunnel.State.UP,
                    isConnecting = false,
                    statusMessage = if (newState == Tunnel.State.UP) "Connected" else "Disconnected"
                )
            }
        }
    }

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, start the tunnel
            scope.launch {
                startTunnel(
                    context = context,
                    goBackend = goBackend,
                    tunnel = tunnel,
                    endpoint = endpoint,
                    id = id,
                    secret = secret,
                    mtu = mtu.toIntOrNull() ?: 1280,
                    dns = dns,
                    upstreamDnsPrimary = "$dns:53",
                    pingInterval = pingInterval.toIntOrNull() ?: 10,
                    pingTimeout = pingTimeout.toIntOrNull() ?: 30,
                    holepunch = holepunchEnabled,
                    logLevel = logLevel,
                    onStateChange = { tunnelState = it }
                )
            }
        } else {
            tunnelState = tunnelState.copy(
                isConnecting = false,
                errorMessage = "VPN permission denied"
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    tunnelState.isConnected -> MaterialTheme.colorScheme.primaryContainer
                    tunnelState.isConnecting -> MaterialTheme.colorScheme.secondaryContainer
                    tunnelState.errorMessage != null -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status: ${tunnelState.statusMessage}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (tunnelState.errorMessage != null) {
                    Text(
                        text = "Error: ${tunnelState.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (tunnelState.isConnecting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        HorizontalDivider()

        Text(
            text = "Configuration",
            style = MaterialTheme.typography.titleMedium
        )

        // Endpoint
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("Endpoint URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
            singleLine = true
        )

        // ID
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("ID") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
            singleLine = true
        )

        // Secret
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Secret") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )

        // MTU and DNS in a row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = mtu,
                onValueChange = { mtu = it },
                label = { Text("MTU") },
                modifier = Modifier.weight(1f),
                enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = dns,
                onValueChange = { dns = it },
                label = { Text("DNS") },
                modifier = Modifier.weight(1f),
                enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
                singleLine = true
            )
        }

        // Ping interval and timeout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = pingInterval,
                onValueChange = { pingInterval = it },
                label = { Text("Ping Interval (s)") },
                modifier = Modifier.weight(1f),
                enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = pingTimeout,
                onValueChange = { pingTimeout = it },
                label = { Text("Ping Timeout (s)") },
                modifier = Modifier.weight(1f),
                enabled = !tunnelState.isConnected && !tunnelState.isConnecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        // Log level dropdown
        var logLevelExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = logLevelExpanded,
            onExpandedChange = {
                if (!tunnelState.isConnected && !tunnelState.isConnecting) {
                    logLevelExpanded = !logLevelExpanded
                }
            }
        ) {
            OutlinedTextField(
                value = logLevel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Log Level") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = logLevelExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                enabled = !tunnelState.isConnected && !tunnelState.isConnecting
            )
            ExposedDropdownMenu(
                expanded = logLevelExpanded,
                onDismissRequest = { logLevelExpanded = false }
            ) {
                listOf("debug", "info", "warn", "error").forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level) },
                        onClick = {
                            logLevel = level
                            logLevelExpanded = false
                        }
                    )
                }
            }
        }

        // Holepunch toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Enable Holepunch",
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = holepunchEnabled,
                onCheckedChange = { holepunchEnabled = it },
                enabled = !tunnelState.isConnected && !tunnelState.isConnecting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connect/Disconnect button
        Button(
            onClick = {
                if (tunnelState.isConnected) {
                    // Disconnect
                    scope.launch {
                        stopTunnel(
                            goBackend = goBackend,
                            tunnel = tunnel,
                            onStateChange = { tunnelState = it }
                        )
                    }
                } else {
                    // Check if we need VPN permission
                    tunnelState = tunnelState.copy(
                        isConnecting = true,
                        statusMessage = "Requesting permission...",
                        errorMessage = null
                    )

                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        vpnPermissionLauncher.launch(prepareIntent)
                    } else {
                        // Permission already granted
                        scope.launch {
                            startTunnel(
                                context = context,
                                goBackend = goBackend,
                                tunnel = tunnel,
                                endpoint = endpoint,
                                id = id,
                                secret = secret,
                                mtu = mtu.toIntOrNull() ?: 1280,
                                dns = dns,
                                upstreamDnsPrimary = "$dns:53",
                                pingInterval = pingInterval.toIntOrNull() ?: 10,
                                pingTimeout = pingTimeout.toIntOrNull() ?: 30,
                                holepunch = holepunchEnabled,
                                logLevel = logLevel,
                                onStateChange = { tunnelState = it }
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !tunnelState.isConnecting,
            colors = if (tunnelState.isConnected) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(
                text = when {
                    tunnelState.isConnecting -> "Connecting..."
                    tunnelState.isConnected -> "Disconnect"
                    else -> "Connect"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private suspend fun startTunnel(
    context: Context,
    goBackend: GoBackend,
    tunnel: Tunnel,
    endpoint: String,
    id: String,
    secret: String,
    mtu: Int,
    dns: String,
    upstreamDnsPrimary: String? = null,
    upstreamDnsSecondary: String? = null,
    pingInterval: Int,
    pingTimeout: Int,
    holepunch: Boolean,
    logLevel: String,
    onStateChange: (TunnelState) -> Unit
) {
    onStateChange(TunnelState(
        isConnecting = true,
        statusMessage = "Connecting..."
    ))

    try {
        withContext(Dispatchers.IO) {
            // Build init config
            val initConfig = InitConfig.Builder()
                .setEnableAPI(false)
                .setLogLevel(logLevel)
                .setAgent("android")
                .setVersion("1.0.0-test")
                .setSocketPath(File(context.filesDir, "pangolin.sock").absolutePath)
                .setEnableAPI(true)
                .build()

            val upstreamDns = mutableListOf<String>()
            if (upstreamDnsPrimary != null) {
                upstreamDns.add(upstreamDnsPrimary)
            }
            if (upstreamDnsSecondary != null) {
                upstreamDns.add(upstreamDnsSecondary)
            }

            // Build tunnel config
            val tunnelConfig = TunnelConfig.Builder()
                .setEndpoint(endpoint)
                .setId(id)
                .setSecret(secret)
                .setMtu(mtu)
                .setDns(dns)
                .setUpstreamDNS(upstreamDns)
                .setPingIntervalSeconds(pingInterval)
                .setPingTimeoutSeconds(pingTimeout)
                .setHolepunch(holepunch)
                // TODO: make these configurable
                .setOverrideDNS(true)
                .setTunnelDNS(false)
                .build()

            Log.d("MainActivity", "Starting tunnel with config: $tunnelConfig")
            Log.d("MainActivity", "Init config: $initConfig")

            goBackend.setState(tunnel, Tunnel.State.UP, tunnelConfig, initConfig)
        }

        onStateChange(TunnelState(
            isConnected = true,
            statusMessage = "Connected"
        ))
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to start tunnel", e)
        onStateChange(TunnelState(
            isConnected = false,
            isConnecting = false,
            statusMessage = "Connection failed",
            errorMessage = e.message ?: "Unknown error"
        ))
    }
}

private suspend fun stopTunnel(
    goBackend: GoBackend,
    tunnel: Tunnel,
    onStateChange: (TunnelState) -> Unit
) {
    onStateChange(TunnelState(
        isConnected = true,
        isConnecting = true,
        statusMessage = "Disconnecting..."
    ))

    try {
        withContext(Dispatchers.IO) {
            goBackend.setState(tunnel, Tunnel.State.DOWN, null, null)
        }

        onStateChange(TunnelState(
            isConnected = false,
            statusMessage = "Disconnected"
        ))
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to stop tunnel", e)
        onStateChange(TunnelState(
            isConnected = false,
            statusMessage = "Disconnected",
            errorMessage = "Error while disconnecting: ${e.message}"
        ))
    }
}
