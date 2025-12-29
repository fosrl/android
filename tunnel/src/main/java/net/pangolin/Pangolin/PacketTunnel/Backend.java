/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pangolin.Pangolin.PacketTunnel;

import java.util.Set;

/**
 * Interface for implementations of the WireGuard secure network tunnel.
 */

public interface Backend {
    /**
     * Enumerate names of currently-running tunnels.
     *
     * @return The set of running tunnel names.
     */
    Set<String> getRunningTunnelNames();

    /**
     * Get the state of a tunnel.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return The state of the tunnel.
     * @throws Exception Exception raised when retrieving tunnel's state.
     */
    Tunnel.State getState(Tunnel tunnel) throws Exception;


    /**
     * Determines whether the service is running in always-on VPN mode.
     * In this mode the system ensures that the service is always running by restarting it when necessary,
     * e.g. after reboot.
     *
     * @return A boolean indicating whether the service is running in always-on VPN mode.
     * @throws Exception Exception raised while retrieving the always-on status.
     */

    boolean isAlwaysOn() throws Exception;

    /**
     * Determines whether the service is running in always-on VPN lockdown mode.
     * In this mode the system ensures that the service is always running and that the apps
     * aren't allowed to bypass the VPN.
     *
     * @return A boolean indicating whether the service is running in always-on VPN lockdown mode.
     * @throws Exception Exception raised while retrieving the lockdown status.
     */

    boolean isLockdownEnabled() throws Exception;
}
