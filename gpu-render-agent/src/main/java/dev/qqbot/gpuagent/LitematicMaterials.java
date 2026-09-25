package dev.qqbot.gpuagent;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Counts placed block states and item stacks stored in block-entity inventories. */
final class LitematicMaterials {
    private static final int MAX_LIST_LENGTH = 10_000_000;
    private static final int MAX_LONG_ARRAY_LENGTH = 32_000_000;

    private LitematicMaterials() {}

    record Material(String id, long count) {}
    record Report(List<Material> blocks, List<Material> containerItems) {}

    static Report analyze(Path path) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            return analyze(input);
        }
    }

    static Report analyze(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("投影数据为空");
        return analyze(new ByteArrayInputStream(data));
    }

    private static Report analyze(InputStream source) throws IOException {
        source.mark(2);
        int first = source.read();
        int second = source.read();
        source.reset();
        InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(source) : source;
        Map<String, Long> blockCounts = new HashMap<>();
        Map<String, Long> itemCounts = new HashMap<>();
        try (DataInputStream input = new DataInputStream(decoded)) {
            if (input.readUnsignedByte() != 10) throw new IOException("NBT 根标签不是 Compound");
            readString(input);
            for (;;) {
                int type = input.readUnsignedByte();
                if (type == 0) break;
                String name = readString(input);
                if ("Regions".equals(name) && type == 10) readRegions(input, blockCounts, itemCounts);
                else skipPayload(input, type, 0);
            }
        } catch (EOFException error) {
            throw new IOException("投影 NBT 不完整", error);
        }
        return new Report(sorted(blockCounts), sorted(itemCounts));
    }

    private static void readRegions(DataInputStream input, Map<String, Long> blocks,
                                    Map<String, Long> containerItems) throws IOException {
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            readString(input);
            if (type == 10) readRegion(input, blocks, containerItems);
            else skipPayload(input, type, 0);
        }
    }

    private static void readRegion(DataInputStream input, Map<String, Long> blocks,
                                   Map<String, Long> containerItems) throws IOException {
        long[] size = null;
        List<String> palette = null;
        long[] states = null;
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) break;
            String name = readString(input);
            switch (name) {
                case "Size" -> size = type == 10 ? readSize(input) : skipAndReturn(input, type, (long[]) null);
                case "BlockStatePalette" -> palette = type == 9 ? readPalette(input) : skipAndReturn(input, type, (List<String>) null);
                case "BlockStates" -> states = type == 12 ? readLongArray(input) : skipAndReturn(input, type, (long[]) null);
                case "TileEntities", "BlockEntities" -> {
                    if (type == 9) readBlockEntities(input, containerItems);
                    else skipPayload(input, type, 0);
                }
                default -> skipPayload(input, type, 0);
            }
        }
        countBlocks(size, palette, states, blocks);
    }

    private static long[] readSize(DataInputStream input) throws IOException {
        long[] result = new long[3];
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return result;
            String name = readString(input);
            long value = readNumberOrSkip(input, type);
            switch (name) {
                case "x" -> result[0] = value;
                case "y" -> result[1] = value;
                case "z" -> result[2] = value;
                default -> { }
            }
        }
    }

    private static List<String> readPalette(DataInputStream input) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = readLength(input, MAX_LIST_LENGTH, "方块调色板");
        List<String> palette = new ArrayList<>(Math.min(length, 4096));
        for (int index = 0; index < length; index++) {
            if (elementType == 10) palette.add(readPaletteEntry(input));
            else {
                skipPayload(input, elementType, 0);
                palette.add("");
            }
        }
        return palette;
    }

    private static String readPaletteEntry(DataInputStream input) throws IOException {
        String name = "";
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return name;
            String key = readString(input);
            if ("Name".equals(key) && type == 8) name = readString(input);
            else skipPayload(input, type, 0);
        }
    }

    private static long[] readLongArray(DataInputStream input) throws IOException {
        int length = readLength(input, MAX_LONG_ARRAY_LENGTH, "方块状态数组");
        long[] values = new long[length];
        for (int index = 0; index < length; index++) values[index] = input.readLong();
        return values;
    }

    private static void countBlocks(long[] rawSize, List<String> palette, long[] states,
                                    Map<String, Long> counts) throws IOException {
        if (rawSize == null || palette == null || palette.isEmpty()) return;
        long sx = Math.abs(rawSize[0]), sy = Math.abs(rawSize[1]), sz = Math.abs(rawSize[2]);
        if (sx == 0 || sy == 0 || sz == 0) return;
        long total;
        try { total = Math.multiplyExact(Math.multiplyExact(sx, sy), sz); }
        catch (ArithmeticException error) { throw new IOException("投影区域体积超出支持范围", error); }
        int bits = Math.max(2, 64 - Long.numberOfLeadingZeros(Math.max(1, palette.size() - 1L)));
        if (palette.size() == 1 && (states == null || states.length == 0)) {
            addBlock(counts, palette.getFirst(), total);
            return;
        }
        if (states == null) throw new IOException("投影缺少方块状态数组");
        long requiredWords = (Math.multiplyExact(total, bits) + 63) >>> 6;
        if (requiredWords > states.length) throw new IOException("方块状态数组短于投影区域尺寸");
        for (long index = 0; index < total; index++) {
            int paletteIndex = unpack(states, index, bits);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
            addBlock(counts, palette.get(paletteIndex), 1);
        }
    }

    private static int unpack(long[] words, long index, int bits) {
        long bitIndex = index * bits;
        int word = (int) (bitIndex >>> 6);
        int shift = (int) (bitIndex & 63);
        if (word >= words.length) return 0;
        long mask = (1L << bits) - 1;
        long value = words[word] >>> shift;
        if (shift + bits > 64 && word + 1 < words.length) value |= words[word + 1] << (64 - shift);
        return (int) (value & mask);
    }

    private static void addBlock(Map<String, Long> counts, String id, long amount) {
        if (id == null || id.isBlank() || isAir(id) || amount <= 0) return;
        counts.merge(id, amount, Long::sum);
    }

    private static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static void readBlockEntities(DataInputStream input, Map<String, Long> counts) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = readLength(input, MAX_LIST_LENGTH, "方块实体列表");
        for (int index = 0; index < length; index++) {
            if (elementType == 10) readBlockEntity(input, counts, 0);
            else skipPayload(input, elementType, 0);
        }
    }

    private static void readBlockEntity(DataInputStream input, Map<String, Long> counts, int depth) throws IOException {
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            String name = readString(input);
            if ("Items".equals(name) && type == 9) readItemList(input, counts, depth + 1);
            else skipPayload(input, type, depth + 1);
        }
    }

    private static void readItemList(DataInputStream input, Map<String, Long> counts, int depth) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = readLength(input, MAX_LIST_LENGTH, "容器物品列表");
        for (int index = 0; index < length; index++) {
            if (elementType == 10) collect(readItemStack(input, depth + 1), counts);
            else skipPayload(input, elementType, depth + 1);
        }
    }

    private static ItemStack readItemStack(DataInputStream input, int depth) throws IOException {
        if (depth > 32) throw new IOException("容器套娃层级过深");
        String id = "";
        long count = 0;
        List<ItemStack> nested = new ArrayList<>();
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return new ItemStack(id, count, nested);
            String name = readString(input);
            if ("id".equals(name) && type == 8) id = readString(input);
            else if (("Count".equals(name) || "count".equals(name)) && isNumber(type)) count = readNumber(input, type);
            else if ("components".equals(name) && type == 10) readItemComponents(input, nested, depth + 1);
            else if ("item".equals(name) && type == 10) nested.add(readItemStack(input, depth + 1));
            else skipPayload(input, type, depth + 1);
        }
    }

    private static void readItemComponents(DataInputStream input, List<ItemStack> nested, int depth) throws IOException {
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            String name = readString(input);
            if (type == 9 && (name.endsWith(":container") || name.endsWith(":bundle_contents"))) {
                readNestedItemList(input, nested, depth + 1);
            } else skipPayload(input, type, depth + 1);
        }
    }

    private static void readNestedItemList(DataInputStream input, List<ItemStack> nested, int depth) throws IOException {
        if (depth > 32) throw new IOException("容器套娃层级过深");
        int elementType = input.readUnsignedByte();
        int length = readLength(input, MAX_LIST_LENGTH, "嵌套容器物品列表");
        for (int index = 0; index < length; index++) {
            if (elementType == 10) nested.add(readItemStack(input, depth + 1));
            else skipPayload(input, elementType, depth + 1);
        }
    }

    private static void collect(ItemStack stack, Map<String, Long> counts) {
        if (stack.id() != null && !stack.id().isBlank() && stack.count() > 0) {
            String id = stack.id().contains(":") ? stack.id() : "minecraft:" + stack.id();
            counts.merge(id, stack.count(), Long::sum);
        }
        for (ItemStack nested : stack.nested()) collect(nested, counts);
    }

    private static List<Material> sorted(Map<String, Long> values) {
        return values.entrySet().stream().map(entry -> new Material(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(Material::count).reversed().thenComparing(Material::id, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static long readNumberOrSkip(DataInputStream input, int type) throws IOException {
        if (!isNumber(type)) { skipPayload(input, type, 0); return 0; }
        return readNumber(input, type);
    }

    private static boolean isNumber(int type) { return type >= 1 && type <= 6; }

    private static long readNumber(DataInputStream input, int type) throws IOException {
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> (long) input.readFloat();
            case 6 -> (long) input.readDouble();
            default -> throw new IOException("NBT 数值类型无效：" + type);
        };
    }

    private static int readLength(DataInputStream input, int maximum, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) throw new IOException(label + "长度非法：" + length);
        return length;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("NBT 字符串不完整");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skipPayload(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT 嵌套层级过深");
        switch (type) {
            case 0 -> { }
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3, 5 -> input.readInt();
            case 4, 6 -> input.readLong();
            case 7 -> skipFully(input, readLength(input, Integer.MAX_VALUE, "NBT byte array"));
            case 8 -> readString(input);
            case 9 -> {
                int elementType = input.readUnsignedByte();
                int length = readLength(input, MAX_LIST_LENGTH, "NBT list");
                for (int index = 0; index < length; index++) skipPayload(input, elementType, depth + 1);
            }
            case 10 -> {
                for (;;) {
                    int childType = input.readUnsignedByte();
                    if (childType == 0) return;
                    readString(input);
                    skipPayload(input, childType, depth + 1);
                }
            }
            case 11 -> {
                int length = readLength(input, Integer.MAX_VALUE / 4, "NBT int array");
                skipFully(input, (long) length * Integer.BYTES);
            }
            case 12 -> {
                int length = readLength(input, MAX_LONG_ARRAY_LENGTH, "NBT long array");
                skipFully(input, (long) length * Long.BYTES);
            }
            default -> throw new IOException("不支持的 NBT 标签类型：" + type);
        }
    }

    private static void skipFully(DataInputStream input, long bytes) throws IOException {
        while (bytes > 0) {
            int skipped = input.skipBytes((int) Math.min(Integer.MAX_VALUE, bytes));
            if (skipped > 0) { bytes -= skipped; continue; }
            if (input.read() < 0) throw new EOFException("NBT 数据不完整");
            bytes--;
        }
    }

    private static <T> T skipAndReturn(DataInputStream input, int type, T result) throws IOException {
        skipPayload(input, type, 0);
        return result;
    }

    private record ItemStack(String id, long count, List<ItemStack> nested) {}
}
