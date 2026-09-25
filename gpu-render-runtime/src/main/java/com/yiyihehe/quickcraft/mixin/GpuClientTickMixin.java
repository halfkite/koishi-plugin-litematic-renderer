package com.yiyihehe.quickcraft.mixin;

import dev.qqbot.gpuruntime.GpuRuntimeClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class GpuClientTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void gpuAgentTick(CallbackInfo callback) {
        GpuRuntimeClient.clientTick((Minecraft) (Object) this);
    }
}
