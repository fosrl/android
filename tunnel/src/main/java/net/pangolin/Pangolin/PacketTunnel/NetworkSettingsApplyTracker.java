package net.pangolin.Pangolin.PacketTunnel;

/**
 * Tracks the newest network-settings version that was actually applied to Android's VPN.
 * A failed attempt deliberately leaves the version pending so the poller retries it.
 */
final class NetworkSettingsApplyTracker {
    private long lastAppliedVersion;
    private long generation;

    synchronized boolean shouldApply(long version) {
        return version > lastAppliedVersion;
    }

    synchronized boolean recordSuccess(long attemptGeneration, long version) {
        if (attemptGeneration != generation) {
            return false;
        }
        if (version > lastAppliedVersion) {
            lastAppliedVersion = version;
        }
        return true;
    }

    synchronized void recordFailure(long version) {
        // Intentionally do not acknowledge the version.
    }

    synchronized long getLastAppliedVersion() {
        return lastAppliedVersion;
    }

    synchronized long reset() {
        lastAppliedVersion = 0;
        return ++generation;
    }

    synchronized long getGeneration() {
        return generation;
    }
}
