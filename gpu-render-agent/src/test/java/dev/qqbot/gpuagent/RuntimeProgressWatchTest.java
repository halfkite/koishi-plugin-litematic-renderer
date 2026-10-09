package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProgressWatchTest {
    @Test
    void freshHeartbeatsWithoutProgressStillTimeOut() {
        var watch = new RuntimeProgressWatch(100, 0);
        assertFalse(watch.stalled(status("building", 0.02, 0), 0));
        assertFalse(watch.stalled(status("building", 0.02, 99), 99));
        assertTrue(watch.stalled(status("building", 0.02, 100), 100));
    }

    @Test
    void advancingProgressResetsTheDeadlineButRegressingProgressDoesNot() {
        var watch = new RuntimeProgressWatch(100, 0);
        assertFalse(watch.stalled(status("building", 0.02, 0), 0));
        assertFalse(watch.stalled(status("building", 0.2, 90), 90));
        assertFalse(watch.stalled(status("building", 0.1, 150), 150));
        assertTrue(watch.stalled(status("building", 0.1, 190), 190));
    }

    @Test
    void aNewStageGetsItsOwnDeadline() {
        var watch = new RuntimeProgressWatch(100, 0);
        assertFalse(watch.stalled(status("building", 1, 0), 0));
        assertFalse(watch.stalled(status("rendering", 0, 90), 90));
        assertFalse(watch.stalled(status("rendering", 0, 189), 189));
        assertTrue(watch.stalled(status("rendering", 0, 190), 190));
    }

    @Test
    void missingStatusDoesNotDisableTheWatchdog() {
        var watch = new RuntimeProgressWatch(100, 0);
        assertFalse(watch.stalled(null, 99));
        assertTrue(watch.stalled(null, 100));
    }

    private static RenderModels.RuntimeStatus status(String stage, double progress, long timestamp) {
        return new RenderModels.RuntimeStatus(timestamp, false, true, true,
                "test", "26.3", "test", 4096, "", progress, stage);
    }
}
