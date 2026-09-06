package dev.qqbot.gpuagent;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class CloudConnection implements WebSocket.Listener, AutoCloseable {
    private final AgentConfig config;
    private final RenderService renderer;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "gpu-agent-heartbeat"); thread.setDaemon(true); return thread;
    });
    private final Map<String, RenderModels.Request> requests = new ConcurrentHashMap<>();
    private final Map<String, RenderModels.TaskMeta> metaByTask = new ConcurrentHashMap<>();
    private final Set<WebSocket> intentionalClose = ConcurrentHashMap.newKeySet();
    private final Object sendLock = new Object();
    private final StringBuilder textBuffer = new StringBuilder();
    private ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean heartbeatStarted;

    CloudConnection(AgentConfig config, RenderService renderer, Consumer<String> log) {
        this.config = config; this.renderer = renderer; this.log = log;
    }

    void start() {
        if (closed) return;
        if (!heartbeatStarted) {
            synchronized (this) {
                if (!heartbeatStarted) {
                    heartbeatStarted = true;
                    scheduler.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
                }
            }
        }
        if (cloudConfigured()) connect();
    }

    /** 在 Agent 进程不中断的情况下应用云端开关、地址或密钥变更。 */
    synchronized void reload() {
        if (closed) return;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) {
            intentionalClose.add(previous);
            try { previous.sendClose(WebSocket.NORMAL_CLOSURE, "configuration changed"); }
            catch (Throwable ignored) { }
        }
        if (cloudConfigured()) {
            log.accept("云端连接配置已更新，正在重新连接");
            connect();
        } else {
            log.accept("云端连接已停用");
        }
    }

    private void connect() {
        if (closed || !cloudConfigured()) return;
        try {
            URI uri = URI.create(config.cloudWebSocketUrl);
            if (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme())) throw new IllegalArgumentException("云端地址必须使用 ws:// 或 wss://");
            if ("ws".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().matches("localhost|127\\..*|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*")) {
                log.accept("警告：正在通过公网普通 ws:// 传输投影文件，建议改用 wss://");
            }
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20)).buildAsync(uri, this)
                    .exceptionally(error -> { log.accept("连接云端 GPU 服务失败：" + error.getMessage()); reconnectLater(); return null; });
        } catch (RuntimeException error) { log.accept(error.getMessage()); reconnectLater(); }
    }

    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello"); hello.addProperty("version", 2); hello.addProperty("agentId", config.agentId);
        send(hello);
        log.accept("已连接云端，正在认证节点 " + config.agentId);
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String message = textBuffer.toString(); textBuffer.setLength(0);
            try { onControl(Protocol.GSON.fromJson(message, JsonObject.class)); }
            catch (Throwable error) { log.accept("云端协议错误：" + error.getMessage()); }
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] part = new byte[data.remaining()]; data.get(part); binaryBuffer.writeBytes(part);
        if (last) {
            byte[] bytes = binaryBuffer.toByteArray(); binaryBuffer = new ByteArrayOutputStream();
            try { onAttachment(Protocol.decodeBinary(ByteBuffer.wrap(bytes))); }
            catch (Throwable error) { log.accept("云端二进制任务无效：" + error.getMessage()); }
        }
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    private void onControl(JsonObject message) {
        String type = message.get("type").getAsString();
        if ("challenge".equals(type)) {
            String challenge = message.get("challenge").getAsString();
            JsonObject auth = new JsonObject(); auth.addProperty("type", "auth"); auth.addProperty("version", 2);
            auth.addProperty("signature", Protocol.hmac(config.sharedSecret, challenge + "." + config.agentId));
            auth.add("capabilities", capabilities()); send(auth); return;
        }
        if ("authenticated".equals(type)) { log.accept("云端 GPU Agent 认证成功"); return; }
        if ("render".equals(type)) {
            JsonObject task = message.getAsJsonObject("task");
            RenderModels.Request request = Protocol.GSON.fromJson(task, RenderModels.Request.class);
            // 视角数量/角度/缩放与分辨率都由本地工具接管：优先使用主界面视角表；表为空时沿用云端视角并只接管分辨率
            List<RenderModels.View> views = new ArrayList<>();
            boolean localViewsOverride = config.views != null && !config.views.isEmpty();
            if (localViewsOverride) {
                for (AgentConfig.ViewEntry entry : config.views) {
                    views.add(new RenderModels.View(entry.id(), entry.name(), entry.yaw(), entry.pitch(), entry.zoom(), entry.autoFillEnabled(),
                            entry.width() > 0 ? entry.width() : config.renderWidth,
                            entry.height() > 0 ? entry.height() : config.renderHeight,
                            entry.background(), entry.transparentBackground(), entry.supersampling(), entry.brightnessFactor()));
                }
            } else {
                for (var view : request.views()) {
                    views.add(new RenderModels.View(view.id(), view.name(), view.yaw(), view.pitch(), view.zoom(),
                            view.autoFill() == null || view.autoFill(), config.renderWidth, config.renderHeight,
                            view.background(), view.transparentBackground(), view.supersampling(), view.brightness()));
                }
            }
            request = new RenderModels.Request(request.version(), request.id(), request.filename(), views,
                    request.resourcePackProfile(), request.pluginVersion(), localViewsOverride ? null : request.renderConfigSha256());
            requests.put(request.id(), request);
            // 捕获来源信息（群号/发送人），渲染完成后写入缓存记录
            String sourceGroup = task.has("sourceGroup") && !task.get("sourceGroup").isJsonNull() ? task.get("sourceGroup").getAsString() : null;
            String sourceUser = task.has("sourceUser") && !task.get("sourceUser").isJsonNull() ? task.get("sourceUser").getAsString() : null;
            metaByTask.put(request.id(), new RenderModels.TaskMeta(sourceGroup, sourceUser));
        }
    }

    private void onAttachment(Protocol.BinaryFrame frame) {
        JsonObject header = frame.header();
        if (!"input".equals(header.get("type").getAsString())) return;
        String taskId = header.get("taskId").getAsString();
        RenderModels.Request request = requests.remove(taskId);
        RenderModels.TaskMeta meta = metaByTask.remove(taskId);
        if (request == null) throw new IllegalArgumentException("missing render manifest for " + taskId);
        renderer.submit(request, frame.payload(), Duration.ofMillis(config.renderTimeoutMillis), "云端", null, meta)
                .whenComplete((result, error) -> {
                    if (error != null) sendError(taskId, error.getCause() == null ? error : error.getCause());
                    else sendResult(taskId, result, request);
                });
    }

    private void sendResult(String taskId, RenderModels.Result result, RenderModels.Request request) {
        debug("sendResult 开始,图片数=" + result.images().size());
        try {
            List<RenderModels.Image> images = result.images();
            // 云端来源：本地视角表中的全部视角都参与拼接，避免新增视角只渲染不回传。
            if (images.size() >= 2 && request != null && !request.views().isEmpty()) {
                MergedPng merged = mergeImages(images, request.views().get(0), config.cloudMergeLayout);
                if (merged != null) {
                    sendBinary(Protocol.binary("image", taskId, "merged", "merged.png", merged.width(), merged.height(), merged.bytes()));
                    debug("已发送合并图 merged.png (" + images.size() + " 张源图，" + mergeLayout(config.cloudMergeLayout) + ")");
                    JsonObject control = new JsonObject(); control.addProperty("type", "result"); control.addProperty("taskId", taskId);
                    control.addProperty("elapsedMillis", result.elapsedMillis()); control.addProperty("cacheHit", result.cacheHit()); send(control);
                    debug("已发送 result 控制消息");
                    log.accept("已回传渲染结果：" + images.size() + " 张图合并为 1 张（任务 " + taskId + "）");
                    return;
                }
            }
            for (var image : images) {
                sendBinary(Protocol.binary("image", taskId, image.id(), image.name(), image.width(), image.height(), Files.readAllBytes(image.path())));
                debug("已发送图片 " + image.name() + " " + image.width() + "x" + image.height());
            }
            JsonObject control = new JsonObject(); control.addProperty("type", "result"); control.addProperty("taskId", taskId);
            control.addProperty("elapsedMillis", result.elapsedMillis()); control.addProperty("cacheHit", result.cacheHit()); send(control);
            debug("已发送 result 控制消息");
            log.accept("已回传渲染结果：" + images.size() + " 张图（任务 " + taskId + "）");
        } catch (Exception error) {
            debug("sendResult 异常: " + error);
            log.accept("回传渲染结果失败：" + error);
            sendError(taskId, error);
        }
    }

    private void sendError(String taskId, Throwable error) {
        try {
            JsonObject control = new JsonObject(); control.addProperty("type", "error"); control.addProperty("taskId", taskId);
            control.addProperty("code", error instanceof RenderService.RenderFailure failure ? failure.code : "AGENT_FAILURE");
            control.addProperty("message", error.getMessage()); send(control);
        } catch (Exception ignored) {}
    }

    /** 把全部视角 PNG 按配置横向或竖向拼接，中间留间隔，底色取首个视角配置。 */
    static MergedPng mergeImages(List<RenderModels.Image> images, RenderModels.View firstView, String layout) throws Exception {
        if (images == null || images.isEmpty() || firstView == null) return null;
        java.util.List<java.awt.image.BufferedImage> decoded = new java.util.ArrayList<>();
        boolean vertical = "vertical".equalsIgnoreCase(layout);
        int totalWidth = 0, totalHeight = 0, maxWidth = 0, maxHeight = 0;
        final int gap = 32;
        for (var image : images) {
            java.awt.image.BufferedImage decoded_image;
            try (var input = Files.newInputStream(image.path())) {
                decoded_image = javax.imageio.ImageIO.read(input);
            }
            if (decoded_image == null) return null;
            decoded.add(decoded_image);
            if (vertical) totalHeight += decoded_image.getHeight() + gap;
            else totalWidth += decoded_image.getWidth() + gap;
            maxWidth = Math.max(maxWidth, decoded_image.getWidth());
            maxHeight = Math.max(maxHeight, decoded_image.getHeight());
        }
        if (vertical) {
            totalWidth = maxWidth;
            totalHeight = Math.max(1, totalHeight - gap);
        } else {
            totalWidth = Math.max(1, totalWidth - gap);
            totalHeight = maxHeight;
        }
        java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(totalWidth, totalHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = canvas.createGraphics();
        try {
            if (!firstView.transparentBackground()) {
                g.setColor(java.awt.Color.decode(firstView.background() == null || firstView.background().isBlank() ? "#000000" : firstView.background()));
                g.fillRect(0, 0, totalWidth, totalHeight);
            }
            int x = 0, y = 0;
            for (java.awt.image.BufferedImage image : decoded) {
                if (vertical) {
                    g.drawImage(image, (totalWidth - image.getWidth()) / 2, y, null);
                    y += image.getHeight() + gap;
                } else {
                    g.drawImage(image, x, (totalHeight - image.getHeight()) / 2, null);
                    x += image.getWidth() + gap;
                }
            }
        } finally { g.dispose(); }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(canvas, "png", out);
        return new MergedPng(out.toByteArray(), totalWidth, totalHeight);
    }

    private static String mergeLayout(String layout) {
        return "vertical".equalsIgnoreCase(layout) ? "竖向" : "横向";
    }

    record MergedPng(byte[] bytes, int width, int height) {}

    private static void debug(String line) {
        try {
            java.nio.file.Path file = Main.defaultDataRoot().resolve("send-debug.log");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, java.time.LocalDateTime.now() + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private JsonObject capabilities() {
        JsonObject value = new JsonObject(); value.addProperty("rendererVersion", Main.VERSION);
        value.addProperty("minecraftVersion", RuntimeInstaller.MINECRAFT_VERSION);
        value.addProperty("nightVisionEnabled", config.nightVisionEnabled);
        value.addProperty("nightVisionLevel", Math.max(1, Math.min(15, config.nightVisionLevel)));
        value.addProperty("lightingProfile", "top-light-v3-per-view-brightness-v1-dynamic-fullbright-sim-y64-smart-fill-v4");
        value.addProperty("localViewsFingerprint", localViewsFingerprint());
        value.addProperty("cloudMergeLayout", mergeLayout(config.cloudMergeLayout));
        RenderModels.RuntimeStatus status = renderer.runtime().currentStatus();
        value.addProperty("maxTextureSize", status == null ? 0 : status.maxTextureSize());
        if (status != null) {
            value.addProperty("gpu", status.gpu());
            value.addProperty("resourcePackFingerprint", status.resourcePackFingerprint());
        }
        return value;
    }

    private String localViewsFingerprint() {
        String value = Protocol.GSON.toJson(config.views) + "|" + config.renderWidth + "x" + config.renderHeight
                + "|" + mergeLayout(config.cloudMergeLayout);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void heartbeat() {
        if (!cloudConfigured() || socket == null || socket.isOutputClosed()) return;
        JsonObject value = new JsonObject(); value.addProperty("type", "heartbeat");
        value.addProperty("busy", renderer.isBusy()); value.addProperty("queueLength", renderer.queueLength());
        value.addProperty("queuedRequestBytes", renderer.retainedRequestBytes()); value.add("capabilities", capabilities()); send(value);
    }

    private void send(JsonObject value) {
        synchronized (sendLock) {
            WebSocket current = socket;
            if (current == null) throw new IllegalStateException("WebSocket 未连接");
            current.sendText(Protocol.GSON.toJson(value), true).join();
        }
    }

    private void sendBinary(java.nio.ByteBuffer payload) {
        synchronized (sendLock) {
            WebSocket current = socket;
            if (current == null) throw new IllegalStateException("WebSocket 未连接");
            current.sendBinary(payload, true).join();
        }
    }
    private void reconnectLater() { if (!closed && cloudConfigured()) scheduler.schedule(this::connect, 5, TimeUnit.SECONDS); }

    private boolean cloudConfigured() {
        return config.cloudEnabled && config.cloudWebSocketUrl != null && !config.cloudWebSocketUrl.isBlank();
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (socket == webSocket) socket = null;
        boolean expected = intentionalClose.remove(webSocket);
        if (!closed && !expected) { log.accept("云端连接断开：" + reason); reconnectLater(); }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }
    @Override public void onError(WebSocket webSocket, Throwable error) {
        if (socket == webSocket) socket = null;
        boolean expected = intentionalClose.remove(webSocket);
        if (!closed && !expected) { log.accept("云端连接错误：" + error.getMessage()); reconnectLater(); }
    }
    @Override public void close() { closed = true; scheduler.shutdownNow(); WebSocket current = socket; socket = null; if (current != null) { intentionalClose.add(current); current.sendClose(1000, "agent stopped"); } }
}
