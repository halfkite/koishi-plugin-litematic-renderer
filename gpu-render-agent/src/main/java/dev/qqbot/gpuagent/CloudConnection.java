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
    private final Object sendLock = new Object();
    private final StringBuilder textBuffer = new StringBuilder();
    private ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
    private volatile WebSocket socket;
    private volatile boolean closed;

    CloudConnection(AgentConfig config, RenderService renderer, Consumer<String> log) {
        this.config = config; this.renderer = renderer; this.log = log;
    }

    void start() {
        if (!config.cloudEnabled || config.cloudWebSocketUrl == null || config.cloudWebSocketUrl.isBlank()) return;
        connect();
        scheduler.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }

    private void connect() {
        if (closed) return;
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
            if (config.views != null && !config.views.isEmpty()) {
                for (AgentConfig.ViewEntry entry : config.views) {
                    views.add(new RenderModels.View(entry.id(), entry.name(), entry.yaw(), entry.pitch(), entry.zoom(), true,
                            entry.width() > 0 ? entry.width() : config.renderWidth,
                            entry.height() > 0 ? entry.height() : config.renderHeight,
                            entry.background(), entry.transparentBackground(), entry.supersampling()));
                }
            } else {
                for (var view : request.views()) {
                    views.add(new RenderModels.View(view.id(), view.name(), view.yaw(), view.pitch(), view.zoom(),
                            view.autoFill(), config.renderWidth, config.renderHeight, view.background(), view.transparentBackground(), view.supersampling()));
                }
            }
            request = new RenderModels.Request(request.version(), request.id(), request.filename(), views, request.resourcePackProfile());
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
            // 云端/HTTP 来源：正反两张拼成一张回传（保持原分辨率，中间留间隔，统一底色），避免刷屏
            if (images.size() >= 2 && request != null && !request.views().isEmpty()) {
                byte[] merged = mergeImages(images, request.views().get(0));
                if (merged != null) {
                    sendBinary(Protocol.binary("image", taskId, "merged", "merged.png", 0, 0, merged));
                    debug("已发送合并图 merged.png (" + images.size() + " 张源图)");
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

    /** 把多张 PNG 横向拼接为一张：保留各自分辨率，中间留间隔，底色取视角配置（透明则透明）。 */
    private static byte[] mergeImages(List<RenderModels.Image> images, RenderModels.View firstView) throws Exception {
        java.util.List<java.awt.image.BufferedImage> decoded = new java.util.ArrayList<>();
        int totalWidth = 0, maxHeight = 0;
        final int gap = 32;
        for (var image : images) {
            java.awt.image.BufferedImage decoded_image = javax.imageio.ImageIO.read(image.path().toFile());
            if (decoded_image == null) return null;
            decoded.add(decoded_image);
            totalWidth += decoded_image.getWidth() + gap;
            maxHeight = Math.max(maxHeight, decoded_image.getHeight());
        }
        totalWidth -= gap;
        java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(totalWidth, maxHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = canvas.createGraphics();
        try {
            if (!firstView.transparentBackground()) {
                g.setColor(java.awt.Color.decode(firstView.background() == null || firstView.background().isBlank() ? "#000000" : firstView.background()));
                g.fillRect(0, 0, totalWidth, maxHeight);
            }
            int x = 0;
            for (java.awt.image.BufferedImage image : decoded) {
                g.drawImage(image, x, (maxHeight - image.getHeight()) / 2, null);
                x += image.getWidth() + gap;
            }
        } finally { g.dispose(); }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    private static void debug(String line) {
        try {
            java.nio.file.Path file = java.nio.file.Path.of(System.getenv("LOCALAPPDATA"), "LitematicGpuAgent", "send-debug.log");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, java.time.LocalDateTime.now() + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private JsonObject capabilities() {
        JsonObject value = new JsonObject(); value.addProperty("rendererVersion", "0.1.0");
        value.addProperty("minecraftVersion", RuntimeInstaller.MINECRAFT_VERSION);
        RenderModels.RuntimeStatus status = renderer.runtime().currentStatus();
        value.addProperty("maxTextureSize", status == null ? 0 : status.maxTextureSize());
        if (status != null) {
            value.addProperty("gpu", status.gpu());
            value.addProperty("resourcePackFingerprint", status.resourcePackFingerprint());
        }
        return value;
    }

    private void heartbeat() {
        if (socket == null || socket.isOutputClosed()) return;
        JsonObject value = new JsonObject(); value.addProperty("type", "heartbeat");
        value.addProperty("busy", renderer.isBusy()); value.addProperty("queueLength", renderer.queueLength()); value.add("capabilities", capabilities()); send(value);
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
    private void reconnectLater() { if (!closed) scheduler.schedule(this::connect, 5, TimeUnit.SECONDS); }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        socket = null; if (!closed) { log.accept("云端连接断开：" + reason); reconnectLater(); }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }
    @Override public void onError(WebSocket webSocket, Throwable error) { socket = null; if (!closed) { log.accept("云端连接错误：" + error.getMessage()); reconnectLater(); } }
    @Override public void close() { closed = true; scheduler.shutdownNow(); if (socket != null) socket.sendClose(1000, "agent stopped"); }
}
