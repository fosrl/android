# StatusPollingManager Documentation

## Overview

The `StatusPollingManager` is a Kotlin utility that automatically polls the tunnel's socket manager for status updates every 3 seconds when the tunnel is active. It provides real-time status information through Kotlin StateFlow, making it easy to observe status changes in your UI components.

## Features

- **Automatic Polling**: Polls `getStatus()` from SocketManager every 3 seconds
- **Reactive Updates**: Exposes status through Kotlin StateFlow for easy observation
- **Error Handling**: Gracefully handles socket errors and connection failures
- **Lifecycle Management**: Start/stop polling based on tunnel state
- **Thread-Safe**: Uses coroutines and proper concurrency controls

## Architecture

```
MainActivity / StatusActivity
        ↓
StatusPollingManager
        ↓
SocketManager (getStatus() every 3s)
        ↓
Unix Socket (pangolin.sock)
        ↓
Go Backend Tunnel
```

## Usage

### Basic Setup

```kotlin
// Initialize the manager with the socket path
val socketPath = File(context.filesDir, "pangolin.sock").absolutePath
val statusPollingManager = StatusPollingManager(socketPath)

// Start polling when tunnel connects
statusPollingManager.startPolling()

// Stop polling when tunnel disconnects
statusPollingManager.stopPolling()

// Clean up when done
statusPollingManager.cleanup()
```

### Observing Status Updates

The manager provides three StateFlows you can observe:

#### 1. Status Flow (Raw Data)
```kotlin
lifecycleScope.launch {
    statusPollingManager.statusFlow.collect { status: SocketStatusResponse? ->
        if (status != null) {
            // Update UI with status
            println("Connected: ${status.connected}")
            println("Tunnel IP: ${status.tunnelIP}")
            println("Peers: ${status.peers?.size ?: 0}")
        }
    }
}
```

#### 2. Status JSON Flow (Formatted String)
```kotlin
lifecycleScope.launch {
    statusPollingManager.statusJsonFlow.collect { jsonString: String ->
        // Display formatted JSON in a TextView
        textView.text = jsonString
    }
}
```

#### 3. Error Flow
```kotlin
lifecycleScope.launch {
    statusPollingManager.errorFlow.collect { error: String? ->
        if (error != null) {
            // Handle error
            showError(error)
        }
    }
}
```

### Integration in MainActivity

```kotlin
class MainActivity : BaseNavigationActivity() {
    private var statusPollingManager: StatusPollingManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize manager
        val socketPath = File(applicationContext.filesDir, "pangolin.sock").absolutePath
        statusPollingManager = StatusPollingManager(socketPath)
    }
    
    // In your tunnel state change callback
    private val tunnel = object : Tunnel {
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.UP) {
                // Tunnel connected - start polling
                statusPollingManager?.startPolling()
            } else {
                // Tunnel disconnected - stop polling
                statusPollingManager?.stopPolling()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        statusPollingManager?.cleanup()
    }
}
```

### Integration in StatusActivity

```kotlin
class StatusActivity : BaseNavigationActivity(), StatusPollingProvider {
    private var statusPollingManager: StatusPollingManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val socketPath = File(filesDir, "pangolin.sock").absolutePath
        statusPollingManager = StatusPollingManager(socketPath)
    }
    
    override fun onResume() {
        super.onResume()
        // Start polling when activity becomes visible
        statusPollingManager?.startPolling()
    }
    
    override fun onPause() {
        super.onPause()
        // Stop polling when activity is not visible
        statusPollingManager?.stopPolling()
    }
    
    override fun getStatusPollingManager(): StatusPollingManager? {
        return statusPollingManager
    }
}
```

### Using in Fragments

```kotlin
class StatusJsonFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val jsonTextView = view.findViewById<TextView>(R.id.jsonStatusText)
        
        // Get manager from activity
        val manager = (activity as? StatusPollingProvider)?.getStatusPollingManager()
        
        // Observe JSON updates
        viewLifecycleOwner.lifecycleScope.launch {
            manager?.statusJsonFlow?.collect { json ->
                jsonTextView.text = json
            }
        }
    }
}
```

## StatusPollingProvider Interface

To share the StatusPollingManager between activities and fragments, implement this interface:

```kotlin
interface StatusPollingProvider {
    fun getStatusPollingManager(): StatusPollingManager?
}
```

## Configuration

### Polling Interval

The default polling interval is 3 seconds (3000ms). You can customize it:

```kotlin
val manager = StatusPollingManager(
    socketPath = socketPath,
    pollingIntervalMs = 5000L  // Poll every 5 seconds
)
```

### Socket Path

The socket path should match what's configured in the tunnel initialization:

```kotlin
val socketPath = File(context.filesDir, "pangolin.sock").absolutePath
```

## Status Response Structure

The `SocketStatusResponse` contains:

```kotlin
data class SocketStatusResponse(
    val status: String?,              // Current status message
    val connected: Boolean,           // Connection state
    val terminated: Boolean,          // Termination state
    val tunnelIP: String?,           // Assigned tunnel IP
    val version: String?,            // Tunnel version
    val agent: String?,              // Agent identifier
    val peers: Map<String, SocketPeer>?, // Connected peers
    val registered: Boolean?,        // Registration state
    val orgId: String?,             // Organization ID
    val networkSettings: NetworkSettings? // Network configuration
)
```

## Error Handling

The manager handles several error conditions:

1. **Socket Does Not Exist**: Socket file not found (tunnel not running)
2. **Connection Failed**: Unable to connect to socket
3. **Invalid Response**: Malformed response from socket
4. **HTTP Error**: Non-200 status code
5. **Decoding Error**: Failed to parse JSON response

Errors are exposed through the `errorFlow` StateFlow.

## Best Practices

1. **Lifecycle Management**: Always call `cleanup()` when done with the manager
2. **Activity Lifecycle**: Start polling in `onResume()`, stop in `onPause()` for activities
3. **Memory Leaks**: Use `viewLifecycleOwner.lifecycleScope` in fragments
4. **Error Display**: Always observe the `errorFlow` to show errors to users
5. **Conditional Start**: Only start polling when the tunnel is actually connected

## Example: Complete Implementation

```kotlin
class StatusActivity : BaseNavigationActivity(), StatusPollingProvider {
    private var statusPollingManager: StatusPollingManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize manager
        val socketPath = File(filesDir, "pangolin.sock").absolutePath
        statusPollingManager = StatusPollingManager(socketPath)
        
        setupUI()
    }
    
    private fun setupUI() {
        // Setup ViewPager with fragments that will observe the manager
        val adapter = StatusPagerAdapter(this)
        binding.viewPager.adapter = adapter
        
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Formatted"
                1 -> "JSON"
                else -> "Unknown"
            }
        }.attach()
    }
    
    override fun onResume() {
        super.onResume()
        statusPollingManager?.startPolling()
    }
    
    override fun onPause() {
        super.onPause()
        statusPollingManager?.stopPolling()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        statusPollingManager?.cleanup()
        statusPollingManager = null
    }
    
    override fun getStatusPollingManager() = statusPollingManager
}
```

## Troubleshooting

### Status Not Updating

- Verify the socket path is correct
- Ensure the tunnel is actually running
- Check that `startPolling()` has been called
- Look for errors in the `errorFlow`

### Memory Leaks

- Make sure to call `cleanup()` in `onDestroy()`
- Use `viewLifecycleOwner.lifecycleScope` in fragments
- Don't hold references to the manager after cleanup

### Socket Errors

- Socket file doesn't exist: Tunnel is not running
- Connection failed: Check socket permissions
- Timeout: Increase `timeoutMs` in SocketManager

## Related Files

- `util/StatusPollingManager.kt` - Main manager implementation
- `util/SocketManager.kt` - Socket communication layer
- `util/Models.kt` - Data models including `SocketStatusResponse`
- `ui/StatusJsonFragment.kt` - Fragment displaying JSON status
- `ui/StatusFormattedFragment.kt` - Fragment displaying formatted status
- `StatusActivity.kt` - Activity managing status display
- `MainActivity.kt` - Main activity controlling tunnel state