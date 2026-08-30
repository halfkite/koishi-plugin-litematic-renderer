package dev.qqbot.gpuagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /** 内存重启阈值（字节）：工具+渲染端总内存占用超过该值时，手头任务完成后自动重启程序；0 = 关闭。默认 4GB。 */
    public long memoryRestartThresholdBytes = 4L * 1024 * 1024 * 1024;
    public long maxRequestBytes = 128L * 1024 * 1024;
    public int maxClockSkewSeconds = 90;
    public boolean startWithWindows = false;
    public boolean minimizeToTray = true;
    public String javaPath = "";
    public String outputDirectory = "";
    public List<String> recentProjectionPaths = new ArrayList<>();
    public List<String> recentOutputDirectories = new ArrayList<>();
    public List<HistoryEntry> history = new ArrayList<>();
    public List<ResourcePackEntry> resourcePacks = new ArrayList<>();

    public record HistoryEntry(String time, String file, int views, long elapsed, String status, String location) {}
    public record ViewEntry(String id, String name, double yaw, double pitch, double zoom, int width, int height, int supersampling, String background, boolean transparentBackground) {}

    public static AgentConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            var config = new AgentConfig();
            config.save(file);
            return config;
        }
        var config = GSON.fromJson(Files.readString(file), AgentConfig.class);
        if (config == null) config = new AgentConfig();
        if (config.resourcePacks == null) config.resourcePacks = new ArrayList<>();
        if (config.maxConcurrentRenders < 1) config.maxConcurrentRenders = 1;
        if (config.maxConcurrentRenders > 4) config.maxConcurrentRenders = 4;
        if (config.memoryRestartThresholdBytes < 0) config.memoryRestartThresholdBytes = 0;
        return config;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(this));
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public record ResourcePackEntry(String path, boolean enabled) {}
}
