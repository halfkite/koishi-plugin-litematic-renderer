package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                    .startsWith("fabric-cache-v3:"));
        } finally {
            try (var files = Files.walk(game)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void invalidatesFabricCacheWhenUpstreamModChanges() throws IOException {
        Path game = Files.createTempDirectory(Path.of("build"), "runtime-upstream-cache-");
        try {
            Path mods = Files.createDirectories(game.resolve("mods"));
            Path runtime = Files.writeString(mods.resolve("litematic-gpu-runtime.jar"), "runtime");
            Path upstream = Files.writeString(mods.resolve("litematica.jar"), "first");
            RuntimeInstaller.ensureFabricCacheCurrent(game, runtime, ignored -> {});
            Path processed = Files.createDirectories(game.resolve(".fabric/processedMods"));
            Files.writeString(processed.resolve("stale.jar"), "stale");
            Files.writeString(upstream, "second");

            RuntimeInstaller.ensureFabricCacheCurrent(game, runtime, ignored -> {});

            assertFalse(Files.exists(processed));
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

    @Test
    void packagedRuntimeContainsOnlyTheCustomMod() throws Exception {
        try (var bundled = RuntimeInstaller.class.getResourceAsStream("/renderer/litematic-gpu-runtime.jar")) {
            assertNotNull(bundled);
            try (ZipInputStream zip = new ZipInputStream(bundled)) {
                boolean foundMetadata = false;
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    assertFalse(entry.getName().startsWith("META-INF/jars/"), "Upstream mods must not be bundled");
                    if ("fabric.mod.json".equals(entry.getName())) foundMetadata = true;
                }
                assertTrue(foundMetadata);
            }
        }
    }

    @Test
    void parallelSlotReceivesUnmodifiedPinnedMods() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "runtime-mod-copy-");
        try {
            Path sourceMods = Files.createDirectories(root.resolve("game/mods"));
            for (String filename : List.of(
                    "litematic-gpu-runtime.jar",
                    "fabric-api-0.161.0+26.3.jar",
                    "malilib-fabric-26.3-0.30.1.jar",
                    "litematica-fabric-26.3-0.29.0.jar")) {
                Files.writeString(sourceMods.resolve(filename), filename);
            }
            Path slot = root.resolve("game-2");
            new RuntimeInstaller(root).copyBundledMods(slot);
            try (var files = Files.list(sourceMods)) {
                for (Path source : files.toList()) {
                    Path copied = slot.resolve("mods").resolve(source.getFileName());
                    assertTrue(RuntimeInstaller.sameFileContent(source, copied));
                }
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void recoversResourcePacksLeftInThePreviousRuntimeDirectory() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "resource-pack-recovery-");
        try {
            Path old = Files.createDirectories(root.resolve("runtime/game/resourcepacks")).resolve("001-old-pack.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(old))) {
                zip.putNextEntry(new ZipEntry("pack.mcmeta"));
                zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            AgentConfig config = new AgentConfig();
            config.resourcePacks = new ArrayList<>();
            assertTrue(ResourcePackManager.recoverLegacyPacks(root, config));
            assertEquals(1, config.resourcePacks.size());
            assertTrue(Files.isRegularFile(Path.of(config.resourcePacks.getFirst().path())));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
