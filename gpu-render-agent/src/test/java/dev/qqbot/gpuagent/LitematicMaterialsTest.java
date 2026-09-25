package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LitematicMaterialsTest {
    @Test
    void countsPlacedBlocksAndNestedContainerItemsFromGzipNbt() throws Exception {
        LitematicMaterials.Report report = LitematicMaterials.analyze(fixture());

        Map<String, Long> blocks = report.blocks().stream().collect(Collectors.toMap(
                LitematicMaterials.Material::id, LitematicMaterials.Material::count));
        Map<String, Long> items = report.containerItems().stream().collect(Collectors.toMap(
                LitematicMaterials.Material::id, LitematicMaterials.Material::count));
        assertEquals(Map.of("minecraft:stone", 2L, "minecraft:chest", 1L), blocks);
        assertEquals(Map.of("minecraft:diamond", 3L, "minecraft:emerald", 2L), items);
    }

    @Test
    void rejectsNonCompoundNbtRoot() {
        assertThrows(java.io.IOException.class, () -> LitematicMaterials.analyze(new byte[] { 3, 0, 0 }));
    }

    private static byte[] fixture() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            compoundStart(out, "");
            compoundStart(out, "Regions");
            compoundStart(out, "main");
            compoundStart(out, "Size");
            intTag(out, "x", 4);
            intTag(out, "y", 1);
            intTag(out, "z", 1);
            end(out);
            listStart(out, "BlockStatePalette", 10, 3);
            paletteEntry(out, "minecraft:stone");
            paletteEntry(out, "minecraft:air");
            paletteEntry(out, "minecraft:chest");
            longArrayTag(out, "BlockStates", 144L);
            listStart(out, "BlockEntities", 10, 1);
            listStart(out, "Items", 10, 1);
            item(out, "minecraft:diamond", 3);
            compoundStart(out, "components");
            listStart(out, "minecraft:container", 10, 1);
            compoundPayloadStart(out);
            stringTag(out, "id", "minecraft:emerald");
            byteTag(out, "count", 2);
            end(out);
            end(out);
            end(out);
            end(out);
            end(out);
            end(out);
            end(out);
        }
        return raw.toByteArray();
    }

    private static void item(DataOutputStream out, String id, int count) throws Exception {
        compoundPayloadStart(out);
        stringTag(out, "id", id);
        byteTag(out, "Count", count);
    }

    private static void paletteEntry(DataOutputStream out, String name) throws Exception {
        compoundPayloadStart(out);
        stringTag(out, "Name", name);
        end(out);
    }

    private static void compoundStart(DataOutputStream out, String name) throws Exception {
        out.writeByte(10);
        writeString(out, name);
    }

    private static void compoundPayloadStart(DataOutputStream out) {
        // List elements contain the compound payload without a tag header or name.
    }

    private static void listStart(DataOutputStream out, String name, int elementType, int length) throws Exception {
        out.writeByte(9);
        writeString(out, name);
        out.writeByte(elementType);
        out.writeInt(length);
    }

    private static void intTag(DataOutputStream out, String name, int value) throws Exception {
        out.writeByte(3);
        writeString(out, name);
        out.writeInt(value);
    }

    private static void byteTag(DataOutputStream out, String name, int value) throws Exception {
        out.writeByte(1);
        writeString(out, name);
        out.writeByte(value);
    }

    private static void stringTag(DataOutputStream out, String name, String value) throws Exception {
        out.writeByte(8);
        writeString(out, name);
        writeString(out, value);
    }

    private static void longArrayTag(DataOutputStream out, String name, long value) throws Exception {
        out.writeByte(12);
        writeString(out, name);
        out.writeInt(1);
        out.writeLong(value);
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void end(DataOutputStream out) throws Exception { out.writeByte(0); }
}
