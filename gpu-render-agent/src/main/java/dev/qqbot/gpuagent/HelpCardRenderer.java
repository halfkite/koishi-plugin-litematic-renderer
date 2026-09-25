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

final class HelpCardRenderer {
    private static final int WIDTH = 1200;
    private static final int MARGIN = 56;
    private static final int CONTENT_WIDTH = WIDTH - MARGIN * 2;
    private static final Color INK = new Color(31, 41, 55);
    private static final Color MUTED = new Color(71, 85, 105);
    private static final Color BLUE = new Color(20, 91, 174);
    private static final Color BORDER = new Color(211, 220, 231);
    private static final Color PANEL = new Color(246, 248, 251);
    private static final String NOTE = "投影编号在缓存保留期间固定。";
    private static final String AUTO_RENDER_NOTE = "群文件自动渲染需要群主开放群内全部消息权限。";

    private HelpCardRenderer() {}

    static String text() {
        return text(new AgentConfig());
    }

    static String text(AgentConfig config) {
        return "投影机器人指令：\n" + intro(config) + "\n" + note(config) + "\n"
                + String.join("\n", commandLines(config));
    }

    static Path create(Path outputDirectory) throws IOException {
        return create(outputDirectory, new AgentConfig());
    }

    static Path create(Path outputDirectory, AgentConfig config) throws IOException {
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 40);
        Font introFont = new Font(Font.SANS_SERIF, Font.BOLD, 27);
        Font commandFont = new Font(Font.SANS_SERIF, Font.BOLD, 27);
        Font noteFont = new Font(Font.SANS_SERIF, Font.BOLD, 25);
        FontMetrics titleMetrics = metrics(titleFont);
        FontMetrics introMetrics = metrics(introFont);
        FontMetrics commandMetrics = metrics(commandFont);
        FontMetrics noteMetrics = metrics(noteFont);
        List<String> introLines = wrap(intro(config), introMetrics, CONTENT_WIDTH - 52);
        List<String> noteLines = wrap(note(config), noteMetrics, CONTENT_WIDTH - 52);
        List<CommandRow> rows = commandLines(config).stream()
                .map(line -> row(line, commandMetrics, CONTENT_WIDTH - 52)).toList();

        int y = MARGIN + titleMetrics.getAscent() + 26;
        y += introLines.size() * (introMetrics.getHeight() + 3) + 15;
        y += noteLines.size() * (noteMetrics.getHeight() + 3) + 24 + 12;
        for (CommandRow row : rows) {
            y += row.lines().size() * (commandMetrics.getHeight() + 3) + 24 + 12;
        }
        int height = y - 12 + MARGIN;

        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, height);
            graphics.setColor(new Color(24, 91, 160));
            graphics.fillRoundRect(MARGIN, MARGIN - 12, 8, 52, 8, 8);
            graphics.setFont(titleFont);
            graphics.setColor(INK);
            graphics.drawString("投影机器人指令：", MARGIN + 24, MARGIN + titleMetrics.getAscent());

            y = MARGIN + titleMetrics.getAscent() + 26;
            graphics.setFont(introFont);
            graphics.setColor(INK);
            y = drawLines(graphics, introLines, MARGIN + 24, y, introMetrics);
            y += 15;

            int noteHeight = noteLines.size() * (noteMetrics.getHeight() + 3) + 24;
            drawPanel(graphics, y, noteHeight, new Color(239, 246, 255));
            graphics.setColor(BLUE);
            graphics.setFont(noteFont);
            drawLines(graphics, noteLines, MARGIN + 38, y + 12 + noteMetrics.getAscent(), noteMetrics);
            y += noteHeight + 12;

            for (CommandRow row : rows) {
                int rowHeight = row.lines().size() * (commandMetrics.getHeight() + 3) + 24;
                drawPanel(graphics, y, rowHeight, PANEL);
                graphics.setColor(new Color(24, 91, 160));
                graphics.fillRoundRect(MARGIN + 14, y + 14, 5, rowHeight - 28, 5, 5);
                graphics.setFont(commandFont);
                graphics.setColor(INK);
                drawLines(graphics, row.lines(), MARGIN + 34, y + 12 + commandMetrics.getAscent(), commandMetrics);
                y += rowHeight + 12;
            }
        } finally {
            graphics.dispose();
        }

        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("help-" + UUID.randomUUID() + ".png");
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("无法生成帮助图片");
        return output;
    }

    static Path createIntroduction(Path outputDirectory, String text) throws IOException {
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 40);
        Font bodyFont = new Font(Font.SANS_SERIF, Font.PLAIN, 28);
        FontMetrics titleMetrics = metrics(titleFont);
        FontMetrics bodyMetrics = metrics(bodyFont);
        List<List<String>> sections = new ArrayList<>();
        for (String paragraph : (text == null ? "" : text).split("\\R")) {
            if (!paragraph.isBlank()) sections.add(wrap(paragraph.trim(), bodyMetrics, CONTENT_WIDTH - 76));
        }
        int height = MARGIN + titleMetrics.getHeight() + 32 + MARGIN;
        for (List<String> section : sections) height += section.size() * (bodyMetrics.getHeight() + 3) + 28 + 14;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, height);
            graphics.setColor(new Color(24, 91, 160));
            graphics.fillRoundRect(MARGIN, MARGIN - 12, 8, 52, 8, 8);
            graphics.setFont(titleFont);
            graphics.setColor(INK);
            graphics.drawString("投影机器人介绍", MARGIN + 24, MARGIN + titleMetrics.getAscent());
            int y = MARGIN + titleMetrics.getHeight() + 20;
            for (List<String> section : sections) {
                int panelHeight = section.size() * (bodyMetrics.getHeight() + 3) + 28;
                drawPanel(graphics, y, panelHeight, PANEL);
                graphics.setFont(bodyFont);
                graphics.setColor(INK);
                drawLines(graphics, section, MARGIN + 24, y + 14 + bodyMetrics.getAscent(), bodyMetrics);
                y += panelHeight + 14;
            }
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("introduction-" + UUID.randomUUID() + ".png");
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("无法生成机器人介绍图片");
        return output;
    }

    private static String name(AgentConfig config, String id) {
        List<String> names = config.commandNames(id);
        return names.isEmpty() ? "" : names.getFirst();
    }

    private static List<String> commandLines(AgentConfig config) {
        List<String> lines = new ArrayList<>();
        if (!config.commandNames("search").isEmpty())
            lines.add("/" + name(config, "search") + " 关键词：显示缓存投影的编号与名称（图片）。");
        if (!config.commandNames("sendProjection").isEmpty()) {
            String send = name(config, "sendProjection");
            lines.add("/" + send + " 123 或 /" + send + "123：按编号发送渲染图和原文件。");
            lines.add("/" + send + " 完整名称：按不含后缀的名称精确匹配；同名请用编号。");
        }
        if (!config.commandNames("sendMaterials").isEmpty()) {
            String materials = name(config, "sendMaterials");
            lines.add("/" + materials + "123：导出该编号的材料表；引用渲染图时可只发 /" + materials + "。");
        }
        if (!config.commandNames("projectionList").isEmpty())
            lines.add("/" + name(config, "projectionList") + "：导出全部缓存投影的 CSV 清单。");
        if (!config.commandNames("moreViews").isEmpty())
            lines.add("/" + name(config, "moreViews") + "123：显示已启用的详细视图按钮。");
        if (!config.commandNames("projectionView").isEmpty())
            lines.add("/" + name(config, "projectionView") + "123 正视图：生成指定的正轴、六面等视图。");
        if (!config.commandNames("mapView").isEmpty())
            lines.add("/" + name(config, "mapView") + "123：生成 Minecraft 地图配色视图。");
        if (!config.commandNames("introduction").isEmpty())
            lines.add("/" + name(config, "introduction") + "：查看机器人介绍。");
        if (!config.commandNames("help").isEmpty())
            lines.add("/" + name(config, "help") + "：显示指令帮助。");
        if (config.automaticRenderingEnabled) lines.add("群内发送 .litematic 投影文件：自动识别并渲染。");
        if (lines.isEmpty()) lines.add("当前没有启用的机器人功能。");
        return List.copyOf(lines);
    }

    private static String intro(AgentConfig config) {
        boolean hasCommands = List.of("search", "sendProjection", "sendMaterials", "projectionList", "help",
                        "introduction", "moreViews", "projectionView", "mapView").stream()
                .anyMatch(id -> !config.commandNames(id).isEmpty());
        if (hasCommands) return "已启用指令可从 / 面板选择，也可 @机器人后输入。";
        return config.automaticRenderingEnabled ? "群内发送投影文件即可自动生成渲染图。" : "当前没有启用机器人功能。";
    }

    private static String note(AgentConfig config) {
        return config.automaticRenderingEnabled ? AUTO_RENDER_NOTE + "投影编号在缓存保留期间固定。" : NOTE;
    }

    private static CommandRow row(String line, FontMetrics metrics, int width) {
        return new CommandRow(wrap(line, metrics, width));
    }

    private static List<String> wrap(String value, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            if (!line.isEmpty() && metrics.stringWidth(line + next) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(next);
            offset += Character.charCount(codePoint);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static int drawLines(Graphics2D graphics, List<String> lines, int x, int baseline, FontMetrics metrics) {
        for (String line : lines) {
            graphics.drawString(line, x, baseline);
            baseline += metrics.getHeight() + 3;
        }
        return baseline;
    }

    private static void drawPanel(Graphics2D graphics, int y, int height, Color fill) {
        graphics.setColor(fill);
        graphics.fillRoundRect(MARGIN, y, CONTENT_WIDTH, height, 14, 14);
        graphics.setColor(BORDER);
        graphics.drawRoundRect(MARGIN, y, CONTENT_WIDTH, height, 14, 14);
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = probe.createGraphics();
        try {
            return graphics.getFontMetrics(font);
        } finally {
            graphics.dispose();
        }
    }

    private record CommandRow(List<String> lines) {}
}
