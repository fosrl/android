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
 * Configuration for starting a tunnel via the Go API.
 * This class matches the StartTunnelConfig structure in the Go API.
 */
public class TunnelConfig {
    private final String endpoint;
    private final String id;
    private final String secret;
    private final int mtu;
    private final String dns;
    private final boolean holepunch;
    private final int pingIntervalSeconds;
    private final int pingTimeoutSeconds;
    @Nullable private final String userToken;
    @Nullable private final String orgId;
    private final List<String> upstreamDNS;
    private final boolean overrideDNS;
    private final boolean tunnelDNS;

    private TunnelConfig(Builder builder) {
        this.endpoint = builder.endpoint;
        this.id = builder.id;
        this.secret = builder.secret;
        this.mtu = builder.mtu;
        this.dns = builder.dns;
        this.holepunch = builder.holepunch;
        this.pingIntervalSeconds = builder.pingIntervalSeconds;
        this.pingTimeoutSeconds = builder.pingTimeoutSeconds;
        this.userToken = builder.userToken;
        this.orgId = builder.orgId;
        this.upstreamDNS = builder.upstreamDNS;
        this.overrideDNS = builder.overrideDNS;
        this.tunnelDNS = builder.tunnelDNS;
    }

    /**
     * Convert this config to a JSON string for the Go API.
     *
     * @return JSON string representation of this config
     * @throws JSONException If JSON serialization fails
     */
    public String toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("endpoint", endpoint);
        json.put("id", id);
        json.put("secret", secret);
        json.put("mtu", mtu);
        json.put("dns", dns);
        json.put("holepunch", holepunch);
        json.put("pingIntervalSeconds", pingIntervalSeconds);
        json.put("pingTimeoutSeconds", pingTimeoutSeconds);
        
        if (userToken != null) {
            json.put("userToken", userToken);
        }
        
        if (orgId != null) {
            json.put("orgId", orgId);
        }
        
        JSONArray upstreamDNSArray = new JSONArray();
        for (String dns : upstreamDNS) {
            upstreamDNSArray.put(dns);
        }
        json.put("upstreamDNS", upstreamDNSArray);
        
        json.put("overrideDNS", overrideDNS);
        json.put("tunnelDNS", tunnelDNS);
        
        return json.toString();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getId() {
        return id;
    }

    public String getSecret() {
        return secret;
    }

    public int getMtu() {
        return mtu;
    }

    public String getDns() {
        return dns;
    }

    public boolean isHolepunch() {
        return holepunch;
    }

    public int getPingIntervalSeconds() {
        return pingIntervalSeconds;
    }

    public int getPingTimeoutSeconds() {
        return pingTimeoutSeconds;
    }

    @Nullable
    public String getUserToken() {
        return userToken;
    }

    @Nullable
    public String getOrgId() {
        return orgId;
    }

    public List<String> getUpstreamDNS() {
        return upstreamDNS;
    }

    public boolean isOverrideDNS() {
        return overrideDNS;
    }

    public boolean isTunnelDNS() {
        return tunnelDNS;
    }

    @Override
    public String toString() {
        return "TunnelConfig{" +
                "endpoint='" + endpoint + '\'' +
                ", id='" + id + '\'' +
                ", secret='[REDACTED]'" +
                ", mtu=" + mtu +
                ", dns='" + dns + '\'' +
                ", holepunch=" + holepunch +
                ", pingIntervalSeconds=" + pingIntervalSeconds +
                ", pingTimeoutSeconds=" + pingTimeoutSeconds +
                ", userToken='" + (userToken != null ? "[REDACTED]" : "null") + '\'' +
                ", orgId='" + orgId + '\'' +
                ", upstreamDNS=" + upstreamDNS +
                ", overrideDNS=" + overrideDNS +
                ", tunnelDNS=" + tunnelDNS +
                '}';
    }

    /**
     * Builder class for TunnelConfig.
     */
    public static class Builder {
        private String endpoint = "";
        private String id = "";
        private String secret = "";
        private int mtu = 1280;
        private String dns = "";
        private boolean holepunch = false;
        private int pingIntervalSeconds = 10;
        private int pingTimeoutSeconds = 30;
        @Nullable private String userToken;
        @Nullable private String orgId;
        private List<String> upstreamDNS = new ArrayList<>();
        private boolean overrideDNS = false;
        private boolean tunnelDNS = false;

        public Builder setEndpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder setId(String id) {
            this.id = id;
            return this;
        }

        public Builder setSecret(String secret) {
            this.secret = secret;
            return this;
        }

        public Builder setMtu(int mtu) {
            this.mtu = mtu;
            return this;
        }

        public Builder setDns(String dns) {
            this.dns = dns;
            return this;
        }

        public Builder setHolepunch(boolean holepunch) {
            this.holepunch = holepunch;
            return this;
        }

        public Builder setPingIntervalSeconds(int pingIntervalSeconds) {
            this.pingIntervalSeconds = pingIntervalSeconds;
            return this;
        }

        public Builder setPingTimeoutSeconds(int pingTimeoutSeconds) {
            this.pingTimeoutSeconds = pingTimeoutSeconds;
            return this;
        }

        public Builder setUserToken(@Nullable String userToken) {
            this.userToken = userToken;
            return this;
        }

        public Builder setOrgId(@Nullable String orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder setUpstreamDNS(List<String> upstreamDNS) {
            this.upstreamDNS = upstreamDNS != null ? upstreamDNS : new ArrayList<>();
            return this;
        }

        public Builder addUpstreamDNS(String dns) {
            this.upstreamDNS.add(dns);
            return this;
        }

        public Builder setOverrideDNS(boolean overrideDNS) {
            this.overrideDNS = overrideDNS;
            return this;
        }

        public Builder setTunnelDNS(boolean tunnelDNS) {
            this.tunnelDNS = tunnelDNS;
            return this;
        }

        public TunnelConfig build() {
            return new TunnelConfig(this);
        }
    }
}