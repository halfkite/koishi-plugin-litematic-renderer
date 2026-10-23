package dev.qqbot.gpuagent;

final class RuntimeMemoryPolicy {
    private static final int RECOVERY_MARGIN_PERCENT = 25;
    private boolean thresholdLatched;

    synchronized boolean shouldRecycle(long usedBytes, long thresholdBytes, boolean runtimeAlive) {
        if (thresholdBytes <= 0 || usedBytes < 0) {
            if (thresholdBytes <= 0) thresholdLatched = false;
            return false;
        }
        long recoveryThreshold = thresholdBytes - thresholdBytes / 100 * RECOVERY_MARGIN_PERCENT;
        if (runtimeAlive && usedBytes < recoveryThreshold) thresholdLatched = false;
        if (usedBytes <= thresholdBytes || thresholdLatched) return false;
        thresholdLatched = true;
        return true;
    }
}
