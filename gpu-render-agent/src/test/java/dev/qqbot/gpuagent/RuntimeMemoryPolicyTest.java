package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeMemoryPolicyTest {
    @Test
    void rearmsOnlyAfterMemoryRecoversInsteadOfWaitingForATimeCooldown() {
        RuntimeMemoryPolicy policy = new RuntimeMemoryPolicy();
        assertFalse(policy.shouldRecycle(1_000, 1_000, true));
        assertTrue(policy.shouldRecycle(1_001, 1_000, true));
        assertFalse(policy.shouldRecycle(1_500, 1_000, true));
        assertFalse(policy.shouldRecycle(700, 1_000, false));
        assertFalse(policy.shouldRecycle(700, 1_000, true));
        assertTrue(policy.shouldRecycle(1_001, 1_000, true));
    }

    @Test
    void disabledThresholdDoesNotTriggerAndResetsTheLatch() {
        RuntimeMemoryPolicy policy = new RuntimeMemoryPolicy();
        assertTrue(policy.shouldRecycle(101, 100, true));
        assertFalse(policy.shouldRecycle(101, 0, true));
        assertTrue(policy.shouldRecycle(101, 100, true));
    }
}
