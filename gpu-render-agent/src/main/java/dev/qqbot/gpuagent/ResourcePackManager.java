package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import java.security.MessageDigest;
import java.util.HexFormat;

final class ResourcePackManager {
    private final AgentConfig config;
    private final RuntimeManager runtime;
    private final Consumer<String> log;

    ResourcePackManager(AgentConfig config, RuntimeManager runtime, Consumer<String> log) {
        this.config = config; this.runtime = runtime; this.log = log;
    }

    /**
     * 早期版本把资源包只记录在 agent.json，实际文件则留在 Minecraft 运行时目录。
     * 升级或切换便携目录后配置列表可能为空，但运行时副本仍然存在；启动时把它们
     * 迁移到 Agent 自己的 resource-packs 目录，避免历史资源包从界面消失。
     */
    static boolean recoverLegacyPacks(Path root, AgentConfig config) throws IOException {
        if (config.resourcePacks != null && !config.resourcePacks.isEmpty()) return false;
        Path managed = root.resolve("resource-packs");
        List<Path> candidates = new ArrayList<>();
        collectZipFiles(managed, candidates);
        if (candidates.isEmpty()) {
            Path runtime = root.resolve("runtime");
            collectZipFiles(runtime.resolve("game/resourcepacks"), candidates);
            try (var children = Files.list(runtime)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    if (child.getFileName().toString().matches("game-\\d+")) collectZipFiles(child.resolve("resourcepacks"), candidates);
                }
            }
        }
        if (candidates.isEmpty()) return false;
        Files.createDirectories(managed);
        List<AgentConfig.ResourcePackEntry> restored = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (Path source : candidates.stream().distinct().sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT))).toList()) {
            Path target = managed.resolve(source.getFileName().toString()).normalize();
            if (!target.startsWith(managed)) continue;
            if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                if (!Files.exists(target)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.isRegularFile(target) || !isResourcePack(target)) continue;
            String hash = hashFile(target);
            if (hashes.contains(hash)) continue;
            hashes.add(hash);
            restored.add(new AgentConfig.ResourcePackEntry(target.toAbsolutePath().toString(), true));
        }
        if (restored.isEmpty()) return false;
        config.resourcePacks = restored;
        return true;
    }

    private static void collectZipFiles(Path directory, List<Path> output) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            output.addAll(files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
                    .toList());
        }
    }

    private static boolean isResourcePack(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) { return zip.getEntry("pack.mcmeta") != null; }
        catch (IOException ignored) { return false; }
    }

    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    synchronized void applyTransactional(List<AgentConfig.ResourcePackEntry> entries) throws Exception {
        for (var entry : entries) validate(Path.of(entry.path()));
        List<Path> gameDirectories = runtime.gameDirectories();
        List<byte[]> previousOptions = new ArrayList<>();
        for (Path gameDirectory : gameDirectories) {
            Path options = gameDirectory.resolve("options.txt");
            previousOptions.add(Files.exists(options) ? Files.readAllBytes(options) : null);
        }
        List<Integer> wasRunning = new ArrayList<>();
        for (int slot = 0; slot < gameDirectories.size(); slot++) {
            if (runtime.isAlive(slot)) wasRunning.add(slot);
        }
        runtime.stop();
        try {
            List<String> enabled = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                if (!entry.enabled()) continue;
                Path source = Path.of(entry.path()).toAbsolutePath().normalize();
                String targetName = "%03d-%s-%s".formatted(index, sha256(source).substring(0, 12),
                        source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"));
                enabled.add("file/" + targetName);
                for (Path gameDirectory : gameDirectories) {
                    Files.createDirectories(gameDirectory.resolve("resourcepacks"));
                    Files.copy(source, gameDirectory.resolve("resourcepacks").resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (Path gameDirectory : gameDirectories) writePackOptions(gameDirectory.resolve("options.txt"), enabled);
            for (int slot : wasRunning) runtime.ensureRunning(slot, Duration.ofMinutes(5));
            config.resourcePacks = new ArrayList<>(entries);
            log.accept("资源包已重载，启用 " + enabled.size() + " 个（覆盖 " + gameDirectories.size() + " 个渲染客户端）");
        } catch (Throwable error) {
            runtime.stop();
            for (int index = 0; index < gameDirectories.size(); index++) {
                byte[] previous = previousOptions.get(index);
                Path options = gameDirectories.get(index).resolve("options.txt");
                if (previous == null) Files.deleteIfExists(options); else Files.write(options, previous);
            }
            for (int slot : wasRunning) {
                try { runtime.ensureRunning(slot, Duration.ofMinutes(5)); }
                catch (Throwable restoreError) { error.addSuppressed(restoreError); }
            }
            throw error;
        }
    }

    private static void validate(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase().endsWith(".zip")) {
            throw new IOException("资源包必须是可读取的 ZIP：" + path);
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("pack.mcmeta") == null) throw new IOException("资源包缺少 pack.mcmeta：" + path.getFileName());
        }
    }

    private static void writePackOptions(Path options, List<String> packs) throws IOException {
        List<String> lines = Files.exists(options) ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8)) : new ArrayList<>();
        lines.removeIf(line -> line.startsWith("resourcePacks:") || line.startsWith("incompatibleResourcePacks:"));
        lines.add("resourcePacks:" + Protocol.GSON.toJson(packs));
        lines.add("incompatibleResourcePacks:[]");
        Files.createDirectories(options.getParent());
        Files.write(options, lines, StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
