/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.Nullable;

/**
 * Polls for network settings changes from the Go backend and applies them to the VPN service.
 *
 * Uses a HandlerThread with THREAD_PRIORITY_FOREGROUND to ensure the polling continues
 * reliably while the VPN service is running. The VPN service itself is a foreground service,
 * which provides protection against most Android background restrictions.
 */
public class NetworkSettingsPoller {
    private static final String TAG = "NetworkSettingsPoller";
    private static final long POLL_INTERVAL_MS = 500;
    private static final int MAX_CONSECUTIVE_ERRORS = 10;

    private final GoBackend goBackend;
    private final AtomicLong lastSettingsVersion = new AtomicLong(0);
    private final AtomicBoolean isPolling = new AtomicBoolean(false);
    private final Object lock = new Object();

    @Nullable private HandlerThread handlerThread;
    @Nullable private Handler handler;
    @Nullable private NetworkSettingsCallback callback;
    private int consecutiveErrors = 0;

    /**
     * Callback interface for network settings changes.
     */
    public interface NetworkSettingsCallback {
        /**
         * Called when network settings have been updated.
         * @param settings The new network settings
         * @return ParcelFileDescriptor for the new tunnel, or null if rebuild not needed
         */
        @Nullable ParcelFileDescriptor onNetworkSettingsUpdated(NetworkSettings settings);
    }

    /**
     * Create a new NetworkSettingsPoller.
     * @param goBackend The GoBackend instance to poll
     */
    public NetworkSettingsPoller(GoBackend goBackend) {
        this.goBackend = goBackend;
    }

    /**
     * Set the callback for network settings changes.
     * @param callback The callback to invoke when settings change
     */
    public void setCallback(@Nullable NetworkSettingsCallback callback) {
        this.callback = callback;
    }

    /**
     * Start polling for network settings changes.
     * Uses a HandlerThread with foreground priority for reliable execution.
     */
    public void startPolling() {
        synchronized (lock) {
            if (isPolling.get()) {
                Log.w(TAG, "Polling already started");
                return;
            }

            Log.d(TAG, "Starting network settings polling");
            lastSettingsVersion.set(0);
            consecutiveErrors = 0;

            // Create a HandlerThread with foreground priority
            // This helps ensure the thread gets CPU time while VPN is active
            handlerThread = new HandlerThread("NetworkSettingsPoller", Process.THREAD_PRIORITY_FOREGROUND);
            handlerThread.start();

            handler = new Handler(handlerThread.getLooper());
            isPolling.set(true);

            // Schedule the first poll
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    /**
     * Stop polling for network settings changes.
     */
    public void stopPolling() {
        synchronized (lock) {
            if (!isPolling.getAndSet(false)) {
                return;
            }

            Log.d(TAG, "Stopping network settings polling");

            if (handler != null) {
                handler.removeCallbacks(pollRunnable);
                handler = null;
            }

            if (handlerThread != null) {
                handlerThread.quitSafely();
                try {
                    handlerThread.join(1000); // Wait up to 1 second for thread to finish
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting for handler thread to finish");
                }
                handlerThread = null;
            }

            lastSettingsVersion.set(0);
            consecutiveErrors = 0;
        }
    }

    /**
     * Check if polling is currently active.
     * @return true if polling is active
     */
    public boolean isPolling() {
        return isPolling.get();
    }

    /**
     * Shutdown the poller and release resources.
     */
    public void shutdown() {
        stopPolling();
    }

    /**
     * Runnable that performs the polling and reschedules itself.
     */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling.get()) {
                return;
            }

            try {
                checkForSettingsUpdate();
                consecutiveErrors = 0; // Reset on success
            } catch (Exception e) {
                consecutiveErrors++;
                Log.e(TAG, "Error in poll runnable (attempt " + consecutiveErrors + ")", e);

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.e(TAG, "Too many consecutive errors, stopping poller");
                    // Post to main thread to avoid deadlock
                    new Handler(Looper.getMainLooper()).post(() -> stopPolling());
                    return;
                }
            }

            // Reschedule for the next poll if still running
            synchronized (lock) {
                if (isPolling.get() && handler != null) {
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        }
    };

    private void checkForSettingsUpdate() {
        try {
            long currentVersion = goBackend.getNetworkSettingsVersionNumber();
            long lastVersion = lastSettingsVersion.get();

            if (currentVersion > lastVersion) {
                Log.d(TAG, "Network settings version changed: " + lastVersion + " -> " + currentVersion);
                lastSettingsVersion.set(currentVersion);

                String settingsJson = goBackend.getNetworkSettingsJSON();
                if (settingsJson != null && !settingsJson.isEmpty() && !settingsJson.equals("{}")) {
                    try {
                        NetworkSettings settings = NetworkSettings.fromJson(settingsJson);
                        if (callback != null) {
                            callback.onNetworkSettingsUpdated(settings);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse network settings JSON", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking for settings update", e);
        }
    }

    /**
     * Apply network settings to a VPN service builder.
     * @param builder The VPN service builder
     * @param settings The network settings to apply
     * @param tunnelName The name of the tunnel for the session
     */
    public static void applySettingsToBuilder(VpnService.Builder builder, NetworkSettings settings, String tunnelName) {
        builder.setSession(tunnelName);

        // Set MTU
        Integer mtu = settings.getMtu();
        if (mtu != null && mtu > 0) {
            builder.setMtu(mtu);
        } else {
            builder.setMtu(1280); // Default MTU
        }

        builder.allowFamily(OsConstants.AF_INET);
        builder.allowFamily(OsConstants.AF_INET6);

        // Add IPv4 addresses
        List<String> ipv4Addresses = settings.getIpv4Addresses();
        List<String> ipv4SubnetMasks = settings.getIpv4SubnetMasks();
        if (ipv4Addresses != null && ipv4SubnetMasks != null) {
            for (int i = 0; i < ipv4Addresses.size(); i++) {
                try {
                    String address = ipv4Addresses.get(i);
                    String mask = i < ipv4SubnetMasks.size() ? ipv4SubnetMasks.get(i) : "255.255.255.255";
                    int prefixLength = subnetMaskToPrefixLength(mask);
                    builder.addAddress(address, prefixLength);
                    Log.d(TAG, "Added IPv4 address: " + address + "/" + prefixLength);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add IPv4 address", e);
                }
            }
        }

        // Add IPv6 addresses
        List<String> ipv6Addresses = settings.getIpv6Addresses();
        List<String> ipv6NetworkPrefixes = settings.getIpv6NetworkPrefixes();
        if (ipv6Addresses != null && ipv6NetworkPrefixes != null) {
            for (int i = 0; i < ipv6Addresses.size(); i++) {
                try {
                    String address = ipv6Addresses.get(i);
                    int prefixLength = 128;
                    if (i < ipv6NetworkPrefixes.size()) {
                        try {
                            prefixLength = Integer.parseInt(ipv6NetworkPrefixes.get(i));
                        } catch (NumberFormatException ignored) {}
                    }
                    builder.addAddress(address, prefixLength);
                    Log.d(TAG, "Added IPv6 address: " + address + "/" + prefixLength);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add IPv6 address", e);
                }
            }
        }

        // Add DNS servers
        List<String> dnsServers = settings.getDnsServers();
        if (dnsServers != null) {
        	boolean hasValidDns = false;
            for (String dns : dnsServers) {
                try {
                    builder.addDnsServer(dns);
                    hasValidDns = true;
                    Log.d(TAG, "Added DNS server: " + dns);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add DNS server: " + dns, e);
                }
            }
            if (hasValidDns) { // we added at least one dns server
	            // Add a search domain that matches everything to ensure all DNS queries go into the tunnel
	            builder.addSearchDomain(".");
            }
        }

        // Add IPv4 included routes
        boolean sawDefaultRoute = false;
        List<IPv4Route> ipv4IncludedRoutes = settings.getIpv4IncludedRoutes();
        if (ipv4IncludedRoutes != null) {
            for (IPv4Route route : ipv4IncludedRoutes) {
                try {
                    String destinationAddress = route.getDestinationAddress();
                    if (destinationAddress != null) {
                        int prefixLength = route.getPrefixLength();
                        if (route.isDefault() || prefixLength == 0) {
                            sawDefaultRoute = true;
                        }
                        builder.addRoute(destinationAddress, prefixLength);
                        Log.d(TAG, "Added IPv4 route: " + destinationAddress + "/" + prefixLength);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add IPv4 route", e);
                }
            }
        }

        // Add IPv6 included routes
        List<IPv6Route> ipv6IncludedRoutes = settings.getIpv6IncludedRoutes();
        if (ipv6IncludedRoutes != null) {
            for (IPv6Route route : ipv6IncludedRoutes) {
                try {
                    String destinationAddress = route.getDestinationAddress();
                    if (destinationAddress != null) {
                        int prefixLength = route.getNetworkPrefixLength() > 0 ? route.getNetworkPrefixLength() : 128;
                        if (route.isDefault() || prefixLength == 0) {
                            sawDefaultRoute = true;
                        }
                        builder.addRoute(destinationAddress, prefixLength);
                        Log.d(TAG, "Added IPv6 route: " + destinationAddress + "/" + prefixLength);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add IPv6 route", e);
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        builder.setBlocking(true);
    }

    /**
     * Convert a subnet mask string to a prefix length.
     * @param subnetMask The subnet mask (e.g., "255.255.255.0")
     * @return The prefix length (e.g., 24)
     */
    public static int subnetMaskToPrefixLength(String subnetMask) {
        if (subnetMask == null || subnetMask.isEmpty()) {
            return 32;
        }
        try {
            InetAddress addr = InetAddress.getByName(subnetMask);
            byte[] bytes = addr.getAddress();
            int prefixLength = 0;
            for (byte b : bytes) {
                int unsigned = b & 0xFF;
                while (unsigned != 0) {
                    prefixLength += (unsigned & 1);
                    unsigned >>>= 1;
                }
            }
            return prefixLength;
        } catch (UnknownHostException e) {
            Log.e(TAG, "Invalid subnet mask: " + subnetMask, e);
            return 32;
        }
    }
}
