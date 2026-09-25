package dev.qqbot.gpuagent;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ProjectionInfoCardRenderer {
    private static final String[][] FIELD_PAIRS = {
            {"保存者游戏 ID", "创建时间"},
            {"方块数体积", "尺寸"},
            {"Litematic 版本", "游戏版本"}
    };

    private ProjectionInfoCardRenderer() {}

    static Path create(String metadata, int width, Path outputDirectory) throws IOException {
        return createComposite(metadata, List.of(), width, outputDirectory);
    }

    static Path createComposite(String metadata, List<RenderModels.Image> views, Path outputDirectory)
            throws IOException {
        return createComposite(metadata, views, 1024, outputDirectory);
    }

    static Path createViewComposite(String projectionName, int ordinal, String viewName,
                                    List<RenderModels.Image> views, Path outputDirectory) throws IOException {
        return createComposite(viewCaption(projectionName, ordinal, viewName), views, outputDirectory);
    }

    static String viewCaption(String projectionName, int ordinal, String viewName) {
        return "投影名称：" + projectionName + "\n投影编号：" + ordinal + " " + viewName;
    }

    private static Path createComposite(String metadata, List<RenderModels.Image> views, int fallbackWidth,
                                        Path outputDirectory) throws IOException {
        if (metadata == null || metadata.isBlank()) return null;
        List<BufferedImage> images = new ArrayList<>();
        if (views != null) for (RenderModels.Image view : views) {
            if (view == null || view.path() == null || !Files.isRegularFile(view.path())) continue;
            BufferedImage image = ImageIO.read(view.path().toFile());
            if (image == null) throw new IOException("渲染图无法解码：" + view.path().getFileName());
            images.add(image);
        }

        long imageWidth = images.stream().mapToLong(BufferedImage::getWidth).sum();
        if (imageWidth > Integer.MAX_VALUE) throw new IOException("横向拼接宽度超出 PNG 支持范围");
        int canvasWidth = Math.max(1, images.isEmpty() ? fallbackWidth : (int) imageWidth);
        int imageHeight = images.stream().mapToInt(BufferedImage::getHeight).max().orElse(0);
        int fontSize = Math.max(18, Math.min(68, canvasWidth / 38
                + (canvasWidth < 1200 ? 2 : canvasWidth >= 2000 ? 3 : 0)));
        Font bodyFont = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.min(72, fontSize + Math.max(3, fontSize / 8)));
        FontMetrics bodyMetrics = metrics(bodyFont);
        FontMetrics titleMetrics = metrics(titleFont);
        int margin = Math.min(Math.max(24, canvasWidth / 28), Math.max(0, (canvasWidth - 16) / 2));
        int innerWidth = Math.max(1, canvasWidth - margin * 2);
        CardLayout card = layout(metadata, innerWidth, margin, bodyMetrics, titleMetrics, fontSize);
        long fullHeight = (long) imageHeight + card.height();
        if (fullHeight > Integer.MAX_VALUE) throw new IOException("投影信息拼图高度超出 PNG 支持范围");

        BufferedImage composite = new BufferedImage(canvasWidth, (int) fullHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = composite.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int x = 0;
            for (BufferedImage image : images) {
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(image, x, (imageHeight - image.getHeight()) / 2, null);
                x += image.getWidth();
            }

            int cardTop = imageHeight;
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, cardTop, canvasWidth, card.height());
            drawCard(graphics, card, margin, cardTop, bodyMetrics, titleMetrics, fontSize);
        } finally {
            graphics.dispose();
        }

        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("projection-composite-" + UUID.randomUUID() + ".png");
        if (!ImageIO.write(composite, "png", output.toFile())) throw new IOException("投影信息拼图编码失败");
        return output;
    }

    private static CardLayout layout(String metadata, int innerWidth, int margin, FontMetrics body,
                                     FontMetrics title, int fontSize) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> extra = new ArrayList<>();
        for (String line : metadata.lines().toList()) {
            int delimiter = line.indexOf('：');
            if (delimiter < 0) delimiter = line.indexOf(':');
            if (delimiter > 0) {
                String name = line.substring(0, delimiter).trim();
                if ("方块数/体积".equals(name)) name = "方块数体积";
                if ("投影编号".equals(name)) extra.add(line.trim());
                else fields.put(name, line.substring(delimiter + 1).trim());
            }
            else if (!line.isBlank()) extra.add(line.trim());
        }

        String projectionName = fields.remove("投影名称");
        String titleText = projectionName == null ? (extra.isEmpty() ? null : extra.removeFirst()) : "投影名称：" + projectionName;
        if (titleText == null) titleText = "投影信息";
        List<String> titleLines = wrap(titleText, title, innerWidth);
        int groupGap = Math.max(fontSize / 2, 18);
        int groupWidth = Math.max(1, (innerWidth - groupGap) / 2);
        int labelValueGap = Math.max(8, fontSize / 3);
        int leftLabelWidth = maxLabelWidth(FIELD_PAIRS, 0, body);
        int rightLabelWidth = maxLabelWidth(FIELD_PAIRS, 1, body);
        int minimumValueWidth = Math.max(48, fontSize * 4);
        boolean pairedColumns = groupWidth - leftLabelWidth - labelValueGap >= minimumValueWidth
                && groupWidth - rightLabelWidth - labelValueGap >= minimumValueWidth;
        int stackedLabelWidth = Math.min(Math.max(leftLabelWidth, rightLabelWidth), Math.max(1, innerWidth / 2));
        int valueGap = pairedColumns ? labelValueGap : Math.max(8, fontSize / 3);
        int leftValueX = pairedColumns ? margin + leftLabelWidth + labelValueGap : margin + stackedLabelWidth + valueGap;
        int leftValueWidth = groupWidth - leftLabelWidth - labelValueGap;
        int rightValueWidthAtCenter = groupWidth - rightLabelWidth - labelValueGap;
        int leftUsedWidth = 0;
        int rightNeededWidth = 0;
        for (String[] pair : FIELD_PAIRS) {
            leftUsedWidth = Math.max(leftUsedWidth, body.stringWidth(fields.getOrDefault(pair[0], "")));
            rightNeededWidth = Math.max(rightNeededWidth, body.stringWidth(fields.getOrDefault(pair[1], "")));
        }
        int minimumColumnGap = Math.max(12, fontSize / 3);
        int shift = pairedColumns ? Math.min(Math.max(0,
                        leftValueWidth - Math.min(leftUsedWidth, leftValueWidth) + groupGap - minimumColumnGap),
                Math.max(0, rightNeededWidth - rightValueWidthAtCenter)) : 0;
        int rightLabelX = margin + groupWidth + groupGap - shift;
        int rightValueEnd = margin + innerWidth;
        int rightValueWidth = pairedColumns
                ? rightValueEnd - rightLabelX - rightLabelWidth - labelValueGap
                : innerWidth - stackedLabelWidth - valueGap;
        int leftLabelColumnWidth = pairedColumns ? leftLabelWidth : stackedLabelWidth;
        int rightLabelColumnWidth = pairedColumns ? rightLabelWidth : stackedLabelWidth;
        List<InfoRow> rows = new ArrayList<>();
        for (String[] pair : FIELD_PAIRS) {
            String left = fields.remove(pair[0]);
            String right = fields.remove(pair[1]);
            InfoFieldLines leftField = left == null ? null : field(pair[0], left, leftLabelColumnWidth,
                    pairedColumns ? groupWidth - leftLabelWidth - labelValueGap : rightValueWidth, body);
            InfoFieldLines rightField = right == null ? null : field(pair[1], right, rightLabelColumnWidth,
                    rightValueWidth, body);
            if (leftField != null || rightField != null) {
                rows.add(new InfoRow(leftField, rightField));
            }
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            rows.add(new InfoRow(field(entry.getKey(), entry.getValue(), stackedLabelWidth, rightValueWidth, body), null));
        }
        for (String line : extra) rows.add(new InfoRow(new InfoFieldLines(wrap(line, body, innerWidth), List.of()), null));

        int titleLineHeight = title.getHeight() + Math.max(5, fontSize / 5);
        int bodyLineHeight = body.getHeight() + Math.max(5, fontSize / 5);
        int rowGap = Math.max(8, fontSize / 4);
        int height = margin + titleLines.size() * titleLineHeight + Math.max(8, fontSize / 5);
        for (InfoRow row : rows) height += rowLineCount(row, pairedColumns) * bodyLineHeight + rowGap;
        height += margin;
        return new CardLayout(titleLines, rows, height, titleLineHeight, bodyLineHeight, rowGap,
                pairedColumns, leftValueX, rightLabelX, rightLabelWidth,
                stackedLabelWidth, valueGap);
    }

    private static void drawCard(Graphics2D graphics, CardLayout card, int margin, int top,
                                 FontMetrics bodyMetrics, FontMetrics titleMetrics, int fontSize) {
        int y = top + margin;
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.min(72, fontSize + Math.max(3, fontSize / 8))));
        graphics.setColor(new Color(26, 32, 40));
        y = drawLines(graphics, card.titleLines(), margin, y + titleMetrics.getAscent(), titleMetrics,
                card.titleLineHeight());
        y -= titleMetrics.getAscent();
        y += Math.max(8, fontSize / 5);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        graphics.setColor(new Color(35, 39, 45));
        for (InfoRow row : card.rows()) {
            if (card.pairedColumns()) {
                int lineCount = Math.max(fieldLineCount(row.left()), fieldLineCount(row.right()));
                for (int index = 0; index < lineCount; index++) {
                    int baseline = y + bodyMetrics.getAscent();
                    drawFieldPart(graphics, row.left(), index, margin, card.leftValueX(), baseline);
                    drawFieldPart(graphics, row.right(), index, card.rightLabelX(),
                            card.rightLabelX() + card.rightLabelWidth() + card.valueGap(),
                            baseline);
                    y += card.bodyLineHeight();
                }
            } else {
                y = drawStackedField(graphics, row.left(), margin, card.leftValueX(), y,
                        card.bodyLineHeight(), bodyMetrics);
                if (row.right() != null) {
                    y = drawStackedField(graphics, row.right(), margin, card.leftValueX(), y,
                            card.bodyLineHeight(), bodyMetrics);
                }
            }
            y += card.rowGap();
        }
    }

    private static int drawStackedField(Graphics2D graphics, InfoFieldLines field, int labelX, int valueX,
                                        int y, int lineHeight, FontMetrics metrics) {
        if (field == null) return y;
        int count = Math.max(field.labelLines().size(), field.valueLines().size());
        for (int index = 0; index < count; index++) {
            int baseline = y + metrics.getAscent();
            if (index < field.labelLines().size()) graphics.drawString(field.labelLines().get(index), labelX, baseline);
            if (index < field.valueLines().size()) graphics.drawString(field.valueLines().get(index), valueX, baseline);
            y += lineHeight;
        }
        return y;
    }

    private static void drawFieldPart(Graphics2D graphics, InfoFieldLines field, int index,
                                      int labelX, int valueX, int baseline) {
        if (field == null) return;
        if (index < field.labelLines().size()) graphics.drawString(field.labelLines().get(index), labelX, baseline);
        if (index < field.valueLines().size()) {
            graphics.drawString(field.valueLines().get(index), valueX, baseline);
        }
    }

    private static int rowLineCount(InfoRow row, boolean pairedColumns) {
        int left = fieldLineCount(row.left());
        int right = fieldLineCount(row.right());
        return pairedColumns ? Math.max(left, right) : left + right;
    }

    private static int fieldLineCount(InfoFieldLines field) {
        return field == null ? 0 : Math.max(field.labelLines().size(), field.valueLines().size());
    }

    private static int drawLines(Graphics2D graphics, List<String> lines, int x, int baseline,
                                 FontMetrics metrics, int lineHeight) {
        for (String line : lines) {
            graphics.drawString(line, x, baseline);
            baseline += lineHeight;
        }
        return baseline;
    }

    private static InfoFieldLines field(String label, String value, int labelWidth, int valueWidth, FontMetrics metrics) {
        return new InfoFieldLines(wrap(label + "：", metrics, Math.max(1, labelWidth)),
                wrap(value == null ? "" : value, metrics, Math.max(1, valueWidth)));
    }

    private static int maxLabelWidth(String[][] pairs, int side, FontMetrics metrics) {
        int maximum = 0;
        for (String[] pair : pairs) maximum = Math.max(maximum, metrics.stringWidth(pair[side] + "："));
        return maximum;
    }

    private static List<String> wrap(String value, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            if (!current.isEmpty() && metrics.stringWidth(current + unit) > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(unit);
            offset += Character.charCount(codePoint);
        }
        if (!current.isEmpty() || lines.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = probe.createGraphics();
        try { return graphics.getFontMetrics(font); }
        finally { graphics.dispose(); }
    }

    private record InfoFieldLines(List<String> labelLines, List<String> valueLines) {}
    private record InfoRow(InfoFieldLines left, InfoFieldLines right) {}

    private record CardLayout(List<String> titleLines, List<InfoRow> rows, int height,
                              int titleLineHeight, int bodyLineHeight, int rowGap, boolean pairedColumns,
                              int leftValueX, int rightLabelX, int rightLabelWidth,
                              int stackedLabelWidth, int valueGap) {}
}
