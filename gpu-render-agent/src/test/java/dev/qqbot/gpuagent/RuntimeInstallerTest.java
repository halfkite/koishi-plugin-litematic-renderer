package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeInstallerTest {
    @Test
    void normalizesClasspathWithoutResolvingTheFilesystem() {
        Path logicalRoot = Path.of("build", "missing-runtime-root").toAbsolutePath();
        Path input = logicalRoot.resolve("libraries").resolve("..").resolve("versions").resolve("client.jar");

        Path normalized = RuntimeInstaller.normalizeClasspathEntry(input);

        assertEquals(logicalRoot.resolve("versions").resolve("client.jar").normalize(), normalized);
    }

    @Test
    void comparesEqualLengthRuntimeJarsByContent() throws IOException {
        Path directory = Path.of("build", "test-tmp", "runtime-installer");
        Files.createDirectories(directory);
        Path installed = Files.write(directory.resolve("installed.jar"), new byte[] { 1, 2, 3, 4 });
        Path bundled = Files.write(directory.resolve("bundled.jar"), new byte[] { 1, 2, 9, 4 });

        assertFalse(RuntimeInstaller.sameFileContent(installed, bundled));
        Files.copy(installed, bundled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(RuntimeInstaller.sameFileContent(installed, bundled));
    }

    @Test
    void clearsFabricProcessedModulesWhenRuntimeJarMarkerChanges() throws IOException {
        Path game = Files.createTempDirectory(Path.of("build"), "runtime-cache-");
        try {
            Path processed = Files.createDirectories(game.resolve(".fabric/processedMods"));
            Path remapped = Files.createDirectories(game.resolve(".fabric/remappedJars"));
            Files.writeString(processed.resolve("fabric-api-old.jar"), "old");
            Files.writeString(remapped.resolve("old.jar"), "old");
            Files.writeString(game.resolve(".fabric/keep.txt"), "keep");
            Files.writeString(game.resolve(".fabric/litematic-gpu-runtime.sha256"), "old-fingerprint\n");
            Path runtimeJar = Files.write(game.resolve("mods.jar"), new byte[] { 1, 2, 3 });

            RuntimeInstaller.ensureFabricCacheCurrent(game, runtimeJar, ignored -> {});

            assertFalse(Files.exists(processed));
            assertFalse(Files.exists(remapped));
            assertTrue(Files.isRegularFile(game.resolve(".fabric/keep.txt")));
            assertFalse(Files.readString(game.resolve(".fabric/litematic-gpu-runtime.sha256"))
                    .startsWith("old-fingerprint"));
            assertTrue(Files.readString(game.resolve(".fabric/litematic-gpu-runtime.sha256"))
                    .startsWith("fabric-cache-v2:"));
        } finally {
            try (var files = Files.walk(game)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void ordersFabricLoaderBeforeSpongeMixinForStableLaunchClassLoader() {
        List<Path> classpath = new ArrayList<>(List.of(
                Path.of("C:/runtime/libraries/net/fabricmc/sponge-mixin/mixin.jar"),
                Path.of("C:/runtime/libraries/net/fabricmc/fabric-loader/loader.jar"),
                Path.of("C:/runtime/versions/26.2/26.2.jar")
        ));

        RuntimeInstaller.orderClasspathForLaunch(classpath);

        assertTrue(classpath.indexOf(Path.of("C:/runtime/libraries/net/fabricmc/fabric-loader/loader.jar"))
                < classpath.indexOf(Path.of("C:/runtime/libraries/net/fabricmc/sponge-mixin/mixin.jar")));
    }
}
