package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.malilib.gui.Message.MessageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "fi.dy.masa.malilib.util.InfoUtils", remap = false)
public abstract class HeadlessMalilibMessageMixin {
    @Inject(method = "showGuiOrInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;Ljava/lang/String;[Ljava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$suppressGuiOrGameMessage(MessageType type, String key, Object[] args, CallbackInfo ci) {
        suppress(ci);
    }

    @Inject(method = "showGuiOrInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;ILjava/lang/String;[Ljava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$suppressGuiOrGameMessageWithTimeout(MessageType type, int timeout, String key,
                                                                         Object[] args, CallbackInfo ci) {
        suppress(ci);
    }

    @Inject(method = "showInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;Ljava/lang/String;[Ljava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$suppressInGameMessage(MessageType type, String key, Object[] args, CallbackInfo ci) {
        suppress(ci);
    }

    @Inject(method = "showInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;JLjava/lang/String;[Ljava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void quickcraft$suppressInGameMessageWithTimeout(MessageType type, long timeout, String key,
                                                                     Object[] args, CallbackInfo ci) {
        suppress(ci);
    }

    private static void suppress(CallbackInfo ci) {
        if (Boolean.getBoolean("gpu.render.agent")) ci.cancel();
    }
}
