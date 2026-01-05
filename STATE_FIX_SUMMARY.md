# MainActivity State Loss Fix

## Problem
When navigating between activities (e.g., from MainActivity to StatusActivity and back), the UI state was being lost even though the VPN was still running. The button showed "Connect" and the status card showed "Disconnected" despite the VPN being active.

## Root Cause
The `BaseNavigationActivity` calls `finish()` on the current activity when navigating to another activity. This destroys the activity completely. When navigating back, a **new instance** of MainActivity is created with `onCreate()` being called fresh.

The original code only checked the tunnel state in `onResume()`, but when a new activity instance is created:
1. `onCreate()` is called first with savedInstanceState = null
2. `tunnelState` is initialized to the default `TunnelState()` (disconnected)  
3. UI is updated to show disconnected state
4. `onResume()` is then called and checks the state

However, there was a race condition or timing issue where the state check wasn't properly updating the UI.

## Solution
Multiple fixes were implemented:

### 1. Check Tunnel State in onCreate()
Added a call to `checkTunnelState()` at the end of `onCreate()` after all initialization is complete. This ensures that when the activity is freshly created (after navigation), it immediately checks if the VPN is already running and updates the UI accordingly.

### 2. Enhanced checkTunnelState()
- Added comprehensive logging to track state transitions
- Properly handles null backend
- Updates UI immediately when service is detected as running
- Waits briefly (500ms) for socket connection to establish
- Fetches current status from polling manager if available
- Stops polling if service is not running

### 3. Improved Status Observer
- Uses `repeatOnLifecycle(Lifecycle.State.STARTED)` to properly respect lifecycle
- Prevents memory leaks
- Ensures observer is active only when activity is visible

### 4. Instance State Save/Restore
- Saves tunnel state in `onSaveInstanceState()`
- Restores state in `onCreate()` if available
- This helps in case Android kills the app in the background

### 5. Comprehensive Logging
Added logging throughout to help debug any future issues:
- onCreate(), onResume(), onSaveInstanceState()
- checkTunnelState() - backend state, polling status, socket status
- State transitions

## Testing
After navigation:
1. Start VPN in MainActivity
2. Navigate to StatusActivity
3. Navigate back to MainActivity  
4. UI should now correctly show:
   - Button says "Disconnect"
   - Status card shows green indicators
   - Service status shows "Running"
   - Socket and Registration status reflect actual state

## Files Changed
- `app/src/main/java/net/pangolin/Pangolin/MainActivity.kt`
  - Added imports: Lifecycle, repeatOnLifecycle, delay
  - Added onSaveInstanceState() method
  - Modified onCreate() to restore state and check tunnel state
  - Enhanced checkTunnelState() with better logic and logging
  - Improved observeStatusUpdates() with lifecycle awareness
  - Added comprehensive logging throughout
