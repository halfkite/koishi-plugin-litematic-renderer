package dev.qqbot.gpuagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 与 Koishi 共用的文件缓存格式。哈希目录是热路径，index.json5 只用于人工查看和索引展示。
 * JSON5 文件写成严格 JSON 子集，Node、Gson 以及常见编辑器都可以直接读取。
 */
final class CacheStore {
    static final int FORMAT_VERSION = 18;
    private static final String LIGHTING_PROFILE = "top-light-v3-per-view-brightness-v2-bottom-base-150-v1-dynamic-fullbright-sim-y64-smart-fill-v4";
    private static final String INDEX_NAME = "index.json5";
    private static final String ABOUT_NAME = "about.json5";
    private static final String[] IMAGE_IDS = {"isometric", "isometric-reverse", "six-face"};
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final AgentConfig config;
    private final Consumer<String> log;

    CacheStore(Path applicationRoot, AgentConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log == null ? ignored -> {} : log;
        this.directory = config.cacheDirectory != null && !config.cacheDirectory.isBlank()
                ? Path.of(config.cacheDirectory).toAbsolutePath().normalize()
                : applicationRoot.resolve("litematic-renderer-cache").toAbsolutePath().normalize();
    }

    Path directory() { return directory; }

    String resourcePackFingerprint() {
        if (config.resourcePacks == null || config.resourcePacks.isEmpty()) return "none";
        StringBuilder value = new StringBuilder();
        for (AgentConfig.ResourcePackEntry pack : config.resourcePacks) {
            if (pack == null || !pack.enabled() || pack.path() == null || pack.path().isBlank()) continue;
            Path path = Path.of(pack.path()).toAbsolutePath().normalize();
            try {
                value.append("enabled|").append(packContentFingerprint(path)).append(';');
            } catch (IOException error) {
                value.append("missing|").append(path).append(';');
            }
        }
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    String hash(byte[] schematic) { return sha256(schematic); }

    String configurationFingerprint(RenderModels.Request request, String toolVersion, String packFingerprint) {
        return sha256(configurationPayload(request, toolVersion, packFingerprint).getBytes(StandardCharsets.UTF_8));
    }

    CacheHit lookup(RenderModels.Request request, byte[] schematic, String toolVersion, String packFingerprint) {
        String fileHash = hash(schematic);
        Path entry = directory.resolve(fileHash);
        Path aboutPath = entry.resolve(ABOUT_NAME);
        try {
            if (!Files.isDirectory(entry) || !Files.isRegularFile(aboutPath)) return null;
            JsonObject about = Protocol.GSON.fromJson(Files.readString(aboutPath), JsonObject.class);
            if (about == null || !fileHash.equals(string(about, "文件哈希"))) return null;
            String expectedSignature = configurationFingerprint(request, toolVersion, packFingerprint);
            String storedSignature = string(about, "出图配置识别数");
            if (storedSignature.isBlank()) storedSignature = string(about, "配置指纹");
            if (!expectedSignature.equals(storedSignature)) return null;
            JsonElement storedViews = about.get("视角");
            if (storedViews == null || !storedViews.equals(Protocol.GSON.toJsonTree(normalizedViews(request.views())))) return null;
            List<RenderModels.Image> images = new ArrayList<>();
            for (RenderModels.View view : request.views()) {
                Path imagePath = entry.resolve(imageName(view.id()));
                if (!Files.isRegularFile(imagePath) || !isPng(imagePath)) return null;
                var image = ImageIO.read(imagePath.toFile());
                if (image == null) return null;
                images.add(new RenderModels.Image(view.id(), imagePath.getFileName().toString(),
                        image.getWidth(), image.getHeight(), imagePath));
            }
            touch(entry);
            return new CacheHit(fileHash, entry, images, string(about, "GPU"));
        } catch (Exception error) {
            log.accept("缓存读取失败，将重新渲染：" + error.getMessage());
            return null;
        }
    }

    List<RenderModels.Image> save(RenderModels.Request request, List<RenderModels.Image> images,
                                  byte[] schematic, RenderModels.TaskMeta meta, String source,
                                  String toolVersion, String packFingerprint, long elapsedMillis, String gpu)
            throws IOException {
        String fileHash = hash(schematic);
        Path entry = directory.resolve(fileHash);
        Files.createDirectories(entry);
        String storedFilename = storedFilename(entry, request.filename());
        if (config.cacheKeepProjections) {
            Path projection = entry.resolve(storedFilename);
            if (!Files.exists(projection)) atomicWrite(projection, schematic);
        }

        JsonArray imageMetadata = new JsonArray();
        List<RenderModels.Image> cachedImages = new ArrayList<>();
        for (RenderModels.Image image : images) {
            String filename = imageName(image.id());
            Path target = entry.resolve(filename);
            Path temporary = target.resolveSibling(filename + ".tmp");
            Files.copy(image.path(), temporary, StandardCopyOption.REPLACE_EXISTING);
            moveReplace(temporary, target);
            cachedImages.add(new RenderModels.Image(image.id(), filename, image.width(), image.height(), target));
            JsonObject item = new JsonObject();
            item.addProperty("id", image.id());
            item.addProperty("文件名", filename);
            item.addProperty("宽", image.width());
            item.addProperty("高", image.height());
            item.addProperty("大小", Files.size(target));
            item.addProperty("SHA-256", sha256(Files.readAllBytes(target)));
            imageMetadata.add(item);
        }

        JsonObject about = new JsonObject();
        about.addProperty("缓存格式版本", FORMAT_VERSION);
        about.addProperty("文件哈希", fileHash);
        about.addProperty("投影文件名", request.filename());
        about.addProperty("存储投影文件名", storedFilename);
        about.addProperty("投影大小", schematic.length);
        about.addProperty("云端插件版本", nullToDefault(request.pluginVersion(), "0"));
        about.addProperty("本地工具版本", Main.VERSION);
        about.addProperty("有效工具版本", Main.VERSION);
        about.addProperty("有效工具", "gpu-agent");
        about.addProperty("Minecraft版本", RuntimeInstaller.MINECRAFT_VERSION);
        about.addProperty("Java版本", System.getProperty("java.version", "unknown"));
        about.addProperty("GPU", nullToDefault(gpu, "unknown"));
        about.addProperty("材质包指纹", packFingerprint);
        about.addProperty("夜视", config.nightVisionEnabled);
        about.addProperty("夜视亮度等级", Math.max(1, Math.min(15, config.nightVisionLevel)));
        about.addProperty("光照配置", LIGHTING_PROFILE);
        String configurationFingerprint = configurationFingerprint(request, Main.VERSION, packFingerprint);
        about.addProperty("出图配置识别数", configurationFingerprint);
        about.addProperty("配置指纹", configurationFingerprint);
        about.add("视角", Protocol.GSON.toJsonTree(normalizedViews(request.views())));
        about.add("视角亮度", brightnessMetadata(request.views()));
        about.add("图片", imageMetadata);
        about.addProperty("来源", nullToDefault(source, "本地"));
        about.addProperty("群号", meta == null ? "-" : nullToDefault(meta.group(), "-"));
        about.addProperty("发送人", meta == null ? "-" : nullToDefault(meta.user(), "-"));
        about.addProperty("最近渲染耗时毫秒", elapsedMillis);
        about.addProperty("更新时间", Instant.now().toString());
        atomicWrite(entry.resolve(ABOUT_NAME), PRETTY_GSON.toJson(about).getBytes(StandardCharsets.UTF_8));
        updateIndex(fileHash, storedFilename);
        touch(entry);
        evict();
        return cachedImages;
    }

    private String storedFilename(Path entry, String requested) throws IOException {
        Path aboutPath = entry.resolve(ABOUT_NAME);
        if (Files.isRegularFile(aboutPath)) {
            try {
                JsonObject about = Protocol.GSON.fromJson(Files.readString(aboutPath), JsonObject.class);
                String stored = string(about, "存储投影文件名");
                if (!stored.isBlank()) return stored;
            } catch (RuntimeException ignored) { }
        }
        return safeProjectionFilename(requested);
    }

    private void updateIndex(String fileHash, String filename) throws IOException {
        Files.createDirectories(directory);
        Path index = directory.resolve(INDEX_NAME);
        JsonArray entries = new JsonArray();
        if (Files.isRegularFile(index)) {
            try {
                JsonElement parsed = Protocol.GSON.fromJson(Files.readString(index), JsonElement.class);
                if (parsed != null && parsed.isJsonArray()) entries = parsed.getAsJsonArray();
            } catch (RuntimeException ignored) { }
        }
        JsonArray updated = new JsonArray();
        boolean found = false;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String hash = string(item, "哈希值");
            if (fileHash.equals(hash)) {
                if (!found) { updated.add(indexEntry(filename, fileHash)); found = true; }
            } else if (hash.matches("[0-9a-fA-F]{64}")) {
                updated.add(indexEntry(string(item, "投影文件名称"), hash));
            }
        }
        if (!found) updated.add(indexEntry(filename, fileHash));
        atomicWrite(index, PRETTY_GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject indexEntry(String filename, String hash) {
        JsonObject item = new JsonObject();
        item.addProperty("投影文件名称", filename == null || filename.isBlank() ? "schematic.litematic" : filename);
        item.addProperty("哈希值", hash);
        item.addProperty("相对路径", hash);
        return item;
    }

    private void evict() {
        long maximum = Math.max(0, config.cacheMaxBytes);
        if (maximum <= 0) return;
        try {
            if (!Files.isDirectory(directory)) return;
            List<Path> entries;
            try (var stream = Files.list(directory)) {
                entries = stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().matches("[0-9a-fA-F]{64}"))
                        .toList();
            }
            long total = entries.stream().mapToLong(CacheStore::size).sum();
            if (total <= maximum) return;
            List<Path> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparingLong(CacheStore::modified));
            for (Path entry : sorted) {
                if (total <= maximum) break;
                long size = size(entry);
                deleteTree(entry);
                total -= size;
            }
        } catch (Exception error) {
            log.accept("缓存清理失败：" + error.getMessage());
        }
    }

    private static String imageName(String id) {
        if ("isometric".equals(id)) return "isometric.png";
        if ("isometric-reverse".equals(id)) return "isometric-reverse.png";
        if ("six-face".equals(id) || "six-faces".equals(id)) return "six-faces.png";
        String safe = id == null ? "view" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        return (safe.isBlank() ? "view" : safe) + ".png";
    }

    static String safeProjectionFilename(String filename) {
        String value = filename == null ? "schematic.litematic" : filename.replace('\u0000', '_');
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").replaceAll("[. ]+$", "").trim();
        if (value.isBlank()) value = "schematic.litematic";
        if (!value.toLowerCase().endsWith(".litematic")) value += ".litematic";
        return value.length() > 180 ? value.substring(0, 170) + ".litematic" : value;
    }

    private static boolean isPng(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < 8) return false;
        try (var input = Files.newInputStream(path)) {
            return java.util.Arrays.equals(input.readNBytes(8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        moveReplace(temporary, target);
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void touch(Path path) {
        try { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now())); }
        catch (IOException ignored) { }
    }

    private static long size(Path path) {
        try (var stream = Files.walk(path)) { return stream.filter(Files::isRegularFile).mapToLong(file -> {
            try { return Files.size(file); } catch (IOException ignored) { return 0; }
        }).sum(); } catch (IOException ignored) { return 0; }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0; }
    }

    private static void deleteTree(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        return object.get(name).getAsString();
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String nullToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private String configurationPayload(RenderModels.Request request, String toolVersion, String packFingerprint) {
        JsonObject payload = new JsonObject();
        payload.addProperty("缓存格式版本", FORMAT_VERSION);
        payload.addProperty("请求版本", request == null ? 0 : request.version());
        payload.addProperty("Minecraft版本", RuntimeInstaller.MINECRAFT_VERSION);
        payload.addProperty("Java版本", System.getProperty("java.version", "unknown"));
        payload.addProperty("本地工具版本", nullToEmpty(toolVersion));
        payload.addProperty("云端插件版本", request == null ? "" : nullToEmpty(request.pluginVersion()));
        payload.addProperty("协议传入配置哈希", request == null ? "" : nullToEmpty(request.renderConfigSha256()));
        payload.addProperty("材质包配置档案", request == null ? "" : nullToEmpty(request.resourcePackProfile()));
        payload.addProperty("材质包指纹", nullToEmpty(packFingerprint));
        payload.addProperty("夜视", config.nightVisionEnabled);
        payload.addProperty("夜视亮度等级", Math.max(1, Math.min(15, config.nightVisionLevel)));
        payload.addProperty("光照配置", LIGHTING_PROFILE);
        payload.add("视角", Protocol.GSON.toJsonTree(normalizedViews(request == null ? null : request.views())));
        return Protocol.GSON.toJson(payload);
    }

    private static List<RenderModels.View> normalizedViews(List<RenderModels.View> views) {
        if (views == null) return List.of();
        return views.stream().map(view -> new RenderModels.View(view.id(), view.name(), view.yaw(), view.pitch(),
                view.zoom(), view.autoFill(), view.width(), view.height(), view.background(),
                view.transparentBackground(), view.supersampling(), view.brightnessFactor())).toList();
    }

    private static JsonArray brightnessMetadata(List<RenderModels.View> views) {
        JsonArray result = new JsonArray();
        if (views == null) return result;
        for (RenderModels.View view : views) {
            JsonObject item = new JsonObject();
            item.addProperty("id", view.id());
            item.addProperty("亮度百分比", Math.round(view.brightnessPercent()));
            item.addProperty("实际亮度倍率", view.brightnessFactor());
            item.addProperty("亮度基准倍率", RenderModels.brightnessBase(view.pitch()));
            result.add(item);
        }
        return result;
    }

    private static String packContentFingerprint(Path path) throws IOException {
        if (Files.isRegularFile(path)) return Files.size(path) + "|" + sha256File(path);
        if (!Files.isDirectory(path)) throw new IOException("资源包不存在: " + path);
        StringBuilder value = new StringBuilder();
        try (var stream = Files.walk(path)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                value.append(path.relativize(file)).append('|').append(Files.size(file)).append('|')
                        .append(sha256File(file)).append(';');
            }
        }
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256File(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    record CacheHit(String fileHash, Path directory, List<RenderModels.Image> images, String gpu) {}
}
