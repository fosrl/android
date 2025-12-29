/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import net.pangolin.Pangolin.PacketTunnel.BackendException.Reason;
import net.pangolin.Pangolin.PacketTunnel.Tunnel.State;

import java.net.InetAddress;
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
 * Implementation of {@link Backend} that uses the wireguard-go userspace implementation to provide
 * WireGuard tunnels.
 */
public final class GoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    private static final String TAG = "WireGuard/GoBackend";
    @Nullable private static AlwaysOnCallback alwaysOnCallback;
    private static CompletableFuture<VpnService> vpnService = new CompletableFuture<>();
    private final Context context;
    @Nullable private Tunnel currentTunnel;
    @Nullable private NetworkSettingsPoller networkSettingsPoller;

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
//
//    /**
//     * Change the state of a given {@link Tunnel}, optionally applying a given {@link Config}.
//     *
//     * @param tunnel The tunnel to control the state of.
//     * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
//     *               {@code TOGGLE}.
//     * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
//     * @return {@link State} of the tunnel after state changes are applied.
//     * @throws Exception Exception raised while changing tunnel state.
//     */
//    @Override
//    public State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
//        final State originalState = getState(tunnel);
//
//        if (state == State.TOGGLE)
//            state = originalState == State.UP ? State.DOWN : State.UP;
//        if (state == originalState && tunnel == currentTunnel && config == currentConfig)
//            return originalState;
//        if (state == State.UP) {
//            final Config originalConfig = currentConfig;
//            final Tunnel originalTunnel = currentTunnel;
//            if (currentTunnel != null)
//                setStateInternal(currentTunnel, null, State.DOWN);
//            try {
//                setStateInternal(tunnel, config, state);
//            } catch (final Exception e) {
//                if (originalTunnel != null)
//                    setStateInternal(originalTunnel, originalConfig, State.UP);
//                throw e;
//            }
//        } else if (state == State.DOWN && tunnel == currentTunnel) {
//            setStateInternal(tunnel, null, State.DOWN);
//        }
//        return getState(tunnel);
//    }
//
//    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
//            throws Exception {
//        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);
//
//        if (state == State.UP) {
//            if (config == null)
//                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);
//
//            if (VpnService.prepare(context) != null)
//                throw new BackendException(Reason.VPN_NOT_AUTHORIZED);
//
//            final VpnService service;
//            if (!vpnService.isDone()) {
//                Log.d(TAG, "Requesting to start VpnService");
//                context.startService(new Intent(context, VpnService.class));
//            }
//
//            try {
//                service = vpnService.get(2, TimeUnit.SECONDS);
//            } catch (final TimeoutException e) {
//                final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
//                be.initCause(e);
//                throw be;
//            }
//            service.setOwner(this);
//
//            if (currentTunnelHandle != -1) {
//                Log.w(TAG, "Tunnel already up");
//                return;
//            }
//
//
//            dnsRetry: for (int i = 0; i < DNS_RESOLUTION_RETRIES; ++i) {
//                // Pre-resolve IPs so they're cached when building the userspace string
//                for (final Peer peer : config.getPeers()) {
//                    final InetEndpoint ep = peer.getEndpoint().orElse(null);
//                    if (ep == null)
//                        continue;
//                    if (ep.getResolved().orElse(null) == null) {
//                        if (i < DNS_RESOLUTION_RETRIES - 1) {
//                            Log.w(TAG, "DNS host \"" + ep.getHost() + "\" failed to resolve; trying again");
//                            Thread.sleep(1000);
//                            continue dnsRetry;
//                        } else
//                            throw new BackendException(Reason.DNS_RESOLUTION_FAILURE, ep.getHost());
//                    }
//                }
//                break;
//            }
//
//            // Build config
//            final String goConfig = config.toWgUserspaceString();
//
//            // Create the vpn tunnel with android API
//            final VpnService.Builder builder = service.getBuilder();
//            builder.setSession(tunnel.getName());
//
//            for (final String excludedApplication : config.getInterface().getExcludedApplications())
//                builder.addDisallowedApplication(excludedApplication);
//
//            for (final String includedApplication : config.getInterface().getIncludedApplications())
//                builder.addAllowedApplication(includedApplication);
//
//            for (final InetNetwork addr : config.getInterface().getAddresses())
//                builder.addAddress(addr.getAddress(), addr.getMask());
//
//            for (final InetAddress addr : config.getInterface().getDnsServers())
//                builder.addDnsServer(addr.getHostAddress());
//
//            for (final String dnsSearchDomain : config.getInterface().getDnsSearchDomains())
//                builder.addSearchDomain(dnsSearchDomain);
//
//            boolean sawDefaultRoute = false;
//            for (final Peer peer : config.getPeers()) {
//                for (final InetNetwork addr : peer.getAllowedIps()) {
//                    if (addr.getMask() == 0)
//                        sawDefaultRoute = true;
//                    builder.addRoute(addr.getAddress(), addr.getMask());
//                }
//            }
//
//            // "Kill-switch" semantics
//            if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
//                builder.allowFamily(OsConstants.AF_INET);
//                builder.allowFamily(OsConstants.AF_INET6);
//            }
//
//            builder.setMtu(config.getInterface().getMtu().orElse(1280));
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
//                builder.setMetered(false);
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
//                service.setUnderlyingNetworks(null);
//
//            builder.setBlocking(true);
//            try (final ParcelFileDescriptor tun = builder.establish()) {
//                if (tun == null)
//                    throw new BackendException(Reason.TUN_CREATION_ERROR);
//                Log.d(TAG, "Go backend " + wgVersion());
//                currentTunnelHandle = wgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
//            }
//            if (currentTunnelHandle < 0)
//                throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);
//
//            currentTunnel = tunnel;
//            currentConfig = config;
//
//            service.protect(wgGetSocketV4(currentTunnelHandle));
//            service.protect(wgGetSocketV6(currentTunnelHandle));
//        } else {
//            if (currentTunnelHandle == -1) {
//                Log.w(TAG, "Tunnel already down");
//                return;
//            }
//            int handleToClose = currentTunnelHandle;
//            currentTunnel = null;
//            currentTunnelHandle = -1;
//            currentConfig = null;
//            wgTurnOff(handleToClose);
//            try {
//                vpnService.get(0, TimeUnit.NANOSECONDS).stopSelf();
//            } catch (final TimeoutException ignored) { }
//        }
//
//        tunnel.onStateChange(state);
//    }

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
                    owner.currentTunnel = null;
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
