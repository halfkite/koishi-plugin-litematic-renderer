package dev.qqbot.gpuagent;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Closing the guard prevents a late timer from interrupting the next queued task. */
final class RenderTaskTimeout implements AutoCloseable {
    private final Thread worker;
    private final long started = System.nanoTime();
    private final Duration timeout;
    private final ScheduledFuture<?> timer;
    private boolean closed;
    private boolean expired;

    RenderTaskTimeout(ScheduledExecutorService scheduler, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Render timeout must be positive");
        this.worker = Thread.currentThread();
        this.timeout = timeout;
        timer = scheduler.schedule(this::expire, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private synchronized void expire() {
        if (closed) return;
        expired = true;
        worker.interrupt();
    }

    synchronized boolean expired() {
        return expired || !closed && System.nanoTime() - started >= timeout.toNanos();
    }

    Duration remaining() throws RenderService.RenderFailure {
        check();
        return Duration.ofNanos(Math.max(1, timeout.toNanos() - (System.nanoTime() - started)));
    }

    void check() throws RenderService.RenderFailure {
        if (expired()) throw failure();
    }

    RenderService.RenderFailure failure() {
        return new RenderService.RenderFailure("TIMEOUT", "渲染超过 " + timeout.toMillis() / 1000.0
                + " 秒，任务已取消，客户端将回收，后续排队任务继续执行");
    }

    @Override public synchronized void close() {
        if (closed) return;
        boolean interruptedByTimer = expired;
        expired = expired();
        closed = true;
        timer.cancel(false);
        if (interruptedByTimer && Thread.currentThread() == worker) Thread.interrupted();
    }
}
