package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionArchiveExporterTest {
    @Test
    void exportsOnlyProjectionFilesAndOptionallyKeepsHashDirectories() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "projection-export-");
        try {
            Path cache = root.resolve("litematic-renderer-cache");
            Path first = cache.resolve("0".repeat(64));
            Path second = cache.resolve("1".repeat(64));
            Files.createDirectories(first);
            Files.createDirectories(second);
            Files.writeString(first.resolve("建筑.litematic"), "first", StandardCharsets.UTF_8);
            Files.writeString(second.resolve("建筑.litematic"), "second", StandardCharsets.UTF_8);
            Files.writeString(first.resolve("isometric.png"), "image", StandardCharsets.UTF_8);
            Files.writeString(first.resolve("about.json5"), "{}", StandardCharsets.UTF_8);

            Path flattened = ProjectionArchiveExporter.export(cache, false);
            assertTrue(flattened.getFileName().toString().matches("缓存投影文件\\d{4}-\\d{4}-\\d{4}(-\\d+)?\\.zip"));
            try (ZipFile zip = new ZipFile(flattened.toFile())) {
                List<String> names = zip.stream().map(entry -> entry.getName()).toList();
                String prefix = flattened.getFileName().toString().replaceFirst("\\.zip$", "") + "/";
                assertTrue(names.contains(prefix + ProjectionCacheIndex.NAME));
                assertTrue(names.contains(prefix + "建筑.litematic"));
                assertTrue(names.stream().anyMatch(name -> name.matches(prefix + "建筑-1{8}(?:-\\d+)?\\.litematic")));
                assertTrue(names.stream().noneMatch(name -> name.endsWith(".png") || name.endsWith("about.json5")));
            }

            Path structured = ProjectionArchiveExporter.export(cache, true);
            try (ZipFile zip = new ZipFile(structured.toFile())) {
                String prefix = structured.getFileName().toString().replaceFirst("\\.zip$", "") + "/";
                assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals(prefix + "0".repeat(64) + "/建筑.litematic")));
                assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals(prefix + "1".repeat(64) + "/建筑.litematic")));
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
