/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;

/**
 * Represents the network configuration for the tunnel.
 * This class parses the JSON structure returned by the Go API.
 */
public class NetworkSettings {
    @Nullable private final String tunnelRemoteAddress;
    @Nullable private final Integer mtu;
    private final List<String> dnsServers;
    private final List<String> ipv4Addresses;
    private final List<String> ipv4SubnetMasks;
    private final List<IPv4Route> ipv4IncludedRoutes;
    private final List<IPv4Route> ipv4ExcludedRoutes;
    private final List<String> ipv6Addresses;
    private final List<String> ipv6NetworkPrefixes;
    private final List<IPv6Route> ipv6IncludedRoutes;
    private final List<IPv6Route> ipv6ExcludedRoutes;

    private NetworkSettings(Builder builder) {
        this.tunnelRemoteAddress = builder.tunnelRemoteAddress;
        this.mtu = builder.mtu;
        this.dnsServers = builder.dnsServers;
        this.ipv4Addresses = builder.ipv4Addresses;
        this.ipv4SubnetMasks = builder.ipv4SubnetMasks;
        this.ipv4IncludedRoutes = builder.ipv4IncludedRoutes;
        this.ipv4ExcludedRoutes = builder.ipv4ExcludedRoutes;
        this.ipv6Addresses = builder.ipv6Addresses;
        this.ipv6NetworkPrefixes = builder.ipv6NetworkPrefixes;
        this.ipv6IncludedRoutes = builder.ipv6IncludedRoutes;
        this.ipv6ExcludedRoutes = builder.ipv6ExcludedRoutes;
    }

    /**
     * Parse NetworkSettings from a JSON string.
     *
     * @param jsonString The JSON string to parse
     * @return A NetworkSettings object
     * @throws JSONException If the JSON is malformed
     */
    public static NetworkSettings fromJson(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        Builder builder = new Builder();

        if (json.has("tunnel_remote_address")) {
            builder.setTunnelRemoteAddress(json.getString("tunnel_remote_address"));
        }

        if (json.has("mtu") && !json.isNull("mtu")) {
            builder.setMtu(json.getInt("mtu"));
        }

        if (json.has("dns_servers")) {
            builder.setDnsServers(parseStringArray(json.getJSONArray("dns_servers")));
        }

        if (json.has("ipv4_addresses")) {
            builder.setIpv4Addresses(parseStringArray(json.getJSONArray("ipv4_addresses")));
        }

        if (json.has("ipv4_subnet_masks")) {
            builder.setIpv4SubnetMasks(parseStringArray(json.getJSONArray("ipv4_subnet_masks")));
        }

        if (json.has("ipv4_included_routes")) {
            builder.setIpv4IncludedRoutes(parseIPv4Routes(json.getJSONArray("ipv4_included_routes")));
        }

        if (json.has("ipv4_excluded_routes")) {
            builder.setIpv4ExcludedRoutes(parseIPv4Routes(json.getJSONArray("ipv4_excluded_routes")));
        }

        if (json.has("ipv6_addresses")) {
            builder.setIpv6Addresses(parseStringArray(json.getJSONArray("ipv6_addresses")));
        }

        if (json.has("ipv6_network_prefixes")) {
            builder.setIpv6NetworkPrefixes(parseStringArray(json.getJSONArray("ipv6_network_prefixes")));
        }

        if (json.has("ipv6_included_routes")) {
            builder.setIpv6IncludedRoutes(parseIPv6Routes(json.getJSONArray("ipv6_included_routes")));
        }

        if (json.has("ipv6_excluded_routes")) {
            builder.setIpv6ExcludedRoutes(parseIPv6Routes(json.getJSONArray("ipv6_excluded_routes")));
        }

        return builder.build();
    }

    private static List<String> parseStringArray(JSONArray jsonArray) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            result.add(jsonArray.getString(i));
        }
        return result;
    }

    private static List<IPv4Route> parseIPv4Routes(JSONArray jsonArray) throws JSONException {
        List<IPv4Route> routes = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            routes.add(IPv4Route.fromJson(jsonArray.getJSONObject(i)));
        }
        return routes;
    }

    private static List<IPv6Route> parseIPv6Routes(JSONArray jsonArray) throws JSONException {
        List<IPv6Route> routes = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            routes.add(IPv6Route.fromJson(jsonArray.getJSONObject(i)));
        }
        return routes;
    }

    @Nullable
    public String getTunnelRemoteAddress() {
        return tunnelRemoteAddress;
    }

    @Nullable
    public Integer getMtu() {
        return mtu;
    }

    public List<String> getDnsServers() {
        return dnsServers;
    }

    public List<String> getIpv4Addresses() {
        return ipv4Addresses;
    }

    public List<String> getIpv4SubnetMasks() {
        return ipv4SubnetMasks;
    }

    public List<IPv4Route> getIpv4IncludedRoutes() {
        return ipv4IncludedRoutes;
    }

    public List<IPv4Route> getIpv4ExcludedRoutes() {
        return ipv4ExcludedRoutes;
    }

    public List<String> getIpv6Addresses() {
        return ipv6Addresses;
    }

    public List<String> getIpv6NetworkPrefixes() {
        return ipv6NetworkPrefixes;
    }

    public List<IPv6Route> getIpv6IncludedRoutes() {
        return ipv6IncludedRoutes;
    }

    public List<IPv6Route> getIpv6ExcludedRoutes() {
        return ipv6ExcludedRoutes;
    }

    @Override
    public String toString() {
        return "NetworkSettings{" +
                "tunnelRemoteAddress='" + tunnelRemoteAddress + '\'' +
                ", mtu=" + mtu +
                ", dnsServers=" + dnsServers +
                ", ipv4Addresses=" + ipv4Addresses +
                ", ipv4SubnetMasks=" + ipv4SubnetMasks +
                ", ipv4IncludedRoutes=" + ipv4IncludedRoutes +
                ", ipv4ExcludedRoutes=" + ipv4ExcludedRoutes +
                ", ipv6Addresses=" + ipv6Addresses +
                ", ipv6NetworkPrefixes=" + ipv6NetworkPrefixes +
                ", ipv6IncludedRoutes=" + ipv6IncludedRoutes +
                ", ipv6ExcludedRoutes=" + ipv6ExcludedRoutes +
                '}';
    }

    /**
     * Builder class for NetworkSettings.
     */
    public static class Builder {
        @Nullable private String tunnelRemoteAddress;
        @Nullable private Integer mtu;
        private List<String> dnsServers = new ArrayList<>();
        private List<String> ipv4Addresses = new ArrayList<>();
        private List<String> ipv4SubnetMasks = new ArrayList<>();
        private List<IPv4Route> ipv4IncludedRoutes = new ArrayList<>();
        private List<IPv4Route> ipv4ExcludedRoutes = new ArrayList<>();
        private List<String> ipv6Addresses = new ArrayList<>();
        private List<String> ipv6NetworkPrefixes = new ArrayList<>();
        private List<IPv6Route> ipv6IncludedRoutes = new ArrayList<>();
        private List<IPv6Route> ipv6ExcludedRoutes = new ArrayList<>();

        public Builder setTunnelRemoteAddress(@Nullable String tunnelRemoteAddress) {
            this.tunnelRemoteAddress = tunnelRemoteAddress;
            return this;
        }

        public Builder setMtu(@Nullable Integer mtu) {
            this.mtu = mtu;
            return this;
        }

        public Builder setDnsServers(List<String> dnsServers) {
            this.dnsServers = dnsServers;
            return this;
        }

        public Builder setIpv4Addresses(List<String> ipv4Addresses) {
            this.ipv4Addresses = ipv4Addresses;
            return this;
        }

        public Builder setIpv4SubnetMasks(List<String> ipv4SubnetMasks) {
            this.ipv4SubnetMasks = ipv4SubnetMasks;
            return this;
        }

        public Builder setIpv4IncludedRoutes(List<IPv4Route> ipv4IncludedRoutes) {
            this.ipv4IncludedRoutes = ipv4IncludedRoutes;
            return this;
        }

        public Builder setIpv4ExcludedRoutes(List<IPv4Route> ipv4ExcludedRoutes) {
            this.ipv4ExcludedRoutes = ipv4ExcludedRoutes;
            return this;
        }

        public Builder setIpv6Addresses(List<String> ipv6Addresses) {
            this.ipv6Addresses = ipv6Addresses;
            return this;
        }

        public Builder setIpv6NetworkPrefixes(List<String> ipv6NetworkPrefixes) {
            this.ipv6NetworkPrefixes = ipv6NetworkPrefixes;
            return this;
        }

        public Builder setIpv6IncludedRoutes(List<IPv6Route> ipv6IncludedRoutes) {
            this.ipv6IncludedRoutes = ipv6IncludedRoutes;
            return this;
        }

        public Builder setIpv6ExcludedRoutes(List<IPv6Route> ipv6ExcludedRoutes) {
            this.ipv6ExcludedRoutes = ipv6ExcludedRoutes;
            return this;
        }

        public NetworkSettings build() {
            return new NetworkSettings(this);
        }
    }
}