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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;

/**
 * Observes the DNS servers assigned to the device's real, underlying network (Wi-Fi,
 * cellular, ethernet) so they can be reported to olm as its UpstreamDNS/PublicDNS.
 *
 * Unlike Linux/macOS/Windows, olm cannot read Android's DNS configuration itself, so this
 * class does the platform-native detection on its behalf, explicitly excluding the VPN's
 * own network to avoid ever reporting olm's own DNS override back to itself.
 *
 * Several non-VPN networks are often up at once (e.g. Wi-Fi plus cellular kept alive by
 * "mobile data always active", which many devices enable by default). Only the one Android
 * would actually use as the default network is reported: a carrier's DNS server is usually
 * unreachable from Wi-Fi and vice versa, so reporting the wrong network breaks every DNS
 * query that olm forwards upstream.
 */
public class SystemDnsMonitor {
    private static final String TAG = "SystemDnsMonitor";

    private final ConnectivityManager connectivityManager;
    private final Object lock = new Object();
    // State of every matching non-VPN network, keyed by network. Guarded by lock.
    private final Map<Network, NetworkCapabilities> capabilities = new HashMap<>();
    private final Map<Network, LinkProperties> linkProperties = new HashMap<>();
    // API 31+: the network Android itself ranks best for the request. Guarded by lock.
    @Nullable private Network bestNetwork;
    @Nullable private List<String> lastReported;
    @Nullable private volatile String strictPrivateDnsServerName;

    @Nullable private SystemDnsCallback callback;
    @Nullable private ConnectivityManager.NetworkCallback networkCallback;
    @Nullable private HandlerThread callbackThread;

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
     * Returns the hostname configured for Android's Private DNS when it is in strict
     * ("Private DNS provider hostname") mode, or null otherwise. In strict mode Android
     * sends every lookup to that provider over DNS-over-TLS, including lookups on the VPN
     * network, so olm's DNS proxy never sees them and Pangolin aliases cannot resolve.
     */
    @Nullable
    public String getStrictPrivateDnsServerName() {
        return strictPrivateDnsServerName;
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
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // The "active" network is our own VPN; there's no synchronous way to reach
            // the underlying network here. The NetworkCallback started by start() will
            // report it directly once it observes it.
            return new ArrayList<>();
        }
        LinkProperties lp = connectivityManager.getLinkProperties(network);
        updatePrivateDnsState(lp);
        return formatDnsServers(lp);
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

        synchronized (lock) {
            capabilities.clear();
            linkProperties.clear();
            bestNetwork = null;
            lastReported = null;
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();

        final boolean useBestMatching = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                synchronized (lock) {
                    if (useBestMatching) {
                        // The best network changed; state for the previous one is stale.
                        bestNetwork = network;
                        capabilities.keySet().retainAll(Collections.singleton(network));
                        linkProperties.keySet().retainAll(Collections.singleton(network));
                    }
                }
                reevaluate();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                synchronized (lock) {
                    capabilities.put(network, caps);
                }
                reevaluate();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                synchronized (lock) {
                    linkProperties.put(network, lp);
                }
                reevaluate();
            }

            @Override
            public void onLost(Network network) {
                synchronized (lock) {
                    capabilities.remove(network);
                    linkProperties.remove(network);
                    if (network.equals(bestNetwork)) {
                        bestNetwork = null;
                    }
                }
                reevaluate();
            }
        };

        // A dedicated thread keeps callbacks (and the JNI call they trigger) off the
        // shared ConnectivityThread and in a defined order.
        callbackThread = new HandlerThread("SystemDnsMonitor");
        callbackThread.start();
        Handler handler = new Handler(callbackThread.getLooper());

        if (useBestMatching) {
            // Tracks exactly the network Android would pick as the default for a non-VPN
            // app, including switches between Wi-Fi and cellular.
            connectivityManager.registerBestMatchingNetworkCallback(request, networkCallback, handler);
        } else {
            connectivityManager.registerNetworkCallback(request, networkCallback, handler);
        }
        Log.d(TAG, "Started system DNS monitoring (bestMatching=" + useBestMatching + ")");
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
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
        }
        synchronized (lock) {
            capabilities.clear();
            linkProperties.clear();
            bestNetwork = null;
            lastReported = null;
        }
        Log.d(TAG, "Stopped system DNS monitoring");
    }

    private void reevaluate() {
        final List<String> servers;
        synchronized (lock) {
            Network selected = selectNetwork();
            if (selected == null) {
                return;
            }
            LinkProperties lp = linkProperties.get(selected);
            if (lp == null) {
                return;
            }
            updatePrivateDnsState(lp);
            servers = formatDnsServers(lp);
            if (servers.isEmpty() || servers.equals(lastReported)) {
                return;
            }
            lastReported = servers;
        }
        report(servers);
    }

    /**
     * Chooses the network whose DNS servers should be reported. Must hold lock.
     */
    @Nullable
    private Network selectNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return bestNetwork;
        }
        // Before API 31 there is no public way to ask which non-VPN network the system
        // would choose, so approximate its ranking: validated networks first, then
        // unmetered transports (Wi-Fi/ethernet) over cellular.
        Network best = null;
        int bestScore = -1;
        for (Map.Entry<Network, LinkProperties> entry : linkProperties.entrySet()) {
            NetworkCapabilities caps = capabilities.get(entry.getKey());
            int score = scoreNetwork(caps);
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        return best;
    }

    static int scoreNetwork(@Nullable NetworkCapabilities caps) {
        if (caps == null) {
            return 0;
        }
        int score = 1;
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            score += 4;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            score += 2;
        }
        return score;
    }

    private void updatePrivateDnsState(@Nullable LinkProperties lp) {
        if (lp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        String name = lp.isPrivateDnsActive() ? lp.getPrivateDnsServerName() : null;
        if (name != null && !name.equals(strictPrivateDnsServerName)) {
            Log.w(TAG, "Android Private DNS is in strict mode (" + name + "); "
                    + "DNS queries will bypass the tunnel's DNS server and aliases will not resolve");
        }
        strictPrivateDnsServerName = name;
    }

    private void report(List<String> dnsServers) {
        SystemDnsCallback cb = callback;
        if (dnsServers.isEmpty() || cb == null) {
            return;
        }
        Log.d(TAG, "System DNS changed: " + dnsServers);
        cb.onSystemDnsChanged(dnsServers);
    }

    /**
     * Formats a network's DNS servers for olm, IPv4 first, then global IPv6. olm only
     * tries the first two upstream servers, so the most reliably reachable ones must come
     * first. Link-local IPv6 servers (common from router RDNSS) are only usable with their
     * interface zone, so they are used (with the zone) only when nothing else is known.
     */
    private List<String> formatDnsServers(@Nullable LinkProperties lp) {
        List<String> ipv4 = new ArrayList<>();
        List<String> ipv6 = new ArrayList<>();
        List<String> linkLocal = new ArrayList<>();
        if (lp == null) {
            return ipv4;
        }
        String interfaceName = lp.getInterfaceName();
        for (InetAddress address : lp.getDnsServers()) {
            if (address instanceof Inet4Address) {
                ipv4.add(address.getHostAddress() + ":53");
            } else if (address instanceof Inet6Address) {
                String host = stripZone(address.getHostAddress());
                if (address.isLinkLocalAddress()) {
                    if (interfaceName != null && !interfaceName.isEmpty()) {
                        linkLocal.add("[" + host + "%" + interfaceName + "]:53");
                    }
                    // Without an interface a link-local server is unreachable; drop it.
                } else {
                    ipv6.add("[" + host + "]:53");
                }
            }
        }
        List<String> result = new ArrayList<>(ipv4.size() + ipv6.size() + linkLocal.size());
        result.addAll(ipv4);
        result.addAll(ipv6);
        if (result.isEmpty()) {
            result.addAll(linkLocal);
        }
        return result;
    }

    private static String stripZone(String host) {
        int scopeIndex = host.indexOf('%');
        return scopeIndex >= 0 ? host.substring(0, scopeIndex) : host;
    }
}
