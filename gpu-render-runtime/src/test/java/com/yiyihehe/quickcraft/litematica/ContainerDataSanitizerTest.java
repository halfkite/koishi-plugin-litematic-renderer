package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerDataSanitizerTest {
    @Test
    void removesInventoryAndProcessingStateButKeepsBlockEntityIdentity() {
        CompoundData data = new CompoundData()
                .putString("id", "minecraft:furnace")
                .putString("CustomName", "Furnace bank")
                .putString("Items", "large inventory")
                .putString("LootTable", "minecraft:chests/simple_dungeon")
                .putInt("BurnTime", 200)
                .putInt("CookTime", 100)
                .putInt("CookTimeTotal", 200)
                .putString("RecipesUsed", "many recipes");

        assertTrue(ContainerDataSanitizer.stripInventory(data));

        assertEquals("minecraft:furnace", data.getString("id"));
        assertEquals("Furnace bank", data.getString("CustomName"));
        assertFalse(data.containsLenient("Items"));
        assertFalse(data.containsLenient("LootTable"));
        assertFalse(data.containsLenient("BurnTime"));
        assertFalse(data.containsLenient("CookTime"));
        assertFalse(data.containsLenient("CookTimeTotal"));
        assertFalse(data.containsLenient("RecipesUsed"));
    }

    @Test
    void sanitizesChestHopperAndOtherContainerVariants() {
        for (String id : new String[]{"minecraft:chest", "minecraft:trapped_chest", "minecraft:hopper", "minecraft:barrel"}) {
            CompoundData data = new CompoundData().putString("id", id).putString("Items", "inventory");

            assertTrue(ContainerDataSanitizer.stripInventory(data), id);
            assertFalse(data.containsLenient("Items"), id);
        }
    }

    @Test
    void leavesNonContainerBlockEntitiesUntouched() {
        CompoundData data = new CompoundData().putString("id", "minecraft:sign").putString("Items", "custom data");

        assertFalse(ContainerDataSanitizer.stripInventory(data));
        assertTrue(data.containsLenient("Items"));
    }
}
