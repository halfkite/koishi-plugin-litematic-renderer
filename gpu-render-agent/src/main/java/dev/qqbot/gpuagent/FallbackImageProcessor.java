package dev.qqbot.gpuagent;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class FallbackImageProcessor {
    private static final double SMART_FRAME_FILL = 0.90;

    private FallbackImageProcessor() {}

    static BufferedImage process(BufferedImage source, RenderModels.View view) {
        int width = view.width();
        int height = view.height();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            if (!view.transparentBackground()) {
                graphics.setColor(parseBackground(view.background()));
                graphics.fillRect(0, 0, width, height);
            } else {
                graphics.setComposite(AlphaComposite.Clear);
                graphics.fillRect(0, 0, width, height);
            }

            Bounds bounds = Boolean.FALSE.equals(view.autoFill()) ? null : contentBounds(source);
            int sourceX = bounds == null ? 0 : bounds.minX;
            int sourceY = bounds == null ? 0 : bounds.minY;
            int sourceWidth = bounds == null ? source.getWidth() : bounds.width();
            int sourceHeight = bounds == null ? source.getHeight() : bounds.height();
            double fill = bounds == null ? 1.0 : SMART_FRAME_FILL;
            double scale = Math.min(width * fill / sourceWidth, height * fill / sourceHeight);
            int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
            int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
            int targetX = (width - targetWidth) / 2;
            int targetY = (height - targetHeight) / 2;

            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            drawWithBrightness(graphics, source, view.brightnessFactor(),
                    sourceX, sourceY, sourceWidth, sourceHeight,
                    targetX, targetY, targetX + targetWidth, targetY + targetHeight);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static void drawWithBrightness(Graphics2D graphics, BufferedImage source, double brightness,
                                           int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                           int targetX1, int targetY1, int targetX2, int targetY2) {
        double factor = Math.max(0.25, Math.min(3.0, Double.isFinite(brightness) ? brightness : 1.0));
        if (Math.abs(factor - 1.0) < 0.0001) {
            graphics.drawImage(source, targetX1, targetY1, targetX2, targetY2,
                    sourceX, sourceY, sourceX + sourceWidth, sourceY + sourceHeight, null);
            return;
        }

        BufferedImage adjusted = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB);
        try {
            for (int y = 0; y < sourceHeight; y++) {
                for (int x = 0; x < sourceWidth; x++) {
                    int pixel = source.getRGB(sourceX + x, sourceY + y);
                    int alpha = pixel >>> 24;
                    if (alpha <= 8) {
                        adjusted.setRGB(x, y, pixel);
                        continue;
                    }
                    int red = Math.min(255, (int) Math.round(((pixel >>> 16) & 0xFF) * factor));
                    int green = Math.min(255, (int) Math.round(((pixel >>> 8) & 0xFF) * factor));
                    int blue = Math.min(255, (int) Math.round((pixel & 0xFF) * factor));
                    adjusted.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
            graphics.drawImage(adjusted, targetX1, targetY1, targetX2, targetY2,
                    0, 0, sourceWidth, sourceHeight, null);
        } finally {
            adjusted.flush();
        }
    }

    private static Bounds contentBounds(BufferedImage image) {
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
        return maxX < minX || maxY < minY ? null : new Bounds(minX, minY, maxX, maxY);
    }

    private static Color parseBackground(String value) {
        try {
            String text = value == null ? "#000000" : value.trim();
            return Color.decode(text.isEmpty() ? "#000000" : text);
        } catch (NumberFormatException ignored) {
            return Color.BLACK;
        }
    }

    private record Bounds(int minX, int minY, int maxX, int maxY) {
        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
    }
}
