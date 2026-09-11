package net.pangolin.Pangolin.PacketTunnel;

/** Pure restart and ownership decisions used by the Android VPN service lifecycle. */
final class VpnServiceRestartPolicy {
    static final String ACTION_MANUAL_START =
            "net.pangolin.Pangolin.PacketTunnel.action.MANUAL_START";

    private VpnServiceRestartPolicy() {}

    static boolean shouldRestartAfterProcessDeath(final boolean alwaysOnOwned) {
        return alwaysOnOwned;
    }

    static boolean isSystemStartOnLegacy(final String action) {
        return !ACTION_MANUAL_START.equals(action);
    }

    static boolean resolveOwnership(
            final boolean previouslyOwned,
            final boolean platformStateKnown,
            final boolean platformAlwaysOn,
            final boolean ownershipRevoked) {
        if (ownershipRevoked)
            return false;
        return platformStateKnown ? platformAlwaysOn : previouslyOwned;
    }
}
