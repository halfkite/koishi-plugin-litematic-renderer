package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderTaskQueueTest {
    @Test
    void queuedWorkStaysQueuedUntilDrainCompletes() throws Exception {
        RenderTaskQueue<String> queue = new RenderTaskQueue<>();
        assertEquals(RenderTaskQueue.OfferResult.ACCEPTED, queue.offer("pending"));
        queue.startDraining();
        CountDownLatch drained = new CountDownLatch(1);
        queue.setDrainCompleteListener(drained::countDown);
        assertTrue(drained.await(2, TimeUnit.SECONDS));
        assertEquals(1, queue.size());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(queue::take);
            assertFalse(waiting.isDone());
            queue.resume();
            assertEquals("pending", waiting.get(2, TimeUnit.SECONDS));
            assertEquals(1, queue.inFlightCount());
            queue.completeTask();
            assertEquals(0, queue.size());
        }
    }

    @Test
    void drainCallbackWaitsForAlreadyClaimedTasks() throws Exception {
        RenderTaskQueue<String> queue = new RenderTaskQueue<>();
        queue.offer("active");
        queue.offer("pending");
        assertEquals("active", queue.take());
        queue.startDraining();
        CountDownLatch drained = new CountDownLatch(1);
        queue.setDrainCompleteListener(drained::countDown);
        assertFalse(drained.await(50, TimeUnit.MILLISECONDS));
        queue.completeTask();
        assertTrue(drained.await(2, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        queue.resume();
        assertEquals("pending", queue.take());
        queue.completeTask();
    }

    @Test
    void rejectsSubmissionsWhileDrainingAndWakesWaitersOnClose() throws Exception {
        RenderTaskQueue<String> queue = new RenderTaskQueue<>();
        queue.startDraining();
        assertEquals(RenderTaskQueue.OfferResult.DRAINING, queue.offer("not accepted"));
        queue.close();
        assertNull(queue.take());
        assertEquals(RenderTaskQueue.OfferResult.CLOSED, queue.offer("closed"));
    }
}
