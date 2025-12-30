/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
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

    private static native long getNetworkSettingsVersion();

    private static native String getNetworkSettings();

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
            Log.d(TAG, "Network settings updated: " + settings);
            try {
                final VpnService service = vpnService.getNow(null);
                if (service != null) {
                    applyNetworkSettings(service, settings, tunnelName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply network settings", e);
            }
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
     * Apply network settings to the VPN service.
     *
     * @param service The VPN service
     * @param settings The network settings to apply
     * @param tunnelName The name of the tunnel
     * @return The new tunnel file descriptor, or null if failed
     */
    @Nullable
    public ParcelFileDescriptor applyNetworkSettings(VpnService service, NetworkSettings settings, String tunnelName) {
        try {
            final VpnService.Builder builder = service.getBuilder();
            NetworkSettingsPoller.applySettingsToBuilder(builder, settings, tunnelName);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                service.setUnderlyingNetworks(null);
            }

            ParcelFileDescriptor tun = builder.establish();
            if (tun != null) {
                Log.d(TAG, "Successfully applied network settings and established tunnel");
            }
            return tun;
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

           // Create a minimal VPN builder - the Go API will configure the interface
           final VpnService.Builder builder = service.getBuilder();
           builder.setSession(tunnel.getName());

           // Set a minimal MTU
           builder.setMtu(config.getMtu());

           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
               builder.setMetered(false);
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
               service.setUnderlyingNetworks(null);

           builder.setBlocking(true);
           
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
           } catch (JSONException e) {
               Log.e(TAG, "Failed to serialize tunnel config", e);
               if (currentTunFd != null) {
                   currentTunFd.close();
                   currentTunFd = null;
               }
               throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, -1);
           }

           tunnelActive = true;
           currentTunnel = tunnel;
           currentConfig = config;

           // Start polling for network settings changes
           startNetworkSettingsPolling(tunnel.getName());

       } else {
           if (!tunnelActive) {
               Log.w(TAG, "Tunnel already down");
               return;
           }

           // Stop network settings polling
           stopNetworkSettingsPolling();

           // Stop the tunnel via Go API
           String stopResult = stopTunnel();
           Log.d(TAG, "Tunnel stop result: " + stopResult);

           tunnelActive = false;
           currentTunnel = null;
           currentConfig = null;

           if (currentTunFd != null) {
               try {
                   currentTunFd.close();
               } catch (Exception ignored) {}
               currentTunFd = null;
           }

           try {
               vpnService.get(0, TimeUnit.NANOSECONDS).stopSelf();
           } catch (final TimeoutException ignored) { }
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
        @Nullable private GoBackend owner;

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            vpnService.complete(this);
            super.onCreate();
        }

        @Override
        public void onDestroy() {
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
    }
}