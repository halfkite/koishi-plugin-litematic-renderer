package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 直接连接 QQ 官方机器人 Gateway 与 REST API，不依赖 Koishi。 */
final class OfficialQqAdapter implements BotAdapter, WebSocket.Listener {
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private final AgentConfig.BotProfile profile;
    private final BiConsumer<BotAdapter, BotMessage> receiver;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "qq-official-" + profileId());
        thread.setDaemon(true);
        return thread;
    });
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile long reconnects;
    private volatile long lastEventAt;
    private volatile String lastError = "";
    private volatile String accessToken = "";
    private volatile long tokenExpiresAt;
    private volatile int heartbeatInterval = 45_000;
    private volatile long sequence = -1;
    private volatile String gatewayUrl = "";
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean connecting;
    private final Object connectionLock = new Object();
    private final StringBuilder incoming = new StringBuilder();

    OfficialQqAdapter(AgentConfig.BotProfile profile, BiConsumer<BotAdapter, BotMessage> receiver, Consumer<String> log) {
        this.profile = profile;
        this.receiver = receiver;
        this.log = log == null ? ignored -> {} : log;
    }

    private String profileId() { return profile.id == null ? "bot" : profile.id; }

    @Override public void start() {
        closed = false;
        scheduler.execute(this::connect);
    }

    private void connect() {
        synchronized (connectionLock) {
            if (closed || connected || connecting) return;
            connecting = true;
        }
        boolean retry = false;
        try {
            if (profile.appId == null || profile.appId.isBlank() || profile.appSecret == null || profile.appSecret.isBlank())
                throw new IllegalArgumentException("AppID 或 AppSecret 为空");
            ensureToken();
            if (gatewayUrl.isBlank()) gatewayUrl = requestGateway();
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20)).buildAsync(URI.create(gatewayUrl), this).join();
        } catch (Throwable error) {
            fail(error);
            retry = true;
        } finally {
            synchronized (connectionLock) { connecting = false; }
        }
        if (retry) reconnectLater();
    }

    private void ensureToken() throws IOException, InterruptedException {
        if (!accessToken.isBlank() && System.currentTimeMillis() < tokenExpiresAt - 60_000) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("appId", profile.appId);
        payload.addProperty("clientSecret", profile.appSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Protocol.GSON.toJson(payload))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("获取 QQ Access Token 失败 HTTP " + response.statusCode());
        JsonObject value = Protocol.GSON.fromJson(response.body(), JsonObject.class);
        accessToken = string(value, "access_token");
        long expires = number(value, "expires_in", 7_200);
        if (accessToken.isBlank()) throw new IOException("QQ Access Token 响应为空");
        tokenExpiresAt = System.currentTimeMillis() + expires * 1000L;
    }

    private String requestGateway() throws IOException, InterruptedException {
        String base = profile.sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/gateway/bot")).timeout(Duration.ofSeconds(30))
                .header("Authorization", "QQBot " + accessToken).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("获取 QQ Gateway 失败 HTTP " + response.statusCode());
        String value = string(Protocol.GSON.fromJson(response.body(), JsonObject.class), "url");
        if (value.isBlank()) throw new IOException("QQ Gateway 响应缺少 url");
        return value;
    }

    @Override public void onOpen(WebSocket webSocket) {
        WebSocket previous;
        synchronized (connectionLock) {
            previous = socket;
            socket = webSocket;
            connected = true;
            ScheduledFuture<?> pending = reconnectTask;
            reconnectTask = null;
            if (pending != null) pending.cancel(false);
        }
        if (previous != null && previous != webSocket) previous.sendClose(WebSocket.NORMAL_CLOSURE, "replaced");
        lastError = "";
        log.accept("官方 QQ［" + profile.name + "］已连接 Gateway");
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (incoming) {
            incoming.append(data);
            if (last) {
                String text = incoming.toString();
                incoming.setLength(0);
                try { handle(Protocol.GSON.fromJson(text, JsonObject.class)); }
                catch (Throwable error) { fail(error); }
            }
        }
        webSocket.request(1);
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    private void handle(JsonObject packet) {
        if (packet == null) return;
        if (packet.has("s") && !packet.get("s").isJsonNull()) sequence = packet.get("s").getAsLong();
        int op = packet.has("op") ? packet.get("op").getAsInt() : -1;
        JsonObject data = packet.has("d") && packet.get("d").isJsonObject() ? packet.getAsJsonObject("d") : new JsonObject();
        if (op == 10) {
            heartbeatInterval = data.has("heartbeat_interval") ? data.get("heartbeat_interval").getAsInt() : 45_000;
            identify();
            ScheduledFuture<?> previous = heartbeatTask;
            if (previous != null) previous.cancel(false);
            heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
        } else if (op == 7 || op == 9) {
            WebSocket current;
            synchronized (connectionLock) {
                current = socket;
                socket = null;
                connected = false;
                gatewayUrl = "";
            }
            if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "gateway reconnect");
            reconnectLater();
        } else if (op == 0) {
            lastEventAt = System.currentTimeMillis();
            dispatch(packet.has("t") ? packet.get("t").getAsString() : "", data);
        }
    }

    private void identify() {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + accessToken);
        data.addProperty("intents", profile.intents);
        JsonArray shard = new JsonArray(); shard.add(0); shard.add(1); data.add("shard", shard);
        JsonObject packet = new JsonObject(); packet.addProperty("op", 2); packet.add("d", data);
        send(packet);
    }

    private void heartbeat() {
        if (!connected) return;
        JsonObject packet = new JsonObject(); packet.addProperty("op", 1);
        if (sequence >= 0) packet.addProperty("d", sequence); else packet.add("d", com.google.gson.JsonNull.INSTANCE);
        send(packet);
    }

    private void dispatch(String event, JsonObject data) {
        boolean direct = "C2C_MESSAGE_CREATE".equals(event) || "DIRECT_MESSAGE_CREATE".equals(event);
        boolean group = event.startsWith("GROUP_") || "PUBLIC_GUILD_MESSAGES".equals(event);
        if (!direct && !group) return;
        String messageId = first(data, "id", "msg_id", "message_id");
        String groupId = first(data, "group_openid", "group_id");
        String userId = first(data, "openid", "user_openid", "user_id", "author.member_openid", "author.user_openid");
        String selfId = first(data, "self_id", "bot_id");
        JsonObject author = object(data, "author");
        if (userId.isBlank() && author != null) userId = first(author, "member_openid", "user_openid", "id");
        boolean mentioned = "GROUP_AT_MESSAGE_CREATE".equals(event) || event.contains("AT_MESSAGE");
        List<BotAttachment> attachments = new ArrayList<>();
        JsonArray values = array(data, "attachments");
        if (values != null) for (JsonElement item : values) if (item != null && item.isJsonObject()) {
            JsonObject value = item.getAsJsonObject();
            attachments.add(new BotAttachment(first(value, "filename", "file_name", "name"),
                    first(value, "url", "src"), "", 0, number(value, "size", 0)));
        }
        receiver.accept(this, new BotMessage(profile.id, messageId, userId, groupId, selfId, direct, mentioned, attachments,
                first(data, "content", "text")));
    }

    @Override public void sendText(BotMessage message, String text) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("content", text == null ? "" : text);
            body.addProperty("msg_type", 0);
            if (message.messageId() != null && !message.messageId().isBlank()) body.addProperty("msg_id", message.messageId());
            postMessage(message, body);
        } catch (Throwable error) { fail(error); }
    }

    @Override public void sendResult(BotMessage message, RenderModels.Result result, String metadata) {
        Path temporary = null;
        try {
            if (result.images().isEmpty()) { sendText(message, "投影渲染完成，但没有可发送的图片。"); return; }
            byte[] bytes;
            if (result.images().size() == 1) bytes = Files.readAllBytes(result.images().getFirst().path());
            else {
                var view = new RenderModels.View("overview", "投影结果", 0, 0, 1.0, true,
                        result.images().getFirst().width(), result.images().getFirst().height(), "#000000", false, 1);
                CloudConnection.MergedPng merged = CloudConnection.mergeImages(result.images(), view, "horizontal");
                bytes = merged == null ? Files.readAllBytes(result.images().getFirst().path()) : merged.bytes();
            }
            temporary = Files.createTempFile("litematic-qq-", ".png");
            Files.write(temporary, bytes);
            JsonObject fileInfo = uploadImage(message, bytes);
            JsonObject body = new JsonObject();
            // 官方 QQ 的媒体消息支持同时携带正文，避免元数据另发一条造成重复结果。
            body.addProperty("content", metadata == null ? "" : metadata);
            body.addProperty("msg_type", 7);
            body.add("media", fileInfo);
            if (message.messageId() != null && !message.messageId().isBlank()) body.addProperty("msg_id", message.messageId());
            postMessage(message, body);
        } catch (Throwable error) { fail(error); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    private JsonObject uploadImage(BotMessage message, byte[] bytes) throws IOException, InterruptedException {
        String target = message.direct() ? message.userId() : message.groupId();
        if (target == null || target.isBlank()) throw new IOException("官方 QQ 消息缺少发送目标");
        String base = profile.sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
        JsonObject body = new JsonObject(); body.addProperty("file_type", 1);
        body.addProperty("file_data", Base64.getEncoder().encodeToString(bytes)); body.addProperty("srv_send_msg", false);
        JsonObject value = post(base + (message.direct() ? "/v2/users/" : "/v2/groups/") + target + "/files", body);
        return value.has("file_info") && value.get("file_info").isJsonObject() ? value.getAsJsonObject("file_info") : value;
    }

    private void postMessage(BotMessage message, JsonObject body) throws IOException, InterruptedException {
        String target = message.direct() ? message.userId() : message.groupId();
        if (target == null || target.isBlank()) throw new IOException("官方 QQ 消息缺少发送目标");
        String base = profile.sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
        post(base + (message.direct() ? "/v2/users/" : "/v2/groups/") + target + "/messages", body);
    }

    private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
        ensureToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "QQBot " + accessToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Protocol.GSON.toJson(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("QQ REST 请求失败 HTTP " + response.statusCode() + "：" + response.body());
        JsonObject value = Protocol.GSON.fromJson(response.body().isBlank() ? "{}" : response.body(), JsonObject.class);
        return value == null ? new JsonObject() : value;
    }

    private void send(JsonObject packet) {
        WebSocket current = socket;
        if (current != null && connected) current.sendText(Protocol.GSON.toJson(packet), true);
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        boolean current;
        synchronized (connectionLock) {
            current = socket == webSocket;
            if (current) {
                connected = false;
                socket = null;
            }
        }
        if (current) {
            ScheduledFuture<?> heartbeat = heartbeatTask;
            if (heartbeat != null) heartbeat.cancel(false);
            if (!closed) reconnectLater();
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        boolean current;
        synchronized (connectionLock) {
            current = socket == webSocket;
            if (current) {
                connected = false;
                socket = null;
            }
        }
        if (current) {
            fail(error);
            if (!closed) reconnectLater();
        }
    }

    private void reconnectLater() {
        synchronized (connectionLock) {
            if (closed || connected || connecting || (reconnectTask != null && !reconnectTask.isDone())) return;
            reconnects++;
            long delay = Math.min(120, Math.max(5, reconnects * 5));
            reconnectTask = scheduler.schedule(() -> {
                synchronized (connectionLock) { reconnectTask = null; }
                connect();
            }, delay, TimeUnit.SECONDS);
        }
    }

    private void fail(Throwable error) {
        lastError = error.getMessage() == null ? String.valueOf(error) : error.getMessage();
        log.accept("官方 QQ［" + profile.name + "］连接/发送失败：" + lastError);
    }

    @Override public BotStatus status() {
        return new BotStatus(profile.id, profile.name, "official", profile.enabled, connected,
                connected ? "已连接" : (lastError.isBlank() ? "连接中" : "失败"), reconnects, lastError, lastEventAt);
    }

    @Override public java.util.concurrent.CompletableFuture<BotAttachment> resolveAttachment(BotMessage message, BotAttachment attachment) {
        return java.util.concurrent.CompletableFuture.completedFuture(attachment);
    }

    @Override public void close() {
        closed = true;
        connected = false;
        ScheduledFuture<?> heartbeat = heartbeatTask;
        if (heartbeat != null) heartbeat.cancel(false);
        ScheduledFuture<?> reconnect = reconnectTask;
        if (reconnect != null) reconnect.cancel(false);
        scheduler.shutdownNow();
        WebSocket current = socket;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "agent stopped");
    }

    private static String first(JsonObject value, String... keys) {
        if (value == null) return "";
        for (String key : keys) {
            JsonElement item = value;
            for (String part : key.split("\\.")) {
                if (item == null || !item.isJsonObject() || !item.getAsJsonObject().has(part)) { item = null; break; }
                item = item.getAsJsonObject().get(part);
            }
            if (item != null && item.isJsonPrimitive()) return item.getAsString();
        }
        return "";
    }

    private static JsonObject object(JsonObject value, String key) { return value != null && value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : null; }
    private static JsonArray array(JsonObject value, String key) { return value != null && value.has(key) && value.get(key).isJsonArray() ? value.getAsJsonArray(key) : null; }
    private static String string(JsonObject value, String key) { return first(value, key); }
    private static long number(JsonObject value, String key, long fallback) { try { return value != null && value.has(key) ? value.get(key).getAsLong() : fallback; } catch (RuntimeException ignored) { return fallback; } }
}
