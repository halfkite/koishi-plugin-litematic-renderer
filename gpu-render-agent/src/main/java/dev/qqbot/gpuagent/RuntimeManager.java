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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minecraft 渲染客户端池：支持多个隐藏客户端并行渲染（每个槽位独立游戏目录、独立进程与任务队列）。
 * 槽位 0 沿用原有 root/game 目录；其余槽位使用 root/game-2、root/game-3…
 * 并通过 NTFS 目录联接共享槽位 0 的网格缓存（litematica-preview-cache），避免重复建网格。
 */
final class RuntimeManager implements AutoCloseable {
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

        Slot(int index) {
            this.index = index;
            this.gameDirectory = index == 0 ? root.resolve("game") : root.resolve("game-" + (index + 1));
        }
    }

    RuntimeManager(Path applicationRoot, AgentConfig config) {
        this.root = applicationRoot.resolve("runtime");
        this.config = config;
        this.installer = new RuntimeInstaller(root);
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
        List<Long> pids = new ArrayList<>();
        for (Slot slot : slots) {
            Process process = slot.process;
            if (process != null && process.isAlive()) pids.add(process.pid());
        }
        return pids;
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
        try {
            return Protocol.GSON.fromJson(Files.readString(slots.get(slot).gameDirectory.resolve("gpu-render-runtime/status.json")), RenderModels.RuntimeStatus.class);
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    void setLog(Consumer<String> log) { this.log = log; installer.setLog(log); }

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

    private synchronized void start(Slot slot) throws Exception {
        if (slot.process != null && slot.process.isAlive()) return;
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
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Djava.library.path=" + spec.natives());
        command.add("-Dgpu.render.agent=true");
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
        ProcessBuilder builder = new ProcessBuilder(command).directory(slot.gameDirectory.toFile()).redirectErrorStream(true);
        try {
            slot.process = builder.start();
        } catch (IOException error) {
            throw new IOException("无法启动 Minecraft 进程（java=" + command.get(0) + "）：" + error.getMessage(), error);
        }
        Process child = slot.process;
        Thread reader = new Thread(() -> readLogs(slot, child), "minecraft-runtime-log-" + slot.index);
        reader.setDaemon(true);
        reader.start();
        log.accept("Minecraft 26.2 GPU 运行时已启动（隐藏窗口，客户端 " + (slot.index + 1) + "/" + slots.size() + "）");
    }

    /** 为非 0 槽位准备独立游戏目录：mods 里的渲染 jar + 共享网格缓存（目录联接到槽位 0）。 */
    private void prepareSlotGameDirectory(Slot slot) throws IOException {
        if (slot.index == 0) return;
        Files.createDirectories(slot.gameDirectory.resolve("mods"));
        installer.copyBundledMods(slot.gameDirectory);
        Path sharedCache = slots.get(0).gameDirectory.resolve("litematica-preview-cache");
        Path link = slot.gameDirectory.resolve("litematica-preview-cache");
        if (Files.exists(link)) return;
        try {
            Files.createDirectories(sharedCache);
            new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), sharedCache.toString())
                    .redirectErrorStream(true).start().waitFor();
            log.accept("客户端 " + (slot.index + 1) + " 已共享网格缓存目录");
        } catch (Throwable error) {
            log.accept("网格缓存共享失败（客户端 " + (slot.index + 1) + " 将使用独立缓存）：" + error.getMessage());
        }
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
        candidates.add(Path.of(System.getProperty("java.home"), "bin", "java.exe"));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome, "bin", "java.exe"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(";")) {
                if (entry.isBlank()) continue;
                Path candidate = Path.of(entry.trim(), "java.exe");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException("""
                未找到可用的 Java 启动器（java.exe）。jpackage 自带运行时可能不含启动器，\
                请在 agent.json 的 javaPath 中填写一个完整 JDK/JRE 的 java.exe 路径后重试。""");
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

    private synchronized void stop(Slot slot) {
        Process child = slot.process;
        slot.process = null;
        if (child == null) return;
        child.destroy();
        try { if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
    }

    @Override public void close() { stop(); }
}
