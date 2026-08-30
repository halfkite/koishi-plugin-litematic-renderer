package dev.qqbot.gpuagent;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class Main {
    /** 工具版本号：与 build.gradle 保持一致，用作缓存目录的版本文件夹名。 */
    public static final String VERSION = "0.2.26";

    private Main() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("file.encoding", "UTF-8");
        String configuredHome = System.getenv("LITEMATIC_GPU_AGENT_HOME");
        Path root = configuredHome == null || configuredHome.isBlank()
                ? Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), "LitematicGpuAgent")
                : Path.of(configuredHome).toAbsolutePath().normalize();
        Path configPath = root.resolve("agent.json");
        AgentConfig config = AgentConfig.load(configPath);
        if (args.length > 0) {
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
            System.err.println("Usage: java -jar litematic-gpu-agent-all.jar --render FILE.litematic --output DIRECTORY");
            System.exit(2);
        }
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        try (RenderService renderer = new RenderService(root, config)) {
            renderer.setLog(System.out::println);
            List<RenderModels.View> views = List.of(
                    new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true, 2048, 2048, "#000000", false, 1),
                    new RenderModels.View("isometric-reverse", "反向正二轴测", 315, 36, 0.82, true, 2048, 2048, "#000000", false, 1));
            var request = new RenderModels.Request(2, UUID.randomUUID().toString(), input.getFileName().toString(), views, null);
            var result = renderer.submit(request, Files.readAllBytes(input), Duration.ofMillis(config.renderTimeoutMillis)).join();
            Files.createDirectories(output);
            for (var image : result.images()) Files.copy(image.path(), output.resolve(image.name()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Rendered " + result.images().size() + " image(s) in " + result.elapsedMillis() + " ms to " + output);
        }
    }
}
