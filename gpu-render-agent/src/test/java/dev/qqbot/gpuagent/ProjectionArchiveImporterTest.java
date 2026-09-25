package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionArchiveImporterTest {
    @Test
    void preservesExportedOrdinalsWhenImportingIntoAnEmptyCache() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "projection-import-ordinals-");
        try {
            AgentConfig sourceConfig = new AgentConfig();
            sourceConfig.cacheDirectory = root.resolve("source").toString();
            CacheStore source = new CacheStore(root, sourceConfig, ignored -> {});
            byte[] first = {10, 0, 0, 0};
            byte[] second = {10, 0, 0, 0, 0};
            source.importProjection(first, "first.litematic");
            source.importProjection(second, "second.litematic");
            ProjectionCacheIndex.rebuild(source.directory());
            Path archive = ProjectionArchiveExporter.export(source.directory(), false);

            AgentConfig targetConfig = new AgentConfig();
            targetConfig.cacheDirectory = root.resolve("target").toString();
            CacheStore target = new CacheStore(root, targetConfig, ignored -> {});
            ProjectionArchiveImporter.importArchive(archive, target);
            var original = ProjectionCacheIndex.read(source.directory());
            var imported = ProjectionCacheIndex.read(target.directory());
            assertEquals(original.stream().map(ProjectionCacheIndex.Entry::ordinal).toList(),
                    imported.stream().map(ProjectionCacheIndex.Entry::ordinal).toList());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void importsFlattenedAndStructuredArchivesIntoHashCacheWithoutImages() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "projection-import-");
        try {
            Path sourceCache = root.resolve("source-cache");
            Path first = sourceCache.resolve("0".repeat(64));
            Path second = sourceCache.resolve("1".repeat(64));
            Files.createDirectories(first);
            Files.createDirectories(second);
            Files.write(first.resolve("建筑.litematic"), new byte[] {10, 0, 0, 0});
            Files.write(second.resolve("建筑.litematic"), new byte[] {10, 0, 0, 0, 0});
            Path archive = ProjectionArchiveExporter.export(sourceCache, false);

            AgentConfig config = new AgentConfig();
            config.cacheDirectory = root.resolve("target-cache").toString();
            CacheStore store = new CacheStore(root, config, ignored -> {});
            ProjectionArchiveImporter.ImportResult result = ProjectionArchiveImporter.importArchive(archive, store);
            assertEquals(2, result.importedCount());
            assertEquals(0, result.existingFiles());
            assertTrue(result.importedFiles().stream().allMatch(path -> path.startsWith(store.directory())));
            assertTrue(result.importedFiles().stream().allMatch(path -> Files.isRegularFile(path)));
            assertTrue(result.importedFiles().stream().allMatch(path -> Files.isRegularFile(path.resolveSibling("about.json5"))));
            assertTrue(result.importedFiles().stream().noneMatch(path -> Files.exists(path.resolveSibling("isometric.png"))));
            assertTrue(Files.isRegularFile(store.directory().resolve(ProjectionCacheIndex.NAME)));
            assertEquals(2, ProjectionCacheIndex.read(store.directory()).size());

            ProjectionArchiveImporter.ImportResult repeated = ProjectionArchiveImporter.importArchive(archive, store);
            assertEquals(0, repeated.importedCount());
            assertEquals(2, repeated.existingFiles());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void ignoresUnsafeEntriesAndRejectsArchivesWithoutProjections() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "projection-import-invalid-");
        try {
            Path archive = root.resolve("invalid.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("../outside.litematic"));
                zip.write("outside".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("notes.txt"));
                zip.write("ignored".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            AgentConfig config = new AgentConfig();
            config.cacheDirectory = root.resolve("cache").toString();
            CacheStore store = new CacheStore(root, config, ignored -> {});
            IOException error = assertThrows(IOException.class, () -> ProjectionArchiveImporter.importArchive(archive, store));
            assertTrue(error.getMessage().contains("没有可导入"));
            assertFalse(Files.exists(root.resolve("outside.litematic")));
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!Files.exists(root)) return;
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                return;
            } catch (java.nio.file.DirectoryNotEmptyException transientRace) {
                if (attempt == 4) throw transientRace;
                Thread.sleep(25L * (attempt + 1));
            }
        }
    }
}
