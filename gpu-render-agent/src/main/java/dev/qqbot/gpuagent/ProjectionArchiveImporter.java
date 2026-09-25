package dev.qqbot.gpuagent;

import com.google.gson.JsonElement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports projection-only cache archives produced by {@link ProjectionArchiveExporter}. */
final class ProjectionArchiveImporter {
    static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    private ProjectionArchiveImporter() {}

    static ImportResult importArchive(Path archive, CacheStore cacheStore) throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) throw new IOException("缓存压缩包不存在");
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) throw new IOException("缓存压缩包超过 512 MB");
        List<Path> imported = new ArrayList<>();
        int existing = 0;
        int invalid = 0;
        long totalBytes = 0;
        java.util.Set<String> existingHashes = new java.util.HashSet<>();
        for (ProjectionCacheIndex.Entry indexed : ProjectionCacheIndex.ensure(cacheStore.directory())) {
            existingHashes.add(indexed.fileHash());
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Map<String, ProjectionCacheIndex.Entry> indexed = readIndex(zip);
            ProjectionCacheIndex.importNextOrdinal(cacheStore.directory(), readNextOrdinal(zip));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (!name.toLowerCase(Locale.ROOT).endsWith(".litematic")) continue;
                if (!safeEntry(name)) { invalid++; continue; }
                if (entry.getSize() > MAX_ENTRY_BYTES) { invalid++; continue; }
                byte[] bytes = readEntry(zip, entry, totalBytes);
                totalBytes += bytes.length;
                if (totalBytes > MAX_ARCHIVE_BYTES) throw new IOException("压缩包解压内容超过 512 MB");
                try {
                    LitematicMetadata.validateNbtRoot(bytes);
                } catch (IOException invalidProjection) {
                    invalid++;
                    continue;
                }
                String filename = name.substring(name.lastIndexOf('/') + 1);
                String contentHash = cacheStore.hash(bytes);
                ProjectionCacheIndex.Entry source = indexed.get(contentHash);
                if (source != null) filename = source.filename();
                CacheStore.ImportedProjection result = cacheStore.importProjection(bytes, filename);
                if (result.created()) {
                    imported.add(result.path());
                    if (source != null && !existingHashes.contains(contentHash)) {
                        ProjectionCacheIndex.prepareImportedOrdinal(cacheStore.directory(), contentHash, source.ordinal());
                    }
                }
                else existing++;
            }
            // Rebuild once after all entries so a large archive does not rescan thousands of cache folders per file.
            ProjectionCacheIndex.rebuild(cacheStore.directory());
        }
        if (imported.isEmpty() && existing == 0) throw new IOException("压缩包中没有可导入的 .litematic 投影文件");
        return new ImportResult(List.copyOf(imported), existing, invalid, totalBytes);
    }

    private static Map<String, ProjectionCacheIndex.Entry> readIndex(ZipFile zip) throws IOException {
        Map<String, ProjectionCacheIndex.Entry> result = new HashMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
            if (entry.isDirectory() || !name.endsWith("/" + ProjectionCacheIndex.NAME)) continue;
            try (InputStream input = zip.getInputStream(entry)) {
                JsonElement parsed = Protocol.GSON.fromJson(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonElement.class);
                for (ProjectionCacheIndex.Entry indexed : ProjectionCacheIndex.parse(parsed)) {
                    result.putIfAbsent(indexed.fileHash(), indexed);
                }
            } catch (RuntimeException ignored) {
                // The source files remain authoritative if an older/corrupt index is bundled.
            }
            break;
        }
        return result;
    }

    private static int readNextOrdinal(ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
            if (entry.isDirectory() || !name.endsWith("/" + ProjectionCacheIndex.ORDER_NAME)) continue;
            try (InputStream input = zip.getInputStream(entry)) {
                var order = Protocol.GSON.fromJson(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                        com.google.gson.JsonObject.class);
                return order == null || !order.has("下一个序号") ? 0 : Math.max(0, order.get("下一个序号").getAsInt());
            } catch (RuntimeException ignored) { return 0; }
        }
        return 0;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, long totalBefore) throws IOException {
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long count = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                count += read;
                if (count > MAX_ENTRY_BYTES || totalBefore + count > MAX_ARCHIVE_BYTES) {
                    throw new IOException("压缩包解压内容超过允许上限");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean safeEntry(String name) {
        if (name.isBlank() || name.indexOf('\u0000') >= 0 || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return false;
        for (String part : name.split("/")) if (part.equals("..") || part.isBlank()) return false;
        return true;
    }

    record ImportResult(List<Path> importedFiles, int existingFiles, int invalidEntries, long uncompressedBytes) {
        int importedCount() { return importedFiles.size(); }
    }
}
