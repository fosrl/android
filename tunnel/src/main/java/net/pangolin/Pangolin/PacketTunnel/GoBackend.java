/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import net.pangolin.Pangolin.PacketTunnel.BackendException.Reason;
import net.pangolin.Pangolin.PacketTunnel.Tunnel.State;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import org.json.JSONException;

/**
 * Use the pangolin-go implementation to provide WireGuard tunnels.
 */
public final class GoBackend implements Backend {
    private static final String TAG = "WireGuard/GoBackend";
    @Nullable private static AlwaysOnCallback alwaysOnCallback;
    private static CompletableFuture<VpnService> vpnService = new CompletableFuture<>();
    private final Context context;
    @Nullable private Tunnel currentTunnel;
    @Nullable private TunnelConfig currentConfig;
    @Nullable private NetworkSettingsPoller networkSettingsPoller;
    @Nullable private ParcelFileDescriptor currentTunFd;
    private boolean tunnelActive = false;

    /**
     * Public constructor for GoBackend.
     *
     * @param context An Android {@link Context}
     */
    public GoBackend(final Context context) {
        SharedLibraryLoader.loadSharedLibrary(context, "pangolin-go");
        this.context = context;
    }

    /**
     * Set a {@link AlwaysOnCallback} to be invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     *
     * @param cb Callback to be invoked
     */
    public static void setAlwaysOnCallback(final AlwaysOnCallback cb) {
        alwaysOnCallback = cb;
    }

    private static native String initOlm(String configJSON);

    private static native String startTunnel(int fd, String configJSON);

    private static native String stopTunnel();

    private static native String addDevice(int fd);

    private static native long getNetworkSettingsVersion();

    private static native String getNetworkSettings();

    private static native String nativeSetPowerMode(String mode);

    /**
     * Set the power mode of the OLM tunnel.
     * This is a static method that can be called from anywhere.
     *
     * @param mode The power mode to set ("normal" or "low")
     * @return Result message from the Go backend
     */
    public static String setPowerMode(String mode) {
        try {
            return nativeSetPowerMode(mode);
        } catch (UnsatisfiedLinkError e) {
            // Library not loaded yet
            Log.w(TAG, "Native library not loaded, cannot set power mode: " + mode);
            return "Error: Native library not loaded";
        }
    }

    /**
     * Method to get the names of running tunnels.
     *
     * @return A set of string values denoting names of running tunnels.
     */
    @Override
    public Set<String> getRunningTunnelNames() {
        if (currentTunnel != null) {
            final Set<String> runningTunnels = new ArraySet<>();
            runningTunnels.add(currentTunnel.getName());
            return runningTunnels;
        }
        return Collections.emptySet();
    }

    /**
     * Get the associated {@link State} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return {@link State} associated with the given tunnel.
     */
    @Override
    public State getState(final Tunnel tunnel) {
        return currentTunnel == tunnel ? State.UP : State.DOWN;
    }

    /**
     * Initialize OLM with the given configuration.
     *
     * @param configJSON JSON configuration string
     * @return Result string from initialization
     */
    public String initializeOlm(String configJSON) {
        return initOlm(configJSON);
    }

    /**
     * Start the tunnel with the given file descriptor and configuration.
     *
     * @param fd The tunnel file descriptor
     * @param configJSON JSON configuration string
     * @return Result string from starting the tunnel
     */
    public String startTunnelWithConfig(int fd, String configJSON) {
        return startTunnel(fd, configJSON);
    }

    /**
     * Stop the currently running tunnel.
     *
     * @return Result string from stopping the tunnel
     */
    public String stopCurrentTunnel() {
        return stopTunnel();
    }

    /**
     * Get the current network settings version number.
     *
     * @return The network settings version number
     */
    public long getNetworkSettingsVersionNumber() {
        return getNetworkSettingsVersion();
    }

    /**
     * Get the current network settings as a JSON string.
     *
     * @return JSON string containing network settings
     */
    public String getNetworkSettingsJSON() {
        return getNetworkSettings();
    }

    /**
     * Start polling for network settings changes.
     * Settings changes will be applied to the VPN service automatically.
     *
     * @param tunnelName The name of the tunnel for the VPN session
     */
    public void startNetworkSettingsPolling(final String tunnelName) {
        if (networkSettingsPoller == null) {
            networkSettingsPoller = new NetworkSettingsPoller(this);
        }

        networkSettingsPoller.setCallback(settings -> {
            Log.d(TAG, "=== Network settings callback invoked ===");
            Log.d(TAG, "Network settings updated: " + settings);
            try {
                final VpnService service = vpnService.getNow(null);
                if (service != null) {
                    Log.d(TAG, "VpnService available, applying network settings");
                    applyNetworkSettings(service, settings, tunnelName);
                    Log.d(TAG, "Network settings application completed");
                } else {
                    Log.w(TAG, "VpnService is null, cannot apply network settings");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply network settings", e);
            }
            Log.d(TAG, "=== Network settings callback completed ===");
            return null;
        });

        networkSettingsPoller.startPolling();
        Log.d(TAG, "Started network settings polling");
    }

    /**
     * Stop polling for network settings changes.
     */
    public void stopNetworkSettingsPolling() {
        if (networkSettingsPoller != null) {
            networkSettingsPoller.stopPolling();
            Log.d(TAG, "Stopped network settings polling");
        }
    }

    /**
     * Pause network settings polling (called when entering low power mode).
     */
    public void pauseNetworkSettingsPolling() {
        if (networkSettingsPoller != null) {
            networkSettingsPoller.pausePolling();
            Log.d(TAG, "Paused network settings polling (low power mode)");
        }
    }

    /**
     * Resume network settings polling (called when exiting low power mode).
     */
    public void resumeNetworkSettingsPolling() {
        if (networkSettingsPoller != null) {
            networkSettingsPoller.resumePolling();
            Log.d(TAG, "Resumed network settings polling (normal power mode)");
        }
    }

    /**
     * Apply network settings to the VPN service.
     *
     * @param service The VPN service
     * @param settings The network settings to apply
     * @param tunnelName The name of the tunnel
     * @return The new tunnel file descriptor, or null if failed
     */
    @Nullable
    public ParcelFileDescriptor applyNetworkSettings(VpnService service, NetworkSettings settings, String tunnelName) {
        Log.d(TAG, "applyNetworkSettings called for tunnel: " + tunnelName);
        try {
            final VpnService.Builder builder = service.getBuilder();
            Log.d(TAG, "Got VpnService.Builder");

            NetworkSettingsPoller.applySettingsToBuilder(builder, settings, tunnelName);
            Log.d(TAG, "Applied settings to builder");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                service.setUnderlyingNetworks(null);
                Log.d(TAG, "Set underlying networks to null");
            }

            Log.d(TAG, "Calling builder.establish()...");
            ParcelFileDescriptor tun = builder.establish();

            if (tun != null) {
                Log.d(TAG, "Successfully established tunnel, got ParcelFileDescriptor");

                // Hot-swap the new tunnel interface into the Go backend
                // We need to detach the fd to pass ownership to the Go side
                int fd = tun.detachFd();
                Log.d(TAG, "Detached fd=" + fd + ", calling addDevice()...");

                String result = addDevice(fd);
                Log.d(TAG, "addDevice() returned: " + result);

                if (result != null && result.startsWith("Error:")) {
                    Log.e(TAG, "Failed to add device to Go backend: " + result);
                    // The fd was detached, so we can't return it as a ParcelFileDescriptor anymore
                    // The Go side should handle cleanup if addDevice fails
                    return null;
                }
                Log.d(TAG, "Successfully hot-swapped tunnel interface to Go backend: " + result);

                // Update the current tunnel fd reference
                // Note: Since we detached the fd, we create a new ParcelFileDescriptor if needed
                // but typically after addDevice, the Go backend owns the fd
                if (currentTunFd != null) {
                    Log.d(TAG, "Closing old currentTunFd");
                    try {
                        currentTunFd.close();
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing old tunFd", e);
                    }
                }
                currentTunFd = null; // Go backend now owns the fd
                Log.d(TAG, "Network settings application completed successfully");
            } else {
                Log.e(TAG, "builder.establish() returned null - failed to establish tunnel");
            }
            // After detachFd(), the ParcelFileDescriptor is no longer valid
            // Return null to indicate the fd has been transferred to Go backend
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply network settings", e);
            return null;
        }
    }

    /**
     * Get the current network settings parsed from JSON.
     *
     * @return NetworkSettings object, or null if parsing fails or no settings available
     */
    @Nullable
    public NetworkSettings getCurrentNetworkSettings() {
        String json = getNetworkSettingsJSON();
        if (json == null || json.isEmpty() || json.equals("{}")) {
            return null;
        }
        try {
            return NetworkSettings.fromJson(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse network settings", e);
            return null;
        }
    }

    /**
     * Determines if the service is running in always-on VPN mode.
     * @return {@link boolean} whether the service is running in always-on VPN mode.
     */
    @Override
    public boolean isAlwaysOn() throws ExecutionException, InterruptedException, TimeoutException {
        return vpnService.get(0, TimeUnit.NANOSECONDS).isAlwaysOn();
    }

    /**
     * Determines if the service is running in always-on VPN lockdown mode.
     * @return {@link boolean} whether the service is running in always-on VPN lockdown mode.
     */
    @Override
    public boolean isLockdownEnabled() throws ExecutionException, InterruptedException, TimeoutException {
        return vpnService.get(0, TimeUnit.NANOSECONDS).isLockdownEnabled();
    }


   /**
    * Change the state of a given {@link Tunnel}, optionally applying a given {@link TunnelConfig}.
    *
    * @param tunnel The tunnel to control the state of.
    * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
    *               {@code TOGGLE}.
    * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
    * @param initConfig The initialization configuration for OLM, may be null if state is {@code DOWN}.
    * @return {@link State} of the tunnel after state changes are applied.
    * @throws Exception Exception raised while changing tunnel state.
    */
   @Override
   public State setState(final Tunnel tunnel, State state, @Nullable final TunnelConfig config, @Nullable final InitConfig initConfig) throws Exception {
       final State originalState = getState(tunnel);

       if (state == State.TOGGLE)
           state = originalState == State.UP ? State.DOWN : State.UP;
       if (state == originalState && tunnel == currentTunnel && config == currentConfig)
           return originalState;
       if (state == State.UP) {
           final TunnelConfig originalConfig = currentConfig;
           final Tunnel originalTunnel = currentTunnel;
           if (currentTunnel != null)
               setStateInternal(currentTunnel, null, null, State.DOWN);
           try {
               setStateInternal(tunnel, config, initConfig, state);
           } catch (final Exception e) {
               if (originalTunnel != null)
                   setStateInternal(originalTunnel, originalConfig, initConfig, State.UP);
               throw e;
           }
       } else if (state == State.DOWN && tunnel == currentTunnel) {
           setStateInternal(tunnel, null, null, State.DOWN);
       }
       return getState(tunnel);
   }

   private void setStateInternal(final Tunnel tunnel, @Nullable final TunnelConfig config, @Nullable final InitConfig initConfig, final State state)
           throws Exception {
       Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

       if (state == State.UP) {
           if (config == null)
               throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);

           if (VpnService.prepare(context) != null)
               throw new BackendException(Reason.VPN_NOT_AUTHORIZED);

           final VpnService service;
           if (!vpnService.isDone()) {
               Log.d(TAG, "Requesting to start VpnService");
               context.startService(new Intent(context, VpnService.class));
           }

           try {
               service = vpnService.get(2, TimeUnit.SECONDS);
           } catch (final TimeoutException e) {
               final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
               be.initCause(e);
               throw be;
           }
           service.setOwner(this);

           if (tunnelActive) {
               Log.w(TAG, "Tunnel already up");
               return;
           }

           // Initialize OLM first
           if (initConfig != null) {
               try {
                   String initConfigJson = initConfig.toJson();
                   Log.d(TAG, "Initializing OLM with config: " + initConfigJson);
                   String initResult = initOlm(initConfigJson);
                   Log.d(TAG, "OLM init result: " + initResult);
                   if (initResult != null && initResult.startsWith("Error:")) {
                       throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, -1);
                   }
               } catch (JSONException e) {
                   Log.e(TAG, "Failed to serialize init config", e);
                   throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, -1);
               }
           }

           // Create VPN builder
           final VpnService.Builder builder = service.getBuilder();
           builder.setSession(tunnel.getName());

           // Set a minimal MTU
           builder.setMtu(config.getMtu());

           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
               builder.setMetered(false);
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
               service.setUnderlyingNetworks(null);

           builder.setBlocking(true);

           // add a dummy address so we can get the interface up. We will change it later...
           builder.addAddress("169.254.169.254", 32);

           // Establish the tunnel to get a file descriptor
           currentTunFd = builder.establish();
           if (currentTunFd == null)
               throw new BackendException(Reason.TUN_CREATION_ERROR);

           // Start the tunnel with the Go API
           try {
               String tunnelConfigJson = config.toJson();
               Log.d(TAG, "Starting tunnel with config");
               int fd = currentTunFd.detachFd();
               String startResult = startTunnel(fd, tunnelConfigJson);
               Log.d(TAG, "Tunnel start result: " + startResult);
               if (startResult != null && startResult.startsWith("Error:")) {
                   throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, -1);
               }
           } catch (Exception e) {
               Log.e(TAG, "Failed to start tunnel", e);
               if (currentTunFd != null) {
                   try {
                       currentTunFd.close();
                   } catch (Exception ignored) {}
                   currentTunFd = null;
               }
               // Stop the VPN service since tunnel start failed
               try {
                   final VpnService svc = vpnService.get(2, TimeUnit.SECONDS);
                   Log.i(TAG, "Stopping VPN service due to tunnel start failure");
                   svc.stopSelf();
               } catch (final TimeoutException te) {
                   Log.w(TAG, "VPN service not available when trying to stop after failure");
                   try {
                       context.stopService(new Intent(context, VpnService.class));
                   } catch (Exception ex) {
                       Log.e(TAG, "Failed to stop VPN service via context after failure", ex);
                   }
               } catch (final Exception ex) {
                   Log.e(TAG, "Error stopping VPN service after tunnel start failure", ex);
               }
               throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, -1);
           }

           tunnelActive = true;
           currentTunnel = tunnel;
           currentConfig = config;

           // Start polling for network settings changes
           startNetworkSettingsPolling(tunnel.getName());

       } else {
           // Always attempt to stop, even if tunnelActive is false
           // This ensures VPN service cleanup if tunnel didn't fully start
           if (!tunnelActive) {
               Log.w(TAG, "Tunnel not marked as active, but attempting cleanup anyway");
           }

           // Stop network settings polling
           stopNetworkSettingsPolling();

           // Stop the tunnel via Go API if it was active
           if (tunnelActive) {
               String stopResult = stopTunnel();
               Log.d(TAG, "Tunnel stop result: " + stopResult);
           }

           tunnelActive = false;
           currentTunnel = null;
           currentConfig = null;

           if (currentTunFd != null) {
               try {
                   currentTunFd.close();
               } catch (Exception ignored) {}
               currentTunFd = null;
           }

           // Stop the VPN service - give it time to start if it hasn't yet
           try {
               final VpnService service = vpnService.get(2, TimeUnit.SECONDS);
               Log.i(TAG, "Stopping VPN service");
               service.stopSelf();
           } catch (final TimeoutException e) {
               Log.w(TAG, "VPN service not available when trying to stop, may not have started yet");
               // Try to stop the service directly via context if it exists
               try {
                   context.stopService(new Intent(context, VpnService.class));
               } catch (Exception ex) {
                   Log.e(TAG, "Failed to stop VPN service via context", ex);
               }
           } catch (final Exception e) {
               Log.e(TAG, "Error stopping VPN service", e);
           }
       }

       tunnel.onStateChange(state);
   }

    /**
     * Callback for {@link GoBackend} that is invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     */
    public interface AlwaysOnCallback {
        void alwaysOnTriggered();
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        private static final String TAG = "VpnService/PowerState";
        @Nullable private GoBackend owner;
        @Nullable private PowerManager powerManager;
        private boolean isReceiverRegistered = false;
        private boolean isInDozeMode = false;
        private boolean isInPowerSaveMode = false;

        private final BroadcastReceiver powerStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                switch (intent.getAction()) {
                    case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                        handleDozeModeChange();
                        break;
                    case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                        handlePowerSaveModeChange();
                        break;
                }
            }
        };

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            vpnService.complete(this);
            super.onCreate();

            // Initialize power manager and start monitoring
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            startPowerStateMonitoring();
        }

        @Override
        public void onDestroy() {
            // Stop power state monitoring
            stopPowerStateMonitoring();

            if (owner != null) {
                // Stop network settings polling
                owner.stopNetworkSettingsPolling();

                final Tunnel tunnel = owner.currentTunnel;
                if (tunnel != null) {
                    stopTunnel();
                    owner.tunnelActive = false;
                    owner.currentTunnel = null;
                    owner.currentConfig = null;
                    tunnel.onStateChange(State.DOWN);
                }
            }
            vpnService = vpnService.newIncompleteFuture();
            super.onDestroy();
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            vpnService.complete(this);
            if (intent == null || intent.getComponent() == null || !intent.getComponent().getPackageName().equals(getPackageName())) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return super.onStartCommand(intent, flags, startId);
        }

        public void setOwner(final GoBackend owner) {
            this.owner = owner;
        }

        /**
         * Start monitoring power states (Doze mode and Power Save mode)
         */
        private void startPowerStateMonitoring() {
            if (isReceiverRegistered) {
                Log.d(TAG, "Power state monitoring already active");
                return;
            }

            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);

                registerReceiver(powerStateReceiver, filter);
                isReceiverRegistered = true;

                // Log and apply initial state
                logCurrentPowerState();
                updatePowerMode();

                Log.i(TAG, "Power state monitoring started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start power state monitoring", e);
            }
        }

        /**
         * Stop monitoring power states
         */
        private void stopPowerStateMonitoring() {
            if (!isReceiverRegistered) {
                return;
            }

            try {
                unregisterReceiver(powerStateReceiver);
                isReceiverRegistered = false;

                Log.i(TAG, "Power state monitoring stopped");
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop power state monitoring", e);
            }
        }

        /**
         * Handle doze mode state changes
         */
        private void handleDozeModeChange() {
            if (powerManager == null) return;

            boolean wasInDozeMode = isInDozeMode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isInDozeMode = powerManager.isDeviceIdleMode();
            } else {
                isInDozeMode = false;
            }

            if (wasInDozeMode != isInDozeMode) {
                String message = isInDozeMode ? "Device ENTERED Doze mode" : "Device EXITED Doze mode";
                Log.i(TAG, message);
                updatePowerMode();
            }
        }

        /**
         * Handle power save mode changes
         */
        private void handlePowerSaveModeChange() {
            if (powerManager == null) return;

            boolean wasInPowerSaveMode = isInPowerSaveMode;
            isInPowerSaveMode = powerManager.isPowerSaveMode();

            if (wasInPowerSaveMode != isInPowerSaveMode) {
                String message = isInPowerSaveMode ? "Device ENTERED Power Save mode" : "Device EXITED Power Save mode";
                Log.i(TAG, message);
                updatePowerMode();
            }
        }

        /**
         * Update OLM power mode based on current power states.
         * If either Doze mode OR Power Save mode is active, use low power mode.
         * Only use normal mode when both are inactive.
         */
        private void updatePowerMode() {
            String mode = (isInDozeMode || isInPowerSaveMode) ? "low" : "normal";
            Log.i(TAG, "Setting OLM power mode to: " + mode + " (doze=" + isInDozeMode + ", powerSave=" + isInPowerSaveMode + ")");

            try {
                String result = setPowerMode(mode);
                Log.d(TAG, "setPowerMode result: " + result);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set power mode", e);
            }

            // Pause/resume network settings polling based on power mode
            if (owner != null) {
                if (isInDozeMode || isInPowerSaveMode) {
                    owner.pauseNetworkSettingsPolling();
                } else {
                    owner.resumeNetworkSettingsPolling();
                }
            }
        }

        /**
         * Log current power state
         */
        private void logCurrentPowerState() {
            if (powerManager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isInDozeMode = powerManager.isDeviceIdleMode();
            }
            isInPowerSaveMode = powerManager.isPowerSaveMode();

            String ignoringBatteryOpt = "N/A";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean ignoring = powerManager.isIgnoringBatteryOptimizations(getPackageName());
                ignoringBatteryOpt = ignoring ? "YES" : "NO";
            }

            String status = String.format("Power State: Doze=%s, PowerSave=%s, IgnoringBatteryOpt=%s",
                    isInDozeMode ? "YES" : "NO",
                    isInPowerSaveMode ? "YES" : "NO",
                    ignoringBatteryOpt);

            Log.i(TAG, status);
        }
    }
}
