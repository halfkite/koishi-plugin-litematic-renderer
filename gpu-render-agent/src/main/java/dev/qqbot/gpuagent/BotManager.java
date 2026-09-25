package dev.qqbot.gpuagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 管理多个机器人账号，并把平台事件接入统一的投影渲染管线。 */
final class BotManager implements AutoCloseable {
    static final List<String> ADDITIONAL_VIEW_NAMES = List.of(
            "正轴视图", "反轴视图", "正视图", "后视图", "左视图", "右视图", "俯视图", "仰视图", "地图视图");
    private static final long QUOTED_RENDER_TTL_MILLIS = Duration.ofDays(30).toMillis();
    private static final long OBSERVED_INDEX_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int MAX_QUOTED_RENDER_MESSAGES = 10_000;
    private static final String INTRODUCTION_PRIVACY = "通过 BOT 渲染的投影会进行缓存，并可能被其他群玩家获取；不会泄露投影文件之外的信息，官方平台也有发送限制。使用功能即视为同意。";

    private final Path root;
    private final Path configPath;
    private final AgentConfig config;
    private final RenderService renderer;
    private final Path renderedMessageIndexPath;
    private final AnnouncementGroups announcementGroups;
    private final AnnouncementSchedules announcementSchedules;
    private final ScheduledExecutorService announcementTimer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "announcement-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean announcementTimerStarted = new AtomicBoolean();
    private final Consumer<String> log;
    private final Map<String, BotAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, Long> handledMessages = new ConcurrentHashMap<>();
    private final Map<RenderedMessageKey, QuotedProjection> renderedMessages = new ConcurrentHashMap<>();
    private final Map<ObservedMessageKey, ObservedMessageIndices> observedMessageIndices = new ConcurrentHashMap<>();
    private final List<String> recentLogs = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile DownloadProgress downloadProgress = DownloadProgress.idle();
    private volatile Consumer<DownloadProgress> downloadProgressListener = ignored -> {};

    BotManager(Path root, Path configPath, AgentConfig config, RenderService renderer, Consumer<String> log) {
        this.root = root;
        this.configPath = configPath;
        this.config = config;
        this.renderer = renderer;
        this.renderedMessageIndexPath = root.resolve("rendered-message-index.json5");
        this.log = message -> {
            String value = message == null ? "" : message;
            recentLogs.add(value);
            while (recentLogs.size() > 300) recentLogs.removeFirst();
            if (log != null) log.accept(value);
        };
        this.announcementGroups = new AnnouncementGroups(root.resolve("announcement-groups.json"), this.log);
        this.announcementSchedules = new AnnouncementSchedules(root.resolve("announcement-schedules.json"), this.log);
        loadRenderedMessages();
    }

    synchronized void start() {
        if (closed) return;
        reload(true);
        if (announcementTimerStarted.compareAndSet(false, true))
            announcementTimer.scheduleWithFixedDelay(this::runDueAnnouncements, 5, 10, TimeUnit.SECONDS);
    }

    /** 配置保存后只重启机器人适配器，渲染任务和 Minecraft Runtime 保持复用。 */
    synchronized void reload() {
        reload(false);
    }

    void syncOfficialCommandPanels() {
        for (BotAdapter adapter : adapters.values()) {
            if (adapter instanceof OfficialQqAdapter official) {
                Thread.startVirtualThread(official::syncSlashCommandPanels);
            }
        }
    }

    private synchronized void reload(boolean automatic) {
        if (closed) return;
        closeAdapters();
        if (config.botProfiles == null) config.botProfiles = new ArrayList<>();
        for (AgentConfig.BotProfile profile : config.botProfiles) {
            if (profile == null) continue;
            profile.normalize();
            if (!profile.enabled) continue;
            if (automatic && !profile.autoConnect) {
                log.accept("跳过未启用启动自动连接的机器人账号［" + profile.name + "］");
                continue;
            }
            try {
                startAdapter(profile);
            } catch (Throwable error) {
                CrashLogger.record("启动机器人账号失败［" + profile.name + "］", error);
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

    List<AnnouncementGroup> listAnnouncementGroups() {
        List<AnnouncementGroup> result = new ArrayList<>();
        if (config.botProfiles == null) return result;
        for (AgentConfig.BotProfile profile : config.botProfiles) {
            if (profile == null || !profile.enabled) continue;
            BotAdapter adapter = adapters.get(profile.id);
            boolean connected = adapter != null && adapter.status().connected();
            Map<String, String> names = new java.util.LinkedHashMap<>();
            for (String id : announcementGroups.list(profile.id)) names.put(id, "");
            if (connected && "onebot".equals(profile.type)) {
                try {
                    for (BotGroup group : adapter.listGroups()) {
                        names.put(group.id(), group.name());
                    }
                } catch (Throwable error) {
                    log.accept("OneBot［" + profile.name + "］读取公告群列表失败：" + message(error));
                }
            }
            for (Map.Entry<String, String> group : names.entrySet()) {
                result.add(new AnnouncementGroup(profile.id, profile.name, profile.type,
                        group.getKey(), group.getValue(), connected));
            }
        }
        return List.copyOf(result);
    }

    void addAnnouncementGroup(String profileId, String groupId) {
        if (profile(profileId) == null) throw new IllegalArgumentException("机器人账号不存在");
        announcementGroups.add(profileId, groupId == null ? "" : groupId.trim());
    }

    void removeAnnouncementGroup(String profileId, String groupId) {
        announcementGroups.remove(profileId, groupId);
    }

    boolean isSavedAnnouncementGroup(String profileId, String groupId) {
        return announcementGroups.list(profileId).contains(groupId);
    }

    AnnouncementGroups.ImportResult importAnnouncementGroupsFromLegacy() throws IOException {
        Set<String> accounts = config.botProfiles == null ? Set.of() : config.botProfiles.stream()
                .filter(profile -> profile != null && profile.id != null)
                .map(profile -> profile.id).collect(java.util.stream.Collectors.toSet());
        return announcementGroups.importLegacy(legacyAnnouncementIndexPath(), accounts);
    }

    Path legacyAnnouncementIndexPath() {
        String configured = config.announcementLegacyIndexPath;
        if (configured == null || configured.isBlank()) return renderedMessageIndexPath;
        Path selected = Path.of(configured.trim()).toAbsolutePath().normalize();
        return Files.isDirectory(selected) ? selected.resolve("rendered-message-index.json5") : selected;
    }

    List<AnnouncementSchedules.Job> scheduledAnnouncements() { return announcementSchedules.list(); }

    AnnouncementSchedules.Job scheduleAnnouncement(long runAtMillis, String content) throws IOException {
        return announcementSchedules.add(runAtMillis, content);
    }

    boolean cancelScheduledAnnouncement(String id) throws IOException { return announcementSchedules.cancel(id); }

    private void runDueAnnouncements() {
        try {
            if (closed || config.botProfiles == null || config.botProfiles.stream().noneMatch(p -> p != null && p.enabled)) return;
            for (AgentConfig.BotProfile profile : config.botProfiles) {
                if (profile == null || !profile.enabled) continue;
                BotAdapter adapter = adapters.get(profile.id);
                if (adapter == null || !adapter.status().connected()) return;
            }
            AnnouncementSchedules.Job job;
            while (!closed && (job = announcementSchedules.claimDue(System.currentTimeMillis())) != null) {
                sendScheduledAnnouncement(job);
            }
        } catch (Throwable error) { log.accept("定时公告执行失败：" + message(error)); }
    }

    private void sendScheduledAnnouncement(AnnouncementSchedules.Job job) {
        int succeeded = 0;
        int failed = 0;
        int handled = 0;
        String lastError = "";
        List<AnnouncementGroup> targets = List.of();
        try {
            try {
                AnnouncementGroups.ImportResult imported = importAnnouncementGroupsFromLegacy();
                if (imported.addedGroups() > 0) log.accept("定时公告发送前从旧缓存新增 " + imported.addedGroups() + " 个群标识");
            } catch (IOException error) {
                if (Files.exists(legacyAnnouncementIndexPath())) log.accept("定时公告读取旧缓存失败：" + message(error));
            }
            targets = listAnnouncementGroups();
            if (targets.isEmpty()) lastError = "没有可用的公告目标群";
            for (AnnouncementGroup target : targets) {
                if (closed) { failed += targets.size() - handled; lastError = "工具关闭，剩余群未发送"; break; }
                try { sendAnnouncement(target, job.content()); succeeded++; }
                catch (Throwable error) {
                    failed++;
                    lastError = message(error);
                    log.accept("定时公告发送失败：" + target.profileName() + " / " + target.groupId() + "：" + lastError);
                }
                handled++;
                if (succeeded + failed < targets.size()) Thread.sleep(1000);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            lastError = "工具关闭，定时公告未全部发送";
            failed += Math.max(1, targets.size() - handled);
        } catch (Throwable error) {
            lastError = message(error);
            failed++;
            log.accept("定时公告执行异常：" + lastError);
        } finally {
            try { announcementSchedules.complete(job.id(), succeeded, failed, lastError); }
            catch (IOException error) { log.accept("定时公告结果保存失败：" + message(error)); }
            log.accept("定时公告完成：成功 " + succeeded + " 个，失败 " + failed + " 个");
        }
    }


    void sendAnnouncement(AnnouncementGroup group, String text) {
        if (group == null) throw new IllegalArgumentException("未选择公告群");
        if (text == null || text.isBlank() || text.length() > 2000)
            throw new IllegalArgumentException("公告内容须为 1 至 2000 字符");
        AgentConfig.BotProfile profile = profile(group.profileId());
        if (profile == null || !profile.enabled || !profile.type.equals(group.type())
                || (!"onebot".equals(profile.type)
                    && !announcementGroups.list(group.profileId()).contains(group.groupId())))
            throw new IllegalStateException("公告群或机器人账号已不可用");
        BotAdapter adapter = adapters.get(group.profileId());
        if (adapter == null || !adapter.status().connected()) throw new IllegalStateException("机器人账号未连接");
        adapter.sendAnnouncement(group.profileId(), group.groupId(), text);
        log.accept("公告已发送：" + profile.name + " / " + group.groupId());
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
        catch (Throwable error) {
            CrashLogger.record("重载机器人账号失败［" + profile.name + "］", error);
            log.accept("重载机器人账号失败［" + profile.name + "］：" + message(error));
        }
    }

    List<String> recentLogs() { return List.copyOf(recentLogs); }

    void setDownloadProgress(Consumer<DownloadProgress> listener) {
        downloadProgressListener = listener == null ? ignored -> {} : listener;
    }

    DownloadProgress downloadProgress() { return downloadProgress; }

    private void updateDownloadProgress(String file, long downloaded, long total, boolean active, String error) {
        DownloadProgress value = new DownloadProgress(file == null ? "" : file, downloaded, total, active, error == null ? "" : error, System.currentTimeMillis());
        downloadProgress = value;
        try { downloadProgressListener.accept(value); } catch (Throwable ignored) { }
    }

    private void accept(BotAdapter adapter, BotMessage message) {
        if (closed || message == null) return;
        if (adapter instanceof OfficialQqAdapter && !message.direct()
                && message.groupId() != null && !message.groupId().isBlank())
            announcementGroups.add(message.profileId(), message.groupId());
        observeMessageIndices(message);
        String rawText = normalizeCommandText(message.rawText());
        SearchCommand search = parseSearchCommand(rawText);
        ProjectionSendCommand send = parseSendCommand(rawText, config.commandNames("sendProjection"));
        SendCommand materials = parseMaterialsCommand(rawText, config.commandNames("sendMaterials"));
        ViewCommand viewCommand = parseViewCommand(rawText, config.commandNames("moreViews"),
                config.commandNames("projectionView"), config.commandNames("mapView"));
        boolean projectionList = parseProjectionListCommand(rawText, config.commandNames("projectionList"));
        boolean slashCommand = rawText.startsWith("/");
        boolean help = parseHelpCommand(rawText, config.commandNames("help"));
        boolean hasAttachments = message.attachments() != null && !message.attachments().isEmpty();
        List<String> introductionNames = config.commandNames("introduction");
        boolean introduction = !introductionNames.isEmpty()
                && isIntroductionRequest(rawText, message.mentioned(), hasAttachments, introductionNames);
        if (introduction) {
            boolean introductionPanelCommand = slashCommand || isIntroductionPanelCommand(rawText);
            log.accept("机器人［" + profileName(message.profileId()) + "］收到介绍请求（@=" + message.mentioned()
                    + "，斜杠/面板指令=" + introductionPanelCommand + "）");
            Thread.startVirtualThread(() -> processIntroductionCommand(adapter, message, introductionPanelCommand));
            return;
        }
        if (help) {
            log.accept("机器人［" + profileName(message.profileId()) + "］收到帮助请求（@=" + message.mentioned()
                    + "，斜杠指令=" + slashCommand + "）");
            Thread.startVirtualThread(() -> processHelpCommand(adapter, message, slashCommand));
            return;
        }
        if (materials != null) {
            log.accept("机器人［" + profileName(message.profileId()) + "］收到文字指令：sendMaterials（@=" + message.mentioned() + "）");
            Thread.startVirtualThread(() -> processMaterialsCommand(adapter, message, materials, slashCommand));
            return;
        }
        if (viewCommand != null) {
            Thread.startVirtualThread(() -> processViewCommand(adapter, message, viewCommand, slashCommand));
            return;
        }
        if (projectionList) {
            log.accept("机器人［" + profileName(message.profileId()) + "］收到文字指令：projectionList（@=" + message.mentioned() + "）");
            Thread.startVirtualThread(() -> processProjectionListCommand(adapter, message, slashCommand));
            return;
        }
        if (search != null || send != null) {
            String command = search != null ? "search" : "sendProjection";
            log.accept("机器人［" + profileName(message.profileId()) + "］收到文字指令：" + command
                    + "（" + (message.direct() ? "私聊" : "群聊") + "，@=" + message.mentioned() + "）");
            Thread.startVirtualThread(() -> processSearchCommand(adapter, message, search, send, slashCommand));
            return;
        }
        String commandCandidate = configuredCommandCandidate(rawText);
        if (!commandCandidate.isBlank()) {
            log.accept("机器人［" + profileName(message.profileId()) + "］收到疑似投影指令但未匹配：" + commandCandidate
                    + "（@=" + message.mentioned() + "，文本长度=" + rawText.length() + "）");
        }
        if (!config.automaticRenderingEnabled || message.attachments() == null || message.attachments().isEmpty()) return;
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

    private void processIntroductionCommand(BotAdapter adapter, BotMessage message, boolean slashCommand) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        if (profile == null || !profile.enabled) return;
        if (!message.direct() && profile.commandRequireMention && !message.mentioned() && !slashCommand) return;
        if (!message.direct() && !isGroupAllowed(profile, message.groupId())) return;
        try {
            Path card = HelpCardRenderer.createIntroduction(root.resolve("introduction-cards"), introductionText());
            try { adapter.sendImage(message, card); }
            finally { deleteTemporary(card, "机器人介绍图片"); }
        } catch (Throwable error) {
            log.accept("机器人［" + profile.name + "］介绍图片发送失败：" + message(error));
        }
    }

    private void processHelpCommand(BotAdapter adapter, BotMessage message, boolean slashCommand) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        if (profile == null || !profile.enabled) return;
        if (!message.direct() && profile.commandRequireMention && !message.mentioned() && !slashCommand) return;
        if (!message.direct() && !isGroupAllowed(profile, message.groupId())) return;
        Path card = null;
        try {
            card = HelpCardRenderer.create(root.resolve("help-cards"), config);
            adapter.sendImage(message, card);
        } catch (Throwable error) {
            log.accept("机器人［" + profile.name + "］帮助图片发送失败，改发文字帮助：" + message(error));
            try {
                adapter.sendText(message, HelpCardRenderer.text(config));
            } catch (Throwable fallbackError) {
                log.accept("机器人［" + profile.name + "］文字帮助发送失败：" + message(fallbackError));
            }
        } finally {
            if (card != null) {
                try { Files.deleteIfExists(card); }
                catch (IOException error) { log.accept("帮助图片临时文件清理失败：" + message(error)); }
            }
        }
    }

    private void processSearchCommand(BotAdapter adapter, BotMessage message, SearchCommand search,
                                      ProjectionSendCommand send, boolean slashCommand) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        if (profile == null || !profile.enabled) return;
        if (message.direct() && !profile.allowPrivateRender) return;
        if (!message.direct() && profile.commandRequireMention && !message.mentioned() && !slashCommand) {
            log.accept("机器人［" + profile.name + "］未执行群指令：没有收到可验证的 @ 事件");
            try {
                adapter.sendText(message, "已识别到投影指令，但当前设置要求 @ 机器人。请从群聊 @ 列表选择机器人后重发，或在账号设置中关闭“指令需要 @”。");
            } catch (Throwable sendError) {
                log.accept("群指令提示发送失败：" + message(sendError));
            }
            return;
        }
        if (!message.direct() && !isGroupAllowed(profile, message.groupId())) return;
        try {
            if (search != null) {
                ProjectionSearch.SearchPage page = ProjectionSearch.search(renderer.cacheDirectory(),
                        search.keyword(), config.projectionSearchResultLimit);
                if (page.entries().isEmpty()) {
                    adapter.sendText(message, "没有找到包含“" + search.keyword() + "”的已缓存投影。");
                    return;
                }
                Path sheet = ProjectionSearch.createContactSheet(page, root.resolve("projection-search"));
                try {
                    adapter.sendSearchTracked(message, page, sheet);
                }
                finally { Files.deleteIfExists(sheet); }
                log.accept("机器人［" + profile.name + "］已返回投影搜索结果：" + search.keyword() + "（" + page.entries().size() + " 个）");
                return;
            }
            if (send.ordinal() == null && send.exactName().isBlank()) {
                adapter.sendText(message, "请发送“/" + commandName("sendProjection", "发送投影")
                        + " 编号”或“/" + commandName("sendProjection", "发送投影") + " 完整投影名称”。");
                return;
            }
            ProjectionSearch.SearchEntry entry;
            if (send.ordinal() != null) {
                entry = ProjectionSearch.findByOrdinal(renderer.cacheDirectory(), send.ordinal());
            } else {
                List<ProjectionSearch.SearchEntry> matches = ProjectionSearch.findByExactName(
                        renderer.cacheDirectory(), send.exactName());
                if (matches.size() > 1) {
                    adapter.sendText(message, "有多个同名投影“" + send.exactName() + "”，请使用投影编号发送。");
                    return;
                }
                entry = matches.isEmpty() ? null : matches.getFirst();
            }
            if (entry == null) {
                adapter.sendText(message, "没有找到对应的投影缓存。请使用固定编号或完整投影名称（不含 .litematic）。");
                return;
            }
            if (!Files.isRegularFile(entry.projection())) {
                adapter.sendText(message, "缓存中没有保留编号 " + entry.ordinal() + " 的投影文件。");
                return;
            }
            byte[] schematic = Files.readAllBytes(entry.projection());
            String filename = entry.originalFilename();
            try {
                LitematicMetadata.validateNbtRoot(schematic);
            } catch (IOException invalidProjection) {
                throw new IOException("缓存中的投影文件无效或已损坏：" + filename + "。请重新导入完整的 .litematic 文件。", invalidProjection);
            }
            String metadata = metadata(filename, schematic, entry.ordinal());
            RenderModels.Request request = searchRequest(filename);
            RenderModels.Result result = renderer.submit(request, schematic, Duration.ofMillis(config.renderTimeoutMillis),
                    "搜索投影:" + profile.name, null, new RenderModels.TaskMeta(message.groupId(), message.userId())).join();
            Path infoImage = createMetadataImage(metadata, result);
            try {
                List<String> resultIds = adapter.sendResultWithActionsTracked(message, result, metadata, infoImage,
                        "投影编号：" + entry.ordinal(), projectionActions(entry.ordinal()));
                List<String> fileIds = adapter.sendFileTracked(message, entry.projection());
                List<String> allIds = new ArrayList<>(resultIds);
                allIds.addAll(fileIds);
                rememberRenderedMessages(message, allIds, entry.projection());
            }
            finally { deleteTemporary(infoImage, "投影信息图片"); }
            log.accept("机器人［" + profile.name + "］已发送投影：" + filename + "（编号 " + entry.ordinal() + "）");
        } catch (Throwable error) {
            String action = search == null ? "发送投影" : "搜索投影";
            log.accept("机器人［" + profile.name + "］" + action + "失败：" + message(error));
            try { adapter.sendText(message, action + "失败：" + message(error)); }
            catch (Throwable sendError) { log.accept("机器人错误消息发送失败：" + message(sendError)); }
        }
    }

    private List<BotAction> projectionActions(int ordinal) {
        if (ordinal <= 0) return List.of();
        List<BotAction> actions = new ArrayList<>();
        if (!config.commandNames("sendMaterials").isEmpty())
            actions.add(new BotAction("导出材料", "/" + commandName("sendMaterials", "导出材料") + ordinal));
        if (!config.commandNames("moreViews").isEmpty())
            actions.add(new BotAction("更多视图", "/" + commandName("moreViews", "更多视图") + ordinal));
        if (!config.commandNames("mapView").isEmpty())
            actions.add(new BotAction("地图视图", "/" + commandName("mapView", "地图视图") + ordinal));
        return List.copyOf(actions);
    }

    private String introductionText() {
        List<String> enabledCommands = new ArrayList<>();
        for (String id : List.of("search", "sendProjection", "sendMaterials", "projectionList", "moreViews", "projectionView", "mapView", "help")) {
            List<String> names = config.commandNames(id);
            if (!names.isEmpty()) enabledCommands.add("/" + names.getFirst());
        }
        StringBuilder text = new StringBuilder(config.automaticRenderingEnabled
                ? "这是一个会自动识别群内投影文件并生成渲染图的 BOT。"
                : "这是一个用于查询、获取和处理投影的 BOT。");
        if (!enabledCommands.isEmpty()) text.append("\n当前启用指令：").append(String.join("、", enabledCommands));
        else text.append("\n当前没有启用文字指令。");
        if (config.automaticRenderingEnabled) {
            text.append("\n群内自动渲染需要群主将 BOT 设置中的“机器人获取的群聊消息范围”设为获取群内全部消息。");
        }
        text.append("\n").append(INTRODUCTION_PRIVACY);
        text.append("\n如果介意请自行部署，项目名称为 koishi-plugin-litematic-renderer，开源在 GitHub 上。");
        return text.toString();
    }

    private int ordinalOf(Path projection) throws IOException {
        if (projection == null || projection.getParent() == null) return 0;
        String hash = projection.getParent().getFileName().toString();
        for (ProjectionCacheIndex.Entry entry : ProjectionCacheIndex.ensure(renderer.cacheDirectory())) {
            if (hash.equalsIgnoreCase(entry.fileHash())) return entry.ordinal();
        }
        return 0;
    }

    static ViewCommand parseViewCommand(String text) {
        AgentConfig defaults = new AgentConfig();
        return parseViewCommand(text, defaults.commandNames("moreViews"), defaults.commandNames("projectionView"),
                defaults.commandNames("mapView"));
    }

    static ViewCommand parseViewCommand(String text, List<String> moreViewNames,
                                        List<String> projectionViewNames, List<String> mapViewNames) {
        ViewCommand command = parseViewCommand(text, moreViewNames, "更多视图", false);
        if (command != null) return command;
        command = parseViewCommand(text, projectionViewNames, "投影视图", true);
        if (command != null) return command;
        return parseViewCommand(text, mapViewNames, "地图视图", false);
    }

    private static ViewCommand parseViewCommand(String text, List<String> names, String action, boolean acceptsViewName) {
        if (text == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String tail = acceptsViewName ? "(?:\\s+(.+?))?" : "";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "^(?:/|!)?" + java.util.regex.Pattern.quote(name) + "\\s*(\\d+)" + tail + "\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text.trim());
            if (!matcher.matches()) continue;
            try {
                String viewName = acceptsViewName && matcher.group(2) != null ? matcher.group(2).trim() : "";
                String parsedAction = "地图视图".equals(action) && "地图画模式".equals(name) ? name : action;
                return new ViewCommand(parsedAction, Integer.parseInt(matcher.group(1)), viewName);
            } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private void processViewCommand(BotAdapter adapter, BotMessage message, ViewCommand command, boolean slashCommand) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        if (!authorizeTextCommand(adapter, message, profile, slashCommand)) return;
        Path infoImage = null;
        try {
            ProjectionSearch.SearchEntry entry = ProjectionSearch.findByOrdinal(renderer.cacheDirectory(), command.ordinal());
            if (entry == null || !Files.isRegularFile(entry.projection())) {
                adapter.sendText(message, "没有找到编号 " + command.ordinal() + " 对应的缓存投影。");
                return;
            }
            if ("更多视图".equals(command.action())) {
                List<BotAction> actions = new ArrayList<>();
                for (String name : ADDITIONAL_VIEW_NAMES) {
                    if ("地图视图".equals(name)) {
                        if (config.commandNames("mapView").isEmpty()) continue;
                    } else if (config.commandNames("projectionView").isEmpty()) continue;
                    String commandText = "地图视图".equals(name)
                            ? "/" + commandName("mapView", "地图视图") + command.ordinal()
                            : "/" + commandName("projectionView", "投影视图") + command.ordinal() + " " + name;
                    actions.add(new BotAction(name, commandText));
                }
                if (actions.isEmpty()) {
                    adapter.sendText(message, "详细视图和地图视图功能都已停用。");
                    return;
                }
                adapter.sendActions(message, "投影编号：" + command.ordinal() + " · 更多视图", actions);
                return;
            }
            boolean mapArt = "地图视图".equals(command.action()) || "地图画模式".equals(command.action())
                    || "地图视图".equals(command.viewName());
            RenderModels.View view = mapArt ? mapArtView() : auxiliaryView(command.viewName());
            if (view == null) {
                adapter.sendText(message, "不支持的视角。请点击“更多视图”重新选择。");
                return;
            }
            byte[] schematic = Files.readAllBytes(entry.projection());
            RenderModels.Request request = new RenderModels.Request(2, UUID.randomUUID().toString(),
                    entry.originalFilename(), List.of(view), null, Main.VERSION, null);
            RenderModels.Result result = renderer.submitAuxiliary(request, schematic,
                    Duration.ofMillis(config.renderTimeoutMillis), "投影视图:" + profile.name,
                    new RenderModels.TaskMeta(message.groupId(), message.userId())).join();
            infoImage = ProjectionInfoCardRenderer.createViewComposite(entry.displayName(), command.ordinal(),
                    mapArt ? "地图视图" : command.viewName(), result.images(), root.resolve("projection-info"));
            List<String> ids = adapter.sendResultTracked(message, result, "", infoImage);
            rememberRenderedMessages(message, ids, entry.projection());
        } catch (Throwable error) {
            log.accept("投影视图渲染失败：" + message(error));
            try { adapter.sendText(message, "投影视图渲染失败：" + message(error)); }
            catch (Throwable ignored) { }
        } finally {
            deleteTemporary(infoImage, "详细视图图片");
        }
    }

    RenderModels.View auxiliaryView(String name) {
        if (name == null) return null;
        double yaw;
        double pitch;
        String id;
        switch (name) {
            case "正轴视图" -> { id = "axis"; yaw = 135; pitch = 36; }
            case "反轴视图" -> { id = "reverse-axis"; yaw = 315; pitch = 36; }
            case "正视图" -> { id = "front"; yaw = 0; pitch = 0; }
            case "后视图" -> { id = "back"; yaw = 180; pitch = 0; }
            case "左视图" -> { id = "left"; yaw = 270; pitch = 0; }
            case "右视图" -> { id = "right"; yaw = 90; pitch = 0; }
            case "俯视图" -> { id = "top"; yaw = 0; pitch = 90; }
            case "仰视图" -> { id = "bottom"; yaw = 0; pitch = -90; }
            case "上" -> { id = "top"; yaw = 0; pitch = 90; }
            case "下" -> { id = "bottom"; yaw = 0; pitch = -90; }
            case "前" -> { id = "front"; yaw = 180; pitch = 0; }
            case "后" -> { id = "back"; yaw = 0; pitch = 0; }
            default -> { return null; }
        }
        int width = Math.max(64, config.renderWidth);
        int height = Math.max(64, config.renderHeight);
        return new RenderModels.View("extra-" + id, name, yaw, pitch, 1.0, true,
                width, height, "#000000", false, 1);
    }

    RenderModels.View mapArtView() {
        return new RenderModels.View("extra-map-art", "地图视图", 0, 90, 1.0, true,
                1024, 1024, "#000000", false, 1);
    }

    private void processMaterialsCommand(BotAdapter adapter, BotMessage message, SendCommand command, boolean slashCommand) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        boolean quotedBareCommand = command.index() == 0 && !quotedMessageIds(message).isEmpty();
        if (!authorizeTextCommand(adapter, message, profile, slashCommand || quotedBareCommand)) return;
        Path table = null;
        Path tableDirectory = root.resolve("material-tables").resolve(UUID.randomUUID().toString());
        try {
            Path projection;
            String filename;
            if (command.index() == 0 && !quotedMessageIds(message).isEmpty()) {
                QuotedProjection quoted = findQuotedProjection(message);
                if (quoted == null) {
                    log.accept("引用导出材料未命中渲染缓存：引用索引已解析，但没有对应投影消息映射");
                    String name = commandName("sendMaterials", "导出材料");
                    adapter.sendText(message, "没有匹配到这张渲染图对应的投影。请引用机器人刚发送的渲染图后发送“" + name + "”；也可发送“" + name + "1 关键词”。");
                    return;
                }
                projection = quoted.projection();
                filename = quoted.filename();
            } else {
                if (command.index() == 0) {
                    String name = commandName("sendMaterials", "导出材料");
                    adapter.sendText(message, "请引用机器人刚发送的渲染图后发送“" + name + "”；也可发送“" + name + "1 关键词”。");
                    return;
                }
                ProjectionSearch.SearchEntry entry = ProjectionSearch.findByOrdinal(renderer.cacheDirectory(), command.index());
                if (entry == null || !entry.displayName().toLowerCase(java.util.Locale.ROOT)
                        .contains(command.keyword().toLowerCase(java.util.Locale.ROOT))) {
                    adapter.sendText(message, "没有找到编号 " + command.index() + " 对应的缓存投影。");
                    return;
                }
                projection = entry.projection();
                filename = entry.originalFilename();
            }
            if (!Files.isRegularFile(projection)) throw new IOException("对应的缓存投影文件已不存在");
            LitematicMaterials.Report report = LitematicMaterials.analyze(projection);
            table = ProjectionMaterialsExporter.create(filename, report, tableDirectory, root, projection);
            adapter.sendFile(message, table);
            log.accept("机器人［" + profile.name + "］材料清单文件已发送：" + filename
                    + "（方块种类 " + report.blocks().size() + "，容器材料种类 " + report.containerItems().size() + "）");
        } catch (Throwable error) {
            log.accept("机器人［" + profile.name + "］生成材料清单文件失败：" + message(error));
            try { adapter.sendText(message, "材料清单文件生成失败：" + message(error)); }
            catch (Throwable sendError) { log.accept("材料清单错误消息发送失败：" + message(sendError)); }
        } finally {
            if (table != null) try { Files.deleteIfExists(table); } catch (IOException error) { log.accept("材料清单临时文件清理失败：" + message(error)); }
            try { Files.deleteIfExists(tableDirectory); } catch (IOException ignored) { }
        }
    }

    private void processProjectionListCommand(BotAdapter adapter, BotMessage message, boolean slashCommand) {
        AgentConfig.BotProfile profile = profile(message.profileId());
        if (!authorizeTextCommand(adapter, message, profile, slashCommand)) return;
        Path output = null;
        try {
            Path cache = renderer.cacheDirectory();
            ProjectionCacheIndex.rebuild(cache);
            List<ProjectionCacheIndex.Entry> entries = new ArrayList<>();
            for (ProjectionCacheIndex.Entry entry : ProjectionCacheIndex.read(cache)) {
                if (!entry.fileHash().matches("[0-9a-fA-F]{64}")) continue;
                Path directory = cache.resolve(entry.relativePath()).normalize();
                if (!directory.getParent().equals(cache) || !Files.isDirectory(directory)) continue;
                Path projection = directory.resolve(CacheStore.safeProjectionFilename(entry.filename()));
                if (Files.isRegularFile(projection)) entries.add(entry);
            }
            if (entries.isEmpty()) {
                adapter.sendText(message, "缓存中没有可列出的投影文件。");
                return;
            }
            output = ProjectionListExporter.create(entries, root.resolve("projection-lists"));
            adapter.sendFile(message, output);
            log.accept("机器人［" + profile.name + "］已发送缓存投影列表 CSV，共 " + entries.size() + " 个投影");
        } catch (Throwable error) {
            log.accept("机器人［" + profile.name + "］生成投影列表失败：" + message(error));
            try { adapter.sendText(message, "投影列表生成失败：" + message(error)); }
            catch (Throwable sendError) { log.accept("投影列表错误消息发送失败：" + message(sendError)); }
        } finally {
            if (output != null) try { Files.deleteIfExists(output); }
            catch (IOException error) { log.accept("投影列表 CSV 临时文件清理失败：" + message(error)); }
        }
    }

    private boolean authorizeTextCommand(BotAdapter adapter, BotMessage message,
                                         AgentConfig.BotProfile profile, boolean slashCommand) {
        if (profile == null || !profile.enabled) return false;
        if (message.direct()) return profile.allowPrivateRender;
        if (profile.commandRequireMention && !message.mentioned() && !slashCommand) {
            try { adapter.sendText(message, "当前设置要求指令 @ 机器人，或从 / 指令面板选择后发送。"); }
            catch (Throwable error) { log.accept("指令权限提示发送失败：" + message(error)); }
            return false;
        }
        return isGroupAllowed(profile, message.groupId());
    }

    private SearchCommand parseSearchCommand(String text) {
        return parseSearchCommand(text, config.commandNames("search"));
    }

    static SearchCommand parseSearchCommand(String text, List<String> names) {
        if (text == null || names == null) return null;
        List<String> candidates = new ArrayList<>(names);
        for (String name : candidates) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "^[/!]?" + java.util.regex.Pattern.quote(name) + "\\s+(.+?)\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (matcher.matches()) return new SearchCommand(matcher.group(1).trim());
        }
        return null;
    }

    static boolean parseHelpCommand(String text, List<String> names) {
        if (text == null || names == null) return false;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            if (java.util.regex.Pattern.compile("^[/!]?"
                            + java.util.regex.Pattern.quote(name) + "\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).matches()) return true;
        }
        return false;
    }

    static boolean isIntroductionRequest(String text, boolean mentioned, boolean hasAttachments) {
        return isIntroductionRequest(text, mentioned, hasAttachments, List.of("介绍投影BOT"));
    }

    static boolean isIntroductionRequest(String text, boolean mentioned, boolean hasAttachments, List<String> names) {
        if (hasAttachments) return false;
        return (mentioned && (text == null || text.isBlank())) || parseIntroductionCommand(text)
                || parseHelpCommand(text, names);
    }

    static boolean parseIntroductionCommand(String text) {
        return text != null && java.util.regex.Pattern.compile("^[/!]?(?:介绍投影BOT|介绍)\\s*$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text.trim()).matches();
    }

    static boolean isIntroductionPanelCommand(String text) {
        return text != null && java.util.regex.Pattern.compile("^[/!]?介绍投影BOT\\s*$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text.trim()).matches();
    }

    static ProjectionSendCommand parseSendCommand(String text, List<String> names) {
        if (text == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            if (java.util.regex.Pattern.compile("^[/!]?" + java.util.regex.Pattern.quote(name) + "\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).matches()) {
                return new ProjectionSendCommand(null, "");
            }
            java.util.regex.Matcher numbered = java.util.regex.Pattern.compile(
                    "^[/!]?" + java.util.regex.Pattern.quote(name) + "\\s*(\\d+)\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (numbered.matches()) {
                try {
                    int ordinal = Integer.parseInt(numbered.group(1));
                    return ordinal > 0 ? new ProjectionSendCommand(ordinal, "") : null;
                } catch (NumberFormatException ignored) { return null; }
            }
            java.util.regex.Matcher named = java.util.regex.Pattern.compile(
                    "^[/!]?" + java.util.regex.Pattern.quote(name) + "\\s+(.+?)\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (named.matches()) return new ProjectionSendCommand(null, named.group(1).trim());
        }
        return null;
    }

    static SendCommand parseMaterialsCommand(String text, List<String> names) {
        return parseIndexedMaterialCommand(text, names);
    }

    private static SendCommand parseIndexedMaterialCommand(String text, List<String> names) {
        if (text == null || names == null) return null;
        for (String name : names) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "^[/!]?" + java.util.regex.Pattern.quote(name) + "(?:\\s*(\\d+)(?=\\s|$))?(?:\\s+(.+?))?\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (!matcher.matches()) continue;
            try {
                int index = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
                return new SendCommand(index, matcher.group(2) == null ? "" : matcher.group(2).trim());
            }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static boolean parseProjectionListCommand(String text, List<String> names) {
        if (text == null || names == null) return false;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            if (java.util.regex.Pattern.compile("^[/!]?" + java.util.regex.Pattern.quote(name) + "\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text.trim()).matches()) return true;
        }
        return false;
    }

    private String commandName(String id, String fallback) {
        List<String> names = config.commandNames(id);
        return names.isEmpty() ? fallback : names.getFirst();
    }

    private RenderModels.Request searchRequest(String filename) {
        return new RenderModels.Request(2, "search-" + UUID.randomUUID(), filename,
                configuredViews(), null, Main.VERSION, null);
    }

    /** Removes adapter-specific leading mention markup before matching bot commands. */
    static String normalizeCommandText(String text) {
        String value = text == null ? "" : text.trim();
        value = value.replaceFirst("^(?:\\[CQ:reply,[^]]+\\]\\s*)+", "");
        value = value.replaceFirst("^(?:\\[CQ:at,[^]]+\\]\\s*)+", "");
        value = value.replaceFirst("^(?:<@!?[^>]+>\\s*)+", "");
        value = value.replaceFirst("^@[^\\s]+(?:\\s+|$)", "");
        return value.trim();
    }

    private String configuredCommandCandidate(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        for (String id : List.of("search", "sendProjection", "sendMaterials", "projectionList")) {
            for (String name : config.commandNames(id)) {
                if (name != null && !name.isBlank() && normalized.contains(name.toLowerCase(java.util.Locale.ROOT))) return id;
            }
        }
        return "";
    }

    private String profileName(String profileId) {
        AgentConfig.BotProfile profile = profile(profileId);
        return profile == null || profile.name == null ? "未知账号" : profile.name;
    }

    private static String deliveryFingerprint(BotMessage message) {
        StringBuilder value = new StringBuilder()
                .append(message.profileId()).append('|')
                .append(message.direct()).append('|')
                .append(nullToEmpty(message.groupId())).append('|')
                .append(nullToEmpty(message.userId())).append('|')
                .append(nullToEmpty(message.quotedMessageId())).append('|')
                .append(nullToEmpty(message.rawText()));
        for (String quoteId : quotedMessageIds(message)) value.append('|').append(quoteId);
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

    private void rememberRenderedMessages(BotMessage source, List<String> messageIds, Path projection) {
        if (source == null || messageIds == null || messageIds.isEmpty() || projection == null
                || !Files.isRegularFile(projection)) return;
        long now = System.currentTimeMillis();
        renderedMessages.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        ConversationKey conversation = ConversationKey.of(source);
        QuotedProjection value = new QuotedProjection(projection, projection.getFileName().toString(),
                now + QUOTED_RENDER_TTL_MILLIS);
        observedMessageIndices.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        for (String id : messageIds) {
            if (id == null || id.isBlank()) continue;
            renderedMessages.put(new RenderedMessageKey(conversation, id), value);
            ObservedMessageIndices observed = observedMessageIndices.get(new ObservedMessageKey(conversation, id));
            if (observed != null) for (String index : observed.indices()) {
                renderedMessages.put(new RenderedMessageKey(conversation, index), value);
            }
        }
        pruneRenderedMessages();
        persistRenderedMessages();
    }

    private void observeMessageIndices(BotMessage message) {
        if (message.messageId() == null || message.messageId().isBlank()
                || message.messageIndices() == null || message.messageIndices().isEmpty()) return;
        ConversationKey conversation = ConversationKey.of(message);
        long expiresAt = System.currentTimeMillis() + OBSERVED_INDEX_TTL_MILLIS;
        ObservedMessageKey observedKey = new ObservedMessageKey(conversation, message.messageId());
        ObservedMessageIndices observed = new ObservedMessageIndices(List.copyOf(message.messageIndices()), expiresAt);
        observedMessageIndices.put(observedKey, observed);

        QuotedProjection projection = renderedMessages.get(new RenderedMessageKey(conversation, message.messageId()));
        if (projection != null) {
            for (String index : observed.indices()) {
                if (index != null && !index.isBlank()) {
                    renderedMessages.put(new RenderedMessageKey(conversation, index), projection);
                }
            }
            pruneRenderedMessages();
            persistRenderedMessages();
        }
        observedMessageIndices.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= System.currentTimeMillis());
        while (observedMessageIndices.size() > MAX_QUOTED_RENDER_MESSAGES) {
            ObservedMessageKey oldest = observedMessageIndices.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) break;
            observedMessageIndices.remove(oldest);
        }
    }

    private void loadRenderedMessages() {
        if (!Files.isRegularFile(renderedMessageIndexPath)) return;
        int loaded = 0;
        Path cacheRoot = renderer.cacheDirectory().toAbsolutePath().normalize();
        try {
            JsonArray rows = Protocol.GSON.fromJson(Files.readString(renderedMessageIndexPath), JsonArray.class);
            if (rows == null) return;
            long now = System.currentTimeMillis();
            for (JsonElement element : rows) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                String messageId = jsonText(row, "消息ID");
                String projectionText = jsonText(row, "投影路径");
                long expiresAt = jsonLong(row, "过期时间");
                if (messageId.isBlank() || expiresAt <= now || projectionText.isBlank()) continue;
                Path projection = Path.of(projectionText).toAbsolutePath().normalize();
                if (!isCachedProjection(cacheRoot, projection) || !Files.isRegularFile(projection)) continue;
                ConversationKey conversation = new ConversationKey(jsonText(row, "账号ID"),
                        jsonText(row, "群ID"), jsonText(row, "用户ID"), jsonBoolean(row, "私聊"));
                String filename = jsonText(row, "文件名");
                if (filename.isBlank()) filename = projection.getFileName().toString();
                renderedMessages.put(new RenderedMessageKey(conversation, messageId),
                        new QuotedProjection(projection, filename, expiresAt));
                loaded++;
            }
            pruneRenderedMessages();
            if (loaded > 0) log.accept("已载入可引用渲染结果映射：" + renderedMessages.size() + " 条");
        } catch (Exception error) {
            log.accept("读取渲染结果引用索引失败，将从新渲染结果重新记录：" + message(error));
        }
    }

    private static boolean isCachedProjection(Path cacheRoot, Path projection) {
        if (!projection.startsWith(cacheRoot)) return false;
        Path relative = cacheRoot.relativize(projection);
        return relative.getNameCount() == 2 && relative.getName(0).toString().matches("[0-9a-fA-F]{64}")
                && relative.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic");
    }

    private void pruneRenderedMessages() {
        long now = System.currentTimeMillis();
        renderedMessages.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now
                || !Files.isRegularFile(entry.getValue().projection()));
        while (renderedMessages.size() > MAX_QUOTED_RENDER_MESSAGES) {
            Map.Entry<RenderedMessageKey, QuotedProjection> oldest = renderedMessages.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().expiresAt())).orElse(null);
            if (oldest == null) break;
            renderedMessages.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private synchronized void persistRenderedMessages() {
        JsonArray rows = new JsonArray();
        for (Map.Entry<RenderedMessageKey, QuotedProjection> entry : renderedMessages.entrySet()) {
            RenderedMessageKey key = entry.getKey();
            QuotedProjection projection = entry.getValue();
            if (!Files.isRegularFile(projection.projection())) continue;
            JsonObject row = new JsonObject();
            row.addProperty("账号ID", key.conversation().profileId());
            row.addProperty("群ID", key.conversation().groupId());
            row.addProperty("用户ID", key.conversation().userId());
            row.addProperty("私聊", key.conversation().direct());
            row.addProperty("消息ID", key.messageId());
            row.addProperty("投影路径", projection.projection().toAbsolutePath().normalize().toString());
            row.addProperty("文件名", projection.filename());
            row.addProperty("过期时间", projection.expiresAt());
            rows.add(row);
        }
        Path temporary = renderedMessageIndexPath.resolveSibling(renderedMessageIndexPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(renderedMessageIndexPath.getParent());
            Files.writeString(temporary, Protocol.GSON.toJson(rows), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, renderedMessageIndexPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, renderedMessageIndexPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            log.accept("保存渲染结果引用索引失败：" + message(error));
        }
    }

    private static String jsonText(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    private static long jsonLong(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    private static boolean jsonBoolean(JsonObject object, String key) {
        try { return object.has(key) && object.get(key).getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private QuotedProjection findQuotedProjection(BotMessage message) {
        List<String> messageIds = quotedMessageIds(message);
        if (messageIds.isEmpty()) return null;
        ConversationKey conversation = ConversationKey.of(message);
        renderedMessages.entrySet().removeIf(entry -> !isUsableQuotedProjection(entry.getValue()));

        Map<Path, QuotedProjection> exactMatches = new java.util.HashMap<>();
        for (String messageId : messageIds) {
            QuotedProjection projection = renderedMessages.get(new RenderedMessageKey(conversation, messageId));
            if (isUsableQuotedProjection(projection)) {
                exactMatches.put(projection.projection().toAbsolutePath().normalize(), projection);
            }
        }
        if (exactMatches.size() == 1) return exactMatches.values().iterator().next();
        if (exactMatches.size() > 1) {
            log.accept("引用消息包含多个已映射 ID，分别指向不同投影，已拒绝模糊匹配");
            return null;
        }

        Map<ConversationKey, Map<Path, QuotedProjection>> candidates = new java.util.HashMap<>();
        for (Map.Entry<RenderedMessageKey, QuotedProjection> entry : renderedMessages.entrySet()) {
            RenderedMessageKey candidateKey = entry.getKey();
            ConversationKey candidateConversation = candidateKey.conversation();
            if (!messageIds.contains(candidateKey.messageId())
                    || !conversation.profileId().equals(candidateConversation.profileId())
                    || conversation.direct() != candidateConversation.direct()) continue;
            Path path = entry.getValue().projection().toAbsolutePath().normalize();
            candidates.computeIfAbsent(candidateConversation, ignored -> new java.util.HashMap<>())
                    .putIfAbsent(path, entry.getValue());
        }
        if (candidates.size() == 1) {
            Map<Path, QuotedProjection> projections = candidates.values().iterator().next();
            if (projections.size() == 1) {
                QuotedProjection resolved = projections.values().iterator().next();
                for (String messageId : messageIds) {
                    if (renderedMessages.entrySet().stream().anyMatch(entry -> messageId.equals(entry.getKey().messageId())
                            && entry.getKey().conversation().profileId().equals(conversation.profileId())
                            && entry.getKey().conversation().direct() == conversation.direct()
                            && entry.getValue().projection().toAbsolutePath().normalize()
                                    .equals(resolved.projection().toAbsolutePath().normalize()))) {
                        renderedMessages.put(new RenderedMessageKey(conversation, messageId), resolved);
                    }
                }
                persistRenderedMessages();
                log.accept("引用消息会话标识不一致，已按账号和唯一消息索引恢复投影关联");
                return resolved;
            }
        }
        if (!candidates.isEmpty()) {
            log.accept("引用消息索引存在多个投影候选，已拒绝模糊匹配（候选会话=" + candidates.size() + "）");
        } else {
            long otherScopeMatches = renderedMessages.keySet().stream()
                    .filter(candidateKey -> messageIds.contains(candidateKey.messageId()))
                    .count();
            log.accept(otherScopeMatches == 0
                    ? "引用消息ID没有已记录的渲染结果别名，无法关联投影"
                    : "引用消息ID只在其他机器人账号或会话类型中有记录，已拒绝跨范围匹配");
        }
        return null;
    }

    private static List<String> quotedMessageIds(BotMessage message) {
        if (message == null) return List.of();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (message.quotedMessageId() != null && !message.quotedMessageId().isBlank()) {
            ids.add(message.quotedMessageId().trim());
        }
        if (message.quotedMessageAliases() != null) for (String id : message.quotedMessageAliases()) {
            if (id != null && !id.isBlank()) ids.add(id.trim());
        }
        return List.copyOf(ids);
    }

    private static boolean isUsableQuotedProjection(QuotedProjection projection) {
        return projection != null && projection.expiresAt() > System.currentTimeMillis()
                && Files.isRegularFile(projection.projection());
    }

    private Path cachedProjection(byte[] schematic, String requestedFilename) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(schematic));
            Path cacheRoot = renderer.cacheDirectory().toAbsolutePath().normalize();
            Path directory = cacheRoot.resolve(hash).normalize();
            if (!directory.startsWith(cacheRoot) || !Files.isDirectory(directory)) return null;
            Path requested = directory.resolve(CacheStore.safeProjectionFilename(requestedFilename)).normalize();
            if (requested.startsWith(directory) && Files.isRegularFile(requested)) return requested;
            try (var files = Files.list(directory)) {
                return files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic"))
                        .findFirst().orElse(null);
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            log.accept("定位渲染缓存投影文件失败：" + message(error));
            return null;
        }
    }

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
            Path projection = cachedProjection(schematic, filename);
            int ordinal = ordinalOf(projection);
            String metadata = metadata(filename, schematic, ordinal);
            Path infoImage = createMetadataImage(metadata, result);
            try {
                List<String> resultIds = adapter.sendResultWithActionsTracked(message, result, metadata, infoImage,
                        "投影编号：" + ordinal, projectionActions(ordinal));
                List<String> allIds = new ArrayList<>(resultIds);
                if (message.messageId() != null) allIds.add(message.messageId());
                allIds.addAll(message.messageIndices());
                rememberRenderedMessages(message, allIds, projection);
            }
            finally { deleteTemporary(infoImage, "投影信息图片"); }
            log.accept("机器人［" + profile.name + "］已处理投影：" + filename + (result.cacheHit() ? "（缓存命中）" : ""));
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            updateDownloadProgress(attachmentName(message), 0, 0, false, message(cause));
            log.accept("机器人［" + profile.name + "］投影处理失败：" + message(cause));
            try { adapter.sendText(message, "投影渲染失败，请检查渲染器配置或导出诊断文件。\n" + message(cause)); }
            catch (Throwable sendError) { log.accept("机器人错误消息发送失败：" + message(sendError)); }
        }
    }

    private byte[] download(BotAttachment attachment, long limit) throws Exception {
        if (attachment.url() == null || attachment.url().isBlank()) throw new IOException("附件没有可下载地址");
        String value = attachment.url();
        // QQ 官方附件通常是带查询参数的 HTTPS 直链，不能先交给 Windows Path 解析。
        if (isHttpUrl(value)) return downloadHttpWithProgress(value, limit, Math.max(0, attachment.size()));
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
        return downloadHttpWithProgress(value, limit, 0, null);
    }

    private byte[] downloadHttpWithProgress(String value, long limit, long expectedSize) throws IOException, InterruptedException {
        return downloadHttpWithProgress(value, limit, expectedSize, this::updateDownloadProgress);
    }

    private static byte[] downloadHttpWithProgress(String value, long limit, long expectedSize,
                                                   DownloadProgressConsumer progress) throws IOException, InterruptedException {
        String file = attachmentFileName(value);
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (progress != null) progress.update(file, 0, expectedSize, true, "");
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(value))
                        .timeout(Duration.ofMinutes(3)).GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    throw new IOException("附件下载失败 HTTP " + response.statusCode());
                }
                long total = response.headers().firstValueAsLong("Content-Length").orElse(expectedSize);
                if (total > limit) {
                    response.body().close();
                    throw new IOException("文件超过 " + (limit / 1024) + " KB 限制");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Math.max(total, 0), 1024 * 1024));
                long completed = 0;
                try (InputStream input = response.body()) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        if (read == 0) continue;
                        completed += read;
                        if (completed > limit) throw new IOException("文件超过 " + (limit / 1024) + " KB 限制");
                        output.write(buffer, 0, read);
                        if (progress != null) progress.update(file, completed, total, true, "");
                    }
                }
                byte[] result = output.toByteArray();
                if (progress != null) progress.update(file, result.length, total > 0 ? total : result.length, false, "");
                return result;
            } catch (HttpTimeoutException | ConnectException error) {
                last = error;
                if (progress != null) progress.update(file, 0, expectedSize, attempt < 3, attempt < 3 ? "连接超时，正在重试" : error.getMessage());
                if (attempt < 3) Thread.sleep(attempt * 1000L);
            } catch (IOException error) {
                throw error;
            }
        }
        throw new IOException("附件下载失败（已重试 3 次）：" + (last == null ? "连接超时" : last.getMessage()), last);
    }

    private static String attachmentFileName(String value) {
        try {
            String path = URI.create(value).getPath();
            if (path != null && !path.isBlank()) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < path.length()) return path.substring(slash + 1);
            }
        } catch (RuntimeException ignored) { }
        return "投影文件";
    }

    @FunctionalInterface
    private interface DownloadProgressConsumer {
        void update(String file, long downloaded, long total, boolean active, String error);
    }

    record DownloadProgress(String file, long downloadedBytes, long totalBytes, boolean active, String error, long timestamp) {
        static DownloadProgress idle() { return new DownloadProgress("", 0, 0, false, "", System.currentTimeMillis()); }
        double fraction() { return totalBytes > 0 ? Math.max(0, Math.min(1, downloadedBytes / (double) totalBytes)) : 0; }
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

    private String metadata(String filename, byte[] schematic, int ordinal) {
        String description = LitematicMetadata.format(schematic, filename, config);
        if (ordinal <= 0) return description;
        return description.isBlank() ? "投影编号：" + ordinal : description + "\n投影编号：" + ordinal;
    }

    private Path createMetadataImage(String metadata, RenderModels.Result result) throws IOException {
        if (!"image".equalsIgnoreCase(config.metadataFormat)) return null;
        return ProjectionInfoCardRenderer.createComposite(metadata,
                result == null ? List.of() : result.images(), root.resolve("projection-info"));
    }

    private void deleteTemporary(Path path, String description) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException error) { log.accept(description + "临时文件清理失败：" + message(error)); }
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

    record SearchCommand(String keyword) {}
    record ProjectionSendCommand(Integer ordinal, String exactName) {}
    record SendCommand(int index, String keyword) {}
    record ViewCommand(String action, int ordinal, String viewName) {}
    private record ConversationKey(String profileId, String groupId, String userId, boolean direct) {
        static ConversationKey of(BotMessage message) {
            return new ConversationKey(nullToEmpty(message.profileId()),
                    message.direct() ? "" : nullToEmpty(message.groupId()),
                    message.direct() ? nullToEmpty(message.userId()) : "", message.direct());
        }
    }
    private record RenderedMessageKey(ConversationKey conversation, String messageId) {}
    private record ObservedMessageKey(ConversationKey conversation, String messageId) {}
    private record ObservedMessageIndices(List<String> indices, long expiresAt) {}
    private record QuotedProjection(Path projection, String filename, long expiresAt) {}

    private void closeAdapters() {
        for (BotAdapter adapter : adapters.values()) try { adapter.close(); } catch (Throwable error) { log.accept("关闭机器人连接失败：" + message(error)); }
        adapters.clear();
    }

    private void startAdapter(AgentConfig.BotProfile profile) {
        BotAdapter adapter = "onebot".equals(profile.type)
                ? new OneBotAdapter(profile, this::accept, log)
                : new OfficialQqAdapter(profile, config, config.cloudMergeLayout, this::accept,
                        (groupId, joined) -> {
                            if (groupId == null || groupId.isBlank()) return;
                            if (joined) announcementGroups.add(profile.id, groupId);
                            else announcementGroups.remove(profile.id, groupId);
                        }, log);
        adapters.put(profile.id, adapter);
        adapter.start();
    }

    @Override public synchronized void close() {
        closed = true;
        announcementTimer.shutdownNow();
        renderedMessages.clear();
        closeAdapters();
    }
}
