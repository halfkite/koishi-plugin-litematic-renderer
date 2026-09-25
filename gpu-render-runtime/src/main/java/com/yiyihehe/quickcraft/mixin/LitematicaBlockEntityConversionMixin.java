package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.yiyihehe.quickcraft.litematica.ContainerDataSanitizer;

@Mixin(targets = "fi.dy.masa.litematica.schematic.conversion.SchematicConversionMaps", remap = false)
public abstract class LitematicaBlockEntityConversionMixin {
    @Inject(method = "updateBlockEntity(Lfi/dy/masa/malilib/util/data/tag/CompoundData;I)Lfi/dy/masa/malilib/util/data/tag/CompoundData;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void gpuRuntime$skipContainerContents(CompoundData data, int dataVersion,
                                                                 CallbackInfoReturnable<CompoundData> callback) {
        if (!Boolean.getBoolean("gpu.render.agent") || data == null) return;
        // Keep block appearance and custom names, but discard bulky inventory and processing data.
        if (ContainerDataSanitizer.stripInventory(data)) {
            callback.setReturnValue(data);
        }
    }
}
