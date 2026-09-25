package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;

final class RuntimeManagerTest {
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
