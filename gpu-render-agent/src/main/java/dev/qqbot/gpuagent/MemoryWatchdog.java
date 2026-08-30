package dev.qqbot.gpuagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 内存看门狗：定期统计「工具进程 + 全部 Minecraft 渲染客户端」的物理内存占用。
 * 超过阈值后进入排空状态（不再领取新任务），手头任务全部完成后自动重启程序释放内存。
 * 10 分钟内重启超过 3 次则自动禁用 30 分钟，防止重启循环。
 */
final class MemoryWatchdog {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long CHECK_INTERVAL_SECONDS = 10;
    private static final int RESTART_LIMIT = 3;
    private static final long RESTART_WINDOW_MILLIS = 10 * 60_000L;
    private static final long RESTART_COOLDOWN_MILLIS = 30 * 60_000L;

    private final AgentConfig config;
    private final RenderService renderer;
    private final RuntimeManager runtime;
    private final Path dataRoot;
    private final java.util.function.Consumer<String> log;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "memory-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long lastReportedTotalBytes;
    private volatile long autoRestartDisabledUntil;

    MemoryWatchdog(AgentConfig config, RenderService renderer, RuntimeManager runtime, Path dataRoot, java.util.function.Consumer<String> log) {
        this.config = config;
        this.renderer = renderer;
        this.runtime = runtime;
        this.dataRoot = dataRoot;
        this.log = log;
    }

    void start() {
        if (config.memoryRestartThresholdBytes <= 0) {
            log.accept("内存自动重启已关闭（阈值 = 0）");
            return;
        }
        renderer.setDrainCompleteListener(this::restartProgram);
        scheduler.scheduleWithFixedDelay(this::check, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.accept("内存看门狗已启动：阈值 " + formatBytes(config.memoryRestartThresholdBytes) + "，超限将在手头任务完成后自动重启");
    }

    long lastReportedTotalBytes() { return lastReportedTotalBytes; }

    private void check() {
        try {
            if (config.memoryRestartThresholdBytes <= 0 || renderer.isDraining()) return;
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
                    + "：停止领取新任务，等待手头 " + runningCountText() + "完成后自动重启程序");
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

    /** 手头任务完成后的重启：拉起新程序实例 → 退出当前进程；带防循环保护。 */
    private void restartProgram() {
        try {
            String exePath = launcherExecutablePath();
            if (exePath == null || !exePath.toLowerCase().endsWith(".exe")) {
                log.accept("无法定位程序启动器（开发模式运行？），已跳过自动重启。请手动重启程序以释放内存。");
                return;
            }
            Path guardFile = dataRoot.resolve("restart-guard.json");
            List<Long> restarts = readRestarts(guardFile);
            long now = System.currentTimeMillis();
            restarts.removeIf(timestamp -> now - timestamp > RESTART_WINDOW_MILLIS);
            if (restarts.size() >= RESTART_LIMIT) {
                autoRestartDisabledUntil = now + RESTART_COOLDOWN_MILLIS;
                log.accept("检测到 " + RESTART_WINDOW_MILLIS / 60000 + " 分钟内已自动重启 " + restarts.size()
                        + " 次，已暂停自动重启 30 分钟。请检查内存占用异常的原因。");
                return;
            }
            restarts.add(now);
            Files.writeString(guardFile, GSON.toJson(restarts));
            log.accept("手头任务已完成，正在重启程序以释放内存...");
            new ProcessBuilder("cmd", "/c", "start", "", exePath).start();
            Thread.sleep(1500);
            System.exit(0);
        } catch (Throwable error) {
            log.accept("自动重启失败：" + error.getMessage() + "，请手动重启程序");
        }
    }

    /** jpackage 启动的程序里，当前 java 进程的父进程就是 Litematic GPU Agent.exe。 */
    private static String launcherExecutablePath() {
        return ProcessHandle.current().parent()
                .flatMap(parent -> parent.info().command())
                .orElse(null);
    }

    private static List<Long> readRestarts(Path file) {
        try {
            if (Files.exists(file)) {
                Long[] array = GSON.fromJson(Files.readString(file), Long[].class);
                if (array != null) return new ArrayList<>(List.of(array));
            }
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
