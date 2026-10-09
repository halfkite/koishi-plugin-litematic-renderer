package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class UserRenderLimiterTest {
    @Test void slidingWindowRejectsOnlyExcessFilesAndRoundsWaitUp() {
        AtomicLong now = new AtomicLong();
        var limiter = new UserRenderLimiter(now::get);
        for (int i = 0; i < 3; i++) assertTrue(limiter.acquire("bot", "user", true, 60, 3).allowed());
        assertEquals(new UserRenderLimiter.Decision(false, 60), limiter.acquire("bot", "user", true, 60, 3));
        now.set(59_500_000_000L);
        assertEquals(1, limiter.acquire("bot", "user", true, 60, 3).retryAfterSeconds());
        now.set(60_000_000_000L);
        assertTrue(limiter.acquire("bot", "user", true, 60, 3).allowed());
    }

    @Test void usersAndAccountsAreIndependentAndChangesApplyImmediately() {
        var limiter = new UserRenderLimiter(() -> 0);
        assertTrue(limiter.acquire("a", "u", true, 60, 1).allowed());
        assertFalse(limiter.acquire("a", "u", true, 60, 1).allowed());
        assertTrue(limiter.acquire("a", "v", true, 60, 1).allowed());
        assertTrue(limiter.acquire("b", "u", true, 60, 1).allowed());
        assertTrue(limiter.acquire("a", "u", false, 60, 1).allowed());
        assertTrue(limiter.acquire("a", "u", true, 60, 1).allowed());
        assertTrue(limiter.acquire("a", "u", true, 30, 2).allowed());
        assertTrue(limiter.acquire("a", "u", true, 30, 2).allowed());
        assertFalse(limiter.acquire("a", "u", true, 30, 2).allowed());
    }

    @Test void parallelFileEventsCannotExceedQuota() throws Exception {
        var limiter = new UserRenderLimiter(() -> 0);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++) jobs.add(executor.submit(() -> {
                if (limiter.acquire("a", "u", true, 60, 3).allowed()) accepted.incrementAndGet();
            }));
            for (var job : jobs) job.get();
        }
        assertEquals(3, accepted.get());
    }
}
