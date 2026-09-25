package dev.qqbot.gpuagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AgentConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String agentId = "windows-gpu-1";
    public String sharedSecret = "";
    public String cloudWebSocketUrl = "";
    public boolean cloudEnabled = false;
    public String listenHost = "127.0.0.1";
    public int listenPort = 39080;
    public int renderTimeoutMillis = 240_000;
    /** 渲染缓存目录（空 = 数据目录下 cache）；存放投影、渲染图与记录文件。 */
    public String cacheDirectory = "";
    /** 是否在缓存目录保存投影文件本身。 */
    public boolean cacheKeepProjections = true;
    /** 兼容旧配置的总图片缓存容量；保存时同步为近期与历史容量之和。 */
    public long cacheMaxBytes = 10L * 1024 * 1024 * 1024;
    /** 投影保存时间在一年内的完整渲染图和搜索缩略图容量上限。默认 8GB。 */
    public long cacheRecentImageMaxBytes = 8L * 1024 * 1024 * 1024;
    /** 投影保存时间超过一年的完整渲染图和搜索缩略图容量上限。默认 2GB。 */
    public long cacheHistoricalImageMaxBytes = 2L * 1024 * 1024 * 1024;
    /** 导出缓存投影时是否保留每个投影的 SHA-256 哈希目录；默认压平目录。 */
    public boolean exportKeepHashDirectories = false;
    /** 渲染分辨率（全局默认宽/高），主界面可改。 */
    public int renderWidth = 2048;
    public int renderHeight = 2048;
    /** 持久化的视角列表。 */
    public List<ViewEntry> views = new ArrayList<>();
    /** 渲染运行时空闲多久后自动关闭（毫秒）；0 = 不自动关闭。 */
    public int renderIdleStopMillis = 300_000;
    /** 并行渲染数：同时运行的 Minecraft 渲染客户端数量（1-4，默认 1）。 */
    public int maxConcurrentRenders = 1;
    /** 可选的本地 Minecraft 26.3 客户端 JAR；为空时从 Mojang 官方地址下载。 */
    public String localMinecraftClientPath = "";
    /** 是否给本地 GPU 预览启用可配置的满亮度光照；新配置默认关闭。 */
    public boolean nightVisionEnabled = false;
    /** 夜视光照等级（1-15，15 = Minecraft 满亮度）。 */
    public int nightVisionLevel = 15;
    /** 云端多视角结果的拼接方向。 */
    public String cloudMergeLayout = "horizontal";
    /** 本地批量渲染是否额外生成一张多视角拼接图；默认关闭以保持原有输出。 */
    public boolean localMergeEnabled = false;
    /** 发送投影信息时显示投影名称；默认开启。 */
    public boolean showMetadataProjectionName = true;
    /** 发送投影信息时显示保存者游戏 ID；默认开启。 */
    public boolean showMetadataAuthor = true;
    /** 发送投影信息时显示创建时间；默认开启。 */
    public boolean showMetadataCreatedAt = true;
    /** 发送投影信息时显示方块数和体积；默认开启。 */
    public boolean showMetadataBlockStats = true;
    /** 发送投影信息时显示尺寸；默认开启。 */
    public boolean showMetadataSize = true;
    /** 发送投影信息时显示 Litematic 版本；默认开启。 */
    public boolean showMetadataLitematicVersion = true;
    /** 发送投影信息时显示游戏版本和数据版本；默认开启。 */
    public boolean showMetadataGameVersion = true;
    /** 投影介绍格式：full=完整标签版，compact=精简版，image=与渲染图等宽的图片版。 */
    public String metadataFormat = "full";
    /** 搜索投影默认返回的结果数量；近期保存的投影优先，其余按渲染次数排序。 */
    public int projectionSearchResultLimit = 15;
    /** 内存重启阈值（字节）：工具+渲染端总内存占用超过该值时，手头任务完成后重启 Minecraft 运行时；0 = 关闭。默认 4GB。 */
    public long memoryRestartThresholdBytes = 4L * 1024 * 1024 * 1024;
    /** 队列中保留的投影数据总量上限；超过后拒绝新任务，避免大量排队文件导致 Agent 爆内存。0 = 不限制。 */
    public long maxQueuedRequestBytes = 512L * 1024 * 1024;
    public long maxRequestBytes = 128L * 1024 * 1024;
    public int maxClockSkewSeconds = 90;
    public boolean startWithWindows = false;
    public boolean minimizeToTray = true;
    /** Linux/Web 管理后台。默认监听所有网卡，必须通过登录密码保护。 */
    public boolean webEnabled = false;
    public String webBindHost = "0.0.0.0";
    public int webPort = 2618;
    public String webUsername = "admin";
    /** 按用户要求明文保存；接口、日志和诊断导出均不会返回该字段。 */
    public String webPassword = "";
    public long webSessionTimeoutMillis = 12L * 60 * 60 * 1000;
    /** 仅用于页面提示，不强制修改。 */
    public boolean webPasswordChangeNotice = true;
    /** Agent 直接接收 QQ/OneBot 消息时的全局文件限制。 */
    public long maxFileSizeKb = 1024;
    public long privateMaxFileSizeKb = 1024;
    public String javaPath = "";
    public String outputDirectory = "";
    public List<String> recentProjectionPaths = new ArrayList<>();
    public List<String> recentOutputDirectories = new ArrayList<>();
    public List<HistoryEntry> history = new ArrayList<>();
    public List<ResourcePackEntry> resourcePacks = new ArrayList<>();
    /** 多账号列表；每个账号只能是 official 或 onebot。 */
    public List<BotProfile> botProfiles = new ArrayList<>();
    /** 旧渲染消息索引或其所在目录；空值使用当前数据目录。 */
    public String announcementLegacyIndexPath = "";
    /** 机器人文字指令配置；文件自动识别不使用此列表。 */
    public List<CommandDefinition> commands = defaultCommandDefinitions();
    /** 是否自动识别机器人消息中的投影附件；文字指令仍可独立使用。 */
    public boolean automaticRenderingEnabled = true;
    /** 已完成的指令名称迁移版本。 */
    public int commandAliasMigrationVersion = 0;

    public static List<CommandDefinition> defaultCommandDefinitions() {
        List<CommandDefinition> definitions = new ArrayList<>(List.of(
                new CommandDefinition("search", "搜索投影"),
                new CommandDefinition("sendProjection", "发送投影"),
                new CommandDefinition("sendMaterials", "导出材料"),
                new CommandDefinition("projectionList", "投影列表"),
                new CommandDefinition("help", "帮助"),
                new CommandDefinition("introduction", "介绍投影BOT"),
                new CommandDefinition("moreViews", "更多视图"),
                new CommandDefinition("projectionView", "投影视图")));
        CommandDefinition mapView = new CommandDefinition("mapView", "地图视图");
        mapView.aliases.add(new CommandAlias("地图画模式", true));
        definitions.add(mapView);
        return definitions;
    }

    /** 当前可识别的一个主指令及其别名。defaultName 用于“重置原名称”。 */
    public static final class CommandDefinition {
        public String id = "";
        public String defaultName = "";
        public String name = "";
        public boolean enabled = true;
        public List<CommandAlias> aliases = new ArrayList<>();

        public CommandDefinition() {}

        public CommandDefinition(String id, String defaultName) {
            this.id = id;
            this.defaultName = defaultName;
            this.name = defaultName;
        }

        void normalize() {
            if (id == null) id = "";
            if (defaultName == null || defaultName.isBlank()) defaultName = name == null ? "" : name.trim();
            if (name == null || name.isBlank()) name = defaultName;
            name = name.trim();
            if (aliases == null) aliases = new ArrayList<>();
            List<CommandAlias> clean = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (CommandAlias alias : aliases) {
                if (alias == null || alias.name == null || alias.name.isBlank()) continue;
                alias.name = alias.name.trim();
                String key = alias.name.toLowerCase(Locale.ROOT);
                if (alias.name.equalsIgnoreCase(name) || !seen.add(key)) continue;
                clean.add(alias);
            }
            aliases = clean;
        }
    }

    public static final class CommandAlias {
        public String name = "";
        public boolean enabled = true;

        public CommandAlias() {}

        public CommandAlias(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }
    }

    /** 补齐内置指令，保留未知 ID 以便未来版本继续兼容。 */
    public void normalizeCommandDefinitions() {
        List<CommandDefinition> configured = commands == null ? new ArrayList<>() : commands;
        List<CommandDefinition> normalized = new ArrayList<>();
        for (CommandDefinition builtin : defaultCommandDefinitions()) {
            CommandDefinition selected = null;
            for (CommandDefinition candidate : configured) {
                if (candidate != null && builtin.id.equals(candidate.id)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) selected = builtin;
            selected.defaultName = builtin.defaultName;
            if ("sendMaterials".equals(selected.id)) {
                if ("发送材料".equals(selected.name) || "材料".equals(selected.name)) selected.name = builtin.name;
                String primaryName = selected.name;
                if (selected.aliases != null) selected.aliases.removeIf(alias -> alias != null
                        && ("发送材料".equals(alias.name) || "材料".equals(alias.name)
                        || "导出材料".equals(alias.name) && "导出材料".equals(primaryName)));
            }
            selected.normalize();
            normalized.add(selected);
        }
        for (CommandDefinition candidate : configured) {
            if (candidate == null || candidate.id == null || candidate.id.isBlank()) continue;
            if ("renderSearch".equals(candidate.id)) continue;
            boolean known = normalized.stream().anyMatch(item -> item.id.equals(candidate.id));
            if (!known) {
                candidate.normalize();
                normalized.add(candidate);
            }
        }
        commands = normalized;
    }

    public List<String> commandNames(String id) {
        normalizeCommandDefinitions();
        for (CommandDefinition definition : commands) {
            if (!definition.id.equals(id)) continue;
            if (!definition.enabled) return List.of();
            List<String> names = new ArrayList<>();
            if (definition.name != null && !definition.name.isBlank()) names.add(definition.name);
            for (CommandAlias alias : definition.aliases) {
                if (alias != null && alias.enabled && alias.name != null && !alias.name.isBlank()) names.add(alias.name);
            }
            return List.copyOf(names);
        }
        return List.of();
    }

    public static final class BotProfile {
        public String id = "bot-" + UUID.randomUUID();
        public String name = "机器人账号";
        public String type = "official";
        public boolean enabled = false;
        /** Agent 启动时是否自动建立该账号连接；关闭后仍可由界面或 Web 手动重载。 */
        public boolean autoConnect = true;

        // QQ 官方机器人
        public String appId = "";
        public String appSecret = "";
        public boolean sandbox = false;
        public long intents = (1L << 30) | (1L << 25) | (1L << 26);
        /** 群消息触发方式：received=群文件自动识别，mentionOnly=仅@，any=两者都允许。 */
        public String groupMessageMode = "received";
        /** 是否要求群里的文字指令带 @；关闭只影响指令，不影响文件识别。 */
        public boolean commandRequireMention = true;

        // OneBot v11 / NekoBot
        public String transport = "forward";
        public String webSocketUrl = "";
        public String accessToken = "";
        public String listenHost = "0.0.0.0";
        public int listenPort = 39200;
        public String path = "/onebot/v11/ws";

        // 账号级发送与权限
        public boolean allowPrivateRender = false;
        public boolean groupWhitelistEnabled = false;
        public List<String> groupWhitelist = new ArrayList<>();
        public boolean groupBlacklistEnabled = false;
        public List<String> groupBlacklist = new ArrayList<>();
        public String sendMode = "combined";
        public boolean replyAndMention = true;
        public boolean showViewTitles = false;
        public boolean successNotice = true;
        /** 渲染图发送方式：horizontal/vertical 为拼接，separate 为逐张发送。 */
        public String imageSendLayout = "horizontal";
        /** 账号级群文件上限（KB）；0 = 跟随全局 maxFileSizeKb。 */
        public long maxFileSizeKb = 0;
        /** 账号级私聊文件上限（KB）；0 = 跟随全局 privateMaxFileSizeKb。 */
        public long privateMaxFileSizeKb = 0;

        public void normalize() {
            if (id == null || id.isBlank()) id = "bot-" + UUID.randomUUID();
            if (name == null || name.isBlank()) name = id;
            if (!"onebot".equalsIgnoreCase(type)) type = "official";
            type = type.toLowerCase(java.util.Locale.ROOT);
            if (!"reverse".equalsIgnoreCase(transport)) transport = "forward";
            transport = transport.toLowerCase(java.util.Locale.ROOT);
            if (webSocketUrl == null) webSocketUrl = "";
            if (path == null || path.isBlank()) path = "/onebot/v11/ws";
            if (!path.startsWith("/")) path = "/" + path;
            if (listenPort < 1 || listenPort > 65535) listenPort = 39200;
            if (groupMessageMode == null || (!"mentionOnly".equalsIgnoreCase(groupMessageMode)
                    && !"received".equalsIgnoreCase(groupMessageMode)
                    && !"any".equalsIgnoreCase(groupMessageMode))) groupMessageMode = "received";
            groupMessageMode = groupMessageMode.toLowerCase(java.util.Locale.ROOT);
            if (!"forward".equalsIgnoreCase(sendMode)) sendMode = "combined";
            imageSendLayout = normalizeImageSendLayout(imageSendLayout);
            if (groupWhitelist == null) groupWhitelist = new ArrayList<>();
            if (groupBlacklist == null) groupBlacklist = new ArrayList<>();
            if (maxFileSizeKb < 0) maxFileSizeKb = 0;
            if (privateMaxFileSizeKb < 0) privateMaxFileSizeKb = 0;
        }
    }

    static String normalizeImageSendLayout(String value) {
        if ("vertical".equalsIgnoreCase(value)) return "vertical";
        if ("separate".equalsIgnoreCase(value)
                || "horizontal-separate".equalsIgnoreCase(value)
                || "vertical-separate".equalsIgnoreCase(value)) return "separate";
        return "horizontal";
    }

    static boolean separateImageSend(String value) {
        return "separate".equals(normalizeImageSendLayout(value));
    }

    static boolean verticalImageSend(String value) {
        return normalizeImageSendLayout(value).startsWith("vertical");
    }

    /** 任一端要求逐张发送时，禁止另一端提前生成 merged.png。 */
    static String effectiveImageSendLayout(String localLayout, String requestedLayout) {
        String local = normalizeImageSendLayout(localLayout);
        if (separateImageSend(local)) return local;
        if (requestedLayout != null && separateImageSend(requestedLayout)) return normalizeImageSendLayout(requestedLayout);
        return requestedLayout == null ? local : normalizeImageSendLayout(requestedLayout);
    }

    /**
     * 官方 QQ 直发也遵循全局发送模式。
     * 全局逐张发送具有最高优先级，避免账号旧配置中的默认 horizontal 把图片重新合并。
     */
    static String effectiveOfficialImageSendLayout(String globalLayout, String profileLayout) {
        String global = normalizeImageSendLayout(globalLayout);
        String profile = normalizeImageSendLayout(profileLayout);
        if (separateImageSend(global)) return global;
        if (separateImageSend(profile)) return profile;
        return global;
    }

    public record HistoryEntry(String time, String file, int views, long elapsed, String status, String location) {}
    public record ViewEntry(String id, String name, double yaw, double pitch, double zoom, int width, int height,
                            int supersampling, String background, boolean transparentBackground, Boolean autoFill,
                            Double brightness) {
        public ViewEntry(String id, String name, double yaw, double pitch, double zoom, int width, int height,
                         int supersampling, String background, boolean transparentBackground) {
            this(id, name, yaw, pitch, zoom, width, height, supersampling, background, transparentBackground,
                    true, RenderModels.brightnessBase(pitch));
        }

        public ViewEntry(String id, String name, double yaw, double pitch, double zoom, int width, int height,
                         int supersampling, String background, boolean transparentBackground, Boolean autoFill) {
            this(id, name, yaw, pitch, zoom, width, height, supersampling, background, transparentBackground,
                    autoFill, RenderModels.brightnessBase(pitch));
        }

        /** 旧版 agent.json 没有该字段，缺失时按新功能默认开启。 */
        public boolean autoFillEnabled() { return this.autoFill == null || this.autoFill; }

        /** 旧版 agent.json 没有亮度字段；底视图按原有 150% 实际亮度作为 100% 基准。 */
        public double brightnessFactor() {
            return clampBrightness(this.brightness == null ? RenderModels.brightnessBase(this.pitch) : this.brightness);
        }

        static double clampBrightness(double value) {
            return Math.max(0.25, Math.min(3.0, Double.isFinite(value) ? value : 1.0));
        }
    }

    public static AgentConfig load(Path file) throws IOException {
        boolean newFile = !Files.exists(file);
        String json = newFile ? "{}" : Files.readString(file);
        JsonObject raw = GSON.fromJson(json, JsonObject.class);
        var config = GSON.fromJson(json, AgentConfig.class);
        if (config == null) config = new AgentConfig();
        if (raw == null || !raw.has("nightVisionEnabled")) config.nightVisionEnabled = false;
        if (raw == null || !raw.has("nightVisionLevel")) config.nightVisionLevel = 15;
        if (raw == null || !raw.has("maxQueuedRequestBytes")) config.maxQueuedRequestBytes = 512L * 1024 * 1024;
        if (raw == null || !raw.has("cacheRecentImageMaxBytes") || !raw.has("cacheHistoricalImageMaxBytes")) {
            long legacyMaximum = raw != null && raw.has("cacheMaxBytes")
                    ? Math.max(0, config.cacheMaxBytes) : 10L * 1024 * 1024 * 1024;
            config.cacheRecentImageMaxBytes = legacyMaximum * 4 / 5;
            config.cacheHistoricalImageMaxBytes = legacyMaximum - config.cacheRecentImageMaxBytes;
        }
        config.cacheRecentImageMaxBytes = Math.max(0, config.cacheRecentImageMaxBytes);
        config.cacheHistoricalImageMaxBytes = Math.max(0, config.cacheHistoricalImageMaxBytes);
        config.cacheMaxBytes = config.cacheRecentImageMaxBytes + config.cacheHistoricalImageMaxBytes;
        if (config.resourcePacks == null) config.resourcePacks = new ArrayList<>();
        if (config.botProfiles == null) config.botProfiles = new ArrayList<>();
        for (BotProfile profile : config.botProfiles) if (profile != null) profile.normalize();
        boolean commandMigration = config.commandAliasMigrationVersion < 4;
        if (config.commandAliasMigrationVersion < 1) {
            config.commandAliasMigrationVersion = 1;
        }
        if (config.commandAliasMigrationVersion < 2) {
            config.renameLegacySearchCommand();
            config.commandAliasMigrationVersion = 2;
        }
        if (config.commandAliasMigrationVersion < 3) config.commandAliasMigrationVersion = 3;
        if (config.commandAliasMigrationVersion < 4) config.commandAliasMigrationVersion = 4;
        config.normalizeCommandDefinitions();
        if (raw != null && raw.has("mergeRenderImages") && !raw.get("mergeRenderImages").getAsBoolean()
                && !separateImageSend(config.cloudMergeLayout)) {
            config.cloudMergeLayout = "separate";
        }
        if (raw != null && raw.has("botProfiles") && raw.get("botProfiles").isJsonArray()) {
            var rawProfiles = raw.getAsJsonArray("botProfiles");
            for (int index = 0; index < config.botProfiles.size() && index < rawProfiles.size(); index++) {
                if (config.botProfiles.get(index) != null && rawProfiles.get(index).isJsonObject()
                        && rawProfiles.get(index).getAsJsonObject().has("mergeRenderImages")
                        && !rawProfiles.get(index).getAsJsonObject().get("mergeRenderImages").getAsBoolean()
                        && !rawProfiles.get(index).getAsJsonObject().has("imageSendLayout")) {
                    config.botProfiles.get(index).imageSendLayout = "separate";
                }
            }
        }
        if (config.maxConcurrentRenders < 1) config.maxConcurrentRenders = 1;
        if (config.maxConcurrentRenders > 4) config.maxConcurrentRenders = 4;
        if (config.memoryRestartThresholdBytes < 0) config.memoryRestartThresholdBytes = 0;
        if (config.maxQueuedRequestBytes < 0) config.maxQueuedRequestBytes = 0;
        if (config.nightVisionLevel < 1) config.nightVisionLevel = 1;
        if (config.nightVisionLevel > 15) config.nightVisionLevel = 15;
        config.cloudMergeLayout = normalizeImageSendLayout(config.cloudMergeLayout);
        if (config.webBindHost == null || config.webBindHost.isBlank()) config.webBindHost = "0.0.0.0";
        if (config.webPort < 1 || config.webPort > 65535) config.webPort = 2618;
        if (config.webUsername == null || config.webUsername.isBlank()) config.webUsername = "admin";
        if (config.webSessionTimeoutMillis < 5 * 60 * 1000L) config.webSessionTimeoutMillis = 12L * 60 * 60 * 1000;
        if (config.maxFileSizeKb < 1) config.maxFileSizeKb = 1024;
        if (config.privateMaxFileSizeKb < 1) config.privateMaxFileSizeKb = 1024;
        if (!"compact".equalsIgnoreCase(config.metadataFormat)
                && !"image".equalsIgnoreCase(config.metadataFormat)) config.metadataFormat = "full";
        if (config.projectionSearchResultLimit < 1) config.projectionSearchResultLimit = 15;
        if (config.projectionSearchResultLimit > 100) config.projectionSearchResultLimit = 100;
        if (raw == null || !raw.has("webPassword") || config.webPassword == null || config.webPassword.isBlank()) {
            config.webPassword = randomPassword();
            config.generatedWebPassword = true;
            config.webPasswordChangeNotice = true;
            config.save(file);
            writeCredentialHint(file, config.webUsername, config.webPassword);
        } else if (commandMigration) config.save(file);
        return config;
    }

    private void renameLegacySearchCommand() {
        normalizeCommandDefinitions();
        for (CommandDefinition definition : commands) {
            if ("search".equals(definition.id) && "投影搜索".equals(definition.name)) {
                definition.name = "搜索投影";
                return;
            }
        }
    }

    private transient boolean generatedWebPassword;

    public boolean generatedWebPassword() { return generatedWebPassword; }

    /** 允许本机 GUI 在不经过 Web 登录的情况下修改管理后台密码。 */
    public void changeWebPassword(Path file, String first, String second) throws IOException {
        if (first == null || first.isBlank() || !first.equals(second)) {
            throw new IllegalArgumentException("两次新 Web 密码必须一致且不能为空");
        }
        webPassword = first;
        webPasswordChangeNotice = false;
        save(file);
        writeCredentialHint(file, webUsername, webPassword);
    }

    static void writeCredentialHintForCurrentUser(Path file, String username, String password) throws IOException {
        writeCredentialHint(file, username, password);
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) value.append(random.nextInt(10));
        return value.toString();
    }

    private static void writeCredentialHint(Path configFile, String username, String password) {
        try {
            Path hint = configFile.resolveSibling("web-credentials.txt");
            Files.writeString(hint, "用户名：" + username + "\n密码：" + password + "\n首次登录后建议修改密码。\n");
        } catch (IOException ignored) { }
    }

    /** 在不替换共享配置对象的前提下应用网页保存的配置。 */
    public void applyFrom(AgentConfig source) {
        try {
            for (var field : AgentConfig.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.set(this, field.get(source));
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("应用 Agent 配置失败", error);
        }
    }

    public void save(Path file) throws IOException {
        normalizeCommandDefinitions();
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(this));
        try {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record ResourcePackEntry(String path, boolean enabled) {}
}
