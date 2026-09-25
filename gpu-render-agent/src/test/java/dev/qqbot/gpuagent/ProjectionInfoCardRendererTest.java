package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionInfoCardRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsReadableInfoCardAtRenderedWidth() throws Exception {
        String metadata = "投影名称：半筝\n保存者游戏 ID：half_kite\n尺寸：7×14×21\n游戏版本：1.21（数据版本：3953）";

        Path imagePath = ProjectionInfoCardRenderer.create(metadata, 1024, temporaryDirectory);
        var image = ImageIO.read(imagePath.toFile());

        assertNotNull(image);
        assertEquals(1024, image.getWidth());
        assertTrue(image.getHeight() > 100);
        assertEquals(3, image.getColorModel().getNumColorComponents());
        assertTrue(Files.size(imagePath) < 100_000);
    }

    @Test
    void keepsExactWidthForSmallRenderedViews() throws Exception {
        Path imagePath = ProjectionInfoCardRenderer.create("投影名称：小视角", 128, temporaryDirectory);

        assertEquals(128, ImageIO.read(imagePath.toFile()).getWidth());
    }

    @Test
    void horizontallyStitchesViewsAboveTheInfoCard() throws Exception {
        Path firstPath = temporaryDirectory.resolve("first.png");
        Path secondPath = temporaryDirectory.resolve("second.png");
        writeView(firstPath, 120, 90, Color.RED);
        writeView(secondPath, 180, 110, Color.BLUE);
        List<RenderModels.Image> views = List.of(
                new RenderModels.Image("first", "前视图", 120, 90, firstPath),
                new RenderModels.Image("second", "侧视图", 180, 110, secondPath));

        Path compositePath = ProjectionInfoCardRenderer.createComposite(
                "投影名称：半筝\n保存者游戏 ID：half_kite\n创建时间：2026-08-28 03:46:58\n"
                        + "方块数体积：805/880\n尺寸：11 × 16 × 5\nLitematic 版本：7\n游戏版本：1.21.11（数据版本：4671）",
                views, temporaryDirectory);
        BufferedImage image = ImageIO.read(compositePath.toFile());

        assertEquals(300, image.getWidth());
        assertTrue(image.getHeight() > 110);
        assertEquals(Color.RED.getRGB(), image.getRGB(20, 20));
        assertEquals(Color.BLUE.getRGB(), image.getRGB(152, 20));
        assertEquals(Color.BLUE.getRGB(), image.getRGB(126, 20));
        assertEquals(Color.WHITE.getRGB(), image.getRGB(0, image.getHeight() - 1));
    }

    @Test
    void detailedViewHasTwoLineCaptionBelowTheImage() throws Exception {
        Path viewPath = temporaryDirectory.resolve("detail.png");
        writeView(viewPath, 512, 512, Color.RED);
        var views = List.of(new RenderModels.Image("right", "右视图", 512, 512, viewPath));

        assertEquals("投影名称：半筝\n投影编号：1321 右视图",
                ProjectionInfoCardRenderer.viewCaption("半筝", 1321, "右视图"));
        Path output = ProjectionInfoCardRenderer.createViewComposite(
                "半筝", 1321, "右视图", views, temporaryDirectory);
        BufferedImage image = ImageIO.read(output.toFile());
        assertEquals(512, image.getWidth());
        assertTrue(image.getHeight() > 512);
        assertEquals(Color.RED.getRGB(), image.getRGB(0, 511));
        assertEquals(Color.WHITE.getRGB(), image.getRGB(0, 512));
    }

    @Test
    void alignsRightColumnValuesAtOneLeftEdge() throws Exception {
        Path cardPath = ProjectionInfoCardRenderer.create("投影名称：测试\n"
                + "创建时间：111111111111111111\n尺寸：111\n游戏版本：11111111", 1600, temporaryDirectory);
        BufferedImage card = ImageIO.read(cardPath.toFile());
        List<Integer> starts = new ArrayList<>();
        int previousInkRow = -100;
        for (int y = 0; y < card.getHeight(); y++) {
            int firstInk = -1;
            for (int x = 1600 * 65 / 100; x < 1600 - 20; x++) {
                int rgb = card.getRGB(x, y);
                if (((rgb >> 16) & 255) < 150 && ((rgb >> 8) & 255) < 150 && (rgb & 255) < 150) {
                    firstInk = x;
                    break;
                }
            }
            if (firstInk < 0) continue;
            if (y - previousInkRow > 10) starts.add(firstInk);
            else starts.set(starts.size() - 1, Math.min(starts.getLast(), firstInk));
            previousInkRow = y;
        }
        assertEquals(3, starts.size(), "right-column value rows");
        assertTrue(starts.stream().mapToInt(Integer::intValue).max().orElseThrow()
                        - starts.stream().mapToInt(Integer::intValue).min().orElseThrow() <= 4,
                "right-column values should share a left edge: " + starts);
    }

    @Test
    void keepsTypicalGameVersionOnOneLineAfterAligningValues() throws Exception {
        String common = "投影名称：测试\n保存者游戏 ID：alltheman114514\n创建时间：2026-08-28 03:46:58\n"
                + "方块数体积：805/880\n尺寸：11 × 16 × 5\nLitematic 版本：7\n游戏版本：";
        BufferedImage shortCard = ImageIO.read(ProjectionInfoCardRenderer.create(
                common + "1.21", 1600, temporaryDirectory).toFile());
        BufferedImage fullCard = ImageIO.read(ProjectionInfoCardRenderer.create(
                common + "1.21.11（数据版本：4671）", 1600, temporaryDirectory).toFile());
        assertEquals(shortCard.getHeight(), fullCard.getHeight());
    }

    private static void writeView(Path path, int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }
}
