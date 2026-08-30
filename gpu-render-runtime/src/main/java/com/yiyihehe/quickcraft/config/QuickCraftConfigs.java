package com.yiyihehe.quickcraft.config;

/** Headless defaults required by the MIT QuickCraft preview renderer. */
public final class QuickCraftConfigs {
    private QuickCraftConfigs() {}
    public static boolean isLitematica3DPreviewEnabled() { return true; }
    public static boolean shouldReplaceLitematicaPreviewWith3D() { return false; }
    public static boolean shouldAutoDisableShadersFor3DPreview() { return false; }
    public static boolean canAddLitematicaPreviewImages() { return false; }
}
