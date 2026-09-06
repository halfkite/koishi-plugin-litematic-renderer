package dev.qqbot.gpuagent;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存看门狗：定期统计「工具进程 + 全部 Minecraft 渲染客户端」的物理内存占用。
 * 超过阈值后进入排空状态（不再领取新任务），手头任务全部完成后只重启 Minecraft 渲染端释放内存。
 * 10 分钟内重启超过 3 次则自动禁用 30 分钟，防止重启循环。
 */
final class MemoryWatchdog {
    private static final long CHECK_INTERVAL_SECONDS = 10;
    private static final int RESTART_LIMIT = 3;
    private static final long RESTART_WINDOW_MILLIS = 10 * 60_000L;
    private static final long RESTART_COOLDOWN_MILLIS = 30 * 60_000L;

    private final AgentConfig config;
    private final RenderService renderer;
    private final RuntimeManager runtime;
    private final java.util.function.Consumer<String> log;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "memory-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean restarting = new AtomicBoolean();
    private final List<Long> restartTimes = new ArrayList<>();
    private volatile long lastReportedTotalBytes;
    private volatile long autoRestartDisabledUntil;

    MemoryWatchdog(AgentConfig config, RenderService renderer, RuntimeManager runtime, Path dataRoot, java.util.function.Consumer<String> log) {
        this.config = config;
        this.renderer = renderer;
        this.runtime = runtime;
        this.log = log;
    }

    void start() {
        if (closed.get()) return;
        if (config.memoryRestartThresholdBytes <= 0) {
            log.accept("内存自动重启已关闭（阈值 = 0）");
            return;
        }
        renderer.setDrainCompleteListener(this::restartProgram);
        scheduler.scheduleWithFixedDelay(this::check, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.accept("内存看门狗已启动：阈值 " + formatBytes(config.memoryRestartThresholdBytes) + "，超限将在手头任务完成后重启 Minecraft GPU 运行时");
    }

    long lastReportedTotalBytes() { return lastReportedTotalBytes; }

    void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            renderer.setDrainCompleteListener(null);
        }
    }

    private void check() {
        try {
            if (closed.get() || config.memoryRestartThresholdBytes <= 0 || renderer.isDraining()) return;
            List<Long> pids = new ArrayList<>();
            pids.add(ProcessHandle.current().pid());
            pids.addAll(runtime.runningProcessPids());
            long total = totalWorkingSetBytes(pids);
            if (total < 0) return;
            lastReportedTotalBytes = total;
            if (total <= config.memoryRestartThresholdBytes) return;
            if (System.currentTimeMillis() < autoRestartDisabledUntil) {
                log.accept("内存占用 " + formatBytes(total) + " 已超过阈值，但自动重启处于冷却期，请手动重启程序");
                return;
            }
            log.accept("内存占用 " + formatBytes(total) + " 超过阈值 " + formatBytes(config.memoryRestartThresholdBytes)
                    + "：停止领取新任务，等待手头 " + runningCountText() + "完成后重启 Minecraft GPU 运行时");
            renderer.startDraining();
        } catch (Throwable error) {
            log.accept("内存监控异常：" + error.getMessage());
        }
    }

    private String runningCountText() {
        int running = renderer.runningTaskCount();
        return running > 0 ? running + " 个任务" : "任务";
    }

    /** 用 PowerShell 读取一组进程的物理内存（WorkingSet64）总和；失败返回 -1 表示本轮跳过。 */
    private static long totalWorkingSetBytes(List<Long> pids) {
        try {
            StringBuilder ids = new StringBuilder();
            for (Long pid : pids) ids.append(pid).append(',');
            ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + ids.substring(0, ids.length() - 1) + " -ErrorAction SilentlyContinue | "
                            + "Measure-Object WorkingSet64 -Sum).Sum");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            process.waitFor(15, TimeUnit.SECONDS);
            if (output == null) return -1;
            output = output.trim().replaceAll("[^0-9]", "");
            if (output.isEmpty()) return -1;
            return Long.parseLong(output);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 手头任务完成后的运行时重启：不重启 Agent，不丢弃排队任务。 */
    private void restartProgram() {
        if (closed.get() || !restarting.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            synchronized (restartTimes) {
                restartTimes.removeIf(timestamp -> now - timestamp > RESTART_WINDOW_MILLIS);
                if (restartTimes.size() >= RESTART_LIMIT) {
                    autoRestartDisabledUntil = now + RESTART_COOLDOWN_MILLIS;
                }
            }
            if (now < autoRestartDisabledUntil) {
                log.accept("检测到短时间内多次内存重启，已暂停自动重启 30 分钟，请检查内存占用异常原因");
                renderer.restartRuntimeAfterDrain();
                return;
            }
            synchronized (restartTimes) {
                restartTimes.add(now);
            }
            renderer.restartRuntimeAfterDrain();
        } catch (Throwable error) {
            log.accept("Minecraft GPU 运行时自动重启失败：" + error + "，将恢复队列；请检查运行时日志");
            try {
                renderer.restartRuntimeAfterDrain();
            } catch (Throwable ignored) {
                log.accept("恢复渲染队列失败，请手动重启 Agent");
            }
        } finally {
            restarting.set(false);
            // drainCompleteListener 是一次性回调；重新挂载后，下一次内存回收仍能触发运行时重启。
            if (!closed.get()) renderer.setDrainCompleteListener(this::restartProgram);
        }
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
