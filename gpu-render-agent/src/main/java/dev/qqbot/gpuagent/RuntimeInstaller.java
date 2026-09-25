package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class RuntimeInstaller {
    static final String MINECRAFT_VERSION = "26.3";
    static final String FABRIC_LOADER_VERSION = "0.19.5";
    /**
     * Bump this whenever the Fabric launch/class-loader setup changes. Fabric's
     * processed nested-mod output is not compatible across those changes even
     * when the bundled renderer JAR itself has the same SHA-256.
     */
    private static final String FABRIC_CACHE_FORMAT = "fabric-cache-v3";
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_PROFILE = "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";
    private static final String MODRINTH_VERSION = "https://api.modrinth.com/v2/version/%s";
    private static final List<ModVersion> UPSTREAM_MODS = List.of(
            new ModVersion("Fabric API", "bNnaTiuM", "fabric-api-0.161.0+26.3.jar",
                    "ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d"),
            new ModVersion("MaLiLib", "DPcJACN6", "malilib-fabric-26.3-0.30.1.jar",
                    "65ea34b14757ce1c37f87bc4a519d1290d2a715f77bcb370e47b46d99756b5fccb4f18a636e425dfe66e7526791945273782813fb828e6b9686a2024ce1250bc"),
            new ModVersion("Litematica", "fEqsesPK", "litematica-fabric-26.3-0.29.0.jar",
                    "cf0c0310acf8a40eb2a3365061f10611d048771e65a63e6f9679df5da282406d27d93b259b98dee5a9d2107cc21b3c06935608c311cad3110fb0985d9683f3a1"));

    private record ModVersion(String name, String versionId, String filename, String sha512) {}

    private final Path runtimeRoot;
    private final Path gameDirectory;
    private final AgentConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private Consumer<String> log = ignored -> {};
    private Consumer<InstallProgress> progressListener = ignored -> {};
    private volatile InstallProgress progress = InstallProgress.idle();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong completedBytes = new AtomicLong();
    private final AtomicLong totalFiles = new AtomicLong();
    private final AtomicLong completedFiles = new AtomicLong();
    private volatile String stage = "未安装";
    private volatile String currentFile = "";

    RuntimeInstaller(Path runtimeRoot, AgentConfig config) {
        this.runtimeRoot = runtimeRoot;
        this.gameDirectory = runtimeRoot.resolve("game");
        this.config = config;
    }

    RuntimeInstaller(Path runtimeRoot) { this(runtimeRoot, null); }

    void setLog(Consumer<String> log) { this.log = log; }

    void setProgress(Consumer<InstallProgress> listener) { this.progressListener = listener == null ? ignored -> {} : listener; }

    InstallProgress progress() { return progress; }

    LaunchSpec install() throws Exception {
        beginInstall();
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(gameDirectory.resolve("mods"));
        installMods();
        JsonObject manifest = json(VERSION_MANIFEST);
        JsonObject versionRef = null;
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            if (MINECRAFT_VERSION.equals(element.getAsJsonObject().get("id").getAsString())) {
                versionRef = element.getAsJsonObject();
                break;
            }
        }
        if (versionRef == null) throw new IOException("官方版本清单中没有 Minecraft " + MINECRAFT_VERSION);
        JsonObject version = json(versionRef.get("url").getAsString());
        JsonObject fabric = json(FABRIC_PROFILE.formatted(MINECRAFT_VERSION, FABRIC_LOADER_VERSION));

        Path clientJar = runtimeRoot.resolve("versions").resolve(MINECRAFT_VERSION).resolve(MINECRAFT_VERSION + ".jar");
        installClient(version.getAsJsonObject("downloads").getAsJsonObject("client"), clientJar);
        JsonObject assetIndex = version.getAsJsonObject("assetIndex");
        Path assetIndexPath = runtimeRoot.resolve("assets/indexes").resolve(assetIndex.get("id").getAsString() + ".json");
        download(assetIndex, assetIndexPath);
        installAssets(Protocol.GSON.fromJson(Files.readString(assetIndexPath), JsonObject.class));

        List<Path> classpath = new ArrayList<>();
        Path natives = runtimeRoot.resolve("natives").resolve(MINECRAFT_VERSION);
        Files.createDirectories(natives);
        installLibraries(version.getAsJsonArray("libraries"), classpath, natives);
        installLibraries(fabric.getAsJsonArray("libraries"), classpath, natives);
        classpath.add(clientJar);
        for (int index = 0; index < classpath.size(); index++) {
            classpath.set(index, normalizeClasspathEntry(classpath.get(index)));
        }
        orderClasspathForLaunch(classpath);

        List<String> gameArguments = resolveGameArguments(version);
        List<String> fabricJvmArguments = resolveArguments(fabric.getAsJsonObject("arguments"), "jvm");
        String assetId = assetIndex.get("id").getAsString();
        finishInstall();
        return new LaunchSpec(fabric.get("mainClass").getAsString(), classpath, natives, fabricJvmArguments, gameArguments, assetId);
    }

    private void beginInstall() {
        totalBytes.set(0); completedBytes.set(0); totalFiles.set(0); completedFiles.set(0);
        updateProgress("准备 Minecraft " + MINECRAFT_VERSION, "", true);
    }

    private void finishInstall() { updateProgress("Minecraft " + MINECRAFT_VERSION + " 已准备", "", true); }

    private void updateProgress(String nextStage, String file, boolean force) {
        stage = nextStage == null ? "" : nextStage;
        currentFile = file == null ? "" : file;
        InstallProgress next = new InstallProgress(stage, currentFile, completedBytes.get(), totalBytes.get(), completedFiles.get(), totalFiles.get(), System.currentTimeMillis());
        progress = next;
        if (force || next.changedFrom(progress)) {
            try { progressListener.accept(next); } catch (Throwable ignored) { }
        } else {
            try { progressListener.accept(next); } catch (Throwable ignored) { }
        }
    }

    private void emitProgress() {
        InstallProgress next = new InstallProgress(stage, currentFile, completedBytes.get(), totalBytes.get(), completedFiles.get(), totalFiles.get(), System.currentTimeMillis());
        progress = next;
        try { progressListener.accept(next); } catch (Throwable ignored) { }
    }

    private void registerFile(long size) {
        totalFiles.incrementAndGet();
        if (size > 0) totalBytes.addAndGet(size);
        emitProgress();
    }

    private void completeFile(long size, boolean downloaded) {
        if (!downloaded && size > 0) completedBytes.addAndGet(size);
        completedFiles.incrementAndGet();
        emitProgress();
    }

    record InstallProgress(String stage, String currentFile, long completedBytes, long totalBytes,
                           long completedFiles, long totalFiles, long timestamp) {
        static InstallProgress idle() { return new InstallProgress("未安装", "", 0, 0, 0, 0, System.currentTimeMillis()); }
        double fraction() {
            if (totalBytes > 0) return Math.max(0, Math.min(1, completedBytes / (double) totalBytes));
            return totalFiles > 0 ? Math.max(0, Math.min(1, completedFiles / (double) totalFiles)) : 0;
        }
        boolean changedFrom(InstallProgress other) { return other == null || timestamp != other.timestamp; }
    }

    static Path normalizeClasspathEntry(Path path) {
        // Keep the logical runtime root: resolving junctions can mix libraries from
        // different installations and split Fabric/Mixin across class loaders.
        return path.toAbsolutePath().normalize();
    }

    static void orderClasspathForLaunch(List<Path> classpath) {
        // Fabric's launcher and Mixin must be resolved from one deterministic
        // application class path. The metadata order can put sponge-mixin before
        // fabric-loader, which makes companion mixin plugins load in Knot while
        // IMixinConfigPlugin comes from the app loader.
        classpath.sort(Comparator.comparing(path -> path.toString().toLowerCase(java.util.Locale.ROOT)));
    }

    private void installMods() throws Exception {
        Path target = gameDirectory.resolve("mods/litematic-gpu-runtime.jar");
        copyResource("/renderer/litematic-gpu-runtime.jar", target);
        for (ModVersion mod : UPSTREAM_MODS) installUpstreamMod(mod);
        ensureFabricCacheCurrent(gameDirectory, target, log);
    }

    private void installUpstreamMod(ModVersion mod) throws Exception {
        Path target = gameDirectory.resolve("mods").resolve(mod.filename());
        if (Files.isRegularFile(target) && mod.sha512().equalsIgnoreCase(hash(target, "SHA-512"))) {
            registerFile(Files.size(target));
            completeFile(Files.size(target), false);
            return;
        }
        JsonObject version = json(MODRINTH_VERSION.formatted(mod.versionId()));
        JsonObject file = null;
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject candidate = element.getAsJsonObject();
            if (mod.filename().equals(candidate.get("filename").getAsString())) {
                file = candidate;
                break;
            }
        }
        if (file == null || !mod.sha512().equalsIgnoreCase(file.getAsJsonObject("hashes").get("sha512").getAsString())) {
            throw new IOException("Modrinth 上的 " + mod.name() + " 固定版本文件与预期不符");
        }
        String url = file.get("url").getAsString();
        if (!"cdn.modrinth.com".equalsIgnoreCase(URI.create(url).getHost())) {
            throw new IOException("Modrinth 文件地址不是官方 CDN：" + mod.name());
        }
        Path verified = target.resolveSibling(mod.filename() + ".download");
        Files.deleteIfExists(verified);
        try {
            updateProgress("下载 Modrinth 模组 " + mod.name(), mod.filename(), true);
            download(url, verified, file.get("size").getAsLong());
            if (!mod.sha512().equalsIgnoreCase(hash(verified, "SHA-512"))) {
                throw new IOException(mod.name() + " SHA-512 校验失败");
            }
            Files.move(verified, target, StandardCopyOption.REPLACE_EXISTING);
            log.accept("已从 Modrinth 安装原版 " + mod.name() + "：" + mod.filename());
        } finally {
            Files.deleteIfExists(verified);
        }
    }

    /** 并行客户端使用同一组已经校验过的原版模组文件。 */
    void copyBundledMods(Path targetGameDirectory) throws IOException {
        Files.createDirectories(targetGameDirectory.resolve("mods"));
        List<String> filenames = new ArrayList<>();
        filenames.add("litematic-gpu-runtime.jar");
        for (ModVersion mod : UPSTREAM_MODS) filenames.add(mod.filename());
        for (String filename : filenames) {
            Path source = gameDirectory.resolve("mods").resolve(filename);
            Path target = targetGameDirectory.resolve("mods").resolve(filename);
            if (!Files.isRegularFile(source)) throw new IOException("主客户端缺少模组：" + filename);
            if (!Files.isRegularFile(target) || !sameFileContent(source, target)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        ensureFabricCacheCurrent(targetGameDirectory, targetGameDirectory.resolve("mods/litematic-gpu-runtime.jar"), log);
    }

    /**
     * Fabric caches nested JARs under the game directory. Keep a content marker
     * so an upgraded runtime cannot reuse processed modules from an older JAR.
     */
    static void ensureFabricCacheCurrent(Path gameDirectory, Path runtimeJar, Consumer<String> log) throws IOException {
        List<Path> mods;
        Path modsDirectory = gameDirectory.resolve("mods");
        if (Files.isDirectory(modsDirectory)) {
            try (var files = Files.list(modsDirectory)) {
                mods = files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
        } else {
            mods = List.of(runtimeJar);
        }
        StringBuilder signature = new StringBuilder(FABRIC_CACHE_FORMAT);
        for (Path mod : mods) signature.append(':').append(mod.getFileName()).append('=').append(sha256(mod));
        String fingerprint = signature.toString();
        Path fabricDirectory = gameDirectory.resolve(".fabric");
        Path marker = fabricDirectory.resolve("litematic-gpu-runtime.sha256");
        String previous = Files.isRegularFile(marker) ? Files.readString(marker).trim() : "";
        if (fingerprint.equalsIgnoreCase(previous)) return;

        boolean hadProcessedCache = Files.exists(fabricDirectory.resolve("processedMods"))
                || Files.exists(fabricDirectory.resolve("remappedJars"));
        clearFabricProcessingCaches(gameDirectory);
        Files.createDirectories(fabricDirectory);
        Files.writeString(marker, fingerprint + System.lineSeparator());
        if (hadProcessedCache && log != null) log.accept("渲染组件版本变化，已清理 Fabric 处理缓存");
    }

    static void clearFabricProcessingCaches(Path gameDirectory) throws IOException {
        Path fabricDirectory = gameDirectory.resolve(".fabric");
        for (String name : List.of("processedMods", "remappedJars")) {
            Path cache = fabricDirectory.resolve(name);
            if (!Files.exists(cache)) continue;
            try (var paths = Files.walk(cache)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        return hash(file, "SHA-256");
    }

    private static String hash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream input = RuntimeInstaller.class.getResourceAsStream(resource)) {
            if (input == null) {
                if (Files.exists(target)) return;
                throw new IOException("安装包缺少渲染组件：" + resource);
            }
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            // 目标文件被占用（如另一实例的 Minecraft 正在运行）且内容一致时，跳过覆盖。
            // JAR 内容变化后文件大小可能不变，不能只比较长度。
            if (Files.exists(target) && sameFileContent(target, temporary)) {
                Files.deleteIfExists(temporary);
                return;
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                Files.deleteIfExists(temporary);
                throw new IOException(
                        "渲染组件 " + target.getFileName() + " 有更新但文件被占用，请关闭残留 Minecraft 渲染进程后重试",
                        locked
                );
            }
        }
    }

    static boolean sameFileContent(Path first, Path second) throws IOException {
        return Files.size(first) == Files.size(second) && Files.mismatch(first, second) == -1L;
    }

    private void installClient(JsonObject descriptor, Path target) throws Exception {
        String configured = config == null || config.localMinecraftClientPath == null ? "" : config.localMinecraftClientPath.trim();
        if (configured.isBlank()) {
            download(descriptor, target);
            return;
        }
        Path source = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) throw new IOException("本地 Minecraft 客户端不存在或不可读：" + source);
        if (!isJar(source)) throw new IOException("本地 Minecraft 客户端不是有效 JAR：" + source);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target) || !sameFileContent(source, target)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        String expected = descriptor.has("sha1") ? descriptor.get("sha1").getAsString() : "";
        if (!expected.isBlank() && !expected.equalsIgnoreCase(sha1(source))) {
            log.accept("警告：本地客户端 SHA-1 与官方 Minecraft 26.3 不同，若启动失败请换用官方客户端 JAR：" + source.getFileName());
        } else log.accept("已导入本地 Minecraft 26.3 客户端：" + source);
        registerFile(Files.size(source));
        completeFile(Files.size(source), false);
    }

    private static boolean isJar(Path file) {
        try (ZipFile ignored = new ZipFile(file.toFile())) {
            return ignored.getEntry("META-INF/MANIFEST.MF") != null || ignored.getEntry("net/minecraft/client/main/Main.class") != null;
        } catch (IOException ignored) { return false; }
    }

    private void installAssets(JsonObject index) throws Exception {
        Map<String, AssetDownload> downloads = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : index.getAsJsonObject("objects").entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            long size = object.has("size") ? object.get("size").getAsLong() : 0;
            downloads.putIfAbsent(hash, new AssetDownload(runtimeRoot.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash), size));
        }
        log.accept("检查 Minecraft 资源文件（" + downloads.size() + " 个对象）");
        try (var executor = Executors.newFixedThreadPool(Math.min(16, Runtime.getRuntime().availableProcessors() * 2))) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, AssetDownload> entry : downloads.entrySet()) {
                if (Files.exists(entry.getValue().path())) continue;
                futures.add(executor.submit(() -> {
                    try { download("https://resources.download.minecraft.net/" + entry.getKey().substring(0, 2) + "/" + entry.getKey(), entry.getValue().path(), entry.getValue().size()); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                }));
            }
            for (var future : futures) future.get();
        }
    }

    private record AssetDownload(Path path, long size) {}

    private void installLibraries(JsonArray libraries, List<Path> classpath, Path natives) throws Exception {
        if (libraries == null) return;
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            if (!rulesAllow(library.getAsJsonArray("rules"))) continue;
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (downloads != null && downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                Path target = runtimeRoot.resolve("libraries").resolve(artifact.get("path").getAsString());
                download(artifact, target);
                classpath.add(target);
            } else {
                String coordinate = library.get("name").getAsString();
                Path relative = mavenPath(coordinate);
                String base = library.has("url") ? library.get("url").getAsString() : "https://libraries.minecraft.net/";
                Path target = runtimeRoot.resolve("libraries").resolve(relative);
                download(base + (base.endsWith("/") ? "" : "/") + relative.toString().replace('\\', '/'), target);
                classpath.add(target);
            }
            if (downloads != null && downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                JsonObject nativeArtifact = selectNativeClassifier(classifiers);
                if (nativeArtifact != null) {
                    Path zip = runtimeRoot.resolve("libraries").resolve(nativeArtifact.get("path").getAsString());
                    download(nativeArtifact, zip);
                    extractNatives(zip, natives);
                }
            }
        }
    }

    private static Path mavenPath(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) throw new IllegalArgumentException("无效 Maven 坐标：" + coordinate);
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return Path.of(parts[0].replace('.', '/'), parts[1], parts[2], parts[1] + "-" + parts[2] + classifier + ".jar");
    }

    private static void extractNatives(Path archive, Path directory) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) throw new IOException("native ZIP 路径越界");
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<String> resolveGameArguments(JsonObject version) {
        JsonObject arguments = version.getAsJsonObject("arguments");
        return resolveArguments(arguments, "game");
    }

    private static List<String> resolveArguments(JsonObject arguments, String key) {
        List<String> output = new ArrayList<>();
        if (arguments == null || !arguments.has(key)) return output;
        JsonElement list = arguments.get(key);
        JsonArray values = list.isJsonArray() ? list.getAsJsonArray() : new JsonArray();
        if (!list.isJsonArray()) values.add(list);
        for (JsonElement element : values) {
            if (element.isJsonPrimitive()) output.add(element.getAsString());
            else {
                JsonObject object = element.getAsJsonObject();
                if (!rulesAllow(object.getAsJsonArray("rules"))) continue;
                JsonElement value = object.get("value");
                if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) output.add(item.getAsString());
                else output.add(value.getAsString());
            }
        }
        return output;
    }

    private static boolean rulesAllow(JsonArray rules) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            JsonObject os = rule.getAsJsonObject("os");
            if (os != null && os.has("name") && !currentOsName().equals(os.get("name").getAsString())) continue;
            if (os != null && os.has("arch") && !matchesArch(os.get("arch").getAsString())) continue;
            if (rule.has("features")) continue;
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static JsonObject selectNativeClassifier(JsonObject classifiers) {
        String os = currentOsName();
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean is64 = arch.contains("64") || arch.contains("amd64") || arch.contains("aarch64") || arch.contains("arm64");
        List<String> preferred = new ArrayList<>();
        if ("windows".equals(os)) {
            if (is64) preferred.add("natives-windows-64");
            preferred.add("natives-windows");
        } else if ("linux".equals(os)) {
            if (is64) preferred.add("natives-linux-64");
            preferred.add("natives-linux");
        } else {
            preferred.add("natives-osx");
            preferred.add("natives-macos");
        }
        for (String name : preferred) if (classifiers.has(name)) return classifiers.getAsJsonObject(name);
        for (Map.Entry<String, JsonElement> entry : classifiers.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("natives-" + os) && (!is64 || !name.matches(".*-(arm|x86)$"))) return entry.getValue().getAsJsonObject();
        }
        return null;
    }

    private static String currentOsName() {
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (name.contains("win")) return "windows";
        if (name.contains("mac") || name.contains("darwin")) return "osx";
        return "linux";
    }

    private static boolean matchesArch(String requested) {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return switch (requested.toLowerCase(java.util.Locale.ROOT)) {
            case "x86", "i386", "i686" -> arch.matches("i[3-6]86|x86");
            case "x86_64", "amd64" -> arch.contains("64") || arch.contains("amd64");
            case "aarch64", "arm64" -> arch.contains("aarch64") || arch.contains("arm64");
            default -> true;
        };
    }

    private JsonObject json(String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("下载元数据失败 HTTP " + response.statusCode() + "：" + url);
        return Protocol.GSON.fromJson(response.body(), JsonObject.class);
    }

    private void download(JsonObject descriptor, Path target) throws Exception {
        download(descriptor.get("url").getAsString(), target, descriptor.has("size") ? descriptor.get("size").getAsLong() : 0);
    }

    private void download(String url, Path target) throws Exception {
        download(url, target, 0);
    }

    private void download(String url, Path target, long expectedSize) throws Exception {
        registerFile(expectedSize);
        boolean modDownload = target.getParent() != null
                && "mods".equals(target.getParent().getFileName().toString())
                && target.getFileName().toString().endsWith(".jar.download");
        currentFile = modDownload
                ? target.getFileName().toString().replaceFirst("\\.download$", "")
                : target.getFileName().toString();
        stage = modDownload ? "下载 Modrinth 模组"
                : target.toString().contains("assets") ? "下载 Minecraft 资源" : "下载运行时文件";
        emitProgress();
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            completeFile(Files.size(target), false);
            return;
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(temporary);
            response.body().close();
            throw new IOException("下载失败 HTTP " + response.statusCode() + "：" + url);
        }
        long responseSize = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        if (expectedSize <= 0 && responseSize > 0) {
            totalBytes.addAndGet(responseSize);
            emitProgress();
        }
        long copied = 0;
        try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                copied += read;
                completedBytes.addAndGet(read);
                if ((copied & ((256 * 1024) - 1)) < buffer.length) emitProgress();
            }
        } catch (Throwable error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        completeFile(copied, true);
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    record LaunchSpec(String mainClass, List<Path> classpath, Path natives, List<String> jvmArguments,
                      List<String> gameArguments, String assetIndex) {}
}
