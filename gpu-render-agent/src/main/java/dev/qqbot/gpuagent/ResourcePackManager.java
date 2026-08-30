package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
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
