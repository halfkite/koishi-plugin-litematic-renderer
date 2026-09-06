package dev.qqbot.gpuagent;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Main {
    /** 工具版本号：与 build.gradle 和预发布包名保持一致。 */
    public static final String VERSION = "0.4.4";

    private Main() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("file.encoding", "UTF-8");
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage();
            return;
        }
        if (args.length == 1 && ("--version".equals(args[0]) || "-v".equals(args[0]))) {
            System.out.println(VERSION);
            return;
        }
        String configuredHome = System.getenv("LITEMATIC_GPU_AGENT_HOME");
        Path root = configuredHome == null || configuredHome.isBlank()
                ? defaultDataRoot()
                : Path.of(configuredHome).toAbsolutePath().normalize();
        Path configPath = root.resolve("agent.json");
        AgentConfig config = AgentConfig.load(configPath);
        if (args.length > 0) {
            if (args.length == 1 && ("--web".equals(args[0]) || "--bot".equals(args[0]))) {
                runHeadless(root, configPath, config, "--web".equals(args[0]));
                return;
            }
            runCli(root, config, args);
            return;
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeLater(() -> {
            AgentFrame frame = new AgentFrame(root, configPath, config);
            frame.setVisible(true);
        });
    }

    private static void runCli(Path root, AgentConfig config, String[] args) throws Exception {
        if (args.length != 4 || !"--render".equals(args[0]) || !"--output".equals(args[2])) {
            System.err.println("用法：java -jar litematic-gpu-agent-" + VERSION + "-all.jar --render FILE.litematic --output DIRECTORY");
            System.exit(2);
        }
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        try (RenderService renderer = new RenderService(root, config)) {
            renderer.setLog(System.out::println);
            List<RenderModels.View> views = List.of(
                    new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true, 2048, 2048, "#000000", false, 1),
                    new RenderModels.View("isometric-reverse", "反向正二轴测", 315, 36, 0.82, true, 2048, 2048, "#000000", false, 1));
            var request = new RenderModels.Request(2, UUID.randomUUID().toString(), input.getFileName().toString(), views, null, "0", null);
            var result = renderer.submit(request, Files.readAllBytes(input), Duration.ofMillis(config.renderTimeoutMillis)).join();
            Files.createDirectories(output);
            for (var image : result.images()) Files.copy(image.path(), output.resolve(image.name()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Rendered " + result.images().size() + " image(s) in " + result.elapsedMillis() + " ms to " + output);
        }
    }

    /** Linux 无桌面模式：渲染、HTTP v1、云端、机器人和 Web 后台共用同一生命周期。 */
    private static void runHeadless(Path root, Path configPath, AgentConfig config, boolean web) throws Exception {
        config.webEnabled = web;
        Files.createDirectories(root);
        Path logFile = root.resolve("agent.log");
        Consumer<String> log = new Consumer<>() {
            @Override public void accept(String message) {
                String line = java.time.LocalDateTime.now() + "  " + (message == null ? "" : message)
                        + System.lineSeparator();
                System.out.print(line);
                synchronized (this) {
                    try { Files.writeString(logFile, line, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND); }
                    catch (IOException ignored) { }
                }
            }
        };
        if (config.generatedWebPassword()) {
            System.out.println("首次 Web 管理后台凭据：" + config.webUsername + " / " + config.webPassword);
            System.out.println("凭据提示文件：" + root.resolve("web-credentials.txt").toAbsolutePath());
        }

        RenderService renderer = new RenderService(root, config);
        renderer.setLog(log);
        HttpV1Server httpServer = new HttpV1Server(config, renderer, log);
        CloudConnection cloud = new CloudConnection(config, renderer, log);
        BotManager bots = new BotManager(root, configPath, config, renderer, log);
        WebAdminServer webServer = new WebAdminServer(root, configPath, config, renderer, bots, cloud, log);
        MemoryWatchdog watchdog = new MemoryWatchdog(config, renderer, renderer.runtime(), root, log);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) return;
            try { watchdog.close(); } catch (Throwable error) { log.accept("关闭内存看门狗失败：" + error.getMessage()); }
            try { bots.close(); } catch (Throwable error) { log.accept("关闭机器人连接失败：" + error.getMessage()); }
            try { webServer.close(); } catch (Throwable error) { log.accept("关闭 Web 管理后台失败：" + error.getMessage()); }
            try { cloud.close(); } catch (Throwable error) { log.accept("关闭云端连接失败：" + error.getMessage()); }
            try { httpServer.close(); } catch (Throwable error) { log.accept("关闭 HTTP v1 失败：" + error.getMessage()); }
            try { renderer.close(); } catch (Throwable error) { log.accept("关闭渲染服务失败：" + error.getMessage()); }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(close, "gpu-agent-shutdown"));
        try {
            watchdog.start();
            httpServer.start();
            cloud.start();
            bots.start();
            if (web) webServer.start();
            System.out.println(web
                    ? "Litematic GPU Agent 已启动，Web 管理后台：http://" + config.webBindHost + ":" + config.webPort + "/"
                    : "Litematic GPU Agent 已启动（机器人模式，无 Web 后台）");
            new CountDownLatch(1).await();
        } finally {
            close.run();
        }
    }

    static Path defaultDataRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) return Path.of(localAppData, "LitematicGpuAgent");
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) return Path.of(xdgDataHome, "LitematicGpuAgent");
        return Path.of(System.getProperty("user.home"), ".litematic-gpu-agent");
    }

    private static void printUsage() {
        System.out.println("Litematic GPU Agent " + VERSION);
        System.out.println("用法：");
        System.out.println("  java -jar litematic-gpu-agent-" + VERSION + "-all.jar");
        System.out.println("      启动图形界面");
        System.out.println("  java -jar litematic-gpu-agent-" + VERSION + "-all.jar --web");
        System.out.println("      无桌面启动 Web 管理后台、机器人和渲染服务（默认端口 2618）");
        System.out.println("  java -jar litematic-gpu-agent-" + VERSION + "-all.jar --bot");
        System.out.println("      无桌面启动机器人和渲染服务，不启动 Web 后台");
        System.out.println("  java -jar litematic-gpu-agent-" + VERSION + "-all.jar --render FILE.litematic --output DIRECTORY");
        System.out.println("      使用本地 GPU 运行时渲染投影");
        System.out.println("  --version       显示版本");
        System.out.println("  --help          显示帮助");
    }
}
