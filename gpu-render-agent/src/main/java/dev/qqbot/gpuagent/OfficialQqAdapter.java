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
import java.util.Map;
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
    private static final String OPEN_API_BASE = "https://api.bot.qq.com";
    private final AgentConfig.BotProfile profile;
    private final AgentConfig config;
    private final String globalImageSendLayout;
    private final BiConsumer<BotAdapter, BotMessage> receiver;
    private final BiConsumer<String, Boolean> groupMembership;
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

    OfficialQqAdapter(AgentConfig.BotProfile profile, AgentConfig config, String globalImageSendLayout,
                      BiConsumer<BotAdapter, BotMessage> receiver,
                      BiConsumer<String, Boolean> groupMembership, Consumer<String> log) {
        this.profile = profile;
        this.config = config;
        this.globalImageSendLayout = globalImageSendLayout;
        this.receiver = receiver;
        this.groupMembership = groupMembership;
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
        Thread.startVirtualThread(this::syncSlashCommandPanels);
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
        if ("GROUP_ADD_ROBOT".equals(event) || "GROUP_DEL_ROBOT".equals(event)) {
            groupMembership.accept(first(data, "group_openid", "group_id"), "GROUP_ADD_ROBOT".equals(event));
            return;
        }
        boolean direct = "C2C_MESSAGE_CREATE".equals(event) || "DIRECT_MESSAGE_CREATE".equals(event);
        boolean group = "GROUP_MESSAGE_CREATE".equals(event) || "GROUP_AT_MESSAGE_CREATE".equals(event)
                || "PUBLIC_GUILD_MESSAGES".equals(event);
        if (!direct && !group) return;
        String messageId = first(data, "id", "msg_id", "message_id");
        String groupId = first(data, "group_openid", "group_id");
        String userId = first(data, "openid", "user_openid", "user_id", "author.member_openid", "author.user_openid");
        String selfId = first(data, "self_id", "bot_id");
        JsonObject author = object(data, "author");
        if (userId.isBlank() && author != null) userId = first(author, "member_openid", "user_openid", "id");
        boolean mentioned = isMentioned(event, data, selfId);
        List<BotAttachment> attachments = new ArrayList<>();
        JsonArray values = array(data, "attachments");
        if (values != null) for (JsonElement item : values) if (item != null && item.isJsonObject()) {
            JsonObject value = item.getAsJsonObject();
            attachments.add(new BotAttachment(first(value, "filename", "file_name", "name"),
                    first(value, "url", "src"), "", 0, number(value, "size", 0)));
        }
        String rawText = first(data, "content", "text");
        if (mentioned) log.accept("官方 QQ 收到群 @ 消息事件：文本长度=" + rawText.length() + "，附件数=" + attachments.size());
        List<String> quotedMessageIds = referencedMessageIds(data);
        receiver.accept(this, new BotMessage(profile.id, messageId, userId, groupId, selfId, direct, mentioned, attachments,
                rawText, new java.util.concurrent.atomic.AtomicInteger(),
                quotedMessageIds.isEmpty() ? "" : quotedMessageIds.getFirst(), messageIndexAliases(data), quotedMessageIds));
    }

    static String referencedMessageId(JsonObject data) {
        List<String> ids = referencedMessageIds(data);
        return ids.isEmpty() ? "" : ids.getFirst();
    }

    static List<String> referencedMessageIds(JsonObject data) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String field : List.of("message_reference.message_id", "message_reference.id",
                "messageReference.message_id", "messageReference.id", "reference.message_id", "reference.id",
                "reply_to_message_id", "ref_msg_idx", "refMsgIdx", "message_scene.ref_msg_idx", "messageScene.refMsgIdx")) {
            addIndex(ids, first(data, field));
        }
        for (String key : List.of("message_reference", "messageReference", "reference")) {
            JsonObject reference = object(data, key);
            if (reference == null) continue;
            for (String field : List.of("message_id", "id", "msg_id", "ref_msg_idx", "refMsgIdx", "msg_idx", "msgIdx")) {
                addIndex(ids, first(reference, field));
            }
        }
        addIndex(ids, sceneIndex(data, "ref_msg_idx"));
        return List.copyOf(ids);
    }

    static List<String> messageIndexAliases(JsonObject data) {
        java.util.LinkedHashSet<String> indices = new java.util.LinkedHashSet<>();
        addIndex(indices, first(data, "msg_idx", "msgIdx", "message_scene.msg_idx", "messageScene.msgIdx"));
        addIndex(indices, sceneIndex(data, "msg_idx"));
        return List.copyOf(indices);
    }

    private static String sceneIndex(JsonObject data, String key) {
        for (String sceneName : List.of("message_scene", "messageScene")) {
            JsonObject scene = object(data, sceneName);
            JsonArray ext = array(scene, "ext");
            if (ext == null) continue;
            for (JsonElement entry : ext) {
                if (entry == null || !entry.isJsonPrimitive()) continue;
                String value = entry.getAsString();
                String prefix = key + "=";
                if (value.startsWith(prefix)) return value.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static void addIndex(java.util.Set<String> indices, String value) {
        if (value != null && !value.isBlank()) indices.add(value.trim());
    }

    static boolean isMentioned(String event, JsonObject data, String selfId) {
        if ("GROUP_AT_MESSAGE_CREATE".equals(event) || (event != null && event.contains("AT_MESSAGE"))) return true;
        JsonArray mentions = array(data, "mentions");
        if (mentions == null) return false;
        for (JsonElement entry : mentions) {
            if (entry == null || entry.isJsonNull()) continue;
            if (entry.isJsonPrimitive()) {
                if (selfId != null && !selfId.isBlank() && selfId.equals(entry.getAsString())) return true;
                continue;
            }
            if (!entry.isJsonObject()) continue;
            JsonObject mention = entry.getAsJsonObject();
            if (truthy(mention, "is_you", "isYou")) return true;
            String mentionedId = first(mention, "id", "openid", "user_openid", "member_openid", "user_id");
            if (selfId != null && !selfId.isBlank() && selfId.equals(mentionedId)) return true;
        }
        return false;
    }

    private static boolean truthy(JsonObject value, String... keys) {
        if (value == null) return false;
        for (String key : keys) {
            if (!value.has(key) || !value.get(key).isJsonPrimitive()) continue;
            String text = value.get(key).getAsString();
            if ("1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) return true;
        }
        return false;
    }

    @Override public void sendText(BotMessage message, String text) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("content", text == null ? "" : text);
            body.addProperty("msg_type", 0);
            addPassiveReply(body, message);
            postMessage(message, body);
        } catch (Throwable error) { throw sendFailure(error); }
    }

    static void addPassiveReply(JsonObject body, BotMessage message) {
        if (message == null || message.messageId() == null || message.messageId().isBlank()) return;
        body.addProperty("msg_id", message.messageId());
        body.addProperty("msg_seq", message.nextPassiveReplySequence());
    }

    @Override public void sendResult(BotMessage message, RenderModels.Result result, String metadata) {
        sendResult(message, result, metadata, null);
    }

    @Override public void sendResult(BotMessage message, RenderModels.Result result, String metadata, Path metadataImage) {
        sendResultTracked(message, result, metadata, metadataImage);
    }

    @Override public List<String> sendResultTracked(BotMessage message, RenderModels.Result result, String metadata,
                                                     Path metadataImage) {
        return sendResultWithActionsTracked(message, result, metadata, metadataImage, "", List.of());
    }

    @Override public List<String> sendResultWithActionsTracked(BotMessage message, RenderModels.Result result,
                                                                String metadata, Path metadataImage, String title,
                                                                List<BotAction> actions) {
        try {
            if (result.images().isEmpty()) { sendText(message, "投影渲染完成，但没有可发送的图片。"); return List.of(); }
            List<byte[]> payloads = new ArrayList<>();
            String imageSendLayout = AgentConfig.effectiveOfficialImageSendLayout(
                    globalImageSendLayout, profile.imageSendLayout);
            boolean mergeImages = !AgentConfig.separateImageSend(imageSendLayout);
            log.accept("官方 QQ［" + profile.name + "］渲染图发送模式：" + imageSendLayout + "，拼接=" + mergeImages);
            if (metadataImage != null) {
                if (!Files.isRegularFile(metadataImage)) throw new IOException("投影信息拼图不存在");
                payloads.add(Files.readAllBytes(metadataImage));
            } else {
                if (mergeImages && result.images().size() > 1) {
                    var view = new RenderModels.View("overview", "投影结果", 0, 0, 1.0, true,
                            result.images().getFirst().width(), result.images().getFirst().height(), "#000000", false, 1);
                    CloudConnection.MergedPng merged = CloudConnection.mergeImages(result.images(), view, imageSendLayout);
                    if (merged != null && merged.bytes().length <= CloudConnection.MAX_MERGED_IMAGE_BYTES) {
                        payloads.add(merged.bytes());
                    } else if (merged != null) {
                        log.accept("官方 QQ［" + profile.name + "］合并图超过 10MB，改为逐张发送");
                    }
                }
                if (payloads.isEmpty()) for (var image : result.images()) payloads.add(Files.readAllBytes(image.path()));
            }
            List<String> messageIds = new ArrayList<>();
            for (int index = 0; index < payloads.size(); index++) {
                byte[] bytes = payloads.get(index);
                JsonObject fileInfo = uploadMedia(message, bytes, 1);
                boolean last = index == payloads.size() - 1;
                String content = metadataImage == null && last && metadata != null ? metadata : "";
                JsonObject body = mediaResultPayload(fileInfo, content);
                addPassiveReply(body, message);
                messageIds.addAll(responseMessageAliases(postMessage(message, body)));
            }
            if (actions != null && !actions.isEmpty()) {
                try { messageIds.addAll(sendActions(message, title, actions)); }
                catch (RuntimeException actionError) {
                    log.accept("官方 QQ 操作按钮发送失败，渲染图片已发送：" + actionError.getMessage());
                }
            }
            return List.copyOf(messageIds);
        } catch (Throwable error) { throw sendFailure(error); }
    }

    static JsonObject mediaResultPayload(JsonObject media, String content) {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", 7);
        body.addProperty("content", content == null ? "" : content);
        body.add("media", media);
        return body;
    }

    static List<String> responseMessageAliases(JsonObject response) {
        if (response == null) return List.of();
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        // QQ's send receipt uses a different identifier for subsequent quoted replies.
        for (String key : List.of("id", "message_id", "msg_id", "data.id", "data.message_id", "data.msg_id",
                "ext_info.ref_idx", "data.ext_info.ref_idx")) {
            String id = first(response, key);
            if (!id.isBlank()) aliases.add(id);
        }
        aliases.addAll(messageIndexAliases(response));
        JsonObject nested = object(response, "data");
        aliases.addAll(messageIndexAliases(nested));
        return List.copyOf(aliases);
    }

    @Override public void sendProjection(BotMessage message, RenderModels.Result result, String metadata, Path projection) {
        sendProjection(message, result, metadata, projection, null);
    }

    @Override public void sendProjection(BotMessage message, RenderModels.Result result, String metadata,
                                         Path projection, Path metadataImage) {
        sendResult(message, result, metadata, metadataImage);
        try {
            if (projection == null || !Files.isRegularFile(projection)) throw new IOException("缓存中没有投影文件");
            String fileName = projection.getFileName().toString();
            JsonObject fileInfo = uploadMedia(message, Files.readAllBytes(projection), 4, fileName);
            JsonObject body = new JsonObject();
            body.addProperty("content", "投影文件：" + fileName);
            body.addProperty("msg_type", 7);
            body.add("media", fileInfo);
            addPassiveReply(body, message);
            postMessage(message, body);
        } catch (Throwable error) {
            throw sendFailure(error);
        }
    }

    @Override public void sendSearch(BotMessage message, ProjectionSearch.SearchPage page, Path contactSheet) {
        sendSearchTracked(message, page, contactSheet);
    }

    @Override public List<String> sendSearchTracked(BotMessage message, ProjectionSearch.SearchPage page, Path contactSheet) {
        try {
            if (contactSheet == null || !Files.isRegularFile(contactSheet)) throw new IOException("投影搜索结果图片不存在");
            JsonObject fileInfo = uploadMedia(message, Files.readAllBytes(contactSheet), 1);
            JsonObject body = new JsonObject();
            body.addProperty("content", "");
            body.addProperty("msg_type", 7);
            body.add("media", fileInfo);
            addPassiveReply(body, message);
            return responseMessageAliases(postMessage(message, body));
        } catch (Throwable error) { throw sendFailure(error); }
    }

    @Override public void sendImage(BotMessage message, Path image) {
        try {
            if (image == null || !Files.isRegularFile(image)) throw new IOException("待发送图片不存在");
            JsonObject fileInfo = uploadMedia(message, Files.readAllBytes(image), 1);
            JsonObject body = new JsonObject();
            body.addProperty("content", "");
            body.addProperty("msg_type", 7);
            body.add("media", fileInfo);
            addPassiveReply(body, message);
            postMessage(message, body);
        } catch (Throwable error) { throw sendFailure(error); }
    }

    @Override public void sendImages(BotMessage message, List<Path> images) {
        for (Path image : images) sendImage(message, image);
    }

    @Override public void sendFile(BotMessage message, Path file) {
        sendFileTracked(message, file);
    }

    @Override public List<String> sendFileTracked(BotMessage message, Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) throw new IOException("待发送文件不存在");
            String fileName = file.getFileName().toString();
            JsonObject media = uploadMedia(message, Files.readAllBytes(file), 4, fileName);
            JsonObject body = new JsonObject();
            body.addProperty("content", "文件：" + fileName);
            body.addProperty("msg_type", 7);
            body.add("media", media);
            addPassiveReply(body, message);
            return responseMessageAliases(postMessage(message, body));
        } catch (Throwable error) { throw sendFailure(error); }
    }

    @Override public List<String> sendActions(BotMessage message, String title, List<BotAction> actions) {
        try {
            JsonObject body = actionCardPayload(title, actions);
            addPassiveReply(body, message);
            return responseMessageAliases(postMessage(message, body));
        } catch (Throwable error) { throw sendFailure(error); }
    }

    static JsonObject actionCardPayload(String title, List<BotAction> actions) {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", 2);
        JsonObject markdown = new JsonObject();
        markdown.addProperty("content", title == null ? "投影操作" : title);
        body.add("markdown", markdown);
        JsonArray rows = new JsonArray();
        for (int offset = 0; offset < actions.size(); offset += 3) {
            JsonArray buttons = new JsonArray();
            for (int index = offset; index < Math.min(offset + 3, actions.size()); index++) {
                BotAction item = actions.get(index);
                JsonObject button = new JsonObject();
                button.addProperty("id", "projection-" + index);
                JsonObject render = new JsonObject();
                render.addProperty("label", item.label());
                render.addProperty("visited_label", item.label());
                render.addProperty("style", 1);
                button.add("render_data", render);
                JsonObject action = new JsonObject();
                action.addProperty("type", 2);
                JsonObject permission = new JsonObject();
                permission.addProperty("type", 2);
                action.add("permission", permission);
                action.addProperty("data", item.command());
                action.addProperty("enter", true);
                button.add("action", action);
                buttons.add(button);
            }
            JsonObject row = new JsonObject();
            row.add("buttons", buttons);
            rows.add(row);
        }
        JsonObject content = new JsonObject();
        content.add("rows", rows);
        JsonObject keyboard = new JsonObject();
        keyboard.add("content", content);
        body.add("keyboard", keyboard);
        return body;
    }

    synchronized void syncSlashCommandPanels() {
        if (closed) return;
        try {
            List<PanelRecord> groupPanels = resolvePanelRecords(listPanels("group"));
            List<PanelRecord> c2cPanels = resolvePanelRecords(listPanels("c2c"));
            List<List<JsonObject>> desired = OfficialSlashCommandPanels.pages(config);
            int totalPanels = groupPanels.size() + c2cPanels.size();
            int desiredCount = desired.size() * 2;
            int managedStaleCount = countStaleManaged(groupPanels, "group", desired.size())
                    + countStaleManaged(c2cPanels, "c2c", desired.size());
            if (!desired.isEmpty() && totalPanels - managedStaleCount + countMissing(groupPanels, "group", desired.size())
                    + countMissing(c2cPanels, "c2c", desired.size()) > OfficialSlashCommandPanels.MAX_PANELS_PER_BOT) {
                log.accept("官方 QQ［" + profile.name + "］指令面板未同步：机器人已有面板，无法在20个面板上限内容纳全部指令（需要 "
                        + desiredCount + " 个全局面板）");
                return;
            }
            syncPanelScope("group", groupPanels, desired);
            syncPanelScope("c2c", c2cPanels, desired);
            log.accept(desired.isEmpty()
                    ? "官方 QQ［" + profile.name + "］已移除本工具注册的群聊与单聊 / 指令面板"
                    : "官方 QQ［" + profile.name + "］群聊与单聊 / 指令面板已同步，共 " + desiredCount + " 个面板");
        } catch (Throwable error) {
            String reason = error.getMessage() == null ? error.toString() : error.getMessage();
            log.accept("官方 QQ［" + profile.name + "］/ 指令面板同步失败：" + reason);
        }
    }

    private void syncPanelScope(String scope, List<PanelRecord> records, List<List<JsonObject>> desired) throws IOException, InterruptedException {
        Map<Integer, PanelRecord> managed = new java.util.HashMap<>();
        for (PanelRecord details : records) {
            int page = OfficialSlashCommandPanels.pageFromRemark(details.remark(), scope);
            if (page > 0) managed.put(page, details);
        }

        int maxPage = desired.size();
        for (var entry : managed.entrySet()) {
            if (entry.getKey() > maxPage) {
                panelRequest("DELETE", OPEN_API_BASE + "/v2/panels/" + path(entry.getValue().panelId()), null);
            }
        }
        for (int page = 1; page <= desired.size(); page++) {
            JsonObject body = OfficialSlashCommandPanels.updateBody(scope, page, desired.get(page - 1));
            PanelRecord existing = managed.get(page);
            if (existing == null) {
                panelRequest("POST", OPEN_API_BASE + "/v2/panels", OfficialSlashCommandPanels.createBody(scope, page, desired.get(page - 1)));
                continue;
            }
            JsonObject current = existing.rawPanel();
            if (!OfficialSlashCommandPanels.matchesPanel(current, body.getAsJsonObject("panel"))) {
                panelRequest("PUT", OPEN_API_BASE + "/v2/panels/" + path(existing.panelId()), body);
            }
        }
    }

    private int countStaleManaged(List<PanelRecord> records, String scope, int desiredPages) {
        int count = 0;
        for (PanelRecord value : records) {
            if (OfficialSlashCommandPanels.pageFromRemark(value.remark(), scope) > desiredPages) count++;
        }
        return count;
    }

    private int countMissing(List<PanelRecord> records, String scope, int desiredPages) {
        java.util.Set<Integer> pages = new java.util.HashSet<>();
        for (PanelRecord value : records) {
            int page = OfficialSlashCommandPanels.pageFromRemark(value.remark(), scope);
            if (page > 0 && page <= desiredPages) pages.add(page);
        }
        return desiredPages - pages.size();
    }

    private List<PanelRecord> resolvePanelRecords(List<JsonObject> records) throws IOException, InterruptedException {
        List<PanelRecord> resolved = new ArrayList<>();
        for (JsonObject raw : records) {
            PanelRecord value = panelRecord(raw);
            if (value.panelId().isBlank()) continue;
            if (value.remark().isBlank() || (OfficialSlashCommandPanels.isManagedRemark(value.remark())
                    && (!value.rawPanel().has("items") || !value.rawPanel().get("items").isJsonArray()))) {
                PanelRecord detail = panelRecord(panelRequest("GET", OPEN_API_BASE + "/v2/panels/" + path(value.panelId()), null));
                value = new PanelRecord(value.panelId(), detail.remark().isBlank() ? value.remark() : detail.remark(), detail.rawPanel());
            }
            resolved.add(value);
        }
        return resolved;
    }

    private List<JsonObject> listPanels(String scope) throws IOException, InterruptedException {
        List<JsonObject> records = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < OfficialSlashCommandPanels.MAX_PANELS_PER_BOT; page++) {
            String url = OPEN_API_BASE + "/v2/panels?scope=" + path(scope) + "&limit=50"
                    + (cursor.isBlank() ? "" : "&cursor=" + path(cursor));
            JsonObject response = panelRequest("GET", url, null);
            JsonObject data = response.has("data") && response.get("data").isJsonObject()
                    ? response.getAsJsonObject("data") : response;
            JsonArray pageRecords = data.has("records") && data.get("records").isJsonArray()
                    ? data.getAsJsonArray("records") : data.has("data") && data.get("data").isJsonArray()
                    ? data.getAsJsonArray("data") : new JsonArray();
            for (JsonElement record : pageRecords) if (record != null && record.isJsonObject()) records.add(record.getAsJsonObject());
            boolean finished = data.has("is_end") && data.get("is_end").getAsBoolean();
            cursor = data.has("next_cursor") && !data.get("next_cursor").isJsonNull()
                    ? data.get("next_cursor").getAsString() : "";
            if (finished || cursor.isBlank() || pageRecords.isEmpty()) break;
        }
        return records;
    }

    private JsonObject panelRequest(String method, String url, JsonObject body) throws IOException, InterruptedException {
        ensureToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "QQBot " + accessToken)
                .header("X-Union-Appid", profile.appId)
                .header("Content-Type", "application/json");
        if ("GET".equals(method)) builder.GET();
        else if ("DELETE".equals(method)) builder.DELETE();
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : Protocol.GSON.toJson(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("面板 API " + method + " 失败 HTTP " + response.statusCode() + "：" + response.body());
        }
        JsonObject value = Protocol.GSON.fromJson(response.body().isBlank() ? "{}" : response.body(), JsonObject.class);
        if (value == null) value = new JsonObject();
        JsonElement code = value.has("err_code") ? value.get("err_code") : value.get("code");
        if (code != null && !code.isJsonNull() && !"0".equals(code.getAsString())) {
            throw new IOException("面板 API " + method + " 返回错误：" + value);
        }
        return value;
    }

    private static PanelRecord panelRecord(JsonObject value) {
        if (value == null) return new PanelRecord("", "", new JsonObject());
        if (value.has("data") && value.get("data").isJsonObject()) value = value.getAsJsonObject("data");
        JsonObject panel = value.has("panel") && value.get("panel").isJsonObject() ? value.getAsJsonObject("panel") : value;
        String id = first(value, "panel_id", "id");
        String remark = first(panel, "remark");
        if (remark.isBlank()) remark = first(value, "remark");
        return new PanelRecord(id, remark, panel);
    }

    private static String path(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record PanelRecord(String panelId, String remark, JsonObject rawPanel) {}

    private RuntimeException sendFailure(Throwable error) {
        fail(error);
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        return error instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("官方 QQ 消息发送失败", error);
    }

    private JsonObject uploadMedia(BotMessage message, byte[] bytes, int fileType) throws IOException, InterruptedException {
        return uploadMedia(message, bytes, fileType, null);
    }

    private JsonObject uploadMedia(BotMessage message, byte[] bytes, int fileType, String fileName) throws IOException, InterruptedException {
        String target = message.direct() ? message.userId() : message.groupId();
        if (target == null || target.isBlank()) throw new IOException("官方 QQ 消息缺少发送目标");
        String base = profile.sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
        JsonObject body = mediaUploadBody(bytes, fileType, fileName);
        JsonObject value = post(base + (message.direct() ? "/v2/users/" : "/v2/groups/") + target + "/files", body);
        return value.has("file_info") && value.get("file_info").isJsonObject() ? value.getAsJsonObject("file_info") : value;
    }

    static JsonObject mediaUploadBody(byte[] bytes, int fileType, String fileName) {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", fileType);
        body.addProperty("file_data", Base64.getEncoder().encodeToString(bytes));
        body.addProperty("srv_send_msg", false);
        if (fileName != null && !fileName.isBlank()) body.addProperty("file_name", fileName);
        return body;
    }

    private JsonObject postMessage(BotMessage message, JsonObject body) throws IOException, InterruptedException {
        String target = message.direct() ? message.userId() : message.groupId();
        if (target == null || target.isBlank()) throw new IOException("官方 QQ 消息缺少发送目标");
        String base = profile.sandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
        return post(base + (message.direct() ? "/v2/users/" : "/v2/groups/") + target + "/messages", body);
    }

    private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
        ensureToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "QQBot " + accessToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Protocol.GSON.toJson(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("QQ REST 请求失败 HTTP " + response.statusCode() + "：" + response.body());
        lastError = "";
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
