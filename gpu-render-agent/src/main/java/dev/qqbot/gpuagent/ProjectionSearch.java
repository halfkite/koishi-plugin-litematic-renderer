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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Searches the unified cache and creates a numbered contact sheet without rendering again. */
final class ProjectionSearch {
    private static final String ABOUT_NAME = "about.json5";
    private static final int MAX_RESULTS = 100;

    private ProjectionSearch() {}

    static SearchPage search(Path cacheDirectory, String keyword, int limit) throws IOException {
        String query = keyword == null ? "" : keyword.trim();
        int resultLimit = Math.max(1, Math.min(MAX_RESULTS, limit));
        List<SearchEntry> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProjectionCacheIndex.Entry indexed : ProjectionCacheIndex.ensure(cacheDirectory)) {
            String hash = indexed.fileHash().toLowerCase(Locale.ROOT);
            if (!seen.add(hash)) continue;
            SearchEntry entry = readEntry(cacheDirectory.resolve(indexed.relativePath()), indexed, query);
            if (entry != null) candidates.add(entry);
        }
        long sixMonthsAgo = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).minusMonths(6).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();
        Comparator<SearchEntry> byRenderCount = Comparator.comparingLong(SearchEntry::renderCount).reversed()
                .thenComparing(SearchEntry::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SearchEntry::fileHash);
        List<SearchEntry> recent = candidates.stream()
                .filter(entry -> entry.savedAtMillis() >= sixMonthsAgo && entry.savedAtMillis() <= now)
                .sorted(Comparator.comparingLong(SearchEntry::savedAtMillis).reversed().thenComparing(byRenderCount))
                .limit(5).toList();
        Set<String> recentHashes = new HashSet<>();
        recent.forEach(entry -> recentHashes.add(entry.fileHash()));
        List<SearchEntry> ordered = new ArrayList<>(recent);
        candidates.stream().filter(entry -> !recentHashes.contains(entry.fileHash())).sorted(byRenderCount).forEach(ordered::add);
        candidates = ordered;
        if (candidates.size() > resultLimit) candidates = new ArrayList<>(candidates.subList(0, resultLimit));
        return new SearchPage(query, List.copyOf(candidates));
    }

    static SearchEntry findByOrdinal(Path cacheDirectory, int ordinal) throws IOException {
        if (ordinal <= 0) return null;
        for (ProjectionCacheIndex.Entry indexed : ProjectionCacheIndex.ensure(cacheDirectory)) {
            if (indexed.ordinal() == ordinal) {
                return readEntry(cacheDirectory.resolve(indexed.relativePath()), indexed, "");
            }
        }
        return null;
    }

    static List<SearchEntry> findByExactName(Path cacheDirectory, String name) throws IOException {
        if (name == null || name.isBlank()) return List.of();
        String target = stripExtension(name.trim());
        List<SearchEntry> matches = new ArrayList<>(2);
        for (ProjectionCacheIndex.Entry indexed : ProjectionCacheIndex.ensure(cacheDirectory)) {
            if (!target.equals(indexed.displayName())) continue;
            SearchEntry entry = readEntry(cacheDirectory.resolve(indexed.relativePath()), indexed, "");
            if (entry != null && target.equals(entry.displayName())) matches.add(entry);
            if (matches.size() > 1) break;
        }
        return List.copyOf(matches);
    }

    static Path createContactSheet(SearchPage page, Path outputDirectory) throws IOException {
        if (page.entries().isEmpty()) throw new IOException("没有可显示的投影搜索结果");
        int width = 1200;
        int margin = 32;
        int gap = 24;
        int columnWidth = (width - margin * 2 - gap) / 2;
        int nameWidth = columnWidth - 132;
        int titleHeight = 92;
        int lineHeight = 36;
        Font nameFont = new Font(Font.SANS_SERIF, Font.BOLD, 27);
        FontMetrics nameMetrics = metrics(nameFont);
        List<List<String>> wrapped = new ArrayList<>();
        for (SearchEntry entry : page.entries()) {
            wrapped.add(wrapText(entry.displayName(), nameMetrics, nameWidth, Integer.MAX_VALUE));
        }
        int rows = (page.entries().size() + 1) / 2;
        int[] rowHeights = new int[rows];
        int height = titleHeight + margin;
        for (int row = 0; row < rows; row++) {
            int lines = Math.max(wrapped.get(row * 2).size(), row * 2 + 1 < wrapped.size()
                    ? wrapped.get(row * 2 + 1).size() : 0);
            rowHeights[row] = Math.max(74, lines * lineHeight + 28);
            height += rowHeights[row];
        }
        height += margin;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(28, 35, 45));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
            graphics.drawString("投影搜索：" + clip(page.keyword(), 28), margin, 58);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            graphics.drawString("共 " + page.entries().size() + " 个结果", margin, 86);
            int y = titleHeight + margin;
            for (int index = 0; index < page.entries().size(); index++) {
                SearchEntry entry = page.entries().get(index);
                int row = index / 2;
                if (index > 0 && index % 2 == 0) y += rowHeights[row - 1];
                int x = margin + (index % 2) * (columnWidth + gap);
                graphics.setColor(new Color(245, 247, 250));
                graphics.fillRect(x, y, columnWidth, rowHeights[row] - 6);
                graphics.setColor(new Color(218, 224, 232));
                graphics.drawRect(x, y, columnWidth, rowHeights[row] - 6);
                String number = String.valueOf(entry.ordinal());
                graphics.setColor(new Color(27, 111, 208));
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
                graphics.drawString(number + ".", x + 16, y + 39);
                graphics.setColor(new Color(35, 42, 53));
                graphics.setFont(nameFont);
                List<String> nameLines = wrapped.get(index);
                for (int line = 0; line < nameLines.size(); line++) {
                graphics.drawString(nameLines.get(line), x + 116, y + 39 + line * lineHeight);
                }
            }
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("projection-search-" + UUID.randomUUID() + ".png");
        if (!ImageIO.write(sheet, "png", output.toFile())) throw new IOException("无法生成投影搜索图片");
        return output;
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = probe.createGraphics();
        try { return graphics.getFontMetrics(font); }
        finally { graphics.dispose(); }
    }

    private static List<String> wrapText(String value, FontMetrics metrics, int maxWidth, int maxLines) {
        String text = value == null || value.isBlank() ? "schematic" : value;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean truncated = false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            offset += Character.charCount(codePoint);
            if (current.length() > 0 && metrics.stringWidth(current + unit) > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
                if (lines.size() == maxLines) {
                    truncated = true;
                    break;
                }
            }
            current.append(unit);
        }
        if (current.length() > 0 && lines.size() < maxLines) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        if (truncated || lines.size() > maxLines) {
            while (lines.size() > maxLines) lines.remove(lines.size() - 1);
            String last = lines.get(lines.size() - 1);
            while (!last.isEmpty() && metrics.stringWidth(last + "…") > maxWidth) last = last.substring(0, last.length() - 1);
            lines.set(lines.size() - 1, last + "…");
        }
        return lines;
    }

    private static SearchEntry readEntry(Path directory, ProjectionCacheIndex.Entry indexed, String query) {
        try {
            if (!Files.isDirectory(directory)) return null;
            String fileHash = indexed.fileHash();
            Path aboutPath = directory.resolve(ABOUT_NAME);
            com.google.gson.JsonObject about = Files.isRegularFile(aboutPath)
                    ? Protocol.GSON.fromJson(Files.readString(aboutPath), com.google.gson.JsonObject.class) : null;
            String requestedName = indexed.displayName();
            String storedName = indexed.filename();
            if (about != null) {
                String aboutName = string(about, "投影文件名");
                if (!aboutName.isBlank()) requestedName = aboutName;
                String aboutStored = string(about, "存储投影文件名");
                if (!aboutStored.isBlank()) storedName = aboutStored;
            }
            Path projection = storedName.isBlank() ? null : directory.resolve(storedName).normalize();
            if (projection == null || !projection.getParent().equals(directory) || !Files.isRegularFile(projection)) {
                try (var files = Files.list(directory)) {
                    projection = files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                            .findFirst().orElse(null);
                }
            }
            if (projection == null || !Files.isRegularFile(projection)) return null;
            LitematicMetadata.validateNbtRoot(projection);
            String displayName = stripExtension(requestedName.isBlank() ? projection.getFileName().toString() : requestedName);
            if (!query.isBlank() && !displayName.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) return null;
            String originalFilename = requestedName.isBlank() ? projection.getFileName().toString() : requestedName;
            long savedAtMillis = about == null ? 0 : longValue(about, "投影保存时间");
            long renderCount = about != null && about.has("渲染次数")
                    ? longValue(about, "渲染次数") : longValue(about, "缓存调用次数");
            return new SearchEntry(indexed.ordinal(), fileHash.toLowerCase(Locale.ROOT), displayName, originalFilename,
                    renderCount, savedAtMillis, directory, projection);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripExtension(String value) {
        String result = value == null || value.isBlank() ? "schematic" : value;
        return result.toLowerCase(Locale.ROOT).endsWith(".litematic")
                ? result.substring(0, result.length() - ".litematic".length()) : result;
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String string(com.google.gson.JsonObject object, String key) {
        try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    private static long longValue(com.google.gson.JsonObject object, String key) {
        try { return object != null && object.has(key) ? Math.max(0, object.get(key).getAsLong()) : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    record SearchPage(String keyword, List<SearchEntry> entries) {}

    record SearchEntry(int ordinal, String fileHash, String displayName, String originalFilename, long renderCount,
                       long savedAtMillis, Path directory, Path projection) {}
}
