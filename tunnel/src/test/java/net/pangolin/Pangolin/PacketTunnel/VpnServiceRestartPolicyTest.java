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
    public void explicitManualActionIsNotAPlatformStart() {
        assertFalse(VpnServiceRestartPolicy.isSystemStart(
                VpnServiceRestartPolicy.ACTION_MANUAL_START));
    }

    @Test
    public void onlySystemActionOrStickyRestartIsAPlatformStart() {
        assertTrue(VpnServiceRestartPolicy.isSystemStart(null));
        assertTrue(VpnServiceRestartPolicy.isSystemStart("android.net.VpnService"));
        assertFalse(VpnServiceRestartPolicy.isSystemStart("other"));
    }

    @Test
    public void systemStartBootstrapsBeforeAndroidReportsEstablishedOwnership() {
        assertTrue(VpnServiceRestartPolicy.resolveStartOwnership(
                false, "android.net.VpnService", false, false));
        assertTrue(VpnServiceRestartPolicy.resolveStartOwnership(
                false, null, false, false));
    }

    @Test
    public void manualStartDoesNotAcquireOwnershipWithoutPlatformEvidence() {
        assertFalse(VpnServiceRestartPolicy.resolveStartOwnership(
                false, VpnServiceRestartPolicy.ACTION_MANUAL_START, false, false));
        assertFalse(VpnServiceRestartPolicy.resolveStartOwnership(
                false, "other", false, false));
        assertTrue(VpnServiceRestartPolicy.resolveStartOwnership(
                false, VpnServiceRestartPolicy.ACTION_MANUAL_START, true, true));
    }

    @Test
    public void bootstrapLatchSurvivesManualRetryUntilPlatformStateIsKnown() {
        assertTrue(VpnServiceRestartPolicy.resolveStartOwnership(
                true, VpnServiceRestartPolicy.ACTION_MANUAL_START, false, false));
        assertFalse(VpnServiceRestartPolicy.resolveStartOwnership(
                true, VpnServiceRestartPolicy.ACTION_MANUAL_START, true, false));
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
