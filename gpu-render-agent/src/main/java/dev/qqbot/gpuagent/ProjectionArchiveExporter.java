package dev.qqbot.gpuagent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 将统一缓存中的投影源文件和名称哈希索引导出为一个 ZIP，不包含图片或 about 记录。 */
final class ProjectionArchiveExporter {
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MMdd-HHmm");
    private static final String HASH_PATTERN = "[0-9a-fA-F]{64}";

    private ProjectionArchiveExporter() {}

    static Path export(Path cacheDirectory, boolean keepHashDirectories) throws IOException {
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        Path parent = cache.getParent() == null ? cache : cache.getParent();
        String stem = "缓存投影文件" + LocalDateTime.now().format(NAME_TIME);
        Path target = uniqueTarget(parent, stem + ".zip");
        String archiveRoot = target.getFileName().toString().replaceFirst("\\.zip$", "");
        ProjectionCacheIndex.rebuild(cache);
        List<ProjectionFile> projections = findProjections(cache);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".cache-projections-", ".tmp");
        try {
            Set<String> usedNames = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.putNextEntry(new ZipEntry(archiveRoot + "/"));
                zip.closeEntry();
                Path index = cache.resolve(ProjectionCacheIndex.NAME);
                if (Files.isRegularFile(index)) {
                    zip.putNextEntry(new ZipEntry(archiveRoot + "/" + ProjectionCacheIndex.NAME));
                    try (InputStream input = Files.newInputStream(index)) { input.transferTo(zip); }
                    zip.closeEntry();
                }
                Path order = cache.resolve(ProjectionCacheIndex.ORDER_NAME);
                if (Files.isRegularFile(order)) {
                    zip.putNextEntry(new ZipEntry(archiveRoot + "/" + ProjectionCacheIndex.ORDER_NAME));
                    try (InputStream input = Files.newInputStream(order)) { input.transferTo(zip); }
                    zip.closeEntry();
                }
                for (ProjectionFile projection : projections) {
                    String filename = keepHashDirectories
                            ? projection.path().getFileName().toString()
                            : uniqueFilename(projection.path().getFileName().toString(), projection.hash(), usedNames);
                    String relative = keepHashDirectories
                            ? archiveRoot + "/" + projection.hash() + "/" + filename
                            : archiveRoot + "/" + filename;
                    zip.putNextEntry(new ZipEntry(relative));
                    try (InputStream input = Files.newInputStream(projection.path())) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
                zip.finish();
            }
            moveReplace(temporary, target);
            return target;
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
    }

    private static List<ProjectionFile> findProjections(Path cacheDirectory) throws IOException {
        if (!Files.isDirectory(cacheDirectory)) return List.of();
        List<ProjectionFile> result = new ArrayList<>();
        try (var stream = Files.walk(cacheDirectory)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                Path hashDirectory = findHashDirectory(cacheDirectory, path);
                if (hashDirectory != null) {
                    result.add(new ProjectionFile(path, hashDirectory.getFileName().toString()));
                }
            }
        }
        return result;
    }

    private static Path findHashDirectory(Path cacheDirectory, Path file) {
        Path current = file.getParent();
        while (current != null && current.startsWith(cacheDirectory)) {
            Path name = current.getFileName();
            if (name != null && name.toString().matches(HASH_PATTERN)) return current;
            if (current.equals(cacheDirectory)) break;
            current = current.getParent();
        }
        return null;
    }

    private static String uniqueFilename(String requested, String hash, Set<String> usedNames) {
        String base = requested == null || requested.isBlank() ? "schematic.litematic" : requested;
        String candidate = base;
        int counter = 1;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            int extension = base.toLowerCase(Locale.ROOT).endsWith(".litematic")
                    ? base.length() - ".litematic".length() : base.length();
            int suffix = counter++;
            candidate = base.substring(0, extension) + "-" + hash.substring(0, 8)
                    + (suffix == 1 ? "" : "-" + suffix) + base.substring(extension);
        }
        return candidate;
    }

    private static Path uniqueTarget(Path parent, String filename) {
        Path target = parent.resolve(filename);
        if (!Files.exists(target)) return target;
        int index = 2;
        while (Files.exists(parent.resolve(filename.substring(0, filename.length() - 4) + "-" + index + ".zip"))) index++;
        return parent.resolve(filename.substring(0, filename.length() - 4) + "-" + index + ".zip");
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ProjectionFile(Path path, String hash) {}
}
