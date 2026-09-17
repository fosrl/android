package net.pangolin.Pangolin.PacketTunnel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkSettingsApplyTrackerTest {
    @Test
    public void failedApplyLeavesVersionPendingForRetry() {
        NetworkSettingsApplyTracker tracker = new NetworkSettingsApplyTracker();

        assertTrue(tracker.shouldApply(1));
        tracker.recordFailure(1);

        assertTrue(tracker.shouldApply(1));
    }

    @Test
    public void successfulApplyAcknowledgesVersion() {
        NetworkSettingsApplyTracker tracker = new NetworkSettingsApplyTracker();
        long generation = tracker.getGeneration();

        tracker.recordSuccess(generation, 3);

        assertFalse(tracker.shouldApply(3));
        assertFalse(tracker.shouldApply(2));
        assertTrue(tracker.shouldApply(4));
    }

    @Test
    public void resetMakesVersionsPendingAgain() {
        NetworkSettingsApplyTracker tracker = new NetworkSettingsApplyTracker();
        long generation = tracker.getGeneration();
        tracker.recordSuccess(generation, 5);

        tracker.reset();

        assertTrue(tracker.shouldApply(1));
    }

    @Test
    public void staleApplyCannotAcknowledgeNewPollingSession() {
        NetworkSettingsApplyTracker tracker = new NetworkSettingsApplyTracker();
        long oldGeneration = tracker.getGeneration();
        tracker.reset();

        assertFalse(tracker.recordSuccess(oldGeneration, 7));
        assertTrue(tracker.shouldApply(7));
    }
}
