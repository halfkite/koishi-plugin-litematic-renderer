package dev.qqbot.gpuagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Exports the searchable projection cache index as a spreadsheet-friendly CSV. */
final class ProjectionListExporter {
    private ProjectionListExporter() {}

    static Path create(List<ProjectionCacheIndex.Entry> entries, Path outputDirectory) throws IOException {
        if (entries == null || entries.isEmpty()) throw new IOException("缓存中没有可导出的投影");
        Files.createDirectories(outputDirectory);
        String baseName = "BOT投影缓存-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MMdd-HHmm"));
        Path output = outputDirectory.resolve(baseName + ".csv");
        for (int suffix = 2; Files.exists(output); suffix++) {
            output = outputDirectory.resolve(baseName + "-" + suffix + ".csv");
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendRow(csv, "序号", "投影名称", "投影文件名", "哈希值", "相对路径");
        for (ProjectionCacheIndex.Entry entry : entries.stream()
                .sorted(java.util.Comparator.comparingInt(ProjectionCacheIndex.Entry::ordinal)).toList()) {
            appendRow(csv, Integer.toString(entry.ordinal()), entry.displayName(), entry.filename(),
                    entry.fileHash(), entry.relativePath());
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        return output;
    }

    private static void appendRow(StringBuilder csv, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            String value = values[index] == null ? "" : values[index];
            if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
