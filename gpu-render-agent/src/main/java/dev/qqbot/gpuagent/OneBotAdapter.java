package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** OneBot 11 WebSocket 适配器，支持 Agent 主动连接和反向连接。 */
final class OneBotAdapter implements BotAdapter, WebSocket.Listener, OneBotWebSocketServer.Handler {
    private final AgentConfig.BotProfile profile;
    private final BiConsumer<BotAdapter, BotMessage> receiver;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "onebot-adapter");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong echo = new AtomicLong();
    private final StringBuilder incoming = new StringBuilder();
    private volatile WebSocket forwardSocket;
    private volatile OneBotWebSocketServer.Channel reverseChannel;
    private volatile OneBotWebSocketServer reverseServer;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile long reconnects;
    private volatile long lastEventAt;
    private volatile String lastError = "";

    OneBotAdapter(AgentConfig.BotProfile profile, BiConsumer<BotAdapter, BotMessage> receiver, Consumer<String> log) {
        this.profile = profile;
        this.receiver = receiver;
        this.log = log == null ? ignored -> {} : log;
    }

    @Override public void start() {
        closed = false;
        if ("reverse".equalsIgnoreCase(profile.transport)) {
            try {
                reverseServer = new OneBotWebSocketServer(profile.listenHost, profile.listenPort, profile.path,
                        profile.accessToken, this);
                reverseServer.start();
                log.accept("OneBot［" + profile.name + "］等待反向 WebSocket：" + profile.listenHost + ":" + profile.listenPort + profile.path);
            } catch (Throwable error) { fail(error); }
        } else scheduler.execute(this::connectForward);
    }

    private void connectForward() {
        if (closed) return;
        try {
            if (profile.webSocketUrl == null || profile.webSocketUrl.isBlank()) throw new IllegalArgumentException("OneBot WebSocket 地址为空");
            var builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20));
            if (profile.accessToken != null && !profile.accessToken.isBlank()) builder.header("Authorization", "Bearer " + profile.accessToken);
            forwardSocket = builder.buildAsync(URI.create(profile.webSocketUrl), this).join();
        } catch (Throwable error) { fail(error); reconnectLater(); }
    }

    @Override public void onOpen(WebSocket socket) {
        forwardSocket = socket; connected = true; lastError = "";
        log.accept("OneBot［" + profile.name + "］正向 WebSocket 已连接");
        socket.request(1);
    }

    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        consumeText(data, last);
        socket.request(1);
        return WebSocket.Listener.super.onText(socket, data, last);
    }

    private void consumeText(CharSequence data, boolean last) {
        synchronized (incoming) {
            incoming.append(data);
            if (!last) return;
            String text = incoming.toString(); incoming.setLength(0);
            try { handle(Protocol.GSON.fromJson(text, JsonObject.class)); } catch (Throwable error) { fail(error); }
        }
    }

    @Override public void onOpen(OneBotWebSocketServer.Channel channel) {
        OneBotWebSocketServer.Channel previous = reverseChannel;
        reverseChannel = channel; connected = true; lastError = "";
        if (previous != null && previous != channel) previous.close();
        log.accept("OneBot［" + profile.name + "］反向 WebSocket 已连接");
    }

    @Override public void onText(OneBotWebSocketServer.Channel channel, String text) {
        try { handle(Protocol.GSON.fromJson(text, JsonObject.class)); } catch (Throwable error) { fail(error); }
    }

    @Override public void onClose(OneBotWebSocketServer.Channel channel) {
        if (reverseChannel == channel) { reverseChannel = null; connected = false; }
    }

    private void handle(JsonObject value) {
        if (value == null) return;
        if (value.has("echo")) {
            String key = primitive(value.get("echo"));
            CompletableFuture<JsonObject> future = pending.remove(key);
            if (future != null) future.complete(value);
            return;
        }
        if (!"message".equals(text(value, "post_type", ""))) return;
        lastEventAt = System.currentTimeMillis();
        String type = text(value, "message_type", "");
        boolean direct = "private".equals(type);
        String messageId = primitive(value.get("message_id"));
        String userId = text(value, "user_id", "");
        String groupId = text(value, "group_id", "");
        String selfId = text(value, "self_id", "");
        boolean mentioned = false;
        String quotedMessageId = "";
        List<BotAttachment> attachments = new ArrayList<>();
        JsonElement message = value.get("message");
        if (message != null && message.isJsonArray()) {
            for (JsonElement segment : message.getAsJsonArray()) {
                if (!segment.isJsonObject()) continue;
                JsonObject item = segment.getAsJsonObject();
                String segmentType = text(item, "type", "");
                JsonObject data = item.has("data") && item.get("data").isJsonObject() ? item.getAsJsonObject("data") : item;
                if ("at".equals(segmentType)) mentioned = true;
                if ("reply".equals(segmentType)) quotedMessageId = text(data, "id", text(data, "message_id", ""));
                if ("file".equals(segmentType)) attachments.add(file(data));
            }
        } else if (message != null && message.isJsonPrimitive()) {
            String raw = message.getAsString();
            if (raw.contains(".litematic")) {
                int start = raw.indexOf("[CQ:file");
                if (start >= 0) attachments.add(parseCqFile(raw.substring(start, raw.indexOf(']', start) + 1)));
            }
        }
        if (value.has("file") && value.get("file").isJsonObject()) attachments.add(file(value.getAsJsonObject("file")));
        String rawText = text(value, "raw_message", text(value, "message", ""));
        if (quotedMessageId.isBlank()) quotedMessageId = replyIdFromRaw(rawText);
        receiver.accept(this, new BotMessage(profile.id, messageId, userId, groupId, selfId, direct, mentioned, attachments,
                rawText, quotedMessageId));
    }

    static String replyIdFromRaw(String text) {
        if (text == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[CQ:reply,id=([^,\\]]+)")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static BotAttachment file(JsonObject value) {
        return new BotAttachment(text(value, "name", text(value, "filename", text(value, "file_name", ""))),
                text(value, "url", text(value, "file", "")),
                text(value, "file_id", text(value, "id", "")), integer(value, "busid", 0), longValue(value, "size", 0));
    }

    private static BotAttachment parseCqFile(String raw) {
        String name = parameter(raw, "name");
        String file = parameter(raw, "file");
        String id = parameter(raw, "file_id");
        return new BotAttachment(name, file, id, integerParameter(raw, "busid"), 0);
    }

    @Override public CompletableFuture<BotAttachment> resolveAttachment(BotMessage message, BotAttachment attachment) {
        if (attachment.url() != null && !attachment.url().isBlank()) return CompletableFuture.completedFuture(attachment);
        if (attachment.fileId() == null || attachment.fileId().isBlank() || message.groupId() == null || message.groupId().isBlank())
            return CompletableFuture.completedFuture(attachment);
        JsonObject params = new JsonObject(); params.addProperty("group_id", message.groupId()); params.addProperty("file_id", attachment.fileId());
        if (attachment.busid() > 0) params.addProperty("busid", attachment.busid());
        return action("get_group_file_url", params).thenApply(value -> {
            JsonObject data = value.has("data") && value.get("data").isJsonObject() ? value.getAsJsonObject("data") : value;
            String url = text(data, "url", text(data, "file", ""));
            return new BotAttachment(attachment.name(), url, attachment.fileId(), attachment.busid(), attachment.size());
        });
    }

    @Override public void sendText(BotMessage message, String text) {
        JsonArray segments = new JsonArray(); addPrefix(segments, message);
        JsonObject value = new JsonObject(); value.addProperty("type", "text"); JsonObject data = new JsonObject(); data.addProperty("text", text == null ? "" : text); value.add("data", data); segments.add(value);
        sendMessage(message, segments);
    }

    @Override public void sendAnnouncement(String profileId, String groupId, String text) {
        JsonObject params = new JsonObject();
        params.addProperty("group_id", numberOrString(groupId));
        JsonArray segments = new JsonArray();
        textSegment(segments, text);
        params.add("message", segments);
        action("send_group_msg", params).join();
    }

    @Override public List<BotGroup> listGroups() {
        JsonObject response = action("get_group_list", new JsonObject()).join();
        return parseGroupList(response);
    }

    static List<BotGroup> parseGroupList(JsonObject response) {
        if (!response.has("data") || !response.get("data").isJsonArray())
            throw new IllegalStateException("OneBot 未返回群列表");
        List<BotGroup> groups = new ArrayList<>();
        for (JsonElement item : response.getAsJsonArray("data")) {
            if (!item.isJsonObject()) continue;
            JsonObject group = item.getAsJsonObject();
            String id = text(group, "group_id", "");
            if (!id.isBlank()) groups.add(new BotGroup(id, text(group, "group_name", "")));
        }
        return groups;
    }

    @Override public void sendResult(BotMessage message, RenderModels.Result result, String metadata) {
        sendResult(message, result, metadata, null);
    }

    @Override public void sendResult(BotMessage message, RenderModels.Result result, String metadata, Path metadataImage) {
        sendResultTracked(message, result, metadata, metadataImage);
    }

    @Override public List<String> sendResultTracked(BotMessage message, RenderModels.Result result, String metadata,
                                                     Path metadataImage) {
        String id;
        if ("forward".equalsIgnoreCase(profile.sendMode)) {
            try { id = sendForward(message, result, metadata, metadataImage); }
            catch (Throwable error) {
                log.accept("OneBot 合并转发失败，改用联合消息：" + error.getMessage());
                id = sendCombined(message, result, metadata, metadataImage);
            }
        } else id = sendCombined(message, result, metadata, metadataImage);
        return id == null || id.isBlank() ? List.of() : List.of(id);
    }

    @Override public void sendProjection(BotMessage message, RenderModels.Result result, String metadata, Path projection) {
        sendProjection(message, result, metadata, projection, null);
    }

    @Override public void sendProjection(BotMessage message, RenderModels.Result result, String metadata,
                                         Path projection, Path metadataImage) {
        sendResult(message, result, metadata, metadataImage);
        sendFile(message, projection);
    }

    @Override public void sendSearch(BotMessage message, ProjectionSearch.SearchPage page, Path contactSheet) {
        sendSearchTracked(message, page, contactSheet);
    }

    @Override public List<String> sendSearchTracked(BotMessage message, ProjectionSearch.SearchPage page, Path contactSheet) {
        JsonArray segments = new JsonArray();
        addPrefix(segments, message);
        segments.add(imageSegment(contactSheet));
        String id = sendMessage(message, segments);
        return id == null || id.isBlank() ? List.of() : List.of(id);
    }

    @Override public void sendImage(BotMessage message, Path image) {
        JsonArray segments = new JsonArray();
        addPrefix(segments, message);
        segments.add(imageSegment(image));
        sendMessage(message, segments);
    }

    @Override public void sendImages(BotMessage message, List<Path> images) {
        JsonArray segments = new JsonArray();
        addPrefix(segments, message);
        for (Path image : images) segments.add(imageSegment(image));
        sendMessage(message, segments);
    }

    @Override public void sendFile(BotMessage message, Path projection) {
        if (projection == null || !Files.isRegularFile(projection)) throw new IllegalArgumentException("缓存中没有投影文件");
        JsonObject params = targetParams(message);
        params.addProperty("file", projection.toAbsolutePath().toString());
        params.addProperty("name", projection.getFileName().toString());
        try {
            action(message.direct() ? "upload_private_file" : "upload_group_file", params).join();
        } catch (Throwable error) {
            log.accept("OneBot 文件上传接口失败，改用文件消息：" + error.getMessage());
            JsonArray segments = new JsonArray();
            addPrefix(segments, message);
            segments.add(fileSegment(projection));
            sendMessage(message, segments);
        }
    }

    private String sendForward(BotMessage message, RenderModels.Result result, String metadata, Path metadataImage) {
        JsonArray nodes = new JsonArray();
        if (metadataImage != null) {
            nodes.add(forwardImageNode(message, metadataImage));
        } else {
            for (RenderModels.Image image : result.images()) nodes.add(forwardImageNode(message, image.path()));
            if (metadata != null && !metadata.isBlank()) {
                JsonObject info = new JsonObject(); info.addProperty("type", "node"); JsonObject infoData = new JsonObject(); infoData.addProperty("name", "Litematic GPU Agent"); infoData.addProperty("uin", message.selfId() == null || message.selfId().isBlank() ? "0" : message.selfId());
                JsonArray content = new JsonArray(); textSegment(content, metadata); infoData.add("content", content); info.add("data", infoData); nodes.add(info);
            }
        }
        JsonObject params = targetParams(message); params.add("messages", nodes);
        String messageId = responseMessageId(action(message.direct() ? "send_private_forward_msg" : "send_group_forward_msg", params).join());
        if (profile.successNotice) sendText(message, projectionName(metadata) + " 已渲染成功，结果如上");
        return messageId;
    }

    private String sendCombined(BotMessage message, RenderModels.Result result, String metadata, Path metadataImage) {
        JsonArray segments = new JsonArray(); addPrefix(segments, message);
        if (metadataImage != null) segments.add(imageSegment(metadataImage));
        else for (RenderModels.Image image : result.images()) segments.add(imageSegment(image.path()));
        String text = metadataImage == null && metadata != null ? metadata : "";
        if (profile.successNotice) text += "\n" + projectionName(metadata) + " 已渲染成功";
        textSegment(segments, text);
        return sendMessage(message, segments);
    }

    private JsonObject forwardImageNode(BotMessage message, Path image) {
        JsonObject node = new JsonObject(); node.addProperty("type", "node");
        JsonObject data = new JsonObject();
        data.addProperty("name", "Litematic GPU Agent");
        data.addProperty("uin", message.selfId() == null || message.selfId().isBlank() ? "0" : message.selfId());
        JsonArray content = new JsonArray(); content.add(imageSegment(image)); data.add("content", content);
        node.add("data", data);
        return node;
    }

    private void addPrefix(JsonArray segments, BotMessage message) {
        if (!profile.replyAndMention) return;
        if (message.messageId() != null && !message.messageId().isBlank()) {
            JsonObject reply = new JsonObject(); reply.addProperty("type", "reply"); JsonObject data = new JsonObject(); data.addProperty("id", message.messageId()); reply.add("data", data); segments.add(reply);
        }
        if (message.userId() != null && !message.userId().isBlank()) {
            JsonObject at = new JsonObject(); at.addProperty("type", "at"); JsonObject data = new JsonObject(); data.addProperty("qq", message.userId()); at.add("data", data); segments.add(at);
        }
        textSegment(segments, "\n");
    }

    private String sendMessage(BotMessage message, JsonArray segments) {
        JsonObject params = targetParams(message); params.add("message", segments);
        String actionName = message.direct() ? "send_private_msg" : "send_group_msg";
        return responseMessageId(action(actionName, params).join());
    }

    private static String responseMessageId(JsonObject response) {
        if (response == null || !response.has("data") || !response.get("data").isJsonObject()) return "";
        JsonObject data = response.getAsJsonObject("data");
        return text(data, "message_id", text(data, "id", ""));
    }

    private JsonObject targetParams(BotMessage message) {
        JsonObject params = new JsonObject();
        if (message.direct()) params.addProperty("user_id", numberOrString(message.userId()));
        else params.addProperty("group_id", numberOrString(message.groupId()));
        return params;
    }

    private CompletableFuture<JsonObject> action(String action, JsonObject params) {
        String id = String.valueOf(echo.incrementAndGet());
        JsonObject request = new JsonObject(); request.addProperty("action", action); request.add("params", params); request.addProperty("echo", id);
        CompletableFuture<JsonObject> future = new CompletableFuture<>(); pending.put(id, future);
        try { sendRaw(Protocol.GSON.toJson(request)); }
        catch (Throwable error) { pending.remove(id); future.completeExceptionally(error); }
        scheduler.schedule(() -> { CompletableFuture<JsonObject> current = pending.remove(id); if (current != null) current.completeExceptionally(new IOException("OneBot 动作超时：" + action)); }, 30, TimeUnit.SECONDS);
        return future.thenCompose(value -> {
            if (value.has("retcode") && value.get("retcode").getAsInt() != 0) return CompletableFuture.failedFuture(new IOException("OneBot 动作失败：" + value));
            return CompletableFuture.completedFuture(value);
        });
    }

    private void sendRaw(String text) throws IOException {
        OneBotWebSocketServer.Channel reverse = reverseChannel;
        if (reverse != null) { reverse.sendText(text); return; }
        WebSocket forward = forwardSocket;
        if (forward != null && connected) { forward.sendText(text, true).join(); return; }
        throw new IOException("OneBot WebSocket 未连接");
    }

    @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        if (forwardSocket == socket) forwardSocket = null; connected = false; if (!closed) reconnectLater();
        return WebSocket.Listener.super.onClose(socket, statusCode, reason);
    }

    @Override public void onError(WebSocket socket, Throwable error) { connected = false; fail(error); if (!closed) reconnectLater(); }

    private void reconnectLater() { if (!closed && "forward".equalsIgnoreCase(profile.transport)) { reconnects++; scheduler.schedule(this::connectForward, Math.min(120, Math.max(5, reconnects * 5)), TimeUnit.SECONDS); } }
    private void fail(Throwable error) { lastError = error.getMessage() == null ? String.valueOf(error) : error.getMessage(); log.accept("OneBot［" + profile.name + "］：" + lastError); }

    @Override public BotStatus status() { return new BotStatus(profile.id, profile.name, "onebot", profile.enabled, connected, connected ? "已连接" : (lastError.isBlank() ? "等待连接" : "失败"), reconnects, lastError, lastEventAt); }

    @Override public void close() {
        closed = true; connected = false; scheduler.shutdownNow();
        try { if (reverseServer != null) reverseServer.close(); } catch (Throwable ignored) { }
        try { if (reverseChannel != null) reverseChannel.close(); } catch (Throwable ignored) { }
        try { if (forwardSocket != null) forwardSocket.sendClose(WebSocket.NORMAL_CLOSURE, "agent stopped"); } catch (Throwable ignored) { }
        for (CompletableFuture<JsonObject> future : pending.values()) future.completeExceptionally(new IOException("OneBot 已关闭"));
        pending.clear();
    }

    private static JsonObject imageSegment(Path path) { JsonObject value = new JsonObject(); value.addProperty("type", "image"); JsonObject data = new JsonObject(); data.addProperty("file", path.toUri().toString()); value.add("data", data); return value; }
    private static JsonObject fileSegment(Path path) { JsonObject value = new JsonObject(); value.addProperty("type", "file"); JsonObject data = new JsonObject(); data.addProperty("file", path.toAbsolutePath().toString()); data.addProperty("name", path.getFileName().toString()); value.add("data", data); return value; }
    private static void textSegment(JsonArray array, String text) { JsonObject value = new JsonObject(); value.addProperty("type", "text"); JsonObject data = new JsonObject(); data.addProperty("text", text == null ? "" : text); value.add("data", data); array.add(value); }
    private static String projectionName(String metadata) { if (metadata == null) return "schematic"; String line = metadata.lines().findFirst().orElse(""); int colon = line.indexOf('：'); return colon >= 0 ? line.substring(colon + 1).replaceFirst("\\.litematic$", "") : "schematic"; }
    private static String text(JsonObject value, String key, String fallback) { try { return value != null && value.has(key) ? value.get(key).getAsString() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static String primitive(JsonElement value) { return value == null || value.isJsonNull() ? "" : value.getAsString(); }
    private static int integer(JsonObject value, String key, int fallback) { try { return value != null && value.has(key) ? value.get(key).getAsInt() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static long longValue(JsonObject value, String key, long fallback) { try { return value != null && value.has(key) ? value.get(key).getAsLong() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static String parameter(String value, String key) { String[] parts = value.split(","); for (String part : parts) if (part.startsWith(key + "=")) return part.substring(key.length() + 1); return ""; }
    private static int integerParameter(String value, String key) { try { return Integer.parseInt(parameter(value, key)); } catch (RuntimeException ignored) { return 0; } }
    private static String numberOrString(String value) { try { Long.parseLong(value); return value; } catch (RuntimeException ignored) { return value == null ? "" : value; } }
}
