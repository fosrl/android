package net.pangolin.Pangolin.PacketTunnel;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpnNotificationPolicyTest {
    @Test
    public void quietModeOnlyRemovesNotificationForReadySystemBoundVpn() {
        assertFalse(VpnNotificationPolicy.needsForegroundNotification(false, true, true));
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(false, false, true));
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(false, true, false));
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(false, false, false));
    }

    @Test
    public void optingInKeepsNotificationRegardlessOfReadinessOrBinding() {
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(true, true, true));
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(true, false, true));
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(true, true, false));
        assertTrue(VpnNotificationPolicy.needsForegroundNotification(true, false, false));
    }
}
