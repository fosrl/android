# StatusPollingManager Implementation Summary

## Overview

A complete polling system has been implemented to automatically fetch tunnel status from the socket manager every 3 seconds when the tunnel is active. The status is displayed in a dedicated StatusActivity with both formatted and JSON views.

## Files Created

### 1. Core Manager
- **`app/src/main/java/net/pangolin/Pangolin/util/StatusPollingManager.kt`**
  - Polls `SocketManager.getStatus()` every 3 seconds
  - Exposes status via Kotlin StateFlow for reactive updates
  - Handles errors gracefully
  - Manages lifecycle (start/stop/cleanup)

### 2. UI Components
- **`app/src/main/java/net/pangolin/Pangolin/ui/StatusJsonFragment.kt`**
  - Displays raw JSON status in `fragment_status_json.xml`
  - Auto-updates from StatusPollingManager
  - Shows error messages

- **`app/src/main/java/net/pangolin/Pangolin/ui/StatusFormattedFragment.kt`**
  - Displays human-readable formatted status
  - Shows connection info, network settings, peers with icons
  - Uses `fragment_status_formatted.xml`

- **`app/src/main/java/net/pangolin/Pangolin/ui/StatusPollingProvider.kt`** (interface)
  - Interface for providing StatusPollingManager to fragments
  - Implemented by StatusActivity

### 3. Documentation
- **`STATUS_POLLING_MANAGER.md`** - Complete usage guide
- **`IMPLEMENTATION_SUMMARY.md`** - This file

## Files Modified

### 1. StatusActivity.kt
**Changes:**
- Implements `StatusPollingProvider` interface
- Initializes `StatusPollingManager` with socket path
- Starts polling in `onResume()`, stops in `onPause()`
- Sets up ViewPager2 with TabLayout for Formatted/JSON tabs
- Provides manager to fragments via interface

### 2. MainActivity.kt
**Changes:**
- Initializes `StatusPollingManager` on create
- Starts polling when tunnel connects (Tunnel.State.UP)
- Stops polling when tunnel disconnects
- Cleans up manager in `onDestroy()`

## How It Works

```
┌─────────────────┐
│   MainActivity  │ Tunnel connects → startPolling()
└────────┬────────┘
         │
         ├──> StatusPollingManager
         │    │
         │    ├─ Every 3 seconds:
         │    │  └─> SocketManager.getStatus()
         │    │      └─> Unix Socket → Go Backend
         │    │
         │    └─ Emits to StateFlows:
         │       ├─> statusFlow (SocketStatusResponse?)
         │       ├─> statusJsonFlow (String)
         │       └─> errorFlow (String?)
         │
         v
┌─────────────────┐
│ StatusActivity  │ onResume() → startPolling()
└────────┬────────┘
         │
         ├──> StatusFormattedFragment
         │    └─ Collects statusFlow
         │       └─ Displays formatted status
         │
         └──> StatusJsonFragment
              └─ Collects statusJsonFlow
                 └─ Displays JSON in TextView
```

## Lifecycle Management

### MainActivity
```kotlin
onCreate()    → Initialize StatusPollingManager
Tunnel.UP     → statusPollingManager.startPolling()
Tunnel.DOWN   → statusPollingManager.stopPolling()
onDestroy()   → statusPollingManager.cleanup()
```

### StatusActivity
```kotlin
onCreate()    → Initialize StatusPollingManager
onResume()    → statusPollingManager.startPolling()
onPause()     → statusPollingManager.stopPolling()
onDestroy()   → statusPollingManager.cleanup()
```

### Fragments
```kotlin
onViewCreated()  → Collect statusFlow/statusJsonFlow
                   (using viewLifecycleOwner.lifecycleScope)
```

## Key Features

### 1. Reactive Updates
```kotlin
// In fragments
viewLifecycleOwner.lifecycleScope.launch {
    statusPollingManager.statusJsonFlow.collect { json ->
        textView.text = json
    }
}
```

### 2. Error Handling
- Socket doesn't exist → "Socket not available"
- Connection failed → Shows error message
- Parsing errors → Displays error with details

### 3. Configurable Polling
```kotlin
StatusPollingManager(
    socketPath = socketPath,
    pollingIntervalMs = 3000L  // 3 seconds (default)
)
```

### 4. Thread Safety
- Uses coroutines with Dispatchers.Default
- StateFlow for thread-safe state management
- Proper cancellation on cleanup

## Status Data Available

The `SocketStatusResponse` provides:
- **Connection State**: connected, terminated, registered
- **Network Info**: tunnelIP, DNS servers, routes
- **Peer Info**: Connected peers with RTT, endpoints, relay status
- **System Info**: version, agent, orgId
- **Settings**: MTU, IPv4/IPv6 addresses and routes

## UI Components

### StatusActivity Tabs
1. **Formatted Tab**: Human-readable status with emojis and sections
2. **JSON Tab**: Raw JSON for debugging and advanced users

### Status Sections (Formatted View)
- 📡 Connection Status
- 🌐 Network Information
- ℹ️ Application Info
- 👥 Peers (with RTT in milliseconds)

## Integration Points

### Starting Polling
Poll status when tunnel connects:
```kotlin
override fun onStateChange(newState: Tunnel.State) {
    if (newState == Tunnel.State.UP) {
        statusPollingManager?.startPolling()
    } else {
        statusPollingManager?.stopPolling()
    }
}
```

### Accessing in Fragments
```kotlin
val manager = (activity as? StatusPollingProvider)?.getStatusPollingManager()
```

## Testing Checklist

- [ ] StatusPollingManager starts when tunnel connects
- [ ] StatusPollingManager stops when tunnel disconnects
- [ ] JSON updates every 3 seconds in StatusActivity
- [ ] Formatted view shows all status fields correctly
- [ ] Errors are displayed when socket unavailable
- [ ] Tabs switch correctly between Formatted/JSON
- [ ] No memory leaks (cleanup called properly)
- [ ] Works across activity lifecycle (pause/resume)

## Dependencies

Required imports:
- `kotlinx.coroutines.*` - For polling and state management
- `kotlinx.serialization.*` - For JSON formatting
- `androidx.lifecycle.*` - For lifecycle-aware observers
- `androidx.fragment.*` - For fragment implementation
- `androidx.viewpager2.*` - For tab navigation

## Next Steps (Optional Enhancements)

1. Add pull-to-refresh in status view
2. Add copy-to-clipboard button for JSON
3. Add export/share status functionality
4. Add notification with connection status
5. Add status history/logging
6. Add bandwidth graphs from peer data
7. Add manual refresh button

## Quick Reference

**Start polling:**
```kotlin
statusPollingManager?.startPolling()
```

**Stop polling:**
```kotlin
statusPollingManager?.stopPolling()
```

**Get current status:**
```kotlin
val status = statusPollingManager?.getCurrentStatus()
```

**Observe updates:**
```kotlin
lifecycleScope.launch {
    statusPollingManager.statusFlow.collect { status ->
        // Handle status update
    }
}
```

## Support

For issues or questions:
- Check `STATUS_POLLING_MANAGER.md` for detailed usage
- Review error messages in `errorFlow`
- Verify socket path matches tunnel configuration
- Ensure tunnel is running before expecting status updates