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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

final class RuntimeInstaller {
    static final String MINECRAFT_VERSION = "26.2";
    static final String FABRIC_LOADER_VERSION = "0.19.3";
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_PROFILE = "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";

    private final Path runtimeRoot;
    private final Path gameDirectory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private Consumer<String> log = ignored -> {};

    RuntimeInstaller(Path runtimeRoot) {
        this.runtimeRoot = runtimeRoot;
        this.gameDirectory = runtimeRoot.resolve("game");
    }

    void setLog(Consumer<String> log) { this.log = log; }

    LaunchSpec install() throws Exception {
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(gameDirectory.resolve("mods"));
        installBundledMods();
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
        download(version.getAsJsonObject("downloads").getAsJsonObject("client"), clientJar);
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
            classpath.set(index, classpath.get(index).toRealPath());
        }

        List<String> gameArguments = resolveGameArguments(version);
        List<String> fabricJvmArguments = resolveArguments(fabric.getAsJsonObject("arguments"), "jvm");
        String assetId = assetIndex.get("id").getAsString();
        return new LaunchSpec(fabric.get("mainClass").getAsString(), classpath, natives, fabricJvmArguments, gameArguments, assetId);
    }

    private void installBundledMods() throws IOException {
        copyResource("/renderer/litematic-gpu-runtime.jar",
                gameDirectory.resolve("mods/litematic-gpu-runtime.jar"));
    }

    /** 为并行渲染的额外客户端槽位准备 mods 目录（其余运行时文件都在共享的 runtime 根目录）。 */
    void copyBundledMods(Path targetGameDirectory) throws IOException {
        Files.createDirectories(targetGameDirectory.resolve("mods"));
        copyResource("/renderer/litematic-gpu-runtime.jar",
                targetGameDirectory.resolve("mods/litematic-gpu-runtime.jar"));
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
            // 目标文件被占用（如另一实例的 Minecraft 正在运行）且内容一致时，跳过覆盖
            if (Files.exists(target) && Files.size(target) == Files.size(temporary)) {
                Files.deleteIfExists(temporary);
                return;
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                // 目标正被运行中的 Minecraft 锁定：内容不同但无法替换，提示后沿用旧文件
                Files.deleteIfExists(temporary);
                System.out.println("[install] " + target.getFileName() + " 被占用且内容有更新，将在下次渲染端重启时更新");
            }
        }
    }

    private void installAssets(JsonObject index) throws Exception {
        Map<String, Path> downloads = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : index.getAsJsonObject("objects").entrySet()) {
            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
            downloads.putIfAbsent(hash, runtimeRoot.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash));
        }
        log.accept("检查 Minecraft 资源文件（" + downloads.size() + " 个对象）");
        try (var executor = Executors.newFixedThreadPool(Math.min(16, Runtime.getRuntime().availableProcessors() * 2))) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, Path> entry : downloads.entrySet()) {
                if (Files.exists(entry.getValue())) continue;
                futures.add(executor.submit(() -> {
                    try { download("https://resources.download.minecraft.net/" + entry.getKey().substring(0, 2) + "/" + entry.getKey(), entry.getValue()); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                }));
            }
            for (var future : futures) future.get();
        }
    }

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
                JsonObject nativeArtifact = classifiers.has("natives-windows")
                        ? classifiers.getAsJsonObject("natives-windows")
                        : classifiers.has("natives-windows-64") ? classifiers.getAsJsonObject("natives-windows-64") : null;
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
            if (os != null && os.has("name") && !"windows".equals(os.get("name").getAsString())) continue;
            if (rule.has("features")) continue;
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private JsonObject json(String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("下载元数据失败 HTTP " + response.statusCode() + "：" + url);
        return Protocol.GSON.fromJson(response.body(), JsonObject.class);
    }

    private void download(JsonObject descriptor, Path target) throws Exception {
        download(descriptor.get("url").getAsString(), target);
    }

    private void download(String url, Path target) throws Exception {
        if (Files.isRegularFile(target) && Files.size(target) > 0) return;
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(temporary);
            throw new IOException("下载失败 HTTP " + response.statusCode() + "：" + url);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    record LaunchSpec(String mainClass, List<Path> classpath, Path natives, List<String> jvmArguments,
                      List<String> gameArguments, String assetIndex) {}
}
