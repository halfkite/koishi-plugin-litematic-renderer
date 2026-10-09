package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    @Test
    void usesTheCompleteMinecraftRegistryAndIdentifiesBlocksWithoutItems() {
        assertEquals(1_286, LitematicMaterials.minecraftBlockRegistrySize());
        assertEquals(1_658, LitematicMaterials.minecraftItemRegistrySize());
        assertEquals("minecraft:oak_sign", LitematicMaterials.blockItemId("minecraft:oak_wall_sign"));
        assertEquals("minecraft:oak_hanging_sign", LitematicMaterials.blockItemId("minecraft:oak_wall_hanging_sign"));
        assertEquals("minecraft:white_banner", LitematicMaterials.blockItemId("minecraft:white_wall_banner"));
        assertEquals("minecraft:redstone_torch", LitematicMaterials.blockItemId("minecraft:redstone_wall_torch"));
        assertEquals("minecraft:iron_chain", LitematicMaterials.blockItemId("minecraft:chain"));
        assertEquals("minecraft:iron_chain", LitematicMaterials.blockItemId("minecraft:iron_chain"));
        assertEquals("minecraft:melon_seeds", LitematicMaterials.blockItemId("minecraft:attached_melon_stem"));
        assertEquals("minecraft:water_bucket", LitematicMaterials.blockItemId("minecraft:bubble_column"));
        assertEquals(List.of(
                new LitematicMaterials.ItemPart("minecraft:flower_pot", 1),
                new LitematicMaterials.ItemPart("minecraft:azalea", 1)),
                LitematicMaterials.blockItemParts("minecraft:potted_azalea_bush"));
        assertEquals(List.of(
                new LitematicMaterials.ItemPart("minecraft:cake", 1),
                new LitematicMaterials.ItemPart("minecraft:black_candle", 1)),
                LitematicMaterials.blockItemParts("minecraft:black_candle_cake"));
        assertNull(LitematicMaterials.blockItemId("minecraft:fire"));
        assertEquals("modded:custom_block", LitematicMaterials.blockItemId("modded:custom_block"));
    }

    @Test
    void everyMinecraftBlockResolvesOnlyToRegisteredItemsOrAnExplicitNonItemState() {
        long unresolved = 0;
        for (String blockId : LitematicMaterials.minecraftBlockIds()) {
            List<LitematicMaterials.ItemPart> parts = LitematicMaterials.blockItemParts(blockId);
            if (parts.isEmpty()) {
                unresolved++;
                continue;
            }
            for (LitematicMaterials.ItemPart part : parts) {
                assertTrue(LitematicMaterials.isMinecraftItem(part.id()), blockId + " -> " + part.id());
            }
        }
        assertEquals(11, unresolved);
    }

    @Test
    void countsPlaceableItemsAndReportsWallSignsWaterAndLavaBlockStates() throws Exception {
        LitematicMaterials.Report report = LitematicMaterials.analyze(variantFixture());
        Map<String, Long> blocks = report.blocks().stream().collect(Collectors.toMap(
                LitematicMaterials.Material::id, LitematicMaterials.Material::count));
        Map<String, Long> waterlogged = report.waterloggedBlocks().stream().collect(Collectors.toMap(
                LitematicMaterials.Material::id, LitematicMaterials.Material::count));

        assertEquals(Map.of(
                "minecraft:redstone_torch", 2L,
                "minecraft:soul_torch", 2L,
                "minecraft:torch", 2L,
                "minecraft:oak_sign", 2L,
                "minecraft:oak_hanging_sign", 2L,
                "minecraft:white_banner", 2L,
                "minecraft:oak_stairs", 1L,
                "minecraft:cauldron", 1L,
                "minecraft:water_bucket", 1L), blocks);
        assertEquals(Map.of("minecraft:oak_stairs", 1L), waterlogged);
        assertEquals(1, report.waterBlockCount());
        assertEquals(2, report.waterContainingBlockCount());
        assertEquals(1, report.lavaCauldronCount());
    }

    @Test
    void tracksTechnicalBlocksSeparatelyInsteadOfExportingThemAsItems() throws Exception {
        LitematicMaterials.Report report = LitematicMaterials.analyze(singleBlockFixture("minecraft:fire"));
        assertTrue(report.blocks().isEmpty());
        assertEquals(List.of(new LitematicMaterials.Material("minecraft:fire", 1)), report.nonItemBlocks());
    }

    @Test
    void expandsPottedPlantsAndCandleCakesIntoTheirCraftableItems() throws Exception {
        LitematicMaterials.Report potted = LitematicMaterials.analyze(singleBlockFixture("minecraft:potted_azalea_bush"));
        assertEquals(Map.of("minecraft:flower_pot", 1L, "minecraft:azalea", 1L), toCountMap(potted.blocks()));

        LitematicMaterials.Report candleCake = LitematicMaterials.analyze(singleBlockFixture("minecraft:black_candle_cake"));
        assertEquals(Map.of("minecraft:cake", 1L, "minecraft:black_candle", 1L), toCountMap(candleCake.blocks()));
    }

    @Test
    void mergesLegacyAndRenamedChainIdsButTracksTheirCountsSeparately() throws Exception {
        LitematicMaterials.Report report = LitematicMaterials.analyze(chainRenameFixture());

        assertEquals(List.of(new LitematicMaterials.Material("minecraft:iron_chain", 2)), report.blocks());
        assertEquals(List.of(new LitematicMaterials.Material("minecraft:iron_chain", 7)), report.containerItems());
        assertEquals(1, report.oldChainBlocks());
        assertEquals(1, report.newChainBlocks());
        assertEquals(3, report.oldChainContainerItems());
        assertEquals(4, report.newChainContainerItems());
    }

    private static Map<String, Long> toCountMap(List<LitematicMaterials.Material> materials) {
        return materials.stream().collect(Collectors.toMap(LitematicMaterials.Material::id,
                LitematicMaterials.Material::count));
    }

    private static byte[] singleBlockFixture(String blockId) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            compoundStart(out, "");
            compoundStart(out, "Regions");
            compoundStart(out, "main");
            compoundStart(out, "Size");
            intTag(out, "x", 1);
            intTag(out, "y", 1);
            intTag(out, "z", 1);
            end(out);
            listStart(out, "BlockStatePalette", 10, 1);
            paletteEntry(out, blockId);
            longArrayTag(out, "BlockStates", 0);
            end(out);
            end(out);
            end(out);
        }
        return raw.toByteArray();
    }

    private static byte[] chainRenameFixture() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            compoundStart(out, "");
            compoundStart(out, "Regions");
            compoundStart(out, "main");
            compoundStart(out, "Size");
            intTag(out, "x", 2);
            intTag(out, "y", 1);
            intTag(out, "z", 1);
            end(out);
            listStart(out, "BlockStatePalette", 10, 2);
            paletteEntry(out, "minecraft:chain");
            paletteEntry(out, "minecraft:iron_chain");
            longArrayTag(out, "BlockStates", 4L);
            listStart(out, "BlockEntities", 10, 1);
            compoundPayloadStart(out);
            listStart(out, "Items", 10, 2);
            item(out, "minecraft:chain", 3);
            end(out);
            item(out, "minecraft:iron_chain", 4);
            end(out);
            end(out);
            end(out);
            end(out);
            end(out);
        }
        return raw.toByteArray();
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

    private static byte[] variantFixture() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            compoundStart(out, "");
            compoundStart(out, "Regions");
            compoundStart(out, "main");
            compoundStart(out, "Size");
            intTag(out, "x", 15);
            intTag(out, "y", 1);
            intTag(out, "z", 1);
            end(out);
            listStart(out, "BlockStatePalette", 10, 15);
            paletteEntry(out, "minecraft:redstone_wall_torch");
            paletteEntry(out, "minecraft:redstone_torch");
            paletteEntry(out, "minecraft:soul_wall_torch");
            paletteEntry(out, "minecraft:soul_torch");
            paletteEntry(out, "minecraft:wall_torch");
            paletteEntry(out, "minecraft:torch");
            paletteEntry(out, "minecraft:oak_wall_sign");
            paletteEntry(out, "minecraft:oak_sign");
            paletteEntry(out, "minecraft:oak_wall_hanging_sign");
            paletteEntry(out, "minecraft:oak_hanging_sign");
            paletteEntry(out, "minecraft:white_wall_banner");
            paletteEntry(out, "minecraft:white_banner");
            paletteEntryWithWaterlogged(out, "minecraft:oak_stairs");
            paletteEntry(out, "minecraft:lava_cauldron");
            paletteEntry(out, "minecraft:water");
            long packed = 0;
            for (int index = 1; index < 15; index++) packed |= (long) index << (index * 4);
            longArrayTag(out, "BlockStates", packed);
            listStart(out, "BlockEntities", 10, 0);
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

    private static void paletteEntryWithWaterlogged(DataOutputStream out, String name) throws Exception {
        compoundPayloadStart(out);
        stringTag(out, "Name", name);
        compoundStart(out, "Properties");
        stringTag(out, "waterlogged", "true");
        end(out);
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
