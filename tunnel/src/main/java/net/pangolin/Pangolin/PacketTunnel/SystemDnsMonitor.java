/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;

/**
 * Observes the DNS servers assigned to the device's real, underlying network (Wi-Fi,
 * cellular, ethernet) so they can be reported to olm as its UpstreamDNS/PublicDNS.
 *
 * Unlike Linux/macOS/Windows, olm cannot read Android's DNS configuration itself, so this
 * class does the platform-native detection on its behalf, explicitly excluding the VPN's
 * own network to avoid ever reporting olm's own DNS override back to itself.
 */
public class SystemDnsMonitor {
    private static final String TAG = "SystemDnsMonitor";

    private final ConnectivityManager connectivityManager;
    @Nullable private SystemDnsCallback callback;
    @Nullable private ConnectivityManager.NetworkCallback networkCallback;

    /**
     * Callback interface for system DNS changes.
     */
    public interface SystemDnsCallback {
        /**
         * Called when the underlying network's DNS servers have changed.
         * @param dnsServers The new DNS servers, formatted as "host:53" (or "[host]:53" for IPv6).
         */
        void onSystemDnsChanged(List<String> dnsServers);
    }

    public SystemDnsMonitor(Context context) {
        this.connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Set the callback for system DNS changes.
     * @param callback The callback to invoke when the underlying network's DNS changes.
     */
    public void setCallback(@Nullable SystemDnsCallback callback) {
        this.callback = callback;
    }

    /**
     * Returns the DNS servers of the current default network, for a synchronous,
     * best-effort read (e.g. to seed the initial tunnel config before the live
     * NetworkCallback below has had a chance to fire). Returns an empty list if the
     * active network is the VPN itself, or if no DNS servers are known yet.
     */
    public List<String> getCurrentDnsServers() {
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return new ArrayList<>();
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // The "active" network is our own VPN; there's no synchronous way to reach
            // the underlying network here. The NetworkCallback started by start() will
            // report it directly once it observes it.
            return new ArrayList<>();
        }
        return formatDnsServers(connectivityManager.getLinkProperties(network));
    }

    /**
     * Starts observing the DNS servers of the device's underlying physical network.
     * The network request explicitly excludes the VPN transport so this never observes
     * olm's own DNS override on the tunnel interface.
     */
    public void start() {
        if (networkCallback != null) {
            Log.w(TAG, "Already monitoring, ignoring start request");
            return;
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                report(formatDnsServers(linkProperties));
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
        Log.d(TAG, "Started system DNS monitoring");
    }

    /**
     * Stops observing DNS servers and releases the underlying network callback.
     */
    public void stop() {
        if (networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException e) {
            // Callback was already unregistered (e.g. concurrent teardown); ignore.
        }
        networkCallback = null;
        Log.d(TAG, "Stopped system DNS monitoring");
    }

    private void report(List<String> dnsServers) {
        if (dnsServers.isEmpty() || callback == null) {
            return;
        }
        Log.d(TAG, "System DNS changed: " + dnsServers);
        callback.onSystemDnsChanged(dnsServers);
    }

    private List<String> formatDnsServers(@Nullable LinkProperties linkProperties) {
        List<String> result = new ArrayList<>();
        if (linkProperties == null) {
            return result;
        }
        for (InetAddress address : linkProperties.getDnsServers()) {
            result.add(formatAddress(address));
        }
        return result;
    }

    private String formatAddress(InetAddress address) {
        String host = address.getHostAddress();
        if (address instanceof Inet6Address) {
            int scopeIndex = host.indexOf('%');
            if (scopeIndex >= 0) {
                host = host.substring(0, scopeIndex);
            }
            return "[" + host + "]:53";
        }
        return host + ":53";
    }
}
