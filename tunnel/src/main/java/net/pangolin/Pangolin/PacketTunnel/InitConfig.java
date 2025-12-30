/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.Nullable;

/**
 * Configuration for initializing OLM via the Go API.
 * This class matches the InitOlmConfig structure in the Go API.
 */
public class InitConfig {
    private final boolean enableAPI;
    private final String socketPath;
    private final String logLevel;
    private final String version;
    private final String agent;

    private InitConfig(Builder builder) {
        this.enableAPI = builder.enableAPI;
        this.socketPath = builder.socketPath;
        this.logLevel = builder.logLevel;
        this.version = builder.version;
        this.agent = builder.agent;
    }

    /**
     * Convert this config to a JSON string for the Go API.
     *
     * @return JSON string representation of this config
     * @throws JSONException If JSON serialization fails
     */
    public String toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("enableAPI", enableAPI);
        json.put("socketPath", socketPath);
        json.put("logLevel", logLevel);
        json.put("version", version);
        json.put("agent", agent);
        return json.toString();
    }

    public boolean isEnableAPI() {
        return enableAPI;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public String getVersion() {
        return version;
    }

    public String getAgent() {
        return agent;
    }

    @Override
    public String toString() {
        return "InitConfig{" +
                "enableAPI=" + enableAPI +
                ", socketPath='" + socketPath + '\'' +
                ", logLevel='" + logLevel + '\'' +
                ", version='" + version + '\'' +
                ", agent='" + agent + '\'' +
                '}';
    }

    /**
     * Builder class for InitConfig.
     */
    public static class Builder {
        private boolean enableAPI = true;
        private String socketPath = "";
        private String logLevel = "info";
        private String version = "";
        private String agent = "android";

        public Builder setEnableAPI(boolean enableAPI) {
            this.enableAPI = enableAPI;
            return this;
        }

        public Builder setSocketPath(String socketPath) {
            this.socketPath = socketPath != null ? socketPath : "";
            return this;
        }

        public Builder setLogLevel(String logLevel) {
            this.logLevel = logLevel != null ? logLevel : "info";
            return this;
        }

        public Builder setVersion(String version) {
            this.version = version != null ? version : "";
            return this;
        }

        public Builder setAgent(String agent) {
            this.agent = agent != null ? agent : "android";
            return this;
        }

        public InitConfig build() {
            return new InitConfig(this);
        }
    }
}