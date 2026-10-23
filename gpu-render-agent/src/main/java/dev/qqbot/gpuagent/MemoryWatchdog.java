package dev.qqbot.gpuagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Monitors Agent and Minecraft processes separately and recycles only the isolated renderer processes. */
final class MemoryWatchdog {
    private static final long CHECK_INTERVAL_SECONDS = 10;

    private final AgentConfig config;
    private final RenderService renderer;
    private final RuntimeManager runtime;
    private final Consumer<String> log;
    private final ProcessMemorySampler sampler = new ProcessMemorySampler();
    private final RuntimeMemoryPolicy memoryPolicy = new RuntimeMemoryPolicy();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "memory-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean restarting = new AtomicBoolean();
    private volatile long lastReportedAgentBytes = -1;
    private volatile long lastReportedRuntimeBytes = -1;
    private volatile String pendingRestartReason = "内存保护";
    private volatile boolean agentPressureLogged;

    MemoryWatchdog(AgentConfig config, RenderService renderer, RuntimeManager runtime, Consumer<String> log) {
        this.config = config;
        this.renderer = renderer;
        this.runtime = runtime;
        this.log = log;
    }

    void start() {
        if (closed.get()) return;
        boolean enabled = config.memoryRestartThresholdBytes > 0 || config.runtimeRecycleAfterTasks > 0;
        if (!enabled) {
            log.accept("运行时自动回收已关闭（内存阈值和任务数回收均为 0）");
        } else {
            log.accept("运行时内存管理已启动：Minecraft 进程内存阈值 "
                    + (config.memoryRestartThresholdBytes > 0 ? formatBytes(config.memoryRestartThresholdBytes) : "关闭")
                    + "，每个客户端处理 "
                    + (config.runtimeRecycleAfterTasks > 0 ? config.runtimeRecycleAfterTasks + " 个任务后轮换" : "不按任务数轮换")
                    + "；不设时间冷却，当前任务完成后回收客户端并继续队列");
        }
        renderer.setDrainCompleteListener(this::restartProgram);
        scheduler.scheduleWithFixedDelay(this::check, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    long lastReportedAgentBytes() { return lastReportedAgentBytes; }
    long lastReportedRuntimeBytes() { return lastReportedRuntimeBytes; }

    void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            renderer.setDrainCompleteListener(null);
        }
    }

    private void check() {
        try {
            if (closed.get() || renderer.isDraining()
                    || config.memoryRestartThresholdBytes <= 0 && config.runtimeRecycleAfterTasks <= 0) return;
            List<Long> runtimePids = runtime.runningProcessPids();
            long agentPid = ProcessHandle.current().pid();
            LinkedHashSet<Long> allPids = new LinkedHashSet<>(runtimePids);
            allPids.add(agentPid);
            Map<Long, Long> memory = sampler.sample(new ArrayList<>(allPids));
            lastReportedAgentBytes = memory.getOrDefault(agentPid, -1L);
            lastReportedRuntimeBytes = sumKnownBytes(runtimePids, memory);

            long threshold = config.memoryRestartThresholdBytes;
            if (threshold > 0 && lastReportedAgentBytes > threshold) {
                if (!agentPressureLogged) {
                    agentPressureLogged = true;
                    log.accept("Agent 进程内存 " + formatBytes(lastReportedAgentBytes)
                            + " 高于运行时阈值；Minecraft 客户端回收不会释放 Agent 内存，请依据诊断日志定位 Agent 保留对象");
                }
            } else if (threshold <= 0 || lastReportedAgentBytes >= 0
                    && lastReportedAgentBytes < threshold - threshold / 4) {
                agentPressureLogged = false;
            }

            if (threshold > 0 && memoryPolicy.shouldRecycle(lastReportedRuntimeBytes, threshold, runtime.isAlive())) {
                requestDrain("Minecraft 客户端进程内存 " + formatBytes(lastReportedRuntimeBytes)
                        + " 超过阈值 " + formatBytes(threshold));
                return;
            }

            int taskLimit = config.runtimeRecycleAfterTasks;
            if (runtime.recycleDueToTaskCount(taskLimit)) {
                requestDrain("Minecraft 客户端已处理 " + runtime.maxCompletedRenderTasks()
                        + " 个任务，达到每客户端 " + taskLimit + " 个任务的轮换上限");
            }
        } catch (Throwable error) {
            log.accept("内存监控异常：" + error.getMessage());
        }
    }

    private synchronized void requestDrain(String reason) {
        if (closed.get() || restarting.get() || renderer.isDraining()) return;
        pendingRestartReason = reason;
        log.accept("运行时回收已触发：" + reason + "；暂停领取新任务，等待当前任务结束");
        renderer.startDraining();
    }

    private void restartProgram() {
        if (closed.get() || !restarting.compareAndSet(false, true)) return;
        long agentBefore = lastReportedAgentBytes;
        long runtimeBefore = lastReportedRuntimeBytes;
        String reason = pendingRestartReason;
        try {
            log.accept("开始回收 Minecraft GPU 客户端：" + reason);
            renderer.restartRuntimeAfterDrain();
            lastReportedRuntimeBytes = 0;
            log.accept("Minecraft 客户端进程树已退出；回收前 Agent=" + formatOptional(agentBefore)
                    + "，Minecraft=" + formatOptional(runtimeBefore)
                    + "。等待中的任务继续由新客户端处理");
        } catch (Throwable error) {
            log.accept("Minecraft GPU 客户端回收失败：" + error + "；队列将恢复，继续检查内存");
            try {
                renderer.restartRuntimeAfterDrain();
            } catch (Throwable recoveryError) {
                log.accept("恢复渲染队列失败，请手动重启 Agent：" + recoveryError.getMessage());
            }
        } finally {
            restarting.set(false);
            if (!closed.get()) renderer.setDrainCompleteListener(this::restartProgram);
        }
    }

    private static long sumKnownBytes(List<Long> processIds, Map<Long, Long> memory) {
        if (processIds.isEmpty()) return 0;
        long total = 0;
        int found = 0;
        for (long processId : processIds) {
            Long bytes = memory.get(processId);
            if (bytes == null) continue;
            total = Math.addExact(total, bytes);
            found++;
        }
        return found == 0 ? -1 : total;
    }

    private static String formatOptional(long bytes) {
        return bytes < 0 ? "不可用" : formatBytes(bytes);
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
