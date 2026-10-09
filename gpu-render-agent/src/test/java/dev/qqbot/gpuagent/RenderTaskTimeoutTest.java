package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class RenderTaskTimeoutTest {
    @Test void timeoutInterruptsExecutionAndDoesNotPoisonNextTask() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            RenderTaskTimeout guard = new RenderTaskTimeout(timer, Duration.ofMillis(50));
            try {
                assertThrows(InterruptedException.class, () -> Thread.sleep(5000));
                assertTrue(guard.expired());
                assertEquals("TIMEOUT", assertThrows(RenderService.RenderFailure.class, guard::remaining).code);
            } finally { guard.close(); }
            assertFalse(Thread.currentThread().isInterrupted());
            try (var next = new RenderTaskTimeout(timer, Duration.ofSeconds(5))) {
                Thread.sleep(75);
                next.check();
                assertFalse(next.expired());
            }
        }
    }

    @Test void closingTimerCannotInterruptLaterWork() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var guard = new RenderTaskTimeout(timer, Duration.ofMillis(50));
            guard.close();
            Thread.sleep(100);
            assertFalse(guard.expired());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
}
