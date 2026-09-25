package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionSearchTest {
    @Test
    void sortsByCacheCallsThenProjectionNameAndCreatesNumberedSheet() throws Exception {
        Path cache = Files.createTempDirectory("projection-search-");
        try {
            writeEntry(cache, "a".repeat(64), "alpha.litematic", 2, new Color(220, 80, 80));
            writeEntry(cache, "b".repeat(64), "alphabet.litematic", 9, new Color(80, 180, 100));
            writeEntry(cache, "c".repeat(64), "alpine.litematic", 9, new Color(80, 100, 220));
            Files.writeString(cache.resolve("index.json5"), "["
                    + "{\"投影文件名称\":\"alpha.litematic\",\"哈希值\":\"" + "a".repeat(64) + "\",\"相对路径\":\"" + "a".repeat(64) + "\"},"
                    + "{\"投影文件名称\":\"alphabet.litematic\",\"哈希值\":\"" + "b".repeat(64) + "\",\"相对路径\":\"" + "b".repeat(64) + "\"},"
                    + "{\"投影文件名称\":\"alpine.litematic\",\"哈希值\":\"" + "c".repeat(64) + "\",\"相对路径\":\"" + "c".repeat(64) + "\"}]\n");

            ProjectionSearch.SearchPage page = ProjectionSearch.search(cache, "alp", 15);
            assertEquals(3, page.entries().size());
            assertEquals("alphabet", page.entries().get(0).displayName());
            assertEquals("alpine", page.entries().get(1).displayName());
            assertEquals("alpha", page.entries().get(2).displayName());
            int stableOrdinal = page.entries().get(0).ordinal();
            assertEquals("alphabet", ProjectionSearch.findByOrdinal(cache, stableOrdinal).displayName());
            writeEntry(cache, "a".repeat(64), "alpha.litematic", 50, new Color(220, 80, 80));
            ProjectionSearch.SearchPage reranked = ProjectionSearch.search(cache, "alp", 15);
            assertEquals("alpha", reranked.entries().getFirst().displayName());
            assertEquals("alphabet", ProjectionSearch.findByOrdinal(cache, stableOrdinal).displayName());

            ProjectionSearch.SearchPage limited = ProjectionSearch.search(cache, "alp", 1);
            assertEquals(1, limited.entries().size());
            Path sheet = ProjectionSearch.createContactSheet(page, cache.resolve("search"));
            assertTrue(Files.isRegularFile(sheet));
            BufferedImage result = ImageIO.read(sheet.toFile());
            assertNotNull(result);
            assertTrue(result.getWidth() > 300);
            assertTrue(result.getHeight() > 250);
        } finally {
            deleteTree(cache);
        }
    }

    @Test
    void laysOutFifteenResultsAsLandscapeSheetWithSquareSlots() throws Exception {
        Path cache = Files.createTempDirectory("projection-search-layout-");
        try {
            for (int index = 0; index < 15; index++) {
                String hash = Integer.toHexString(index).repeat(64).substring(0, 64);
                String filename = index == 0
                        ? "factory-这是一个非常非常长的投影文件名称用于换行测试.litematic"
                        : "factory-projection-" + index + ".litematic";
                writeEntry(cache, hash, filename, index, new Color(80 + index * 8, 100, 140));
            }
            ProjectionSearch.SearchPage page = ProjectionSearch.search(cache, "factory", 15);
            assertEquals(15, page.entries().size());
            Path sheet = ProjectionSearch.createContactSheet(page, cache.resolve("search"));
            BufferedImage image = ImageIO.read(sheet.toFile());
            assertNotNull(image);
            assertTrue(image.getWidth() > image.getHeight());
            assertEquals(new Color(245, 247, 250).getRGB(), image.getRGB(100, 130));
        } finally {
            deleteTree(cache);
        }
    }

    @Test
    void exactNameLookupRejectsSubstringsAndDetectsDuplicates() throws Exception {
        Path cache = Files.createTempDirectory("projection-search-exact-");
        try {
            writeEntry(cache, "a".repeat(64), "半筝.litematic", 1, Color.RED);
            writeEntry(cache, "b".repeat(64), "半筝.litematic", 2, Color.BLUE);
            writeEntry(cache, "c".repeat(64), "半筝改.litematic", 3, Color.GREEN);
            ProjectionCacheIndex.rebuild(cache);
            assertEquals(2, ProjectionSearch.findByExactName(cache, "半筝").size());
            assertEquals(2, ProjectionSearch.findByExactName(cache, "半筝.litematic").size());
            assertEquals(1, ProjectionSearch.findByExactName(cache, "半筝改").size());
            assertTrue(ProjectionSearch.findByExactName(cache, "半").isEmpty());
        } finally {
            deleteTree(cache);
        }
    }

    @Test
    void findsSourceOnlyProjectionFromGeneratedNameHashIndex() throws Exception {
        Path cache = Files.createTempDirectory("projection-search-source-only-");
        try {
            String hash = "d".repeat(64);
            Path entry = cache.resolve(hash);
            Files.createDirectories(entry);
            Files.write(entry.resolve("全物品投影.litematic"), validNbt());
            Files.writeString(entry.resolve("about.json5"), "{\"文件哈希\":\"" + hash + "\",\"投影文件名\":\"全物品投影.litematic\",\"存储投影文件名\":\"全物品投影.litematic\"}");

            ProjectionSearch.SearchPage page = ProjectionSearch.search(cache, "全物品", 15);
            assertEquals(1, page.entries().size());
            assertEquals("全物品投影", page.entries().getFirst().displayName());
            assertEquals("全物品投影", page.entries().getFirst().displayName());
            assertTrue(Files.isRegularFile(cache.resolve(ProjectionCacheIndex.NAME)));
        } finally {
            deleteTree(cache);
        }
    }

    @Test
    void putsAtMostFiveRecentlySavedProjectionsFirstThenUsesRenderCount() throws Exception {
        Path cache = Files.createTempDirectory("projection-search-ranking-");
        try {
            long now = System.currentTimeMillis();
            for (int index = 0; index < 6; index++) {
                writeEntry(cache, Integer.toHexString(index + 1).repeat(64).substring(0, 64),
                        "recent-" + index + ".litematic", 0, new Color(100, 120, 140),
                        now - Duration.ofDays(6 - index).toMillis());
            }
            writeEntry(cache, "a".repeat(64), "historic-top.litematic", 200, new Color(130, 120, 110), 0);
            writeEntry(cache, "b".repeat(64), "historic-second.litematic", 100, new Color(120, 110, 100), 0);

            ProjectionSearch.SearchPage page = ProjectionSearch.search(cache, "", 8);
            assertEquals(8, page.entries().size());
            assertEquals("recent-5", page.entries().get(0).displayName());
            assertEquals("recent-1", page.entries().get(4).displayName());
            assertEquals("historic-top", page.entries().get(5).displayName());
            assertEquals("historic-second", page.entries().get(6).displayName());
            assertEquals("recent-0", page.entries().get(7).displayName());
        } finally {
            deleteTree(cache);
        }
    }

    @Test
    void searchesWithoutReadingOrSendingCachedRenderImages() throws Exception {
        Path cache = Files.createTempDirectory("projection-search-no-preview-");
        try {
            String hash = "e".repeat(64);
            writeEntry(cache, hash, "preview-source.litematic", 1, Color.MAGENTA);
            Files.writeString(cache.resolve(hash).resolve("isometric.png"), "not a PNG");
            ProjectionCacheIndex.rebuild(cache);
            ProjectionSearch.SearchPage page = ProjectionSearch.search(cache, "preview", 15);
            assertEquals(1, page.entries().size());
            assertEquals("preview-source", page.entries().getFirst().displayName());
            assertNotNull(ImageIO.read(ProjectionSearch.createContactSheet(page, cache.resolve("search")).toFile()));
        } finally {
            deleteTree(cache);
        }
    }

    private static void writeEntry(Path cache, String hash, String filename, long calls, Color color) throws Exception {
        Path entry = cache.resolve(hash);
        Files.createDirectories(entry);
        writeEntry(cache, hash, filename, calls, color, 0);
    }

    private static void writeEntry(Path cache, String hash, String filename, long renderCount, Color color, long savedAt) throws Exception {
        Path entry = cache.resolve(hash);
        Files.createDirectories(entry);
        Files.writeString(entry.resolve("about.json5"), "{"
                + "\"文件哈希\":\"" + hash + "\","
                + "\"投影文件名\":\"" + filename + "\","
                + "\"存储投影文件名\":\"" + filename + "\","
                + "\"缓存调用次数\":" + renderCount + ","
                + "\"渲染次数\":" + renderCount
                + (savedAt > 0 ? ",\"投影保存时间\":" + savedAt : "") + "}\n");
        Files.write(entry.resolve(filename), validNbt());
        BufferedImage image = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        assertTrue(ImageIO.write(image, "png", entry.resolve("isometric.png").toFile()));
    }

    private static byte[] validNbt() { return new byte[] {10, 0, 0, 0}; }

    private static void deleteTree(Path root) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!Files.exists(root)) return;
            try (var stream = Files.walk(root)) {
                for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                return;
            } catch (java.nio.file.DirectoryNotEmptyException transientRace) {
                if (attempt == 4) throw transientRace;
                Thread.sleep(25L * (attempt + 1));
            }
        }
    }
}
