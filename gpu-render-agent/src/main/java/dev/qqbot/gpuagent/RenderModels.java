package dev.qqbot.gpuagent;

import java.nio.file.Path;
import java.util.List;

final class RenderModels {
    private RenderModels() {}

    static double brightnessBase(double pitch) {
        return pitch <= -80.0 ? 1.5 : 1.0;
    }

    record View(String id, String name, double yaw, double pitch, Double zoom, Boolean autoFill,
                int width, int height, String background, boolean transparentBackground, int supersampling,
                Double brightness) {
        View(String id, String name, double yaw, double pitch, Double zoom, Boolean autoFill,
             int width, int height, String background, boolean transparentBackground, int supersampling) {
            this(id, name, yaw, pitch, zoom, autoFill, width, height, background, transparentBackground,
                    supersampling, brightnessBase(pitch));
        }

        double brightnessFactor() {
            double value = this.brightness == null ? brightnessBase(this.pitch) : this.brightness;
            return Math.max(0.25, Math.min(3.0, Double.isFinite(value) ? value : 1.0));
        }

        double brightnessPercent() {
            return brightnessFactor() / brightnessBase(this.pitch) * 100.0;
        }
    }

    record Request(int version, String id, String filename, List<View> views, String resourcePackProfile,
                   String pluginVersion, String renderConfigSha256) {
        Request(int version, String id, String filename, List<View> views, String resourcePackProfile) {
            this(version, id, filename, views, resourcePackProfile, "0", null);
        }
        Request(int version, String id, String filename, List<View> views, String resourcePackProfile, String pluginVersion) {
            this(version, id, filename, views, resourcePackProfile, pluginVersion, null);
        }
    }

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
