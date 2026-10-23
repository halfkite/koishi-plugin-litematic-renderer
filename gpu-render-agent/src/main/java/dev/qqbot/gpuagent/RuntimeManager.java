package dev.qqbot.gpuagent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minecraft 渲染客户端池：支持多个隐藏客户端并行渲染（每个槽位独立游戏目录、独立进程与任务队列）。
 * 槽位 0 沿用原有 root/game 目录；其余槽位使用 root/game-2、root/game-3…
 * 各客户端共用配置缓存目录中的内容寻址网格缓存，避免重复建网格。
 */
final class RuntimeManager implements AutoCloseable {
    private static final long STATUS_MAX_AGE_MILLIS = 5_000L;
    private final Path root;
    private final AgentConfig config;
    private final RuntimeInstaller installer;
    private final List<Slot> slots = new ArrayList<>();
    private volatile Consumer<String> log = ignored -> {};

    private final class Slot {
        final int index;
        final Path gameDirectory;
        volatile Process process;
        volatile boolean wasRunningForPacks;
        volatile java.util.concurrent.atomic.AtomicBoolean expectedExit;
        final AtomicInteger completedRenderTasks = new AtomicInteger();

        Slot(int index) {
            this.index = index;
            this.gameDirectory = index == 0 ? root.resolve("game") : root.resolve("game-" + (index + 1));
        }
    }

    RuntimeManager(Path applicationRoot, AgentConfig config) {
        this.root = applicationRoot.resolve("runtime");
        this.config = config;
        this.installer = new RuntimeInstaller(root, config);
        int count = Math.max(1, Math.min(4, config.maxConcurrentRenders));
        for (int index = 0; index < count; index++) slots.add(new Slot(index));
    }

    int slotCount() { return slots.size(); }

    Path gameDirectory() { return slots.get(0).gameDirectory; }

    List<Path> gameDirectories() {
        List<Path> directories = new ArrayList<>();
        for (Slot slot : slots) directories.add(slot.gameDirectory);
        return directories;
    }

    Path slotBridgeDirectory(int slot) { return slots.get(slot).gameDirectory.resolve("gpu-render-runtime"); }

    /** 只下载/校验 Minecraft 运行时文件，不启动进程；供软件预览等不需要 GPU 渲染端的功能使用。 */
    synchronized Path ensureInstalled() throws Exception {
        installer.install();
        return minecraftClientJar();
    }

    Path minecraftClientJar() { return root.resolve("versions").resolve(RuntimeInstaller.MINECRAFT_VERSION).resolve(RuntimeInstaller.MINECRAFT_VERSION + ".jar"); }

    boolean isAlive() {
        for (Slot slot : slots) if (isAlive(slot.index)) return true;
        return false;
    }

    boolean isAlive(int slot) {
        if (slot < 0 || slot >= slots.size()) return false;
        Process process = slots.get(slot).process;
        return process != null && process.isAlive();
    }

    /** 所有运行中渲染端进程的 PID（供内存看门狗统计）。 */
    synchronized List<Long> runningProcessPids() {
        LinkedHashSet<Long> pids = new LinkedHashSet<>();
        for (Slot slot : slots) {
            Process process = slot.process;
            if (process == null || !process.isAlive()) continue;
            try {
                ProcessHandle rootHandle = process.toHandle();
                pids.add(rootHandle.pid());
                rootHandle.descendants().forEach(descendant -> pids.add(descendant.pid()));
            } catch (SecurityException ignored) {
                pids.add(process.pid());
            }
        }
        return List.copyOf(pids);
    }

    void recordRenderCompleted(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) return;
        Slot slot = slots.get(slotIndex);
        Process process = slot.process;
        if (process != null && process.isAlive()) slot.completedRenderTasks.incrementAndGet();
    }

    int maxCompletedRenderTasks() {
        return slots.stream().mapToInt(slot -> slot.completedRenderTasks.get()).max().orElse(0);
    }

    boolean recycleDueToTaskCount(int maximumTasks) {
        return maximumTasks > 0 && slots.stream().anyMatch(slot -> slot.process != null
                && slot.process.isAlive() && slot.completedRenderTasks.get() >= maximumTasks);
    }

    RenderModels.RuntimeStatus currentStatus() {
        for (Slot slot : slots) {
            RenderModels.RuntimeStatus status = currentStatus(slot.index);
            if (status != null) return status;
        }
        return null;
    }

    RenderModels.RuntimeStatus currentStatus(int slot) {
        if (slot < 0 || slot >= slots.size()) return null;
        if (!isAlive(slot)) return null;
        try {
            RenderModels.RuntimeStatus status = Protocol.GSON.fromJson(
                    Files.readString(slots.get(slot).gameDirectory.resolve("gpu-render-runtime/status.json")),
                    RenderModels.RuntimeStatus.class);
            if (status == null || System.currentTimeMillis() - status.timestamp() > STATUS_MAX_AGE_MILLIS) return null;
            return status;
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    void setLog(Consumer<String> log) { this.log = log; installer.setLog(log); }

    void setInstallProgress(Consumer<RuntimeInstaller.InstallProgress> listener) { installer.setProgress(listener); }

    RuntimeInstaller.InstallProgress installProgress() { return installer.progress(); }

    /** 停止现有客户端后导入并校验本地 Minecraft 客户端，供桌面/Web 管理入口使用。 */
    synchronized Path importLocalClient(Path source) throws Exception {
        if (source == null) throw new IllegalArgumentException("本地客户端路径不能为空");
        config.localMinecraftClientPath = source.toAbsolutePath().normalize().toString();
        stop();
        installer.install();
        return minecraftClientJar();
    }

    synchronized void ensureRunning(Duration timeout) throws Exception { ensureRunning(0, timeout); }

    synchronized void ensureRunning(int slotIndex, Duration timeout) throws Exception {
        Slot slot = slots.get(slotIndex);
        if (isAlive(slotIndex)) return;
        start(slot);
        long deadline = System.nanoTime() + timeout.toNanos();
        Path statusPath = slot.gameDirectory.resolve("gpu-render-runtime/status.json");
        while (System.nanoTime() < deadline) {
            if (!isAlive(slotIndex)) throw new IOException("Minecraft GPU 运行时启动后意外退出");
            try {
                RenderModels.RuntimeStatus status = Protocol.GSON.fromJson(Files.readString(statusPath), RenderModels.RuntimeStatus.class);
                if (status != null && status.ready() && System.currentTimeMillis() - status.timestamp() < 5_000) return;
            } catch (IOException | RuntimeException ignored) {}
            Thread.sleep(250);
        }
        throw new IOException("Minecraft GPU 运行时未在限定时间内就绪");
    }

    Path convertProjectionFormat(Path input, int targetVersion, Path output, Duration timeout) throws Exception {
        if (targetVersion != 6 && targetVersion != 7) throw new IllegalArgumentException("仅支持 Litematic 格式版本 6 或 7");
        Path source = input.toAbsolutePath().normalize();
        Path target = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IOException("待转换的投影文件不存在");
        if (target.getParent() == null || source.equals(target)) throw new IOException("投影转换路径无效");
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        ensureRunning(0, Duration.ofMinutes(5));

        Path bridge = slotBridgeDirectory(0);
        Path jobs = bridge.resolve("version-jobs");
        Path results = bridge.resolve("version-results");
        Path versionFiles = bridge.resolve("version-files");
        Files.createDirectories(jobs);
        Files.createDirectories(results);
        Files.createDirectories(versionFiles);
        String id = UUID.randomUUID().toString();
        Path jobPath = jobs.resolve(id + ".version.json");
        Path resultPath = results.resolve(id + ".version.result.json");
        Path runtimeSource = versionFiles.resolve(id + "-source.litematic");
        Path runtimeTarget = versionFiles.resolve(id + "-converted.litematic");
        Path temporary = jobPath.resolveSibling(jobPath.getFileName() + ".tmp");
        try {
            Files.copy(source, runtimeSource);
            com.google.gson.JsonObject request = new com.google.gson.JsonObject();
            request.addProperty("id", id);
            request.addProperty("input", runtimeSource.toString());
            request.addProperty("output", runtimeTarget.toString());
            request.addProperty("targetVersion", targetVersion);
            Files.writeString(temporary, Protocol.GSON.toJson(request), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, jobPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, jobPath);
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(resultPath)) {
                    com.google.gson.JsonObject result = Protocol.GSON.fromJson(
                            Files.readString(resultPath, StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                    if (result == null || !result.has("success")) throw new IOException("运行时返回了无效的投影转换结果");
                    if (!result.get("success").getAsBoolean()) {
                        String error = result.has("error") && !result.get("error").isJsonNull()
                                ? result.get("error").getAsString() : "未知转换错误";
                        throw new IOException(error);
                    }
                    if (!Files.isRegularFile(runtimeTarget) || Files.size(runtimeTarget) == 0)
                        throw new IOException("运行时报告转换成功，但未生成投影文件");
                    Files.copy(runtimeTarget, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return target;
                }
                if (!isAlive(0)) throw new IOException("Minecraft GPU 运行时在投影转换期间退出");
                Thread.sleep(250);
            }
            throw new IOException("投影格式转换超时");
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(jobPath);
            Files.deleteIfExists(resultPath);
            Files.deleteIfExists(runtimeSource);
            Files.deleteIfExists(runtimeTarget);
        }
    }

    private synchronized void start(Slot slot) throws Exception {
        if (slot.process != null) {
            if (slot.process.isAlive()) return;
            // 客户端崩溃后，Java 的主进程可能已退出但其派生进程仍在；启动新客户端前先清理整棵树。
            stop(slot);
        }
        slot.completedRenderTasks.set(0);
        RuntimeInstaller.LaunchSpec spec = installer.install();
        prepareSlotGameDirectory(slot);
        if (slot.index == 0 && config.resourcePacks != null && !config.resourcePacks.isEmpty()) {
            try {
                new ResourcePackManager(config, this, log).applyTransactional(config.resourcePacks);
            } catch (Throwable error) {
                log.accept("资源包自动应用失败（将继续渲染）：" + error.getMessage());
            }
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Xms512m");
        command.add("-Xmx4g");
        command.add("-XX:+ExitOnOutOfMemoryError");
        // C1 compilation of the 26.3 shader pipeline builder crashes HotSpot on
        // this Java 25 runtime. Keep that one-time pipeline setup interpreted.
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-XX:CompileCommand=exclude,com/mojang/renderpearl/frontend/shaders/PipelineBuilder.*");
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Djava.library.path=" + spec.natives());
        command.add("-Dgpu.render.agent=true");
        CacheStore meshStore = new CacheStore(root.getParent(), config, log, false);
        command.add("-Dgpu.render.meshCacheDirectory=" + meshStore.directory().resolve(".mesh"));
        command.add("-Dgpu.render.resourceFingerprint=" + meshStore.resourcePackFingerprint());
        String blockItemMapOutput = System.getProperty("gpu.render.block-item-map-output");
        if (blockItemMapOutput != null && !blockItemMapOutput.isBlank()) {
            command.add("-Dgpu.render.block-item-map-output=" + blockItemMapOutput);
        }
        command.add("-Dgpu.render.nightVision=" + config.nightVisionEnabled);
        command.add("-Dgpu.render.nightVisionLevel=" + Math.max(1, Math.min(15, config.nightVisionLevel)));
        for (String argument : spec.jvmArguments()) {
            String normalized = normalizeJvmArgument(argument);
            if (!"-cp".equals(normalized) && !normalized.contains("${classpath}")) command.add(normalized);
        }
        command.add("-cp");
        command.add(String.join(System.getProperty("path.separator"), spec.classpath().stream().map(Path::toString).toList()));
        command.add(spec.mainClass());
        command.addAll(expandArguments(slot, spec.gameArguments(), spec.assetIndex()));
        command.add("--width"); command.add("1");
        command.add("--height"); command.add("1");
        if (slot.index == 0) writeLaunchDiagnostics(command, spec);
        Files.deleteIfExists(slot.gameDirectory.resolve("gpu-render-runtime/status.json"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(slot.gameDirectory.toFile()).redirectErrorStream(true);
        try {
            slot.process = builder.start();
        } catch (IOException error) {
            throw new IOException("无法启动 Minecraft 进程（java=" + command.get(0) + "）：" + error.getMessage(), error);
        }
        Process child = slot.process;
        var expectedExit = new java.util.concurrent.atomic.AtomicBoolean();
        slot.expectedExit = expectedExit;
        Thread reader = new Thread(() -> readLogs(slot, child), "minecraft-runtime-log-" + slot.index);
        reader.setDaemon(true);
        reader.start();
        child.onExit().thenAccept(process -> {
            int exitCode = process.exitValue();
            if (exitCode != 0 && !expectedExit.get()) {
                CrashLogger.runtimeExit(root, slot.index, exitCode);
                log.accept("Minecraft 26.3 GPU 运行时异常退出（客户端 " + (slot.index + 1) + "，退出码 " + exitCode + "）");
            }
        });
        log.accept("Minecraft 26.3 GPU 运行时已启动（隐藏窗口，客户端 " + (slot.index + 1) + "/" + slots.size() + "）");
    }

    /** Prepare independent mods; the mesh-cache property supplies a shared path on every platform. */
    private void prepareSlotGameDirectory(Slot slot) throws IOException {
        if (slot.index == 0) return;
        Files.createDirectories(slot.gameDirectory.resolve("mods"));
        installer.copyBundledMods(slot.gameDirectory);
    }

    static String normalizeJvmArgument(String argument) {
        String normalized = argument.trim();
        if (!normalized.startsWith("-D")) return normalized;
        int separator = normalized.indexOf('=');
        if (separator < 0) return normalized;
        return normalized.substring(0, separator + 1) + normalized.substring(separator + 1).trim();
    }

    private void writeLaunchDiagnostics(List<String> command, RuntimeInstaller.LaunchSpec spec) throws IOException {
        Path diagnostics = root.resolve("launch-diagnostics.txt");
        List<String> lines = new ArrayList<>();
        lines.add("generatedAt=" + Instant.now());
        lines.add("java=" + javaExecutable());
        lines.add("mainClass=" + spec.mainClass());
        lines.add("classpathEntries=" + spec.classpath().size());
        lines.add("compatibilityClassLoader=false");
        lines.add("");
        lines.add("Arguments (one argument per line):");
        for (int index = 0; index < command.size(); index++) {
            String argument = command.get(index);
            if (index > 0 && "-cp".equals(command.get(index - 1))) {
                lines.add("[" + index + "] <classpath: " + spec.classpath().size() + " entries>");
            } else {
                lines.add("[" + index + "] " + argument);
            }
        }
        lines.add("");
        lines.add("Classpath:");
        for (Path entry : spec.classpath()) lines.add(entry.toString());
        Files.write(diagnostics, lines, StandardCharsets.UTF_8);
    }

    private List<String> expandArguments(Slot slot, List<String> source, String assetIndex) {
        Map<String, String> values = new HashMap<>();
        values.put("${auth_player_name}", "GpuRenderer");
        values.put("${version_name}", RuntimeInstaller.MINECRAFT_VERSION + "-fabric");
        values.put("${game_directory}", slot.gameDirectory.toString());
        values.put("${assets_root}", root.resolve("assets").toString());
        values.put("${assets_index_name}", assetIndex);
        values.put("${auth_uuid}", "00000000000000000000000000000000");
        values.put("${auth_access_token}", "0");
        values.put("${clientid}", "0");
        values.put("${auth_xuid}", "0");
        values.put("${user_type}", "msa");
        values.put("${version_type}", "release");
        List<String> output = new ArrayList<>();
        for (String argument : source) {
            String value = argument;
            for (Map.Entry<String, String> replacement : values.entrySet()) value = value.replace(replacement.getKey(), replacement.getValue());
            if (!value.contains("${")) output.add(value);
        }
        return output;
    }

    private Path javaExecutable() throws IOException {
        List<Path> candidates = new ArrayList<>();
        if (config.javaPath != null && !config.javaPath.isBlank()) candidates.add(Path.of(config.javaPath));
        String launcher = isWindows() ? "java.exe" : "java";
        candidates.add(Path.of(System.getProperty("java.home"), "bin", launcher));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome, "bin", launcher));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path candidate = Path.of(entry.trim(), launcher);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException("""
                未找到可用的 Java 启动器。请在 agent.json 的 javaPath 中填写一个完整 JDK/JRE 的 Java 路径后重试。""");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private void readLogs(Slot slot, Process child) {
        Path logFile = slot.index == 0 ? root.resolve("minecraft-runtime.log") : root.resolve("minecraft-runtime-" + (slot.index + 1) + ".log");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                if (line.startsWith("GPU_AGENT_JSON:")) log.accept(line.substring("GPU_AGENT_JSON:".length()));
                else if (line.contains("ERROR") || line.contains("gpu-render-runtime")) log.accept("[客户端" + (slot.index + 1) + "] " + line);
            }
        } catch (IOException ignored) {}
    }

    synchronized void stop() {
        for (Slot slot : slots) stop(slot);
    }

    synchronized void recoverUnresponsive(int slotIndex, String reason) {
        Slot slot = slots.get(slotIndex);
        Process child = slot.process;
        if (child != null && child.isAlive()) captureStallDiagnostic(slotIndex, child, reason);
        stop(slot);
        try {
            Path runtimeLog = runtimeLogPath(slotIndex);
            if (Files.isRegularFile(runtimeLog)) {
                String snapshotName = runtimeLog.getFileName().toString().replace(".log", "-before-recovery.log");
                Files.copy(runtimeLog, runtimeLog.resolveSibling(snapshotName),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            log.accept("保存重启前客户端日志失败：" + error.getMessage());
        }
        try {
            clearRenderJobs(slotIndex);
        } catch (IOException error) {
            log.accept("清理卡住客户端的任务记录失败：" + error.getMessage());
        }
        log.accept("客户端 " + (slotIndex + 1) + " 已回收，下一项排队任务将使用新客户端");
    }

    void clearRenderJobs(int slotIndex) throws IOException {
        Path bridge = slotBridgeDirectory(slotIndex);
        for (String directory : List.of("jobs", "processing", "results")) {
            Path folder = bridge.resolve(directory);
            if (!Files.isDirectory(folder)) continue;
            String suffix = "results".equals(directory) ? ".result.json" : ".job.json";
            try (var files = Files.list(folder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(suffix) || name.endsWith(suffix + ".tmp")) Files.deleteIfExists(file);
                }
            }
        }
    }

    private Path runtimeLogPath(int slotIndex) {
        return root.resolve(slotIndex == 0 ? "minecraft-runtime.log" : "minecraft-runtime-" + (slotIndex + 1) + ".log");
    }

    private void captureStallDiagnostic(int slotIndex, Process child, String reason) {
        Path diagnostic = root.resolve("runtime-stall.log");
        try {
            Files.createDirectories(root);
            Files.writeString(diagnostic, Instant.now() + " 客户端 " + (slotIndex + 1) + " PID=" + child.pid()
                    + "\n" + reason + "\n状态：" + currentStatus(slotIndex) + "\n", StandardCharsets.UTF_8);
            String name = isWindows() ? "jcmd.exe" : "jcmd";
            Path jcmd = javaExecutable().resolveSibling(name);
            if (!Files.isRegularFile(jcmd)) jcmd = Path.of(System.getProperty("java.home"), "bin", name);
            if (Files.isRegularFile(jcmd)) {
                Process dump = new ProcessBuilder(jcmd.toString(), String.valueOf(child.pid()), "Thread.print", "-l")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(diagnostic.toFile())).start();
                if (!dump.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    dump.destroyForcibly();
                    Files.writeString(diagnostic, "\n线程堆栈采集超时\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                }
            } else {
                Files.writeString(diagnostic, "当前 Java 未提供 jcmd，无法采集客户端线程堆栈\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
            log.accept("渲染卡住诊断已保存：" + diagnostic);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            log.accept("保存渲染卡住诊断失败：" + error.getMessage());
        }
    }

    private synchronized void stop(Slot slot) {
        slot.completedRenderTasks.set(0);
        Process child = slot.process;
        if (child == null) return;
        if (slot.expectedExit != null) slot.expectedExit.set(true);
        terminateProcessTree(child);
        // 只有确认 terminateProcessTree 返回后才清空句柄，避免状态先变成“未启动”而旧客户端仍在退出。
        slot.process = null;
    }

    /** Windows 的 destroy 只保证主进程，使用 taskkill /T 兜底回收 Fabric/GLFW 派生进程。 */
    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        try {
            if (isWindows() && process.isAlive()) {
                Process killer = new ProcessBuilder("taskkill.exe", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .redirectErrorStream(true).start();
                killer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } else if (isWindows()) {
                // 主 Java 已崩溃时，Windows 可能仍留下 GLFW/子 Java；根句柄此时无法再覆盖它们。
                for (ProcessHandle descendant : descendants) {
                    if (!descendant.isAlive()) continue;
                    Process killer = new ProcessBuilder("taskkill.exe", "/PID", String.valueOf(descendant.pid()), "/T", "/F")
                            .redirectErrorStream(true).start();
                    killer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } else {
                descendants.forEach(ProcessHandle::destroy);
                process.destroy();
            }
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            descendants.forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
            // taskkill/forcible destroy 都是异步语义；在启动下一个客户端前确认整棵进程树已经消失。
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && processTreeAlive(process, descendants)) {
                Thread.sleep(50);
            }
            if (processTreeAlive(process, descendants)) {
                descendants.forEach(handle -> {
                    if (handle.isAlive()) handle.destroyForcibly();
                });
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
    }

    private static boolean processTreeAlive(Process process, List<ProcessHandle> descendants) {
        if (process.isAlive()) return true;
        return descendants.stream().anyMatch(ProcessHandle::isAlive);
    }

    @Override public void close() { stop(); }
}
