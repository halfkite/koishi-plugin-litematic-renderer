package dev.qqbot.gpuagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports projection and aggregated container materials to an Excel workbook. */
final class ProjectionMaterialsExporter {
    private static final int COLUMN_COUNT = 10;
    private static final double[] COLUMN_WIDTHS = {
            14.0, 7.54545454545455, 7.54545454545455, 7.54545454545455, 10.6363636363636,
            14.0, 9.54545454545454, 11.8181818181818, 7.54545454545455, 7.54545454545455
    };

    private ProjectionMaterialsExporter() {}

    static Path create(String projectionName, LitematicMaterials.Report report, Path outputDirectory) throws IOException {
        return create(projectionName, report, outputDirectory, null);
    }

    static Path create(String projectionName, LitematicMaterials.Report report, Path outputDirectory,
                       Path applicationRoot) throws IOException {
        return create(projectionName, report, outputDirectory, applicationRoot, null);
    }

    static Path create(String projectionName, LitematicMaterials.Report report, Path outputDirectory,
                       Path applicationRoot, Path projectionFile) throws IOException {
        Files.createDirectories(outputDirectory);
        String safeName = safeFilename(projectionName);
        Path output = outputDirectory.resolve(safeName + "-materials.xlsx");
        Map<String, String> translations = loadChineseNames(applicationRoot);
        LitematicMetadata.Values metadata = null;
        if (projectionFile != null) {
            try { metadata = LitematicMetadata.parse(projectionFile); }
            catch (IOException ignored) { /* Material analysis can still succeed without optional metadata. */ }
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(row("投影文件名", projectionFile == null ? projectionName : projectionFile.getFileName().toString()));
        rows.add(row("保存者游戏 ID", metadata == null ? "未知" : metadata.author()));
        rows.add(row("创建时间", metadata == null ? "未知" : metadata.createdAt()));
        rows.add(row("方块数", number(metadata == null ? null : metadata.totalBlocks())));
        rows.add(row("体积", number(metadata == null ? null : metadata.totalVolume())));
        rows.add(row("尺寸", size(metadata == null ? null : metadata.size())));
        rows.add(row("Litematic 版本", number(metadata == null ? null : metadata.litematicVersion())));
        rows.add(row("游戏版本", metadata == null ? "未知" : known(metadata.minecraftVersion())));
        rows.add(row("数据版本", number(metadata == null ? null : metadata.minecraftDataVersion())));
        rows.add(row("投影材料种类", Integer.toString(report.blocks().size())));
        rows.add(row("容器内材料种类", Integer.toString(report.containerItems().size())));
        rows.add(emptyRow());
        rows.add(row("投影材料列表", "", "", "", "", "投影容器列表"));
        rows.add(row("物品名称", "物品ID", "总数量", "盒数量", "", "容器名称", "容器物品", "容器物品ID", "总数量", "盒数量"));

        int count = Math.max(report.blocks().size(), report.containerItems().size());
        for (int index = 0; index < count; index++) {
            String[] values = emptyRow();
            if (index < report.blocks().size()) {
                LitematicMaterials.Material block = report.blocks().get(index);
                values[0] = localizedName(block.id(), true, translations);
                values[1] = displayItemId(block.id());
                values[2] = Long.toString(block.count());
                values[3] = boxCount(block.count());
            }
            if (index < report.containerItems().size()) {
                LitematicMaterials.Material item = report.containerItems().get(index);
                values[5] = "容器内材料（汇总）";
                values[6] = localizedName(item.id(), false, translations);
                values[7] = displayItemId(item.id());
                values[8] = Long.toString(item.count());
                values[9] = boxCount(item.count());
            }
            rows.add(values);
        }

        writeWorkbook(output, rows);
        return output;
    }

    private static void writeWorkbook(Path output, List<String[]> rows) throws IOException {
        try (OutputStream file = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>
                    """);
            writeEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            writeEntry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="材料清单" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """);
            writeEntry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """);
            writeEntry(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><name val="宋体"/><family val="2"/></font><font><b/><sz val="11"/><name val="宋体"/><family val="2"/></font></fonts>
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
                    <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                    </styleSheet>
                    """);

            StringBuilder sheet = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <dimension ref="A1:J%s"/><sheetViews><sheetView workbookViewId="0"/></sheetViews>
                    <sheetFormatPr defaultRowHeight="18" defaultColWidth="9.81818181818182"/><cols>
                    """.formatted(rows.size()));
            for (int index = 0; index < COLUMN_COUNT; index++) {
                sheet.append("<col min=\"").append(index + 1).append("\" max=\"").append(index + 1)
                        .append("\" width=\"").append(COLUMN_WIDTHS[index]).append("\" customWidth=\"1\"/>");
            }
            sheet.append("</cols><sheetData>");
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                String[] values = rows.get(rowIndex);
                int excelRow = rowIndex + 1;
                sheet.append("<row r=\"").append(excelRow).append("\">");
                for (int column = 0; column < COLUMN_COUNT; column++) {
                    String value = values[column];
                    if (value == null || value.isEmpty()) continue;
                    String reference = columnName(column) + excelRow;
                    boolean bold = excelRow == 1 || excelRow == 13 || excelRow == 14;
                    sheet.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"")
                            .append(bold ? " s=\"1\"" : "").append("><is><t xml:space=\"preserve\">")
                            .append(escapeXml(value)).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData><mergeCells count=\"1\"><mergeCell ref=\"B1:J1\"/></mergeCells>")
                    .append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>");
            writeEntry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String columnName(int zeroBased) {
        StringBuilder value = new StringBuilder();
        for (int number = zeroBased + 1; number > 0; number = (number - 1) / 26) {
            value.insert(0, (char) ('A' + (number - 1) % 26));
        }
        return value.toString();
    }

    private static String escapeXml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!(codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || codePoint >= 0x20 && codePoint <= 0xD7FF
                    || codePoint >= 0xE000 && codePoint <= 0xFFFD
                    || codePoint >= 0x10000 && codePoint <= 0x10FFFF)) continue;
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.appendCodePoint(codePoint);
            }
        }
        return escaped.toString();
    }

    private static String displayItemId(String id) {
        if (id == null) return "";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    static String safeFilename(String value) {
        String name = displayName(value).replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_")
                .replaceAll("[ .]+$", "");
        if (name.isBlank()) name = "projection";
        if (name.length() > 100) name = name.substring(0, 100);
        if (name.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) name = "_" + name;
        return name;
    }

    private static String[] row(String... values) {
        String[] result = emptyRow();
        System.arraycopy(values, 0, result, 0, Math.min(values.length, result.length));
        return result;
    }

    private static String[] emptyRow() {
        return new String[COLUMN_COUNT];
    }

    private static String known(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private static String number(Long value) {
        return value == null ? "未知" : value.toString();
    }

    private static String boxCount(long total) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(1728), 1, RoundingMode.UP).toPlainString();
    }

    private static String size(int[] dimensions) {
        return dimensions == null ? "未知" : dimensions[0] + " × " + dimensions[1] + " × " + dimensions[2];
    }

    private static String localizedName(String id, boolean block, Map<String, String> names) {
        String value = id == null ? "" : id;
        int separator = value.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : value.substring(0, separator);
        String path = separator < 0 ? value : value.substring(separator + 1);
        String blockKey = "block." + namespace + "." + path;
        String itemKey = "item." + namespace + "." + path;
        String localized = names.get(block ? blockKey : itemKey);
        if (localized == null) localized = names.get(block ? itemKey : blockKey);
        if (localized != null && !localized.isBlank()) return localized;
        return path.replace('_', ' ');
    }

    private static Map<String, String> loadChineseNames(Path applicationRoot) {
        if (applicationRoot == null) return Map.of();
        Path indexes = applicationRoot.resolve("runtime/assets/indexes");
        if (!Files.isDirectory(indexes)) return Map.of();
        try (Stream<Path> files = Files.list(indexes)) {
            Path indexFile = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .max((left, right) -> {
                        try { return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right)); }
                        catch (IOException ignored) { return 0; }
                    }).orElse(null);
            if (indexFile == null) return Map.of();
            JsonObject root = JsonParser.parseString(Files.readString(indexFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject objects = root.has("objects") && root.get("objects").isJsonObject()
                    ? root.getAsJsonObject("objects") : null;
            if (objects == null || !objects.has("minecraft/lang/zh_cn.json")) return Map.of();
            JsonObject languageAsset = objects.getAsJsonObject("minecraft/lang/zh_cn.json");
            String hash = languageAsset.get("hash").getAsString();
            if (!hash.matches("[0-9a-fA-F]{40}")) return Map.of();
            Path languageFile = applicationRoot.resolve("runtime/assets/objects")
                    .resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(languageFile)) return Map.of();
            JsonObject language = JsonParser.parseString(Files.readString(languageFile, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> result = new LinkedHashMap<>();
            language.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), entry.getValue().getAsString());
            });
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String displayName(String value) {
        if (value == null || value.isBlank()) return "unnamed";
        return value.replaceFirst("(?i)\\.litematic$", "");
    }
}
