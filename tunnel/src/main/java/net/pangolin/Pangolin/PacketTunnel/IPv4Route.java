/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.Nullable;

/**
 * Represents an IPv4 route configuration.
 */
public final class IPv4Route {
    private final String destinationAddress;
    @Nullable private final String subnetMask;
    @Nullable private final String gatewayAddress;
    private final boolean isDefault;

    public IPv4Route(String destinationAddress, @Nullable String subnetMask, 
                     @Nullable String gatewayAddress, boolean isDefault) {
        this.destinationAddress = destinationAddress;
        this.subnetMask = subnetMask;
        this.gatewayAddress = gatewayAddress;
        this.isDefault = isDefault;
    }

    /**
     * Parse an IPv4Route from a JSON object.
     *
     * @param json The JSON object to parse
     * @return The parsed IPv4Route
     * @throws JSONException If required fields are missing
     */
    public static IPv4Route fromJson(JSONObject json) throws JSONException {
        String destinationAddress = json.getString("destination_address");
        String subnetMask = json.optString("subnet_mask", null);
        String gatewayAddress = json.optString("gateway_address", null);
        boolean isDefault = json.optBoolean("is_default", false);
        
        return new IPv4Route(destinationAddress, subnetMask, gatewayAddress, isDefault);
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    @Nullable
    public String getSubnetMask() {
        return subnetMask;
    }

    @Nullable
    public String getGatewayAddress() {
        return gatewayAddress;
    }

    public boolean isDefault() {
        return isDefault;
    }

    /**
     * Convert subnet mask string to prefix length.
     * For example, "255.255.255.0" returns 24.
     *
     * @return The prefix length, or 32 if subnet mask is null or invalid
     */
    public int getPrefixLength() {
        if (subnetMask == null || subnetMask.isEmpty()) {
            return 32;
        }
        
        try {
            String[] parts = subnetMask.split("\\.");
            if (parts.length != 4) {
                return 32;
            }
            
            int prefix = 0;
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                while (octet > 0) {
                    prefix += (octet & 1);
                    octet >>= 1;
                }
            }
            return prefix;
        } catch (NumberFormatException e) {
            return 32;
        }
    }

    @Override
    public String toString() {
        return "IPv4Route{" +
                "destinationAddress='" + destinationAddress + '\'' +
                ", subnetMask='" + subnetMask + '\'' +
                ", gatewayAddress='" + gatewayAddress + '\'' +
                ", isDefault=" + isDefault +
                '}';
    }
}