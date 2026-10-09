package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ImageCachePolicyTest {
    @TempDir Path root;

    @Test void defaultsAreStrictExceedsAndEitherThresholdSuffices() {
        CacheStore store = new CacheStore(root, new AgentConfig(), null);
        assertFalse(store.shouldCacheImages(2097152, 30));
        assertTrue(store.shouldCacheImages(2097153, 0));
        assertTrue(store.shouldCacheImages(1, 31));
    }

    @Test void smallResultsRemainSendableThenCleanUpAndPopularResultsPersist() throws Exception {
        AgentConfig config = new AgentConfig();
        config.cacheImageMinCalls = 2;
        CacheStore store = new CacheStore(root, config, null);
        Path png = root.resolve("input.png");
        ImageIO.write(new BufferedImage(3, 4, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        byte[] schematic = new byte[] {1, 2, 3};
        var view = new RenderModels.View("isometric", "axis", 135, 36, 1.0, true, 3, 4, "#000000", false, 1);
        var request = new RenderModels.Request(2, "test", "original.litematic", List.of(view), null);
        Path entry = store.directory().resolve(store.hash(schematic));
        for (int call = 1; call <= 3; call++) {
            var images = store.save(request, List.of(new RenderModels.Image("isometric", "axis.png", 3, 4, png)),
                    schematic, null, "test", Main.VERSION, "none", 1, "test");
            Path resultPath = images.getFirst().path();
            try (var result = store.result(request, images, 1, false, "test")) {
                assertTrue(Files.isRegularFile(resultPath));
                assertTrue(Files.isRegularFile(entry.resolve("original.litematic")));
                assertEquals(call > 2, Files.isRegularFile(entry.resolve("isometric.png")));
                assertEquals(call, Protocol.GSON.fromJson(Files.readString(entry.resolve("about.json5")), com.google.gson.JsonObject.class)
                        .get("缓存调用次数").getAsInt());
                assertEquals(call > 2, store.isCurrent(request, schematic, Main.VERSION, "none"));
            }
            assertEquals(call > 2, Files.exists(resultPath));
        }
        config.cacheImageMinCalls = 100;
        assertNull(store.lookup(request, schematic, Main.VERSION, "none"));
        assertFalse(Files.exists(entry.resolve("isometric.png")));
        assertArrayEquals(schematic, Files.readAllBytes(entry.resolve("original.litematic")));
    }

    @Test void failedImageCopyDoesNotLeaveTransientImagesOrDeleteSource() throws Exception {
        CacheStore store = new CacheStore(root, new AgentConfig(), null);
        byte[] schematic = new byte[] {1, 2};
        var request = new RenderModels.Request(2, "test", "original.litematic", List.of(), null);
        assertThrows(java.io.IOException.class, () -> store.save(request,
                List.of(new RenderModels.Image("isometric", "axis.png", 3, 4, root.resolve("missing.png"))),
                schematic, null, "test", Main.VERSION, "none", 1, "test"));
        try (var paths = Files.list(store.directory().resolve(".transient"))) { assertEquals(0, paths.count()); }
        assertArrayEquals(schematic, Files.readAllBytes(store.directory().resolve(store.hash(schematic)).resolve("original.litematic")));
    }
}
