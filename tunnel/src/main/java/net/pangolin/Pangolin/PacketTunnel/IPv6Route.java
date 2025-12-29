/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents an IPv6 route configuration.
 */
public class IPv6Route {
    private final String destinationAddress;
    private final int networkPrefixLength;
    private final String gatewayAddress;
    private final boolean isDefault;

    public IPv6Route(String destinationAddress, int networkPrefixLength, String gatewayAddress, boolean isDefault) {
        this.destinationAddress = destinationAddress;
        this.networkPrefixLength = networkPrefixLength;
        this.gatewayAddress = gatewayAddress;
        this.isDefault = isDefault;
    }

    /**
     * Parse an IPv6Route from a JSONObject.
     *
     * @param json The JSON object containing route data
     * @return A new IPv6Route instance
     * @throws JSONException If required fields are missing
     */
    public static IPv6Route fromJson(JSONObject json) throws JSONException {
        String destinationAddress = json.getString("destination_address");
        int networkPrefixLength = json.optInt("network_prefix_length", 0);
        String gatewayAddress = json.optString("gateway_address", null);
        boolean isDefault = json.optBoolean("is_default", false);
        
        return new IPv6Route(destinationAddress, networkPrefixLength, gatewayAddress, isDefault);
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public int getNetworkPrefixLength() {
        return networkPrefixLength;
    }

    public String getGatewayAddress() {
        return gatewayAddress;
    }

    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public String toString() {
        return "IPv6Route{" +
                "destinationAddress='" + destinationAddress + '\'' +
                ", networkPrefixLength=" + networkPrefixLength +
                ", gatewayAddress='" + gatewayAddress + '\'' +
                ", isDefault=" + isDefault +
                '}';
    }
}