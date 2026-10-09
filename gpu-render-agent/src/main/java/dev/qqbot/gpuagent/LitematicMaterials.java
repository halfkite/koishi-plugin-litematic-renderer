package dev.qqbot.gpuagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Counts placed block items and item stacks stored in block-entity inventories. */
final class LitematicMaterials {
    private static final int MAX_LIST_LENGTH = 10_000_000;
    private static final int MAX_LONG_ARRAY_LENGTH = 32_000_000;
    private static final MinecraftRegistry MINECRAFT_REGISTRY = loadMinecraftRegistry();

    private LitematicMaterials() {}

    record Material(String id, long count) {}
    record ItemPart(String id, long count) {}
    private record MinecraftRegistry(Map<String, String> blockItems, Set<String> items) {}
    record Report(List<Material> blocks, List<Material> containerItems, List<Material> waterloggedBlocks,
                  long waterBlockCount, long lavaCauldronCount, List<Material> nonItemBlocks,
                  long oldChainBlocks, long newChainBlocks,
                  long oldChainContainerItems, long newChainContainerItems) {
        Report(List<Material> blocks, List<Material> containerItems) {
            this(blocks, containerItems, List.of(), 0, 0, List.of(), 0, 0, 0, 0);
        }

        Report(List<Material> blocks, List<Material> containerItems, List<Material> waterloggedBlocks,
               long waterBlockCount, long lavaCauldronCount) {
            this(blocks, containerItems, waterloggedBlocks, waterBlockCount, lavaCauldronCount, List.of(), 0, 0, 0, 0);
        }

        Report(List<Material> blocks, List<Material> containerItems, List<Material> waterloggedBlocks,
               long waterBlockCount, long lavaCauldronCount, List<Material> nonItemBlocks) {
            this(blocks, containerItems, waterloggedBlocks, waterBlockCount, lavaCauldronCount, nonItemBlocks,
                    0, 0, 0, 0);
        }

        long waterContainingBlockCount() {
            return waterBlockCount + waterloggedBlocks.stream().mapToLong(Material::count).sum();
        }
    }

    private record BlockState(String id, boolean waterlogged) {}

    private static final class Counts {
        final Map<String, Long> blocks = new HashMap<>();
        final Map<String, Long> containerItems = new HashMap<>();
        final Map<String, Long> waterloggedBlocks = new HashMap<>();
        final Map<String, Long> nonItemBlocks = new HashMap<>();
        long waterBlockCount;
        long lavaCauldronCount;
        long oldChainBlocks;
        long newChainBlocks;
        long oldChainContainerItems;
        long newChainContainerItems;

        void addBlock(BlockState state, long amount) {
            String id = state.id();
            if (id == null || id.isBlank() || isAir(id) || amount <= 0) return;
            if ("minecraft:chain".equals(id)) oldChainBlocks += amount;
            if ("minecraft:iron_chain".equals(id)) newChainBlocks += amount;

            List<ItemPart> itemParts = blockItemParts(id);
            if (itemParts.isEmpty()) {
                nonItemBlocks.merge(id, amount, Long::sum);
                return;
            }
            for (ItemPart itemPart : itemParts) {
                long itemCount = amount * itemPart.count();
                blocks.merge(itemPart.id(), itemCount, Long::sum);
                if (state.waterlogged()) waterloggedBlocks.merge(itemPart.id(), itemCount, Long::sum);
            }
            if (isWaterBlock(id)) waterBlockCount += amount;
            if ("minecraft:lava_cauldron".equals(id)) lavaCauldronCount += amount;
        }
    }

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
        Counts counts = new Counts();
        try (DataInputStream input = new DataInputStream(decoded)) {
            if (input.readUnsignedByte() != 10) throw new IOException("NBT 根标签不是 Compound");
            readString(input);
            for (;;) {
                int type = input.readUnsignedByte();
                if (type == 0) break;
                String name = readString(input);
                if ("Regions".equals(name) && type == 10) readRegions(input, counts);
                else skipPayload(input, type, 0);
            }
        } catch (EOFException error) {
            throw new IOException("投影 NBT 不完整", error);
        }
        return new Report(sorted(counts.blocks), sorted(counts.containerItems), sorted(counts.waterloggedBlocks),
                counts.waterBlockCount, counts.lavaCauldronCount, sorted(counts.nonItemBlocks),
                counts.oldChainBlocks, counts.newChainBlocks,
                counts.oldChainContainerItems, counts.newChainContainerItems);
    }

    private static void readRegions(DataInputStream input, Counts counts) throws IOException {
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            readString(input);
            if (type == 10) readRegion(input, counts);
            else skipPayload(input, type, 0);
        }
    }

    private static void readRegion(DataInputStream input, Counts counts) throws IOException {
        long[] size = null;
        List<BlockState> palette = null;
        long[] states = null;
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) break;
            String name = readString(input);
            switch (name) {
                case "Size" -> size = type == 10 ? readSize(input) : skipAndReturn(input, type, (long[]) null);
                case "BlockStatePalette" -> palette = type == 9 ? readPalette(input) : skipAndReturn(input, type, (List<BlockState>) null);
                case "BlockStates" -> states = type == 12 ? readLongArray(input) : skipAndReturn(input, type, (long[]) null);
                case "TileEntities", "BlockEntities" -> {
                    if (type == 9) readBlockEntities(input, counts);
                    else skipPayload(input, type, 0);
                }
                default -> skipPayload(input, type, 0);
            }
        }
        countBlocks(size, palette, states, counts);
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

    private static List<BlockState> readPalette(DataInputStream input) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = readLength(input, MAX_LIST_LENGTH, "方块调色板");
        List<BlockState> palette = new ArrayList<>(Math.min(length, 4096));
        for (int index = 0; index < length; index++) {
            if (elementType == 10) palette.add(readPaletteEntry(input));
            else {
                skipPayload(input, elementType, 0);
                palette.add(new BlockState("", false));
            }
        }
        return palette;
    }

    private static BlockState readPaletteEntry(DataInputStream input) throws IOException {
        String name = "";
        boolean waterlogged = false;
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return new BlockState(name, waterlogged);
            String key = readString(input);
            if ("Name".equals(key) && type == 8) name = readString(input);
            else if ("Properties".equals(key) && type == 10) waterlogged = readWaterloggedProperty(input);
            else skipPayload(input, type, 0);
        }
    }

    private static boolean readWaterloggedProperty(DataInputStream input) throws IOException {
        boolean waterlogged = false;
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return waterlogged;
            String name = readString(input);
            if ("waterlogged".equals(name) && type == 8) waterlogged = "true".equalsIgnoreCase(readString(input));
            else skipPayload(input, type, 0);
        }
    }

    private static long[] readLongArray(DataInputStream input) throws IOException {
        int length = readLength(input, MAX_LONG_ARRAY_LENGTH, "方块状态数组");
        long[] values = new long[length];
        for (int index = 0; index < length; index++) values[index] = input.readLong();
        return values;
    }

    private static void countBlocks(long[] rawSize, List<BlockState> palette, long[] states,
                                    Counts counts) throws IOException {
        if (rawSize == null || palette == null || palette.isEmpty()) return;
        long sx = Math.abs(rawSize[0]), sy = Math.abs(rawSize[1]), sz = Math.abs(rawSize[2]);
        if (sx == 0 || sy == 0 || sz == 0) return;
        long total;
        try { total = Math.multiplyExact(Math.multiplyExact(sx, sy), sz); }
        catch (ArithmeticException error) { throw new IOException("投影区域体积超出支持范围", error); }
        int bits = Math.max(2, 64 - Long.numberOfLeadingZeros(Math.max(1, palette.size() - 1L)));
        if (palette.size() == 1 && (states == null || states.length == 0)) {
            counts.addBlock(palette.getFirst(), total);
            return;
        }
        if (states == null) throw new IOException("投影缺少方块状态数组");
        long requiredWords = (Math.multiplyExact(total, bits) + 63) >>> 6;
        if (requiredWords > states.length) throw new IOException("方块状态数组短于投影区域尺寸");
        for (long index = 0; index < total; index++) {
            int paletteIndex = unpack(states, index, bits);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
            counts.addBlock(palette.get(paletteIndex), 1);
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

    static String blockItemId(String blockId) {
        List<ItemPart> parts = blockItemParts(blockId);
        return parts.size() == 1 ? parts.getFirst().id() : null;
    }

    static List<ItemPart> blockItemParts(String blockId) {
        if (blockId == null || blockId.isBlank()) return List.of();
        String path = blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : "";
        String equivalent = switch (path) {
            case "chain", "iron_chain" -> "minecraft:iron_chain";
            case "water", "flowing_water", "bubble_column" -> "minecraft:water_bucket";
            case "lava", "flowing_lava" -> "minecraft:lava_bucket";
            case "water_cauldron", "lava_cauldron", "powder_snow_cauldron" -> "minecraft:cauldron";
            case "attached_melon_stem" -> "minecraft:melon_seeds";
            case "attached_pumpkin_stem" -> "minecraft:pumpkin_seeds";
            case "bamboo_sapling" -> "minecraft:bamboo";
            case "cave_vines_plant" -> "minecraft:glow_berries";
            case "kelp_plant" -> "minecraft:kelp";
            case "tall_seagrass" -> "minecraft:seagrass";
            case "twisting_vines_plant" -> "minecraft:twisting_vines";
            case "weeping_vines_plant" -> "minecraft:weeping_vines";
            default -> null;
        };
        if (equivalent != null) return List.of(new ItemPart(equivalent, 1));

        if (path.equals("candle_cake") || path.endsWith("_candle_cake")) {
            String candle = path.equals("candle_cake") ? "minecraft:candle"
                    : "minecraft:" + path.substring(0, path.length() - "_cake".length());
            return List.of(new ItemPart("minecraft:cake", 1), new ItemPart(candle, 1));
        }
        if (path.startsWith("potted_")) {
            String plant = "minecraft:" + path.substring("potted_".length());
            if ("minecraft:azalea_bush".equals(plant)) plant = "minecraft:azalea";
            if ("minecraft:flowering_azalea_bush".equals(plant)) plant = "minecraft:flowering_azalea";
            if (MINECRAFT_REGISTRY.items().contains(plant)) {
                return List.of(new ItemPart("minecraft:flower_pot", 1), new ItemPart(plant, 1));
            }
        }

        String itemId = MINECRAFT_REGISTRY.blockItems().get(blockId);
        if (itemId == null) {
            if (blockId.startsWith("minecraft:")) return List.of();
            return List.of(new ItemPart(blockId, 1));
        }
        return itemId.isEmpty() ? List.of() : List.of(new ItemPart(itemId, 1));
    }

    static int minecraftBlockRegistrySize() {
        return MINECRAFT_REGISTRY.blockItems().size();
    }

    static int minecraftItemRegistrySize() {
        return MINECRAFT_REGISTRY.items().size();
    }

    static Set<String> minecraftBlockIds() {
        return MINECRAFT_REGISTRY.blockItems().keySet();
    }

    static boolean isMinecraftItem(String id) {
        return MINECRAFT_REGISTRY.items().contains(id);
    }

    private static MinecraftRegistry loadMinecraftRegistry() {
        String resource = "/dev/qqbot/gpuagent/minecraft-26.3-block-item-map.json";
        try (InputStream input = LitematicMaterials.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("缺少 Minecraft 26.3 方块物品映射：" + resource);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject blockJson = json.getAsJsonObject("blocks");
            JsonArray itemJson = json.getAsJsonArray("items");
            if (!"26.3".equals(json.get("minecraftVersion").getAsString()) || blockJson.size() != 1_286) {
                throw new IllegalStateException("Minecraft 26.3 方块注册表清单版本或条目数不匹配");
            }
            Map<String, String> blockItems = new HashMap<>(blockJson.size());
            blockJson.entrySet().forEach(entry -> blockItems.put(entry.getKey(), entry.getValue().getAsString()));
            Set<String> items = new HashSet<>(itemJson.size());
            itemJson.forEach(entry -> items.add(entry.getAsString()));
            for (String itemId : blockItems.values()) {
                if (!itemId.isEmpty() && !items.contains(itemId)) {
                    throw new IllegalStateException("方块映射指向未注册物品：" + itemId);
                }
            }
            return new MinecraftRegistry(Map.copyOf(blockItems), Set.copyOf(items));
        } catch (IOException | RuntimeException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static boolean isWaterBlock(String id) {
        return "minecraft:water".equals(id) || "minecraft:flowing_water".equals(id)
                || "minecraft:bubble_column".equals(id);
    }

    private static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static void readBlockEntities(DataInputStream input, Counts counts) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = readLength(input, MAX_LIST_LENGTH, "方块实体列表");
        for (int index = 0; index < length; index++) {
            if (elementType == 10) readBlockEntity(input, counts, 0);
            else skipPayload(input, elementType, 0);
        }
    }

    private static void readBlockEntity(DataInputStream input, Counts counts, int depth) throws IOException {
        for (;;) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            String name = readString(input);
            if ("Items".equals(name) && type == 9) readItemList(input, counts, depth + 1);
            else skipPayload(input, type, depth + 1);
        }
    }

    private static void readItemList(DataInputStream input, Counts counts, int depth) throws IOException {
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

    private static void collect(ItemStack stack, Counts counts) {
        if (stack.id() != null && !stack.id().isBlank() && stack.count() > 0) {
            String id = stack.id().contains(":") ? stack.id() : "minecraft:" + stack.id();
            if ("minecraft:chain".equals(id)) {
                counts.oldChainContainerItems += stack.count();
                id = "minecraft:iron_chain";
            } else if ("minecraft:iron_chain".equals(id)) {
                counts.newChainContainerItems += stack.count();
            }
            counts.containerItems.merge(id, stack.count(), Long::sum);
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
