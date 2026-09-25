package com.yiyihehe.quickcraft.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 兼容保留。26.3 的动态缓冲池由原版 FeatureRenderDispatcher 自行管理。
 */
@Mixin(StagedVertexBuffer.class)
public interface LitematicaStagedVertexBufferAccessor {
    @Accessor("stagingBuffer")
    ByteBufferBuilder quickcraft$getStagingBuffer();

    @Accessor("currentVertexBuffer")
    @Nullable GpuBuffer quickcraft$getCurrentVertexBuffer();

    @Accessor("currentIndexBuffer")
    @Nullable GpuBuffer quickcraft$getCurrentIndexBuffer();
}
