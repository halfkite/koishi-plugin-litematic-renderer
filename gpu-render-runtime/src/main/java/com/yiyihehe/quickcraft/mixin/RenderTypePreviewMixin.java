package com.yiyihehe.quickcraft.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.yiyihehe.quickcraft.render.QuickCraftPreviewRenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderType.class)
public abstract class RenderTypePreviewMixin {
    @Redirect(
            method = "prepare",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/rendertype/RenderSetup;pipeline:Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
            )
    )
    private RenderPipeline quickcraft$usePreviewPipeline(RenderSetup setup) {
        RenderPipeline pipeline = ((RenderSetupAccessor) (Object) setup).quickcraft$getPipeline();
        return QuickCraftPreviewRenderPipeline.forPreview(pipeline);
    }
}
