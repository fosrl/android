package net.pangolin.Pangolin.PacketTunnel;

/** Pure restart and ownership decisions used by the Android VPN service lifecycle. */
final class VpnServiceRestartPolicy {
    static final String ACTION_MANUAL_START =
            "net.pangolin.Pangolin.PacketTunnel.action.MANUAL_START";

    private VpnServiceRestartPolicy() {}

    static boolean shouldRestartAfterProcessDeath(final boolean alwaysOnOwned) {
        return alwaysOnOwned;
    }

    static boolean isSystemStart(final String action) {
        // Android starts Always-On with SERVICE_INTERFACE, or a null intent when
        // recreating our sticky service. All app-initiated starts have an explicit action.
        return action == null || "android.net.VpnService".equals(action);
    }

    static boolean resolveStartOwnership(
            final boolean previouslyOwned,
            final String action,
            final boolean platformStateKnown,
            final boolean platformAlwaysOn) {
        return isSystemStart(action) || resolveOwnership(
                previouslyOwned, platformStateKnown, platformAlwaysOn, false);
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
