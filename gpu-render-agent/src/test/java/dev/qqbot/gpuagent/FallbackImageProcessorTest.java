package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackImageProcessorTest {
    @Test
    void smartFrameCropsCentersAndLeavesFivePercentOnTheLimitingAxis() {
        BufferedImage source = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        for (int y = 60; y < 140; y++) {
            for (int x = 70; x < 110; x++) source.setRGB(x, y, Color.RED.getRGB());
        }
        RenderModels.View view = view(400, 200, true, true, 1.0);

        BufferedImage result = FallbackImageProcessor.process(source, view);
        int[] bounds = alphaBounds(result);

        assertEquals(180, bounds[3] - bounds[1] + 1);
        assertTrue(Math.abs((bounds[0] + bounds[2]) / 2.0 - 199.5) <= 1.0);
        assertTrue(Math.abs((bounds[1] + bounds[3]) / 2.0 - 99.5) <= 1.0);
        assertTrue(bounds[1] >= 9 && bounds[3] <= 190);
    }

    @Test
    void nonSmartFramePreservesAspectRatioAndAppliesPerViewBrightness() {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 20; y < 80; y++) {
            for (int x = 30; x < 50; x++) source.setRGB(x, y, new Color(100, 20, 10, 255).getRGB());
        }
        RenderModels.View view = view(300, 100, false, true, 1.5);

        BufferedImage result = FallbackImageProcessor.process(source, view);
        int[] bounds = alphaBounds(result);
        Color center = new Color(result.getRGB((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2), true);

        assertEquals(20, bounds[2] - bounds[0] + 1);
        assertEquals(60, bounds[3] - bounds[1] + 1);
        assertEquals(150, center.getRed());
        assertEquals(30, center.getGreen());
    }

    private static RenderModels.View view(int width, int height, boolean autoFill,
                                          boolean transparent, double brightness) {
        return new RenderModels.View("test", "test", 0, 30, 1.0, autoFill, width, height,
                "#000000", transparent, 1, brightness);
    }

    private static int[] alphaBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) <= 8) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new int[]{minX, minY, maxX, maxY};
    }
}
