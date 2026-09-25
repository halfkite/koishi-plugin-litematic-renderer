package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(targets = "fi.dy.masa.litematica.schematic.conversion.SchematicConversionMaps", remap = false)
public abstract class LitematicaBlockEntityConversionMixin {
    @Inject(method = "updateBlockEntity(Lfi/dy/masa/malilib/util/data/tag/CompoundData;I)Lfi/dy/masa/malilib/util/data/tag/CompoundData;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void gpuRuntime$skipInvisibleContainerData(CompoundData data, int dataVersion,
                                                                 CallbackInfoReturnable<CompoundData> callback) {
        if (!Boolean.getBoolean("gpu.render.agent") || data == null) return;
        String id = data.getStringOrDefault("id", "").toLowerCase(Locale.ROOT);
        // Inventories and processing state do not affect the exterior of these blocks.
        if (id.endsWith(":hopper") || id.endsWith(":furnace") || id.endsWith(":blast_furnace")
                || id.endsWith(":smoker") || id.endsWith(":dropper") || id.endsWith(":dispenser")
                || id.endsWith(":crafter") || id.endsWith(":brewing_stand") || id.endsWith(":barrel")) {
            callback.setReturnValue(data);
        }
    }
}
