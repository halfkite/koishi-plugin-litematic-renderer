package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class RenderServiceTimeoutTest {
    @TempDir Path root;

    @Test void timedOutLockWaitReleasesQueueBudgetAndNextTaskStillSucceeds() throws Exception {
        var config = new AgentConfig();
        config.cacheImageMinProjectionBytes = 0;
        var cache = new CacheStore(root, config, ignored -> {});
        byte[] bytes = "test-schematic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var view = new RenderModels.View("test", "test", 135, 36, 1.0, true, 4, 4, "#000000", false, 1);
        var first = new RenderModels.Request(2, "first", "test.litematic", List.of(view), null, Main.VERSION);
        var second = new RenderModels.Request(2, "second", "test.litematic", List.of(view), null, Main.VERSION);
        Path image = root.resolve("test.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        cache.save(first, List.of(new RenderModels.Image("test", "test.png", 4, 4, image)), bytes,
                null, "test", Main.VERSION, cache.resourcePackFingerprint(), 0, "test");
        try (var renderer = new RenderService(root, config)) {
            var lockType = Class.forName("dev.qqbot.gpuagent.RenderService$CacheLock");
            var constructor = lockType.getDeclaredConstructor(); constructor.setAccessible(true);
            Object cacheLock = constructor.newInstance();
            var lockField = lockType.getDeclaredField("lock"); lockField.setAccessible(true);
            var lock = (ReentrantLock) lockField.get(cacheLock);
            var locksField = RenderService.class.getDeclaredField("cacheLocks"); locksField.setAccessible(true);
            @SuppressWarnings("unchecked") var locks = (Map<String, Object>) locksField.get(renderer);
            var finished = new CountDownLatch(2);
            var history = new java.util.concurrent.CopyOnWriteArrayList<String>();
            renderer.setHistoryListener(entry -> { history.add(entry.status()); finished.countDown(); });
            lock.lock();
            try {
                locks.put(cache.hash(bytes), cacheLock);
                var timeout = renderer.submit(first, bytes, Duration.ofMillis(100));
                var next = renderer.submit(second, bytes, Duration.ofSeconds(5));
                var error = assertThrows(ExecutionException.class, () -> timeout.get(5, TimeUnit.SECONDS));
                assertEquals("TIMEOUT", ((RenderService.RenderFailure) error.getCause()).code);
                try (var result = next.get(5, TimeUnit.SECONDS)) { assertTrue(result.cacheHit()); }
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("超时取消", "成功"), history);
                assertEquals(0, renderer.queueLength());
                assertEquals(0, renderer.retainedRequestBytes());
            } finally { lock.unlock(); }
        }
    }
}
