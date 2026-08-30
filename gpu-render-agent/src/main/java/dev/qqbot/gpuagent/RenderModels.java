package dev.qqbot.gpuagent;

import java.nio.file.Path;
import java.util.List;

final class RenderModels {
    private RenderModels() {}

    record View(String id, String name, double yaw, double pitch, Double zoom, Boolean autoFill,
                int width, int height, String background, boolean transparentBackground, int supersampling) {}

    record Request(int version, String id, String filename, List<View> views, String resourcePackProfile) {}

    record Image(String id, String name, int width, int height, Path path) {}

    record Result(String taskId, List<Image> images, long elapsedMillis, boolean cacheHit, String gpu) {}

    record RuntimeStatus(long timestamp, boolean ready, boolean busy, boolean inWorld,
                         String rendererVersion, String minecraftVersion, String gpu,
                         int maxTextureSize, String resourcePackFingerprint, double progress, String stage) {}

    record RuntimeResult(String id, boolean success, String errorCode, String error,
                         long elapsedMillis, boolean cacheHit, String gpu, List<RuntimeImage> images) {}

    record RuntimeImage(String id, String name, int width, int height, String path) {}

    record TaskHistory(String file, int views, long elapsedMillis, String status, String location, String source) {}

    record TaskMeta(String group, String user) {}
}
