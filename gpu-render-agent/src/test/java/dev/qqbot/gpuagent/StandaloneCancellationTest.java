package dev.qqbot.gpuagent;

import dev.qqbot.standalone.PreviewEngine;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneCancellationTest {
    @Test void fallbackLoadHonorsWorkerInterruptionBeforeReadingFiles() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PreviewEngine.load(Path.of("missing.litematic"), Path.of("missing.jar"), java.util.List.of()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
