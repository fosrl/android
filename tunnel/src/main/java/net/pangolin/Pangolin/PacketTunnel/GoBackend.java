/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import net.pangolin.Pangolin.PacketTunnel.BackendException.Reason;
import net.pangolin.Pangolin.PacketTunnel.Tunnel.State;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import org.json.JSONArray;
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
    @Nullable private SystemDnsMonitor systemDnsMonitor;
    @Nullable private ParcelFileDescriptor currentTunFd;
    private final AtomicBoolean networkSettingsApplied = new AtomicBoolean(false);
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

    /** Keep the foreground notification aligned with data-path readiness. */
    public void updateForegroundNotification(final boolean connected) {
        final VpnService service = vpnService.getNow(null);
        if (service != null)
            service.updateForegroundNotification(connected);
    }

    private static native String initOlm(String configJSON);

    private static native String startTunnel(int fd, String configJSON);

    private static native String stopTunnel();

    private static native String addDevice(int fd);

    private static native long getNetworkSettingsVersion();

    private static native String getNetworkSettings();

    private static native String nativeSetPowerMode(String mode);

    private static native String setSystemDNS(String serversJSON);

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
        networkSettingsApplied.set(false);
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
        networkSettingsApplied.set(false);
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
     * Lazily creates (and reuses) the SystemDnsMonitor for this backend instance.
     */
    private SystemDnsMonitor getSystemDnsMonitor() {
        if (systemDnsMonitor == null) {
            systemDnsMonitor = new SystemDnsMonitor(context);
        }
        return systemDnsMonitor;
    }

    /**
     * Starts observing the device's real (non-VPN) DNS servers and pushing changes into
     * olm, since olm cannot read Android's DNS configuration itself.
     */
    public void startSystemDnsMonitoring() {
        SystemDnsMonitor monitor = getSystemDnsMonitor();
        monitor.setCallback(this::pushSystemDnsServers);
        monitor.start();

        // Push a synchronous best-effort read immediately (this runs before the VPN
        // interface exists, so the "active network" is still the real one). This closes
        // the window between olm starting up and the first live NetworkCallback firing,
        // during which olm would otherwise fall back to a hardcoded default DNS server.
        pushSystemDnsServers(monitor.getCurrentDnsServers());
        Log.d(TAG, "Started system DNS monitoring");
    }

    private void pushSystemDnsServers(List<String> dnsServers) {
        if (dnsServers.isEmpty()) {
            return;
        }
        try {
            JSONArray serversJson = new JSONArray();
            for (String server : dnsServers) {
                serversJson.put(server);
            }
            String result = setSystemDNS(serversJson.toString());
            Log.d(TAG, "setSystemDNS result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "Failed to push system DNS to olm", e);
        }
    }

    /**
     * Stops observing the device's real DNS servers.
     */
    public void stopSystemDnsMonitoring() {
        if (systemDnsMonitor != null) {
            systemDnsMonitor.stop();
            Log.d(TAG, "Stopped system DNS monitoring");
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
        networkSettingsApplied.set(false);
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

                if (result == null || result.startsWith("Error:")) {
                    Log.e(TAG, "Failed to add device to Go backend: " + result);
                    // The fd was detached, so we can't return it as a ParcelFileDescriptor anymore
                    // The Go side should handle cleanup if addDevice fails
                    return null;
                }
                Log.d(TAG, "Successfully hot-swapped tunnel interface to Go backend: " + result);
                networkSettingsApplied.set(true);

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

    /** Returns whether Android established and Go accepted the current session's settings. */
    public boolean hasAppliedNetworkSettings() {
        return networkSettingsApplied.get();
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
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                vpnService.get(0, TimeUnit.NANOSECONDS).isAlwaysOn();
    }

    /**
     * Determines if the service is running in always-on VPN lockdown mode.
     * @return {@link boolean} whether the service is running in always-on VPN lockdown mode.
     */
    @Override
    public boolean isLockdownEnabled() throws ExecutionException, InterruptedException, TimeoutException {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                vpnService.get(0, TimeUnit.NANOSECONDS).isLockdownEnabled();
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
               final Intent serviceIntent = new Intent(context, VpnService.class)
                       .setAction(VpnServiceRestartPolicy.ACTION_MANUAL_START);
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                   context.startForegroundService(serviceIntent);
               else
                   context.startService(serviceIntent);
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

           // Start observing the device's real DNS servers as early as possible - before
           // the VPN interface exists - so olm has a real value to apply instead of its
           // hardcoded fallback by the time startTunnel() below reaches that check.
           startSystemDnsMonitoring();

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
           if (currentTunFd == null) {
               stopSystemDnsMonitoring();
               throw new BackendException(Reason.TUN_CREATION_ERROR);
           }

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
               stopSystemDnsMonitoring();
               if (currentTunFd != null) {
                   try {
                       currentTunFd.close();
                   } catch (Exception ignored) {}
                   currentTunFd = null;
               }
               // Stop the VPN service since tunnel start failed
               try {
                   final VpnService svc = vpnService.get(2, TimeUnit.SECONDS);
                   if (svc.isAlwaysOnOwned()) {
                       Log.i(TAG, "Keeping Always-On VPN service alive after tunnel start failure");
                   } else {
                       Log.i(TAG, "Stopping VPN service due to tunnel start failure");
                       svc.stopSelf();
                   }
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
           stopSystemDnsMonitoring();

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
        void alwaysOnStopped();
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        private static final String TAG = "VpnService/PowerState";
        private static final String NOTIFICATION_CHANNEL_ID = "pangolin_vpn";
        private static final int NOTIFICATION_ID = 1001;
        @Nullable private GoBackend owner;
        @Nullable private PowerManager powerManager;
        private boolean isReceiverRegistered = false;
        private boolean isInDozeMode = false;
        private boolean isInPowerSaveMode = false;
        private boolean alwaysOnOwned = false;
        private boolean ownershipRevoked = false;

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
            super.onCreate();
            vpnService.complete(this);
            updateForegroundNotification(false);

            // Initialize power manager and start monitoring
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            startPowerStateMonitoring();
        }

        @Override
        public void onDestroy() {
            final boolean canQueryPlatformOwnership = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            final boolean platformAlwaysOn = canQueryPlatformOwnership && isAlwaysOn();
            final boolean retainAlwaysOn = VpnServiceRestartPolicy.resolveOwnership(
                    alwaysOnOwned, canQueryPlatformOwnership, platformAlwaysOn, ownershipRevoked);

            // Stop power state monitoring
            stopPowerStateMonitoring();

            if (owner != null) {
                // Stop network settings polling
                owner.stopNetworkSettingsPolling();
                owner.stopSystemDnsMonitoring();

                final Tunnel tunnel = owner.currentTunnel;
                if (tunnel != null) {
                    stopTunnel();
                    owner.tunnelActive = false;
                    owner.currentTunnel = null;
                    owner.currentConfig = null;
                    tunnel.onStateChange(State.DOWN);
                }
            }
            vpnService = new CompletableFuture<>();
            stopForeground(STOP_FOREGROUND_REMOVE);
            super.onDestroy();

            if (alwaysOnCallback != null) {
                if (retainAlwaysOn)
                    alwaysOnCallback.alwaysOnTriggered();
                else if (alwaysOnOwned)
                    alwaysOnCallback.alwaysOnStopped();
            }
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            vpnService.complete(this);
            ownershipRevoked = false;
            final boolean systemStart = VpnServiceRestartPolicy.isSystemStartOnLegacy(
                    intent == null ? null : intent.getAction());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                alwaysOnOwned = isAlwaysOn();
            else
                alwaysOnOwned = systemStart;

            if (alwaysOnOwned) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return VpnServiceRestartPolicy.shouldRestartAfterProcessDeath(alwaysOnOwned) ?
                    START_STICKY : START_NOT_STICKY;
        }

        @Override
        public void onRevoke() {
            ownershipRevoked = true;
            alwaysOnOwned = false;
            if (alwaysOnCallback != null)
                alwaysOnCallback.alwaysOnStopped();
            stopSelf();
            super.onRevoke();
        }

        private void updateForegroundNotification(final boolean connected) {
            final NotificationManager manager = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
                manager.createNotificationChannel(new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.vpn_notification_channel),
                        NotificationManager.IMPORTANCE_LOW));
            }

            final Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            final PendingIntent pendingIntent = launchIntent == null ? null : PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    new Notification.Builder(this, NOTIFICATION_CHANNEL_ID) :
                    new Notification.Builder(this);
            builder.setSmallIcon(R.drawable.ic_vpn_lock)
                    .setContentTitle(getString(R.string.vpn_notification_title))
                    .setContentText(getString(connected ?
                            R.string.vpn_notification_connected :
                            R.string.vpn_notification_connecting))
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            if (pendingIntent != null)
                builder.setContentIntent(pendingIntent);
            final Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        }

        boolean isAlwaysOnOwned() {
            return alwaysOnOwned;
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
