package dev.qqbot.standalone;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Litematic {
    record BlockState(String name, Map<String, String> properties) {
        BlockState {
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    record Block(int x, int y, int z, BlockState state) {}

    record BlockEntity(int x, int y, int z, String id, Map<String, Object> data) {
        BlockEntity {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    record Entity(String id, double x, double y, double z, float yaw, float pitch, Map<String, Object> data) {
        Entity {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        double centerX() { return (minX + maxX + 1) / 2.0; }
        double centerY() { return (minY + maxY + 1) / 2.0; }
        double centerZ() { return (minZ + maxZ + 1) / 2.0; }
    }

    private final List<Block> blocks;
    private final List<BlockEntity> blockEntities;
    private final List<Entity> entities;
    private final Bounds bounds;

    private Litematic(List<Block> blocks, List<BlockEntity> blockEntities, List<Entity> entities, Bounds bounds) {
        this.blocks = List.copyOf(blocks);
        this.blockEntities = List.copyOf(blockEntities);
        this.entities = List.copyOf(entities);
        this.bounds = bounds;
    }

    List<Block> blocks() { return blocks; }
    List<BlockEntity> blockEntities() { return blockEntities; }
    List<Entity> entities() { return entities; }
    Bounds bounds() { return bounds; }

    static Litematic read(Path path, int maxBlocks) throws IOException {
        Map<String, Object> root = NbtReader.read(path);
        Map<String, Object> regions = compound(root.get("Regions"));
        if (regions == null || regions.isEmpty()) throw new IOException("Litematic has no regions");

        List<Block> blocks = new ArrayList<>();
        List<BlockEntity> blockEntities = new ArrayList<>();
        List<Entity> entities = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Map.Entry<String, Object> regionEntry : regions.entrySet()) {
            Map<String, Object> region = compound(regionEntry.getValue());
            if (region == null) continue;
            Map<String, Object> position = compound(region.get("Position"));
            Map<String, Object> size = compound(region.get("Size"));
            List<Object> paletteValues = list(region.get("BlockStatePalette"));
            long[] states = region.get("BlockStates") instanceof long[] words ? words : null;
            if (position == null || size == null || paletteValues == null || states == null) continue;

            int px = integer(position.get("x")), py = integer(position.get("y")), pz = integer(position.get("z"));
            int rawX = integer(size.get("x")), rawY = integer(size.get("y")), rawZ = integer(size.get("z"));
            int sx = Math.abs(rawX), sy = Math.abs(rawY), sz = Math.abs(rawZ);
            if (sx == 0 || sy == 0 || sz == 0) continue;
            int ox = rawX < 0 ? px + rawX + 1 : px;
            int oy = rawY < 0 ? py + rawY + 1 : py;
            int oz = rawZ < 0 ? pz + rawZ + 1 : pz;
            if (Boolean.getBoolean("litematic.debugRegions")) {
                System.out.println("Region " + regionEntry.getKey() + ": position=" + px + "," + py + "," + pz
                    + " size=" + rawX + "," + rawY + "," + rawZ + " storageOrigin=" + ox + "," + oy + "," + oz);
            }

            List<Object> tileEntityValues = list(region.get("TileEntities"));
            if (tileEntityValues != null) {
                for (Object tileEntityValue : tileEntityValues) {
                    Map<String, Object> data = compound(tileEntityValue);
                    if (data == null || !(data.get("id") instanceof String id)) continue;
                    Number x = number(data.get("x")), y = number(data.get("y")), z = number(data.get("z"));
                    if (x == null || y == null || z == null) continue;
                    blockEntities.add(new BlockEntity(ox + x.intValue(), oy + y.intValue(), oz + z.intValue(), id, data));
                }
            }

            List<Object> entityValues = list(region.get("Entities"));
            if (entityValues != null) {
                for (Object entityValue : entityValues) {
                    Map<String, Object> data = compound(entityValue);
                    List<Object> entityPosition = data == null ? null : list(data.get("Pos"));
                    if (data == null || !(data.get("id") instanceof String id) || entityPosition == null || entityPosition.size() < 3) continue;
                    Number x = number(entityPosition.get(0)), y = number(entityPosition.get(1)), z = number(entityPosition.get(2));
                    if (x == null || y == null || z == null) continue;
                    List<Object> entityRotation = list(data.get("Rotation"));
                    float yaw = listNumber(entityRotation, 0, 0).floatValue();
                    float pitch = listNumber(entityRotation, 1, 0).floatValue();
                    entities.add(new Entity(id, px + x.doubleValue(), py + y.doubleValue(), pz + z.doubleValue(), yaw, pitch, data));
                }
            }

            List<BlockState> palette = new ArrayList<>(paletteValues.size());
            for (Object paletteValue : paletteValues) palette.add(parseState(compound(paletteValue)));
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            int total = Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
            for (int index = 0; index < total; index++) {
                int paletteIndex = unpack(states, index, bits);
                if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
                BlockState state = palette.get(paletteIndex);
                if (state.name().equals("minecraft:air") || state.name().equals("minecraft:cave_air") || state.name().equals("minecraft:void_air")) continue;
                if (blocks.size() >= maxBlocks) throw new IOException("Non-air block limit exceeded: " + maxBlocks);
                int x = index % sx;
                int z = (index / sx) % sz;
                int y = index / (sx * sz);
                int wx = ox + x, wy = oy + y, wz = oz + z;
                blocks.add(new Block(wx, wy, wz, state));
                minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
                maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
            }
        }
        if (blocks.isEmpty()) throw new IOException("Litematic contains no non-air blocks");
        for (Entity entity : entities) {
            minX = Math.min(minX, (int) Math.floor(entity.x() - 0.8));
            minY = Math.min(minY, (int) Math.floor(entity.y() - 0.1));
            minZ = Math.min(minZ, (int) Math.floor(entity.z() - 0.8));
            maxX = Math.max(maxX, (int) Math.ceil(entity.x() + 0.8) - 1);
            maxY = Math.max(maxY, (int) Math.ceil(entity.y() + 1.4) - 1);
            maxZ = Math.max(maxZ, (int) Math.ceil(entity.z() + 0.8) - 1);
        }
        if (Boolean.getBoolean("litematic.debugEntities")) {
            System.out.println("Block entities: " + blockEntities.size());
            blockEntities.forEach(entity -> System.out.println("  " + entity.id() + " @ " + entity.x() + "," + entity.y() + "," + entity.z()));
            System.out.println("Entities: " + entities.size());
            entities.forEach(entity -> System.out.println("  " + entity.id() + " @ " + entity.x() + "," + entity.y() + "," + entity.z() + " yaw=" + entity.yaw()));
        }
        return new Litematic(blocks, blockEntities, entities, new Bounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static BlockState parseState(Map<String, Object> state) throws IOException {
        if (state == null || !(state.get("Name") instanceof String name)) throw new IOException("Invalid block palette entry");
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, Object> values = compound(state.get("Properties"));
        if (values != null) values.forEach((key, value) -> properties.put(key, String.valueOf(value)));
        return new BlockState(name, properties);
    }

    private static int unpack(long[] words, int index, int bits) {
        long bitIndex = (long) index * bits;
        int word = (int) (bitIndex >>> 6);
        int shift = (int) (bitIndex & 63);
        if (word >= words.length) return 0;
        long mask = (1L << bits) - 1;
        long value = words[word] >>> shift;
        if (shift + bits > 64 && word + 1 < words.length) value |= words[word + 1] << (64 - shift);
        return (int) (value & mask);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> values ? (List<Object>) values : null;
    }

    private static int integer(Object value) throws IOException {
        if (value instanceof Number number) return number.intValue();
        throw new IOException("Expected integer NBT value");
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static Number listNumber(List<Object> values, int index, Number fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        Number number = number(values.get(index));
        return number == null ? fallback : number;
    }
}
