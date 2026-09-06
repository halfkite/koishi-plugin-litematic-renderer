package dev.qqbot.gpuagent;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 管理多个机器人账号，并把平台事件接入统一的投影渲染管线。 */
final class BotManager implements AutoCloseable {
    private final Path root;
    private final Path configPath;
    private final AgentConfig config;
    private final RenderService renderer;
    private final Consumer<String> log;
    private final Map<String, BotAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, Long> handledMessages = new ConcurrentHashMap<>();
    private final List<String> recentLogs = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    BotManager(Path root, Path configPath, AgentConfig config, RenderService renderer, Consumer<String> log) {
        this.root = root;
        this.configPath = configPath;
        this.config = config;
        this.renderer = renderer;
        this.log = message -> {
            String value = message == null ? "" : message;
            recentLogs.add(value);
            while (recentLogs.size() > 300) recentLogs.removeFirst();
            if (log != null) log.accept(value);
        };
    }

    synchronized void start() {
        if (closed) return;
        reload();
    }

    /** 配置保存后只重启机器人适配器，渲染任务和 Minecraft Runtime 保持复用。 */
    synchronized void reload() {
        if (closed) return;
        closeAdapters();
        if (config.botProfiles == null) config.botProfiles = new ArrayList<>();
        for (AgentConfig.BotProfile profile : config.botProfiles) {
            if (profile == null) continue;
            profile.normalize();
            if (!profile.enabled) continue;
            try {
                startAdapter(profile);
            } catch (Throwable error) {
                log.accept("启动机器人账号失败［" + profile.name + "］：" + message(error));
            }
        }
        log.accept("机器人账号已加载：" + adapters.size() + " 个");
    }

    List<BotStatus> statuses() {
        List<BotStatus> result = new ArrayList<>();
        if (config.botProfiles == null) return result;
        for (AgentConfig.BotProfile profile : config.botProfiles) {
            if (profile == null) continue;
            BotAdapter adapter = adapters.get(profile.id);
            result.add(adapter == null
                    ? new BotStatus(profile.id, profile.name, profile.type, profile.enabled, false,
                    profile.enabled ? "未连接" : "已停用", 0, "", 0)
                    : adapter.status());
        }
        return result;
    }

    synchronized void reload(String profileId) {
        if (closed || profileId == null || profileId.isBlank()) return;
        BotAdapter previous = adapters.remove(profileId);
        if (previous != null) {
            try { previous.close(); } catch (Throwable error) { log.accept("关闭机器人账号失败：" + message(error)); }
        }
        AgentConfig.BotProfile profile = profile(profileId);
        if (profile == null || !profile.enabled) return;
        profile.normalize();
        try { startAdapter(profile); }
        catch (Throwable error) { log.accept("重载机器人账号失败［" + profile.name + "］：" + message(error)); }
    }

    List<String> recentLogs() { return List.copyOf(recentLogs); }

    private void accept(BotAdapter adapter, BotMessage message) {
        if (closed || message == null || message.attachments() == null || message.attachments().isEmpty()) return;
        long now = System.currentTimeMillis();
        String messageKey = message.profileId() + ":message:" + (message.messageId() == null ? "" : message.messageId());
        String deliveryKey = deliveryFingerprint(message);
        synchronized (handledMessages) {
            handledMessages.entrySet().removeIf(entry -> entry.getValue() < now);
            // QQ Gateway 在重连或重复投递时偶尔会改变/省略消息 ID；文件指纹在短窗口内兜底去重。
            if (message.messageId() != null && !message.messageId().isBlank()
                    && handledMessages.putIfAbsent(messageKey, now + 10 * 60 * 1000L) != null) {
                return;
            }
            if (handledMessages.putIfAbsent(deliveryKey, now + 60 * 1000L) != null) {
                log.accept("忽略重复的投影文件事件：" + attachmentName(message));
                handledMessages.remove(messageKey, now + 10 * 60 * 1000L);
                return;
            }
        }
        Thread.startVirtualThread(() -> process(adapter, message));
    }

    private static String deliveryFingerprint(BotMessage message) {
        StringBuilder value = new StringBuilder()
                .append(message.profileId()).append('|')
                .append(message.direct()).append('|')
                .append(nullToEmpty(message.groupId())).append('|')
                .append(nullToEmpty(message.userId())).append('|')
                .append(nullToEmpty(message.rawText()));
        for (BotAttachment attachment : message.attachments()) {
            if (attachment == null) continue;
            value.append('|').append(nullToEmpty(attachment.name())).append('|').append(attachment.size());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("delivery:");
            for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "delivery:" + value;
        }
    }

    private static String attachmentName(BotMessage message) {
        return message.attachments().stream().filter(item -> item != null && item.name() != null && !item.name().isBlank())
                .map(BotAttachment::name).findFirst().orElse("未知文件");
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private void process(BotAdapter adapter, BotMessage message) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        if (profile == null || !profile.enabled) return;
        if (!message.direct() && "mentionOnly".equalsIgnoreCase(profile.groupMessageMode) && !message.mentioned()) return;
        if (message.direct() && !profile.allowPrivateRender) return;
        if (!message.direct() && !isGroupAllowed(profile, message.groupId())) return;

        BotAttachment source = message.attachments().stream()
                .filter(item -> item != null && isLitematic(item.name(), item.url()))
                .findFirst().orElse(null);
        if (source == null) return;
        try {
            BotAttachment resolved = adapter.resolveAttachment(message, source).join();
            if (resolved == null) throw new IOException("附件解析失败");
            long limit = effectiveFileLimitKb(config, profile, message.direct()) * 1024L;
            if (resolved.size() > 0 && resolved.size() > limit) throw new IOException("文件超过 " + (limit / 1024) + " KB 限制");
            byte[] schematic = download(resolved, limit);
            List<RenderModels.View> views = configuredViews();
            String filename = safeName(resolved.name(), "schematic.litematic");
            RenderModels.Request request = new RenderModels.Request(2, UUID.randomUUID().toString(), filename,
                    views, null, Main.VERSION, null);
            RenderModels.Result result = renderer.submit(request, schematic, Duration.ofMillis(config.renderTimeoutMillis),
                    "机器人:" + profile.name, null, new RenderModels.TaskMeta(message.groupId(), message.userId())).join();
            adapter.sendResult(message, result, metadata(filename, schematic));
            log.accept("机器人［" + profile.name + "］已处理投影：" + filename + (result.cacheHit() ? "（缓存命中）" : ""));
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            log.accept("机器人［" + profile.name + "］投影处理失败：" + message(cause));
            try { adapter.sendText(message, "投影渲染失败，请检查渲染器配置或导出诊断文件。\n" + message(cause)); }
            catch (Throwable sendError) { log.accept("机器人错误消息发送失败：" + message(sendError)); }
        }
    }

    private byte[] download(BotAttachment attachment, long limit) throws Exception {
        if (attachment.url() == null || attachment.url().isBlank()) throw new IOException("附件没有可下载地址");
        String value = attachment.url();
        // QQ 官方附件通常是带查询参数的 HTTPS 直链，不能先交给 Windows Path 解析。
        if (isHttpUrl(value)) return downloadHttp(value, limit);
        if (value.regionMatches(true, 0, "file:", 0, 5)) return limitedRead(Path.of(URI.create(value)), limit);
        Path local = Path.of(value);
        if (Files.isRegularFile(local)) return limitedRead(local, limit);
        throw new IOException("附件地址不是支持的 HTTP(S) 直链或本地文件");
    }

    static boolean isHttpUrl(String value) {
        return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8));
    }

    static byte[] downloadHttp(String value, long limit) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(value)).timeout(Duration.ofSeconds(90)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("附件下载失败 HTTP " + response.statusCode());
        if (response.body().length > limit) throw new IOException("文件超过 " + (limit / 1024) + " KB 限制");
        return response.body();
    }

    private static byte[] limitedRead(Path path, long limit) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("附件文件不存在");
        if (Files.size(path) > limit) throw new IOException("文件超过 " + (limit / 1024) + " KB 限制");
        return Files.readAllBytes(path);
    }

    private List<RenderModels.View> configuredViews() {
        if (config.views != null && !config.views.isEmpty()) {
            List<RenderModels.View> result = new ArrayList<>();
            for (AgentConfig.ViewEntry view : config.views) if (view != null) {
                result.add(new RenderModels.View(view.id(), view.name(), view.yaw(), view.pitch(), view.zoom(),
                        view.autoFillEnabled(), view.width(), view.height(), view.background(), view.transparentBackground(),
                        view.supersampling(), view.brightnessFactor()));
            }
            if (!result.isEmpty()) return result;
        }
        int width = Math.max(64, config.renderWidth);
        int height = Math.max(64, config.renderHeight);
        return List.of(
                new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true, width, height, "#000000", false, 1),
                new RenderModels.View("isometric-reverse", "反向正二轴测", 315, 36, 0.82, true, width, height, "#000000", false, 1));
    }

    private String metadata(String filename, byte[] schematic) {
        return LitematicMetadata.format(schematic, filename, config);
    }

    static long effectiveFileLimitKb(AgentConfig config, AgentConfig.BotProfile profile, boolean direct) {
        long override = profile == null ? 0 : (direct ? profile.privateMaxFileSizeKb : profile.maxFileSizeKb);
        if (override > 0) return override;
        long global = config == null ? 1024 : (direct ? config.privateMaxFileSizeKb : config.maxFileSizeKb);
        return Math.max(1, global);
    }

    private AgentConfig.BotProfile profile(String id) {
        if (config.botProfiles == null) return null;
        return config.botProfiles.stream().filter(item -> item != null && item.id.equals(id)).findFirst().orElse(null);
    }

    private static boolean isGroupAllowed(AgentConfig.BotProfile profile, String groupId) {
        if (groupId == null || groupId.isBlank()) return true;
        if (profile.groupBlacklistEnabled && profile.groupBlacklist.stream().anyMatch(groupId::equals)) return false;
        return !profile.groupWhitelistEnabled || profile.groupWhitelist.isEmpty()
                || profile.groupWhitelist.stream().anyMatch(groupId::equals);
    }

    private static boolean isLitematic(String name, String url) {
        String value = name == null || name.isBlank() ? url : name;
        return value != null && value.toLowerCase(java.util.Locale.ROOT).split("[?#]", 2)[0].endsWith(".litematic");
    }

    private static String safeName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        String value = name.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        return value.isBlank() ? fallback : value;
    }

    private static String message(Throwable error) { return error == null ? "未知错误" : String.valueOf(error.getMessage() == null ? error : error.getMessage()); }

    private void closeAdapters() {
        for (BotAdapter adapter : adapters.values()) try { adapter.close(); } catch (Throwable error) { log.accept("关闭机器人连接失败：" + message(error)); }
        adapters.clear();
    }

    private void startAdapter(AgentConfig.BotProfile profile) {
        BotAdapter adapter = "onebot".equals(profile.type)
                ? new OneBotAdapter(profile, this::accept, log)
                : new OfficialQqAdapter(profile, this::accept, log);
        adapters.put(profile.id, adapter);
        adapter.start();
    }

    @Override public synchronized void close() {
        closed = true;
        closeAdapters();
    }
}
