package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ProcessMemorySamplerTest {
    @Test
    void parsesLinuxResidentMemoryAsBytes() {
        String status = "Name:\tjava\nVmPeak:\t400000 kB\nVmRSS:\t12345 kB\n";
        assertEquals(12_641_280L, ProcessMemorySampler.parseLinuxStatus(status));
        assertEquals(-1, ProcessMemorySampler.parseLinuxStatus("Name:\tjava\n"));
    }

    @Test
    void parsesOnlyRequestedProcessIdsAndRejectsMalformedRows() {
        var result = ProcessMemorySampler.parseProcessOutput("123:4096\n456:bad\n789:2048\n", Set.of(123L, 789L));
        assertEquals(4096L, result.get(123L));
        assertFalse(result.containsKey(456L));
        assertEquals(2048L, result.get(789L));
    }
}
