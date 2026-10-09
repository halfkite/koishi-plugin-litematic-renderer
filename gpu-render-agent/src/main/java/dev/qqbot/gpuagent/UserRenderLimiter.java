package dev.qqbot.gpuagent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Sliding window shared by a user's conversations within one bot account. */
final class UserRenderLimiter {
    record Decision(boolean allowed, long retryAfterSeconds) {}
    private record Key(String account, String user) {}
    private final Map<Key, ArrayDeque<Long>> calls = new HashMap<>();
    private final LongSupplier clock;
    private int windowSeconds;
    private int maximum;

    UserRenderLimiter() { this(System::nanoTime); }
    UserRenderLimiter(LongSupplier clock) { this.clock = clock; }

    synchronized Decision acquire(String account, String user, boolean enabled, int seconds, int files) {
        if (!enabled) {
            calls.clear();
            return new Decision(true, 0);
        }
        if (seconds < 1 || files < 1) throw new IllegalArgumentException("Invalid render rate limit");
        if (windowSeconds != seconds || maximum != files) {
            calls.clear();
            windowSeconds = seconds;
            maximum = files;
        }
        long now = clock.getAsLong();
        long window = seconds * 1_000_000_000L;
        calls.values().removeIf(queue -> {
            while (!queue.isEmpty() && now - queue.peekFirst() >= window) queue.removeFirst();
            return queue.isEmpty();
        });
        Key key = new Key(account, user);
        ArrayDeque<Long> queue = calls.get(key);
        if (queue != null && queue.size() >= files) {
            long remaining = window - (now - queue.peekFirst());
            return new Decision(false, Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
        }
        // Do not evict active quotas to make room for new users.
        if (queue == null && calls.size() >= 10_000) return new Decision(false, seconds);
        calls.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(now);
        return new Decision(true, 0);
    }
}
