package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CacheStoreTest {
    @Test
    void writesAndReadsTheSharedHashCacheAndInvalidatesChangedViews() throws Exception {
        Path temp = Files.createTempDirectory(Path.of("build"), "cache-store-test-");
        try {
        AgentConfig config = new AgentConfig();
        config.cacheDirectory = temp.resolve("litematic-renderer-cache").toString();
        CacheStore store = new CacheStore(temp, config, ignored -> {});
        byte[] schematic = "test-schematic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path sourceImage = temp.resolve("render.png");
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", sourceImage.toFile());
        RenderModels.View view = new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true,
                1024, 1024, "#000000", false, 1);
        RenderModels.Request request = new RenderModels.Request(2, "task", "建筑:测试.litematic",
                List.of(view), null, "0", "signature-1024");
        List<RenderModels.Image> saved = store.save(request,
                List.of(new RenderModels.Image("isometric", "isometric.png", 2, 3, sourceImage)),
                schematic, null, "本地", Main.VERSION, "none", 12, "test-gpu");

        String hash = store.hash(schematic);
        Path entry = store.directory().resolve(hash);
        assertEquals(entry.resolve("isometric.png"), saved.getFirst().path());
        assertTrue(Files.isRegularFile(entry.resolve("about.json5")));
        assertTrue(Files.isRegularFile(entry.resolve("index.json5")) || Files.isRegularFile(store.directory().resolve("index.json5")));
        String about = Files.readString(entry.resolve("about.json5"));
        assertTrue(about.contains("\"有效工具版本\": \"0.4.4\""));
        assertTrue(about.contains("\"存储投影文件名\": \"建筑_测试.litematic\""));
        assertTrue(about.contains("\"夜视\": false"));
        assertTrue(about.contains("\"夜视亮度等级\": 15"));
        assertTrue(about.contains("\"出图配置识别数\": "));
        assertTrue(about.contains("\"brightness\": 1.0"));
        String index = Files.readString(store.directory().resolve("index.json5"));
        assertTrue(index.contains("\"相对路径\": \"" + hash + "\""));
        assertNotNull(store.lookup(request, schematic, Main.VERSION, "none"));

        RenderModels.View changedView = new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true,
                2048, 2048, "#000000", false, 1);
        RenderModels.Request changedRequest = new RenderModels.Request(2, "task-2", "建筑:测试.litematic",
                List.of(changedView), null, "0", "signature-2048");
        assertNull(store.lookup(changedRequest, schematic, Main.VERSION, "none"));

        RenderModels.View changedBrightness = new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true,
                1024, 1024, "#000000", false, 1, 1.5);
        RenderModels.Request changedBrightnessRequest = new RenderModels.Request(2, "task-4", "建筑:测试.litematic",
                List.of(changedBrightness), null, "0", "signature-1024");
        assertNull(store.lookup(changedBrightnessRequest, schematic, Main.VERSION, "none"));

        RenderModels.Request localRequest = new RenderModels.Request(2, "task-3", "建筑:测试.litematic",
                List.of(view), null, "0", null);
        String beforeNightVisionChange = store.configurationFingerprint(localRequest, Main.VERSION, "none");
        config.nightVisionLevel = 8;
        assertNotEquals(beforeNightVisionChange, store.configurationFingerprint(localRequest, Main.VERSION, "none"));
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
