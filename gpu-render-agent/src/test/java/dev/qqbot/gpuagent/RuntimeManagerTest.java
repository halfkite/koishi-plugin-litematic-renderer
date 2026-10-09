package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeManagerTest {
    @Test
    void recoveryCleansOnlyTheAffectedSlotsRenderBridge(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        AgentConfig config = new AgentConfig();
        config.maxConcurrentRenders = 2;
        try (RuntimeManager manager = new RuntimeManager(root, config)) {
            Path bridge = manager.slotBridgeDirectory(0);
            for (String directory : java.util.List.of("jobs", "processing", "results", "version-jobs")) {
                Files.createDirectories(bridge.resolve(directory));
            }
            Path pending = bridge.resolve("jobs/stale.job.json");
            Path processing = bridge.resolve("processing/stale.job.json");
            Path result = bridge.resolve("results/stale.result.json");
            Path unrelated = bridge.resolve("jobs/notes.txt");
            Path conversion = bridge.resolve("version-jobs/conversion.version.json");
            Path otherSlot = manager.slotBridgeDirectory(1).resolve("jobs/next.job.json");
            Path projection = root.resolve("litematic-renderer-cache/kept.litematic");
            Files.createDirectories(otherSlot.getParent());
            Files.createDirectories(projection.getParent());
            for (Path file : java.util.List.of(pending, processing, result, unrelated, conversion, otherSlot, projection)) {
                Files.writeString(file, "keep-or-clear");
            }

            manager.clearRenderJobs(0);

            assertFalse(Files.exists(pending));
            assertFalse(Files.exists(processing));
            assertFalse(Files.exists(result));
            for (Path file : java.util.List.of(unrelated, conversion, otherSlot, projection)) {
                assertTrue(Files.exists(file), file.toString());
            }
        }
    }

    @Test
    void ignoresBusyStatusWhenRuntimeProcessIsNotAlive() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "runtime-status-");
        try (RuntimeManager manager = new RuntimeManager(root, new AgentConfig())) {
            Path status = manager.gameDirectory().resolve("gpu-render-runtime/status.json");
            Files.createDirectories(status.getParent());
            Files.writeString(status, "{\"timestamp\":" + System.currentTimeMillis()
                    + ",\"ready\":false,\"busy\":true,\"progress\":0.02,\"stage\":\"building\"}");

            assertNull(manager.currentStatus(), "An exited client must not leave the GUI stuck on stale busy status");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
