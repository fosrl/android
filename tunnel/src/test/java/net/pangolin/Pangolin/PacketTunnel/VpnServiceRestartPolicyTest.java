package net.pangolin.Pangolin.PacketTunnel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VpnServiceRestartPolicyTest {
    @Test
    public void manualVpnServiceIsNotSticky() {
        assertFalse(VpnServiceRestartPolicy.shouldRestartAfterProcessDeath(false));
    }

    @Test
    public void alwaysOnVpnServiceIsSticky() {
        assertTrue(VpnServiceRestartPolicy.shouldRestartAfterProcessDeath(true));
    }

    @Test
    public void explicitManualActionIsNotAPlatformStartOnLegacyAndroid() {
        assertFalse(VpnServiceRestartPolicy.isSystemStartOnLegacy(
                VpnServiceRestartPolicy.ACTION_MANUAL_START));
    }

    @Test
    public void missingOrDifferentActionIsAPlatformStartOnLegacyAndroid() {
        assertTrue(VpnServiceRestartPolicy.isSystemStartOnLegacy(null));
        assertTrue(VpnServiceRestartPolicy.isSystemStartOnLegacy("other"));
    }

    @Test
    public void livePlatformStateAdoptsAlwaysOnOwnershipDuringTeardown() {
        assertTrue(VpnServiceRestartPolicy.resolveOwnership(false, true, true, false));
    }

    @Test
    public void livePlatformStateCanReleasePreviousOwnership() {
        assertFalse(VpnServiceRestartPolicy.resolveOwnership(true, true, false, false));
    }

    @Test
    public void unknownPlatformStatePreservesPreviousOwnership() {
        assertTrue(VpnServiceRestartPolicy.resolveOwnership(true, false, false, false));
        assertFalse(VpnServiceRestartPolicy.resolveOwnership(false, false, true, false));
    }

    @Test
    public void explicitRevocationWinsOverStalePlatformOwnership() {
        assertFalse(VpnServiceRestartPolicy.resolveOwnership(true, true, true, true));
    }
}
