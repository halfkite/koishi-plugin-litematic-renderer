package dev.qqbot.gpuagent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 读取 Litematica 文件中不会影响渲染的投影元数据。 */
final class LitematicMetadata {
    private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.ROOT).withZone(ZoneId.of("Asia/Shanghai"));
    private static final Map<Long, String> MINECRAFT_DATA_VERSIONS = minecraftDataVersions();

    private LitematicMetadata() {}

    record Values(String author, String createdAt, Long totalBlocks, Long totalVolume,
                  int[] size, Long litematicVersion, Long minecraftDataVersion,
                  String minecraftVersion) {}

    static String format(byte[] data, String filename, AgentConfig config) {
        Values values;
        try {
            values = parse(data);
        } catch (Exception error) {
            // 元数据只用于消息展示，损坏或非标准元数据不能阻断已经成功的渲染。
            values = new Values("未知", "未知", null, null, null, null, null, null);
        }
        String projectionName = projectionName(filename);
        if ("compact".equalsIgnoreCase(config.metadataFormat)) return formatCompact(projectionName, values, config);
        List<String> lines = new ArrayList<>();
        if (config.showMetadataProjectionName) lines.add("投影名称：" + projectionName);
        if (config.showMetadataAuthor) lines.add("保存者游戏 ID：" + values.author());
        if (config.showMetadataCreatedAt) lines.add("创建时间：" + values.createdAt());
        if (config.showMetadataBlockStats) lines.add("方块数/体积：" + number(values.totalBlocks()) + "/" + number(values.totalVolume()));
        if (config.showMetadataSize) lines.add("尺寸：" + size(values.size()));
        if (config.showMetadataLitematicVersion) lines.add("Litematic 版本：" + number(values.litematicVersion()));
        if (config.showMetadataGameVersion) {
            String gameVersion = values.minecraftVersion() == null ? "未知" : values.minecraftVersion();
            String dataVersion = values.minecraftDataVersion() == null ? ""
                    : "（数据版本：" + values.minecraftDataVersion() + "）";
            lines.add("游戏版本：" + gameVersion + dataVersion);
        }
        return String.join("\n", lines);
    }

    private static String formatCompact(String projectionName, Values values, AgentConfig config) {
        List<String> lines = new ArrayList<>();
        if (config.showMetadataProjectionName) lines.add(projectionName);

        if (config.showMetadataAuthor || config.showMetadataCreatedAt) {
            if (config.showMetadataAuthor && config.showMetadataCreatedAt) {
                lines.add("[" + values.author() + "]于" + values.createdAt() + " 保存");
            } else if (config.showMetadataAuthor) {
                lines.add("[" + values.author() + "]保存");
            } else {
                lines.add("于" + values.createdAt() + " 保存");
            }
        }

        List<String> structure = new ArrayList<>();
        if (config.showMetadataBlockStats) {
            structure.add("方块数" + number(values.totalBlocks()));
            structure.add("体积" + number(values.totalVolume()));
        }
        if (config.showMetadataSize) structure.add("尺寸" + compactSize(values.size()));
        if (!structure.isEmpty()) lines.add(String.join("-", structure));

        List<String> versions = new ArrayList<>();
        if (config.showMetadataLitematicVersion) versions.add("投影版本" + number(values.litematicVersion()));
        if (config.showMetadataGameVersion) {
            versions.add("游戏版本" + (values.minecraftVersion() == null ? "未知" : values.minecraftVersion()));
            if (values.minecraftDataVersion() != null) versions.add("数据版本" + values.minecraftDataVersion());
        }
        if (!versions.isEmpty()) lines.add(String.join("-", versions));
        return String.join("\n", lines);
    }

    static Values parse(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("投影数据为空");
        try (InputStream source = open(data); DataInputStream input = new DataInputStream(source)) {
            return new Reader(input).readRoot();
        }
    }

    private static InputStream open(byte[] data) throws IOException {
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(new ByteArrayInputStream(data));
        }
        return new ByteArrayInputStream(data);
    }

    private static String projectionName(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.toLowerCase(Locale.ROOT).endsWith(".litematic")) value = value.substring(0, value.length() - 10);
        return value.isBlank() ? "schematic" : value;
    }

    private static String number(Long value) { return value == null ? "未知" : String.valueOf(value); }

    private static String size(int[] value) {
        return value == null ? "未知" : value[0] + " × " + value[1] + " × " + value[2];
    }

    private static String compactSize(int[] value) {
        return value == null ? "未知" : value[0] + "×" + value[1] + "×" + value[2];
    }

    private static String createdAt(Long value) {
        if (value == null || value <= 0) return "未知";
        long milliseconds = value < 10_000_000_000L ? value * 1000L : value;
        try { return CREATED_AT.format(Instant.ofEpochMilli(milliseconds)); }
        catch (RuntimeException ignored) { return "未知"; }
    }

    private static Map<Long, String> minecraftDataVersions() {
        Map<Long, String> values = new HashMap<>();
        values.put(4903L, "26.2"); values.put(4790L, "26.1.2"); values.put(4671L, "1.21.11");
        values.put(4557L, "1.21.10"); values.put(4555L, "1.21.9"); values.put(4440L, "1.21.8");
        values.put(4438L, "1.21.7"); values.put(4435L, "1.21.6"); values.put(4325L, "1.21.5");
        values.put(4189L, "1.21.4"); values.put(4082L, "1.21.3"); values.put(4080L, "1.21.2");
        values.put(3955L, "1.21.1"); values.put(3953L, "1.21"); values.put(3839L, "1.20.6");
        values.put(3837L, "1.20.5"); values.put(3700L, "1.20.4"); values.put(3698L, "1.20.3");
        values.put(3578L, "1.20.2"); values.put(3465L, "1.20.1"); values.put(3463L, "1.20");
        values.put(3337L, "1.19.4"); values.put(3218L, "1.19.3"); values.put(3120L, "1.19.2");
        values.put(3117L, "1.19.1"); values.put(3105L, "1.19"); values.put(2975L, "1.18.2");
        return Map.copyOf(values);
    }

    private static final class Reader {
        private final DataInputStream input;

        private Reader(DataInputStream input) { this.input = input; }

        private Values readRoot() throws IOException {
            if (input.readUnsignedByte() != 10) throw new IOException("不是 NBT Compound 数据");
            readString();
            String author = "未知";
            String createdAt = "未知";
            Long totalBlocks = null, totalVolume = null, litematicVersion = null, dataVersion = null;
            int[] size = null;
            Map<String, Long> enclosingSize = new HashMap<>();
            List<int[]> regionBounds = new ArrayList<>();
            for (;;) {
                int type = input.readUnsignedByte();
                if (type == 0) break;
                String name = readString();
                switch (name) {
                    case "Metadata" -> {
                        MetadataValues metadata = readMetadata(type);
                        if (metadata != null) {
                            author = metadata.author(); createdAt = createdAt(metadata.createdAt());
                            totalBlocks = metadata.totalBlocks(); totalVolume = metadata.totalVolume();
                            enclosingSize = metadata.enclosingSize();
                        }
                    }
                    case "Version" -> litematicVersion = readNumberOrSkip(type);
                    case "MinecraftDataVersion" -> dataVersion = readNumberOrSkip(type);
                    case "Regions" -> regionBounds = readRegions(type);
                    default -> skipPayload(type);
                }
            }
            if (!enclosingSize.isEmpty() && enclosingSize.keySet().containsAll(List.of("x", "y", "z"))) {
                size = new int[] {absoluteInt(enclosingSize.get("x")), absoluteInt(enclosingSize.get("y")), absoluteInt(enclosingSize.get("z"))};
            } else if (!regionBounds.isEmpty()) {
                size = boundsSize(regionBounds);
            }
            return new Values(author, createdAt, totalBlocks, totalVolume, size, litematicVersion, dataVersion,
                    dataVersion == null ? null : MINECRAFT_DATA_VERSIONS.get(dataVersion));
        }

        private MetadataValues readMetadata(int type) throws IOException {
            if (type != 10) { skipPayload(type); return null; }
            String author = "未知"; Long created = null, blocks = null, volume = null;
            Map<String, Long> enclosing = new HashMap<>();
            for (;;) {
                int childType = input.readUnsignedByte();
                if (childType == 0) break;
                String name = input.readUTF();
                switch (name) {
                    case "Author" -> {
                        if (childType == 8) author = readString(); else skipPayload(childType);
                    }
                    case "TimeCreated" -> created = readNumberOrSkip(childType);
                    case "TotalBlocks" -> blocks = readNumberOrSkip(childType);
                    case "TotalVolume" -> volume = readNumberOrSkip(childType);
                    case "EnclosingSize" -> readNumericCompound(childType, enclosing);
                    default -> skipPayload(childType);
                }
            }
            return new MetadataValues(author == null || author.isBlank() ? "未知" : author.trim(), created, blocks, volume, enclosing);
        }

        private List<int[]> readRegions(int type) throws IOException {
            if (type != 10) { skipPayload(type); return List.of(); }
            List<int[]> result = new ArrayList<>();
            for (;;) {
                int childType = input.readUnsignedByte();
                if (childType == 0) break;
                readString();
                if (childType == 10) result.add(readRegion()); else skipPayload(childType);
            }
            return result;
        }

        private int[] readRegion() throws IOException {
            Map<String, Long> position = new HashMap<>(), size = new HashMap<>();
            for (;;) {
                int type = input.readUnsignedByte();
                if (type == 0) break;
                String name = readString();
                if ("Position".equals(name)) readNumericCompound(type, position);
                else if ("Size".equals(name)) readNumericCompound(type, size);
                else skipPayload(type);
            }
            if (!position.keySet().containsAll(List.of("x", "y", "z")) || !size.keySet().containsAll(List.of("x", "y", "z"))) return null;
            long x = position.get("x"), y = position.get("y"), z = position.get("z");
            long sx = size.get("x"), sy = size.get("y"), sz = size.get("z");
            if (sx == 0 || sy == 0 || sz == 0) return null;
            long endX = x + sx - Long.signum(sx), endY = y + sy - Long.signum(sy), endZ = z + sz - Long.signum(sz);
            return new int[] {safeInt(Math.min(x, endX)), safeInt(Math.max(x, endX)), safeInt(Math.min(y, endY)), safeInt(Math.max(y, endY)), safeInt(Math.min(z, endZ)), safeInt(Math.max(z, endZ))};
        }

        private void readNumericCompound(int type, Map<String, Long> target) throws IOException {
            if (type != 10) { skipPayload(type); return; }
            for (;;) {
                int childType = input.readUnsignedByte();
                if (childType == 0) break;
                String name = readString();
                Long value = readNumberOrSkip(childType);
                if (value != null && ("x".equals(name) || "y".equals(name) || "z".equals(name))) target.put(name, value);
            }
        }

        private Long readNumberOrSkip(int type) throws IOException {
            return switch (type) {
                case 1 -> (long) input.readByte();
                case 2 -> (long) input.readShort();
                case 3 -> (long) input.readInt();
                case 4 -> input.readLong();
                case 5 -> (long) input.readFloat();
                case 6 -> (long) input.readDouble();
                default -> { skipPayload(type); yield null; }
            };
        }

        private void skipPayload(int type) throws IOException {
            switch (type) {
                case 0 -> {}
                case 1 -> input.readByte();
                case 2 -> input.readShort();
                case 3, 5 -> input.readInt();
                case 4, 6 -> input.readLong();
                case 7, 11, 12 -> {
                    int count = input.readInt();
                    if (count < 0) throw new IOException("NBT 数组长度非法");
                    long bytes = type == 7 ? count : (long) count * (type == 11 ? 4 : 8);
                    skipFully(bytes);
                }
                case 8 -> readString();
                case 9 -> { int elementType = input.readUnsignedByte(); int count = input.readInt(); if (count < 0) throw new IOException("NBT 列表长度非法"); for (int i = 0; i < count; i++) skipPayload(elementType); }
                case 10 -> { for (;;) { int childType = input.readUnsignedByte(); if (childType == 0) break; readString(); skipPayload(childType); } }
                default -> throw new IOException("不支持的 NBT 标签类型：" + type);
            }
        }

        private void skipFully(long bytes) throws IOException {
            while (bytes > 0) {
                long skipped = input.skip(bytes);
                if (skipped > 0) { bytes -= skipped; continue; }
                if (input.read() < 0) throw new EOFException("NBT 数据不完整");
                bytes--;
            }
        }

        private String readString() throws IOException {
            int length = input.readUnsignedShort();
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new EOFException("NBT 字符串不完整");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private record MetadataValues(String author, Long createdAt, Long totalBlocks, Long totalVolume, Map<String, Long> enclosingSize) {}

    private static int[] boundsSize(List<int[]> bounds) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] value : bounds) {
            if (value == null) continue;
            minX = Math.min(minX, value[0]); maxX = Math.max(maxX, value[1]);
            minY = Math.min(minY, value[2]); maxY = Math.max(maxY, value[3]);
            minZ = Math.min(minZ, value[4]); maxZ = Math.max(maxZ, value[5]);
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new int[] {positiveSize(minX, maxX), positiveSize(minY, maxY), positiveSize(minZ, maxZ)};
    }

    private static int positiveSize(int min, int max) { return max >= min ? max - min + 1 : 0; }
    private static int absoluteInt(Long value) { return value == null ? 0 : safeInt(Math.abs(value)); }
    private static int safeInt(long value) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)); }
}
