package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.data.tag.CompoundData;

import java.util.Locale;
import java.util.Set;

public final class ContainerDataSanitizer {
    private static final Set<String> CONTAINER_TYPES = Set.of(
            "barrel", "blast_furnace", "brewing_stand", "campfire", "chest", "chiseled_bookshelf",
            "crafter", "decorated_pot", "dispenser", "dropper", "ender_chest", "furnace", "hopper",
            "jukebox", "shulker_box", "smoker", "suspicious_gravel", "suspicious_sand", "trapped_chest"
    );

    private ContainerDataSanitizer() {
    }

    public static boolean stripInventory(CompoundData data) {
        if (data == null) return false;
        String id = data.getStringOrDefault("id", "").toLowerCase(Locale.ROOT);
        int separator = id.indexOf(':');
        String path = separator >= 0 ? id.substring(separator + 1) : id;
        if (!CONTAINER_TYPES.contains(path)) return false;

        data.remove("Items");
        data.remove("items");
        data.remove("LootTable");
        data.remove("LootTableSeed");
        data.remove("RecipesUsed");
        data.remove("RecipesUsedSize");
        data.remove("BurnTime");
        data.remove("CookTime");
        data.remove("CookTimeTotal");
        data.remove("BrewTime");
        data.remove("Fuel");
        return true;
    }
}
