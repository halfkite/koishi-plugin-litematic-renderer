package dev.qqbot.gpuagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small root-level name-to-hash index used by projection search and cache transfer. */
final class ProjectionCacheIndex {
    static final String NAME = "projection-index.json5";
    static final String ORDER_NAME = "projection-order.json5";
    private static final String HASH_PATTERN = "[0-9a-fA-F]{64}";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ProjectionCacheIndex() {}

    /** Rebuilds the index from the hash directories, including caches created by older versions. */
    static synchronized void rebuild(Path cacheDirectory) throws IOException {
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        Files.createDirectories(cache);
        Map<String, Integer> previous = new HashMap<>();
        for (Entry entry : read(cache)) if (entry.ordinal() > 0) previous.put(entry.fileHash(), entry.ordinal());
        List<Entry> entries = new ArrayList<>();
        try (var directories = Files.list(cache)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String hash = directory.getFileName().toString();
                if (!hash.matches(HASH_PATTERN)) continue;
                Entry entry = readDirectory(directory, hash.toLowerCase(Locale.ROOT));
                if (entry != null) entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::fileHash));
        Set<Integer> reserved = new HashSet<>(previous.values());
        Set<Integer> used = new HashSet<>();
        int next = nextOrdinal(cache, Math.max(previous.values().stream().mapToInt(Integer::intValue).max().orElse(0),
                entries.stream().mapToInt(Entry::ordinal).max().orElse(0)) + 1);
        List<Entry> numbered = new ArrayList<>();
        for (Entry entry : entries) {
            boolean indexedBefore = previous.containsKey(entry.fileHash());
            int ordinal = previous.getOrDefault(entry.fileHash(), entry.ordinal());
            if (ordinal <= 0 || (!indexedBefore && reserved.contains(ordinal)) || !used.add(ordinal)) {
                ordinal = next++;
                used.add(ordinal);
            }
            numbered.add(entry.withOrdinal(ordinal));
            writeOrdinalToAbout(cache.resolve(entry.fileHash()), ordinal);
        }
        write(cache, numbered, Math.max(next, used.stream().mapToInt(Integer::intValue).max().orElse(0) + 1));
    }

    /** Updates one index entry without scanning every cached projection after each render. */
    static synchronized void upsert(Path cacheDirectory, String fileHash, String requestedName, String storedName) throws IOException {
        if (fileHash == null || !fileHash.matches(HASH_PATTERN)) return;
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        List<Entry> entries = new ArrayList<>(ensure(cache));
        int ordinal = entries.stream().filter(entry -> fileHash.equalsIgnoreCase(entry.fileHash()))
                .mapToInt(Entry::ordinal).findFirst().orElse(0);
        int next = nextOrdinal(cache, entries.stream().mapToInt(Entry::ordinal).max().orElse(0) + 1);
        if (ordinal <= 0) ordinal = next++;
        entries.removeIf(entry -> fileHash.equalsIgnoreCase(entry.fileHash()));
        String filename = CacheStore.safeProjectionFilename(storedName == null || storedName.isBlank() ? requestedName : storedName);
        String display = requestedName == null || requestedName.isBlank() ? filename : requestedName;
        entries.add(new Entry(stripExtension(display), filename, fileHash.toLowerCase(Locale.ROOT), fileHash.toLowerCase(Locale.ROOT), ordinal));
        entries.sort(Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::fileHash));
        Files.createDirectories(cache);
        writeOrdinalToAbout(cache.resolve(fileHash.toLowerCase(Locale.ROOT)), ordinal);
        write(cache, entries, next);
    }

    static List<Entry> read(Path cacheDirectory) throws IOException {
        Path index = cacheDirectory.toAbsolutePath().normalize().resolve(NAME);
        if (!Files.isRegularFile(index)) return List.of();
        try {
            JsonElement parsed = Protocol.GSON.fromJson(Files.readString(index), JsonElement.class);
            return parse(parsed);
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    static List<Entry> ensure(Path cacheDirectory) throws IOException {
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        Path index = cache.resolve(NAME);
        if (!Files.isRegularFile(index)) rebuild(cache);
        List<Entry> entries = read(cache);
        if ((entries.isEmpty() && hasHashDirectory(cache)) || entries.stream().anyMatch(entry -> entry.ordinal() <= 0)) {
            rebuild(cache);
            entries = read(cache);
        }
        return entries;
    }

    static List<Entry> parse(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonArray()) return List.of();
        List<Entry> result = new ArrayList<>();
        for (JsonElement value : parsed.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject object = value.getAsJsonObject();
            String hash = string(object, "哈希值");
            if (!hash.matches(HASH_PATTERN)) continue;
            String filename = string(object, "投影文件名称");
            if (filename.isBlank()) filename = string(object, "存储投影文件名");
            filename = CacheStore.safeProjectionFilename(filename);
            String display = string(object, "投影名称");
            if (display.isBlank()) display = string(object, "投影文件名");
            if (display.isBlank()) display = filename;
            int ordinal = integer(object, "序号");
            result.add(new Entry(stripExtension(display), filename, hash.toLowerCase(Locale.ROOT), hash.toLowerCase(Locale.ROOT), ordinal));
        }
        return List.copyOf(result);
    }

    private static Entry readDirectory(Path directory, String hash) {
        try {
            String requested = "";
            String stored = "";
            int ordinal = 0;
            Path about = directory.resolve("about.json5");
            if (Files.isRegularFile(about)) {
                JsonObject object = Protocol.GSON.fromJson(Files.readString(about), JsonObject.class);
                requested = string(object, "投影文件名");
                stored = string(object, "存储投影文件名");
                ordinal = integer(object, "缓存序号");
            }
            Path projection = stored.isBlank() ? null : directory.resolve(stored).normalize();
            if (projection == null || !projection.startsWith(directory) || !Files.isRegularFile(projection)) {
                try (var files = Files.list(directory)) {
                    projection = files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                            .findFirst().orElse(null);
                }
            }
            if (projection == null) return null;
            LitematicMetadata.validateNbtRoot(projection);
            ensureSavedAt(about, projection);
            String filename = projection.getFileName().toString();
            String display = requested.isBlank() ? filename : requested;
            return new Entry(stripExtension(display), filename, hash, hash, ordinal);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void ensureSavedAt(Path aboutPath, Path projection) {
        if (!Files.isRegularFile(aboutPath)) return;
        try {
            JsonObject about = Protocol.GSON.fromJson(Files.readString(aboutPath), JsonObject.class);
            if (about == null || (about.has("投影保存时间") && !about.get("投影保存时间").isJsonNull())) return;
            Long savedAt = LitematicMetadata.parse(projection).createdAtMillis();
            if (savedAt == null) return;
            about.addProperty("投影保存时间", savedAt);
            Path temporary = aboutPath.resolveSibling(aboutPath.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(about), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, aboutPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, aboutPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) { }
    }

    private static void write(Path cache, List<Entry> entries, int nextOrdinal) throws IOException {
        JsonArray array = new JsonArray();
        for (Entry entry : entries.stream().sorted(Comparator.comparingInt(Entry::ordinal)).toList()) {
            JsonObject object = new JsonObject();
            object.addProperty("序号", entry.ordinal());
            object.addProperty("投影名称", entry.displayName());
            object.addProperty("投影文件名称", entry.filename());
            object.addProperty("哈希值", entry.fileHash());
            object.addProperty("相对路径", entry.relativePath());
            array.add(object);
        }
        JsonObject order = new JsonObject();
        order.addProperty("下一个序号", nextOrdinal);
        writeAtomic(cache.resolve(ORDER_NAME), GSON.toJson(order));
        writeAtomic(cache.resolve(NAME), GSON.toJson(array));
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            Path temporary = target.resolveSibling(target.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException error) {
                last = error;
                try { Thread.sleep(30L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            } finally {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
        throw last;
    }

    private static int nextOrdinal(Path cache, int minimum) {
        try {
            Path path = cache.resolve(ORDER_NAME);
            if (!Files.isRegularFile(path)) return Math.max(1, minimum);
            JsonObject order = Protocol.GSON.fromJson(Files.readString(path), JsonObject.class);
            return Math.max(Math.max(1, minimum), integer(order, "下一个序号"));
        } catch (Exception ignored) { return Math.max(1, minimum); }
    }

    static synchronized void importNextOrdinal(Path cache, int importedNext) throws IOException {
        if (importedNext <= 1) return;
        Files.createDirectories(cache);
        JsonObject order = new JsonObject();
        order.addProperty("下一个序号", nextOrdinal(cache, importedNext));
        writeAtomic(cache.resolve(ORDER_NAME), GSON.toJson(order));
    }

    private static void writeOrdinalToAbout(Path directory, int ordinal) {
        Path path = directory.resolve("about.json5");
        if (!Files.isRegularFile(path)) return;
        try {
            JsonObject about = Protocol.GSON.fromJson(Files.readString(path), JsonObject.class);
            if (about == null || integer(about, "缓存序号") == ordinal) return;
            about.addProperty("缓存序号", ordinal);
            writeAtomic(path, GSON.toJson(about));
        } catch (Exception ignored) { }
    }

    static void prepareImportedOrdinal(Path cache, String hash, int ordinal) throws IOException {
        if (ordinal <= 0 || hash == null || !hash.matches(HASH_PATTERN)) return;
        Path path = cache.resolve(hash).resolve("about.json5");
        if (!Files.isRegularFile(path)) return;
        JsonObject about = Protocol.GSON.fromJson(Files.readString(path), JsonObject.class);
        if (about == null || integer(about, "缓存序号") > 0) return;
        about.addProperty("缓存序号", ordinal);
        writeAtomic(path, GSON.toJson(about));
    }

    private static int integer(JsonObject object, String key) {
        try { return object != null && object.has(key) ? Math.max(0, object.get(key).getAsInt()) : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    private static boolean hasHashDirectory(Path cache) throws IOException {
        if (!Files.isDirectory(cache)) return false;
        try (var directories = Files.list(cache)) {
            return directories.anyMatch(path -> Files.isDirectory(path)
                    && path.getFileName().toString().matches(HASH_PATTERN));
        }
    }

    private static String stripExtension(String value) {
        String result = value == null || value.isBlank() ? "schematic" : value;
        return result.toLowerCase(Locale.ROOT).endsWith(".litematic")
                ? result.substring(0, result.length() - ".litematic".length()) : result;
    }

    private static String string(JsonObject object, String key) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    record Entry(String displayName, String filename, String fileHash, String relativePath, int ordinal) {
        Entry withOrdinal(int value) { return new Entry(displayName, filename, fileHash, relativePath, value); }
    }
}
