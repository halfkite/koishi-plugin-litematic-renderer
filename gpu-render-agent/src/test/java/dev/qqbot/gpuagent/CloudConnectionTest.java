package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class CloudConnectionTest {
    private final Path temporaryDirectory = Path.of("build", "test-merge-images");

    @BeforeAll
    static void disableImageIoDiskCache() {
        ImageIO.setUseCache(false);
    }

    @Test
    void mergesAllImagesHorizontallyWithCenteredRows() throws Exception {
        List<RenderModels.Image> images = writeImages(
                new int[] {10, 4}, new int[] {6, 8}, new int[] {8, 6});
        CloudConnection.MergedPng result = CloudConnection.mergeImages(images, view(), "horizontal");

        assertEquals(10 + 6 + 8, result.width());
        assertEquals(8, result.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertNotNull(decoded);
        assertEquals(result.width(), decoded.getWidth());
        assertEquals(result.height(), decoded.getHeight());
        assertEquals(Color.RED.getRGB(), decoded.getRGB(0, 2));
        assertEquals(Color.BLUE.getRGB(), decoded.getRGB(10, 0));
    }

    @Test
    void mergesAllImagesVerticallyWithCenteredColumns() throws Exception {
        List<RenderModels.Image> images = writeImages(
                new int[] {10, 4}, new int[] {6, 8}, new int[] {8, 6});
        CloudConnection.MergedPng result = CloudConnection.mergeImages(images, view(), "vertical");

        assertEquals(10, result.width());
        assertEquals(4 + 8 + 6, result.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertNotNull(decoded);
        assertEquals(result.width(), decoded.getWidth());
        assertEquals(result.height(), decoded.getHeight());
        assertEquals(Color.RED.getRGB(), decoded.getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), decoded.getRGB(2, 4));
    }

    private List<RenderModels.Image> writeImages(int[]... sizes) throws Exception {
        List<RenderModels.Image> result = new ArrayList<>();
        Color[] colors = {Color.RED, Color.BLUE, Color.GREEN};
        Files.createDirectories(temporaryDirectory);
        for (int index = 0; index < sizes.length; index++) {
            BufferedImage image = new BufferedImage(sizes[index][0], sizes[index][1], BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) image.setRGB(x, y, colors[index].getRGB());
            }
            Path path = temporaryDirectory.resolve("view-" + index + ".png");
            ImageIO.write(image, "png", path.toFile());
            result.add(new RenderModels.Image("view-" + index, "view-" + index + ".png",
                    image.getWidth(), image.getHeight(), path));
        }
        return result;
    }

    private static RenderModels.View view() {
        return new RenderModels.View("view", "测试", 0, 0, null, true,
                10, 10, "#000000", false, 1);
    }
}
