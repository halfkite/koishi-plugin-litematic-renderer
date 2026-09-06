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
    /** 缓存总容量上限（字节），超限自动清理最旧文件；默认 10GB。 */
    public long cacheMaxBytes = 10L * 1024 * 1024 * 1024;
    /** 渲染分辨率（全局默认宽/高），主界面可改。 */
    public int renderWidth = 2048;
    public int renderHeight = 2048;
    /** 持久化的视角列表。 */
    public List<ViewEntry> views = new ArrayList<>();
    /** 渲染运行时空闲多久后自动关闭（毫秒）；0 = 不自动关闭。 */
    public int renderIdleStopMillis = 300_000;
    /** 并行渲染数：同时运行的 Minecraft 渲染客户端数量（1-4，默认 1）。 */
    public int maxConcurrentRenders = 1;
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
    /** 投影介绍格式：full=完整标签版，compact=精简版（模式2）。 */
    public String metadataFormat = "full";
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

    public static final class BotProfile {
        public String id = "bot-" + UUID.randomUUID();
        public String name = "机器人账号";
        public String type = "official";
        public boolean enabled = false;

        // QQ 官方机器人
        public String appId = "";
        public String appSecret = "";
        public boolean sandbox = false;
        public long intents = (1L << 30) | (1L << 25) | (1L << 26);
        /** 群消息触发方式：received=群文件自动识别，mentionOnly=仅@，any=两者都允许。 */
        public String groupMessageMode = "received";

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
            if (groupWhitelist == null) groupWhitelist = new ArrayList<>();
            if (groupBlacklist == null) groupBlacklist = new ArrayList<>();
            if (maxFileSizeKb < 0) maxFileSizeKb = 0;
            if (privateMaxFileSizeKb < 0) privateMaxFileSizeKb = 0;
        }
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
        if (config.resourcePacks == null) config.resourcePacks = new ArrayList<>();
        if (config.botProfiles == null) config.botProfiles = new ArrayList<>();
        for (BotProfile profile : config.botProfiles) if (profile != null) profile.normalize();
        if (config.maxConcurrentRenders < 1) config.maxConcurrentRenders = 1;
        if (config.maxConcurrentRenders > 4) config.maxConcurrentRenders = 4;
        if (config.memoryRestartThresholdBytes < 0) config.memoryRestartThresholdBytes = 0;
        if (config.maxQueuedRequestBytes < 0) config.maxQueuedRequestBytes = 0;
        if (config.nightVisionLevel < 1) config.nightVisionLevel = 1;
        if (config.nightVisionLevel > 15) config.nightVisionLevel = 15;
        if (!"vertical".equalsIgnoreCase(config.cloudMergeLayout)) config.cloudMergeLayout = "horizontal";
        if (config.webBindHost == null || config.webBindHost.isBlank()) config.webBindHost = "0.0.0.0";
        if (config.webPort < 1 || config.webPort > 65535) config.webPort = 2618;
        if (config.webUsername == null || config.webUsername.isBlank()) config.webUsername = "admin";
        if (config.webSessionTimeoutMillis < 5 * 60 * 1000L) config.webSessionTimeoutMillis = 12L * 60 * 60 * 1000;
        if (config.maxFileSizeKb < 1) config.maxFileSizeKb = 1024;
        if (config.privateMaxFileSizeKb < 1) config.privateMaxFileSizeKb = 1024;
        if (!"compact".equalsIgnoreCase(config.metadataFormat)) config.metadataFormat = "full";
        if (raw == null || !raw.has("webPassword") || config.webPassword == null || config.webPassword.isBlank()) {
            config.webPassword = randomPassword();
            config.generatedWebPassword = true;
            config.webPasswordChangeNotice = true;
            config.save(file);
            writeCredentialHint(file, config.webUsername, config.webPassword);
        }
        return config;
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
