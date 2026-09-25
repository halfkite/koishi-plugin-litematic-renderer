package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Linux/无桌面部署使用的轻量管理后台。静态页面直接打入 Agent JAR。 */
final class WebAdminServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESOURCE_REQUEST_BYTES = 350 * 1024 * 1024;
    private static final int MAX_RUNTIME_CLIENT_REQUEST_BYTES = 450 * 1024 * 1024;
    private static final int MAX_CACHE_IMPORT_REQUEST_BYTES = 700 * 1024 * 1024;
    private final Path root;
    private final Path configPath;
    private final AgentConfig config;
    private final RenderService renderer;
    private final BotManager bots;
    private final CloudConnection cloud;
    private final Consumer<String> log;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile HttpServer server;

    WebAdminServer(Path root, Path configPath, AgentConfig config, RenderService renderer, BotManager bots, CloudConnection cloud, Consumer<String> log) {
        this.root = root; this.configPath = configPath; this.config = config; this.renderer = renderer; this.bots = bots; this.cloud = cloud;
        this.log = log == null ? ignored -> {} : log;
    }

    void start() throws IOException {
        if (server != null || !config.webEnabled) return;
        server = HttpServer.create(new InetSocketAddress(config.webBindHost, config.webPort), 32);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.accept("Web 管理后台已启动：http://" + config.webBindHost + ":" + config.webPort + "/");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/api/")) { servePage(exchange); return; }
            if ("/api/auth/login".equals(path)) { login(exchange); return; }
            if (!authorized(exchange)) return;
            if ("/api/auth/change-password".equals(path)) { changePassword(exchange); return; }
            if ("/api/auth/change-username".equals(path)) { changeUsername(exchange); return; }
            if ("/api/config".equals(path)) { config(exchange); return; }
            if ("/api/commands/sync".equals(path)) { syncCommands(exchange); return; }
            if ("/api/status".equals(path)) { status(exchange); return; }
            if ("/api/logs".equals(path)) { logs(exchange); return; }
            if ("/api/logs/export".equals(path)) { exportLogs(exchange); return; }
            if (path.startsWith("/api/accounts/") && path.endsWith("/reload")) { reloadAccount(exchange, path); return; }
            if ("/api/resource-packs".equals(path)) { resourcePack(exchange); return; }
            if ("/api/runtime-client".equals(path)) { runtimeClient(exchange); return; }
            if ("/api/cache/import".equals(path)) { importCache(exchange); return; }
            if ("/api/cache/reindex".equals(path)) { rebuildCacheIndex(exchange); return; }
            json(exchange, 404, error("接口不存在"));
        } catch (Throwable error) {
            log.accept("Web 管理请求失败：" + error.getMessage());
            json(exchange, 500, error(error.getMessage() == null ? "服务器内部错误" : error.getMessage()));
        }
    }

    private void servePage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) { text(exchange, 404, "not found"); return; }
        try (var input = WebAdminServer.class.getResourceAsStream("/web/index.html")) {
            if (input == null) { text(exchange, 500, "Web UI resource is missing"); return; }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        String address = exchange.getRemoteAddress() == null ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
        Attempt attempt = attempts.get(address);
        if (attempt != null && attempt.blockedUntil > System.currentTimeMillis()) { json(exchange, 429, error("登录失败次数过多，请稍后再试")); return; }
        JsonObject body = parseBody(exchange);
        String username = string(body, "username"); String password = string(body, "password");
        if (!constantEquals(config.webUsername, username) || !constantEquals(config.webPassword, password)) {
            Attempt next = attempts.computeIfAbsent(address, ignored -> new Attempt()); next.failures++;
            if (next.failures >= 5) next.blockedUntil = System.currentTimeMillis() + 5 * 60 * 1000L;
            json(exchange, 401, error("用户名或密码错误")); return;
        }
        attempts.remove(address);
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        sessions.put(token, new Session(System.currentTimeMillis() + Math.max(5 * 60 * 1000L, config.webSessionTimeoutMillis)));
        exchange.getResponseHeaders().add("Set-Cookie", "LITEMATIC_AGENT_SESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (config.webSessionTimeoutMillis / 1000));
        JsonObject result = new JsonObject(); result.addProperty("ok", true); result.addProperty("passwordChangeNotice", config.webPasswordChangeNotice); json(exchange, 200, result);
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String token = cookie(exchange.getRequestHeaders(), "LITEMATIC_AGENT_SESSION");
        Session session = token == null ? null : sessions.get(token);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            if (token != null) sessions.remove(token);
            json(exchange, 401, error("请先登录")); return false;
        }
        session.expiresAt = System.currentTimeMillis() + Math.max(5 * 60 * 1000L, config.webSessionTimeoutMillis);
        return true;
    }

    private void changePassword(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        JsonObject body = parseBody(exchange); String first = string(body, "newPassword"); String second = string(body, "newPasswordAgain");
        if (first.isBlank() || !constantEquals(first, second)) { json(exchange, 400, error("两次新密码必须一致且不能为空")); return; }
        config.changeWebPassword(configPath, first, second);
        json(exchange, 200, ok());
    }

    private void changeUsername(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        String username = string(parseBody(exchange), "newUsername").trim();
        if (username.isBlank() || username.length() > 64 || username.chars().anyMatch(Character::isISOControl)) {
            json(exchange, 400, error("账户名称不能为空、不能超过 64 个字符且不能包含控制字符")); return;
        }
        config.webUsername = username;
        config.save(configPath);
        try { AgentConfig.writeCredentialHintForCurrentUser(configPath, config.webUsername, config.webPassword); }
        catch (Exception error) { log.accept("更新 Web 凭据提示文件失败：" + error.getMessage()); }
        json(exchange, 200, ok());
    }

    private void config(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) { json(exchange, 200, redactedConfig()); return; }
        if (!"PUT".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        String oldBindHost = config.webBindHost;
        int oldPort = config.webPort;
        String oldCacheDirectory = config.cacheDirectory;
        String oldJavaPath = config.javaPath;
        int oldConcurrentRenders = config.maxConcurrentRenders;
        String oldWebUsername = config.webUsername;
        String oldResourcePacks = Protocol.GSON.toJson(config.resourcePacks);
        String oldBotProfiles = Protocol.GSON.toJson(config.botProfiles);
        String oldCommands = Protocol.GSON.toJson(config.commands);
        JsonObject incoming = parseBody(exchange);
        JsonObject merged = merge(Protocol.GSON.toJsonTree(config).getAsJsonObject(), incoming);
        AgentConfig updated = Protocol.GSON.fromJson(merged, AgentConfig.class);
        if (updated == null) throw new IllegalArgumentException("配置格式无效");
        updated.webPassword = config.webPassword;
        updated.webPasswordChangeNotice = config.webPasswordChangeNotice;
        if (updated.webPort < 1 || updated.webPort > 65535) throw new IllegalArgumentException("Web 端口必须为 1-65535");
        if (updated.webUsername == null || updated.webUsername.isBlank()) throw new IllegalArgumentException("网页登录用户名不能为空");
        if (updated.webSessionTimeoutMillis < 5 * 60 * 1000L) throw new IllegalArgumentException("会话时长不能少于 5 分钟");
        if (updated.maxFileSizeKb < 1 || updated.privateMaxFileSizeKb < 1) throw new IllegalArgumentException("全局文件大小上限必须为正整数 KB");
        if (updated.projectionSearchResultLimit < 1 || updated.projectionSearchResultLimit > 100) throw new IllegalArgumentException("搜索结果数量必须为 1-100");
        if (updated.cacheRecentImageMaxBytes < 0 || updated.cacheHistoricalImageMaxBytes < 0) throw new IllegalArgumentException("近期和历史图片缓存上限不能为负数");
        updated.cacheMaxBytes = updated.cacheRecentImageMaxBytes + updated.cacheHistoricalImageMaxBytes;
        if (!"compact".equalsIgnoreCase(updated.metadataFormat)
                && !"image".equalsIgnoreCase(updated.metadataFormat)) updated.metadataFormat = "full";
        if (updated.botProfiles != null) for (AgentConfig.BotProfile profile : updated.botProfiles) if (profile != null) profile.normalize();
        updated.normalizeCommandDefinitions();
        OfficialSlashCommandPanels.validateConfiguredNames(updated);
        boolean restartRequired = !java.util.Objects.equals(oldBindHost, updated.webBindHost) || oldPort != updated.webPort
                || config.webEnabled != updated.webEnabled
                || !java.util.Objects.equals(oldCacheDirectory, updated.cacheDirectory)
                || !java.util.Objects.equals(oldJavaPath, updated.javaPath)
                || oldConcurrentRenders != updated.maxConcurrentRenders;
        boolean resourcePacksChanged = !java.util.Objects.equals(oldResourcePacks, Protocol.GSON.toJson(updated.resourcePacks));
        boolean botProfilesChanged = !java.util.Objects.equals(oldBotProfiles, Protocol.GSON.toJson(updated.botProfiles));
        boolean commandsChanged = !java.util.Objects.equals(oldCommands, Protocol.GSON.toJson(updated.commands));
        boolean startupChanged = config.startWithWindows != updated.startWithWindows;
        boolean cloudChanged = config.cloudEnabled != updated.cloudEnabled
                || !java.util.Objects.equals(config.cloudWebSocketUrl, updated.cloudWebSocketUrl)
                || !java.util.Objects.equals(config.agentId, updated.agentId)
                || !java.util.Objects.equals(config.sharedSecret, updated.sharedSecret);
        if (resourcePacksChanged) {
            try { new ResourcePackManager(config, renderer.runtime(), log).applyTransactional(updated.resourcePacks == null ? List.of() : updated.resourcePacks); }
            catch (Exception error) { throw new IOException("资源包配置应用失败：" + error.getMessage(), error); }
        }
        config.applyFrom(updated); config.save(configPath);
        if (!java.util.Objects.equals(oldWebUsername, config.webUsername)) {
            try { AgentConfig.writeCredentialHintForCurrentUser(configPath, config.webUsername, config.webPassword); }
            catch (Exception error) { log.accept("更新 Web 凭据提示文件失败：" + error.getMessage()); }
        }
        if (startupChanged) {
            try { StartupManager.setEnabled(config.startWithWindows); }
            catch (Exception error) { log.accept("同步 Windows 启动项失败，配置已保存：" + error.getMessage()); }
        }
        if (cloudChanged) cloud.reload();
        if (botProfilesChanged) bots.reload();
        else if (commandsChanged) bots.syncOfficialCommandPanels();
        JsonObject result = ok(); result.addProperty("restartRequired", restartRequired); json(exchange, 200, result);
    }

    private void syncCommands(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        bots.syncOfficialCommandPanels();
        json(exchange, 200, ok());
    }

    private void status(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("version", Main.VERSION); result.addProperty("web", "http://" + config.webBindHost + ":" + config.webPort + "/");
        result.addProperty("queueLength", renderer.queueLength()); result.addProperty("queuedRequestBytes", renderer.retainedRequestBytes());
        result.addProperty("queuedRequestLimitBytes", config.maxQueuedRequestBytes); result.addProperty("busy", renderer.isBusy());
        result.addProperty("currentFile", renderer.currentFile() == null ? "" : renderer.currentFile()); result.addProperty("cacheDirectory", renderer.cacheDirectory().toString());
        RenderModels.RuntimeStatus runtime = renderer.runtime().currentStatus();
        if (runtime != null) { result.addProperty("runtimeReady", runtime.ready()); result.addProperty("gpu", runtime.gpu()); result.addProperty("minecraftVersion", runtime.minecraftVersion()); result.addProperty("stage", runtime.stage()); result.addProperty("progress", runtime.progress()); }
        RuntimeInstaller.InstallProgress install = renderer.runtime().installProgress();
        result.addProperty("installStage", install.stage()); result.addProperty("installFile", install.currentFile());
        result.addProperty("installProgress", install.fraction()); result.addProperty("installCompletedBytes", install.completedBytes());
        result.addProperty("installTotalBytes", install.totalBytes()); result.addProperty("installCompletedFiles", install.completedFiles()); result.addProperty("installTotalFiles", install.totalFiles());
        BotManager.DownloadProgress attachment = bots.downloadProgress();
        result.addProperty("attachmentDownloadFile", attachment.file()); result.addProperty("attachmentDownloadActive", attachment.active());
        result.addProperty("attachmentDownloadProgress", attachment.fraction()); result.addProperty("attachmentDownloadCompletedBytes", attachment.downloadedBytes());
        result.addProperty("attachmentDownloadTotalBytes", attachment.totalBytes()); result.addProperty("attachmentDownloadError", attachment.error());
        JsonArray accounts = new JsonArray(); for (BotStatus item : bots.statuses()) { JsonObject value = new JsonObject(); value.addProperty("id", item.profileId()); value.addProperty("name", item.name()); value.addProperty("type", item.type()); value.addProperty("enabled", item.enabled()); value.addProperty("connected", item.connected()); value.addProperty("state", item.state()); value.addProperty("reconnects", item.reconnects()); value.addProperty("lastError", item.lastError()); accounts.add(value); } result.add("accounts", accounts);
        json(exchange, 200, result);
    }

    private void logs(HttpExchange exchange) throws IOException {
        int limit = 200;
        try { if (exchange.getRequestURI().getQuery() != null) limit = Math.max(1, Math.min(1000, Integer.parseInt(exchange.getRequestURI().getQuery().replaceFirst(".*limit=", "")))); } catch (RuntimeException ignored) { }
        List<String> lines = new ArrayList<>(); for (Path file : List.of(root.resolve("agent-gui.log"), root.resolve("agent.log"), root.resolve("agent-crash.log"))) if (Files.isRegularFile(file)) { lines = Files.readAllLines(file); if (!lines.isEmpty()) break; }
        int start = Math.max(0, lines.size() - limit); JsonArray values = new JsonArray(); for (int i = start; i < lines.size(); i++) values.add(lines.get(i)); JsonObject result = new JsonObject(); result.add("lines", values); json(exchange, 200, result);
    }

    private void exportLogs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            List<Path> files = List.of(
                    root.resolve("agent.log"), root.resolve("agent-gui.log"), root.resolve("agent-gui.log.old"),
                    root.resolve("agent-crash.log"), root.resolve("send-debug.log"), root.resolve("cache-debug.log"),
                    root.resolve("runtime-crash.log"), root.resolve("runtime/launch-diagnostics.txt"),
                    root.resolve("runtime/minecraft-runtime.log"), root.resolve("runtime/minecraft-runtime-2.log"),
                    root.resolve("runtime/minecraft-runtime-3.log"));
            for (Path file : files) addZipFile(zip, file, null);
            if (Files.isRegularFile(configPath)) {
                JsonObject safe = redactedConfig();
                addZipFile(zip, configPath, Protocol.GSON.toJson(safe).getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] body = bytes.toByteArray();
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"litematic-agent-logs.zip\"");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void addZipFile(ZipOutputStream zip, Path file, byte[] replacement) throws IOException {
        if (replacement == null && !Files.isRegularFile(file)) return;
        byte[] data = replacement == null ? Files.readAllBytes(file) : replacement;
        zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
        zip.write(data);
        zip.closeEntry();
    }

    private void reloadAccount(HttpExchange exchange, String path) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        String id = path.substring("/api/accounts/".length(), path.length() - "/reload".length()); bots.reload(id); json(exchange, 200, ok());
    }

    private void resourcePack(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            JsonArray packs = redactedConfig().getAsJsonArray("resourcePacks");
            json(exchange, 200, packs == null ? new JsonArray() : packs);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        JsonObject body = parseBody(exchange, MAX_RESOURCE_REQUEST_BYTES); String filename = safeFilename(string(body, "filename")); String encoded = string(body, "base64");
        if (filename.isBlank() || encoded.isBlank()) throw new IllegalArgumentException("filename 和 base64 不能为空");
        byte[] bytes = Base64.getDecoder().decode(encoded); if (bytes.length > 256 * 1024 * 1024) throw new IllegalArgumentException("资源包超过 256 MB");
        Path directory = root.resolve("resource-packs"); Files.createDirectories(directory); Path target = directory.resolve(filename); Files.write(target, bytes);
        List<AgentConfig.ResourcePackEntry> entries = new ArrayList<>(config.resourcePacks == null ? List.of() : config.resourcePacks);
        entries.removeIf(item -> item != null && item.path().equals(target.toString()));
        entries.add(new AgentConfig.ResourcePackEntry(target.toString(), true));
        try { new ResourcePackManager(config, renderer.runtime(), log).applyTransactional(entries); }
        catch (Exception error) { throw new IOException("资源包应用失败：" + error.getMessage(), error); }
        config.save(configPath); json(exchange, 200, ok());
    }

    private void runtimeClient(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        JsonObject body = parseBody(exchange, MAX_RUNTIME_CLIENT_REQUEST_BYTES);
        String filename = safeFilename(string(body, "filename"));
        String encoded = string(body, "base64");
        if (filename.isBlank() || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") || encoded.isBlank()) {
            throw new IllegalArgumentException("请上传 .jar 格式的 Minecraft 26.3 客户端");
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("客户端文件不是有效的 Base64 数据", error); }
        if (bytes.length > 300 * 1024 * 1024) throw new IllegalArgumentException("客户端 JAR 超过 300 MB");
        Path directory = root.resolve("imports"); Files.createDirectories(directory);
        Path target = directory.resolve("minecraft-26.3-client.jar");
        Path temporary = directory.resolve("minecraft-26.3-client.jar.part");
        Files.write(temporary, bytes);
        if (!isZipFile(temporary)) { Files.deleteIfExists(temporary); throw new IllegalArgumentException("上传文件不是有效的 JAR"); }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        config.localMinecraftClientPath = target.toAbsolutePath().toString(); config.save(configPath);
        log.accept("已导入本地 Minecraft 26.3 客户端：" + filename + "，将使用它准备内置运行时");
        Thread.startVirtualThread(() -> {
            try { renderer.runtime().importLocalClient(target); log.accept("本地 Minecraft 26.3 客户端已准备完成"); }
            catch (Throwable error) { log.accept("本地 Minecraft 客户端准备失败：" + error.getMessage()); }
        });
        JsonObject result = ok(); result.addProperty("path", target.toString()); result.addProperty("message", "文件已导入，正在准备运行时，可在状态页查看进度"); json(exchange, 200, result);
    }

    private void importCache(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        JsonObject body = parseBody(exchange, MAX_CACHE_IMPORT_REQUEST_BYTES);
        String filename = safeFilename(string(body, "filename"));
        String encoded = string(body, "base64");
        if (filename.isBlank() || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") || encoded.isBlank()) {
            throw new IllegalArgumentException("请上传缓存 ZIP 压缩包");
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("缓存压缩包不是有效的 Base64 数据", error); }
        if (bytes.length > ProjectionArchiveImporter.MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("缓存压缩包超过 512 MB");
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, ".cache-import-", ".zip");
        try {
            Files.write(temporary, bytes);
            if (!isZipFile(temporary)) throw new IllegalArgumentException("上传文件不是有效的 ZIP 压缩包");
            ProjectionArchiveImporter.ImportResult result = renderer.importCacheArchive(temporary);
            JsonObject response = ok();
            response.addProperty("imported", result.importedCount());
            response.addProperty("existing", result.existingFiles());
            response.addProperty("invalid", result.invalidEntries());
            response.addProperty("uncompressedBytes", result.uncompressedBytes());
            response.addProperty("message", "缓存投影已导入，图片会按当前配置重新生成");
            json(exchange, 200, response);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void rebuildCacheIndex(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, error("method not allowed")); return; }
        int count = renderer.rebuildProjectionIndex();
        JsonObject response = ok();
        response.addProperty("count", count);
        response.addProperty("index", renderer.cacheDirectory().resolve(ProjectionCacheIndex.NAME).toString());
        response.addProperty("message", "已重建投影名称与哈希索引");
        json(exchange, 200, response);
    }

    private static boolean isZipFile(Path file) {
        try (java.util.zip.ZipFile ignored = new java.util.zip.ZipFile(file.toFile())) { return true; }
        catch (IOException ignored) { return false; }
    }

    private JsonObject redactedConfig() {
        JsonObject value = Protocol.GSON.toJsonTree(config).getAsJsonObject();
        value.addProperty("webPassword", ""); value.addProperty("webPasswordConfigured", !config.webPassword.isBlank()); value.addProperty("sharedSecret", "");
        JsonArray profiles = value.getAsJsonArray("botProfiles");
        if (profiles != null) for (JsonElement element : profiles) if (element.isJsonObject()) { JsonObject profile = element.getAsJsonObject(); profile.addProperty("appSecret", ""); profile.addProperty("accessToken", ""); }
        return value;
    }

    private JsonObject parseBody(HttpExchange exchange) throws IOException {
        return parseBody(exchange, MAX_BODY_BYTES);
    }

    private static JsonObject parseBody(HttpExchange exchange, int limit) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(limit + 1); if (body.length > limit) throw new IllegalArgumentException("请求体过大");
        JsonObject value = Protocol.GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class); if (value == null) throw new IllegalArgumentException("JSON 请求无效"); return value;
    }

    private static JsonObject merge(JsonObject current, JsonObject incoming) {
        for (Map.Entry<String, JsonElement> entry : incoming.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject() && current.has(entry.getKey()) && current.get(entry.getKey()).isJsonObject()) merge(current.getAsJsonObject(entry.getKey()), value.getAsJsonObject());
            else if (("sharedSecret".equals(entry.getKey()) || "webPassword".equals(entry.getKey())) && value.isJsonPrimitive() && value.getAsString().isBlank()) { }
            else if ("botProfiles".equals(entry.getKey()) && value.isJsonArray()) mergeProfileSecrets(current, value);
            else current.add(entry.getKey(), value);
        }
        return current;
    }

    private static void mergeProfileSecrets(JsonObject current, JsonElement incoming) {
        JsonArray old = current.getAsJsonArray("botProfiles"); JsonArray next = incoming.getAsJsonArray();
        for (JsonElement element : next) if (element.isJsonObject()) {
            JsonObject profile = element.getAsJsonObject(); String id = string(profile, "id");
            for (JsonElement previousElement : old) if (previousElement.isJsonObject() && id.equals(string(previousElement.getAsJsonObject(), "id"))) {
                JsonObject previous = previousElement.getAsJsonObject(); preserveSecret(profile, previous, "appSecret"); preserveSecret(profile, previous, "accessToken"); break;
            }
        }
        current.add("botProfiles", next);
    }

    private static void preserveSecret(JsonObject next, JsonObject previous, String key) { if (!next.has(key) || next.get(key).isJsonNull() || next.get(key).getAsString().isBlank()) next.addProperty(key, string(previous, key)); }
    private static String cookie(Headers headers, String name) { String value = headers.getFirst("Cookie"); if (value == null) return null; for (String part : value.split(";")) { String[] pair = part.trim().split("=", 2); if (pair.length == 2 && name.equals(pair[0])) return pair[1]; } return null; }
    private static boolean constantEquals(String left, String right) { if (left == null || right == null) return false; return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)); }
    private static String string(JsonObject value, String key) { try { return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; } catch (RuntimeException ignored) { return ""; } }
    private static JsonObject ok() { JsonObject value = new JsonObject(); value.addProperty("ok", true); return value; }
    private static JsonObject error(String value) { JsonObject result = new JsonObject(); result.addProperty("error", value == null ? "未知错误" : value); return result; }
    private static void json(HttpExchange exchange, int status, JsonElement value) throws IOException { byte[] bytes = Protocol.GSON.toJson(value).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
    private static void text(HttpExchange exchange, int status, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
    private static String safeFilename(String value) { if (value == null) return ""; String name = value.replace('\\', '/'); int slash = name.lastIndexOf('/'); if (slash >= 0) name = name.substring(slash + 1); return name.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static final class Session {
        volatile long expiresAt;
        Session(long expiresAt) { this.expiresAt = expiresAt; }
    }
    private static final class Attempt { int failures; long blockedUntil; }
    @Override public void close() { if (server != null) server.stop(1); server = null; sessions.clear(); }
}
