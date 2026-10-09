package dev.qqbot.gpuagent;

import java.util.Objects;

/** A fresh heartbeat is not evidence that a render task is making progress. */
final class RuntimeProgressWatch {
    private final long timeoutNanos;
    private long lastAdvanceNanos;
    private String stage;
    private double progress = -1;

    RuntimeProgressWatch(long timeoutNanos, long startedNanos) {
        this.timeoutNanos = timeoutNanos;
        this.lastAdvanceNanos = startedNanos;
    }

    boolean stalled(RenderModels.RuntimeStatus status, long nowNanos) {
        if (status != null) {
            double nextProgress = Double.isFinite(status.progress()) ? status.progress() : -1;
            if (!Objects.equals(stage, status.stage())) {
                stage = status.stage();
                progress = nextProgress;
                lastAdvanceNanos = nowNanos;
            } else if (nextProgress > progress + 0.000001) {
                progress = nextProgress;
                lastAdvanceNanos = nowNanos;
            }
        }
        return nowNanos - lastAdvanceNanos >= timeoutNanos;
    }
}
