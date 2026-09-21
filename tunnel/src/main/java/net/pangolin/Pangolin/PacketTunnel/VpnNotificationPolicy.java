package net.pangolin.Pangolin.PacketTunnel;

/** Keep bootstrap/recovery foreground; a ready VPN is also held by Android's binding. */
final class VpnNotificationPolicy {
    private VpnNotificationPolicy() {}

    static boolean needsForegroundNotification(
            final boolean persistentEnabled, final boolean connected, final boolean systemBound) {
        return persistentEnabled || !connected || !systemBound;
    }
}
