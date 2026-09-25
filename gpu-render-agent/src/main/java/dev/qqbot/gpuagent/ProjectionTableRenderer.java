package dev.qqbot.gpuagent;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ProjectionTableRenderer {
    private static final int MARGIN = 36;
    private static final int MIN_WIDTH = 760;
    private static final int MAX_WIDTH = 2400;
    private static final int LIST_PAGE_SIZE = 80;
    private static final Color INK = gray(32);
    private static final Color MUTED = gray(82);
    private static final Color RULE = gray(190);

    private ProjectionTableRenderer() {}

    static List<Path> createProjectionList(List<String> names, Path outputDirectory) throws IOException {
        if (names == null || names.isEmpty()) return List.of();
        List<Path> pages = new ArrayList<>();
        int pageCount = (names.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;
        for (int page = 0; page < pageCount; page++) {
            int from = page * LIST_PAGE_SIZE;
            int to = Math.min(names.size(), from + LIST_PAGE_SIZE);
            pages.add(createProjectionListPage(names.subList(from, to), from, page + 1, pageCount, outputDirectory));
        }
        return List.copyOf(pages);
    }

    private static Path createProjectionListPage(List<String> names, int offset, int page, int pageCount,
                                                 Path outputDirectory) throws IOException {
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 30);
        Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 21);
        Font rowFont = new Font(Font.SANS_SERIF, Font.PLAIN, 20);
        FontMetrics titleMetrics = metrics(titleFont);
        FontMetrics headerMetrics = metrics(headerFont);
        FontMetrics rowMetrics = metrics(rowFont);
        int widest = Math.max(titleMetrics.stringWidth("缓存投影列表"), headerMetrics.stringWidth("投影名称"));
        for (String name : names) widest = Math.max(widest, rowMetrics.stringWidth(safeName(name)));
        int width = clamp(widest + MARGIN * 2 + 150, MIN_WIDTH, MAX_WIDTH);
        int nameWidth = width - MARGIN * 2 - 150;
        List<List<String>> wrapped = new ArrayList<>(names.size());
        int bodyHeight = headerMetrics.getHeight() + 18;
        for (String name : names) {
            List<String> lines = wrap(safeName(name), rowMetrics, nameWidth - 24);
            wrapped.add(lines);
            bodyHeight += Math.max(1, lines.size()) * (rowMetrics.getHeight() + 5) + 12;
        }
        int titleHeight = titleMetrics.getHeight() + 34;
        int height = MARGIN * 2 + titleHeight + bodyHeight;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setFont(titleFont);
            graphics.setColor(INK);
            graphics.drawString("缓存投影列表" + (pageCount > 1 ? "（" + page + "/" + pageCount + "）" : ""), MARGIN, MARGIN + titleMetrics.getAscent());
            int y = MARGIN + titleHeight;
            graphics.setColor(gray(235));
            graphics.fillRect(MARGIN, y, width - MARGIN * 2, headerMetrics.getHeight() + 16);
            graphics.setFont(headerFont);
            graphics.setColor(INK);
            graphics.drawString("序号", MARGIN + 12, y + 8 + headerMetrics.getAscent());
            graphics.drawString("投影名称", MARGIN + 150, y + 8 + headerMetrics.getAscent());
            y += headerMetrics.getHeight() + 24;
            for (int index = 0; index < names.size(); index++) {
                List<String> lines = wrapped.get(index);
                int rowHeight = lines.size() * (rowMetrics.getHeight() + 5) + 10;
                graphics.setColor((index & 1) == 0 ? Color.WHITE : gray(248));
                graphics.fillRect(MARGIN, y - rowMetrics.getAscent() - 5, width - MARGIN * 2, rowHeight);
                graphics.setFont(rowFont);
                graphics.setColor(MUTED);
                graphics.drawString(String.valueOf(offset + index + 1), MARGIN + 12, y);
                y = drawLines(graphics, lines, MARGIN + 150, y, rowMetrics, INK) + 10;
            }
            graphics.setColor(RULE);
            graphics.drawRect(MARGIN, MARGIN + titleHeight, width - MARGIN * 2, height - MARGIN - (MARGIN + titleHeight));
        } finally {
            graphics.dispose();
        }
        return write(image, outputDirectory, "projection-list");
    }

    private static List<String> wrap(String value, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            if (!line.isEmpty() && metrics.stringWidth(line + unit) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(unit);
            offset += Character.charCount(codePoint);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static int drawLines(Graphics2D graphics, List<String> lines, int x, int y, FontMetrics metrics, Color color) {
        graphics.setColor(color);
        for (String line : lines) {
            graphics.drawString(line, x, y);
            y += metrics.getHeight() + 5;
        }
        return y;
    }

    private static Path write(BufferedImage image, Path outputDirectory, String prefix) throws IOException {
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(prefix + "-" + UUID.randomUUID() + ".png");
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("PNG 编码器不可用");
        return output;
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = probe.createGraphics();
        try { return graphics.getFontMetrics(font); }
        finally { graphics.dispose(); }
    }

    private static Color gray(int level) { return new Color(level, level, level); }
    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static String safeName(String value) { return value == null || value.isBlank() ? "未命名" : value; }
}
