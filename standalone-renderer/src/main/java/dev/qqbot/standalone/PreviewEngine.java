package dev.qqbot.standalone;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互式预览门面：加载一次投影与材质，之后可反复渲染任意 yaw/pitch/zoom 的单帧图像。
 * 供 gpu-render-agent 的 Swing 预览画布使用，与命令行渲染共用同一套软件光栅化实现。
 * 非线程安全：renderFrame 必须由同一个线程（或外部串行化）调用。
 */
public final class PreviewEngine implements AutoCloseable {
    /** zoom=1.0 时画面占画布的比例，与命令行默认 fill 一致。 */
    public static final double DEFAULT_FILL = 0.82;

    private final SoftwareRenderer renderer;
    private final SoftwareRenderer.BakedMesh mesh;
    private final ResourcePacks resources;
    private final int blockCount;
    /** 已渲染帧缓存：来回切换 180°/预设视角时直接命中，无需重新光栅化。 */
    private final Map<Long, BufferedImage> frameCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest) { return size() > 48; }
    };

    private PreviewEngine(SoftwareRenderer renderer, SoftwareRenderer.BakedMesh mesh, ResourcePacks resources, int blockCount) {
        this.renderer = renderer;
        this.mesh = mesh;
        this.resources = resources;
        this.blockCount = blockCount;
    }

    /**
     * 加载投影并烘焙材质与可见面网格（耗时操作，调用方应放在后台线程）。
     * 烘焙耗时约等于软件渲染一帧；之后每帧只需旋转/投影/光栅化，可实现交互式预览。
     *
     * @param minecraftJar  官方客户端 jar，用于内置材质
     * @param resourcePacks 按低到高优先级排列的资源包，可为空
     */
    public static PreviewEngine load(Path litematic, Path minecraftJar, List<Path> resourcePacks) throws IOException {
        if (!Files.isRegularFile(litematic)) throw new IOException("投影文件不存在: " + litematic);
        if (!Files.isRegularFile(minecraftJar)) throw new IOException("Minecraft 客户端 jar 不存在: " + minecraftJar);
        ResourcePacks resources = new ResourcePacks(minecraftJar, resourcePacks == null ? List.of() : resourcePacks);
        try {
            ModelResolver models = new ModelResolver(resources);
            EntityModelResolver entityModels = new EntityModelResolver(resources, models);
            Litematic schematic = Litematic.read(litematic);
            SoftwareRenderer renderer = new SoftwareRenderer(schematic, models, entityModels);
            SoftwareRenderer.BakedMesh mesh = renderer.bakeMesh();
            return new PreviewEngine(renderer, mesh, resources, schematic.blocks().size());
        } catch (IOException | RuntimeException error) {
            try { resources.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    public int blockCount() { return blockCount; }

    /**
     * 渲染一帧 size×size 的 ARGB 预览图（透明背景，由画布决定底色）。
     *
     * @param yawDegrees   水平旋转角（度）
     * @param pitchDegrees 俯仰角（度，正值向下俯视）
     * @param zoom         相对默认取景的缩放倍数，1.0 即默认取景
     */
    public BufferedImage renderFrame(double yawDegrees, double pitchDegrees, double zoom, int size) {
        double fill = Math.max(0.05, Math.min(8.0, DEFAULT_FILL * zoom));
        long key = cacheKey(yawDegrees, pitchDegrees, fill, size);
        synchronized (frameCache) {
            BufferedImage cached = frameCache.get(key);
            if (cached != null) return cached;
        }
        BufferedImage frame = renderer.renderBaked(mesh, size, Math.toRadians(yawDegrees), Math.toRadians(pitchDegrees), fill);
        synchronized (frameCache) { frameCache.put(key, frame); }
        return frame;
    }

    private static long cacheKey(double yawDegrees, double pitchDegrees, double fill, int size) {
        long yaw = Math.floorMod(Math.round(yawDegrees * 4), 1440);
        long pitch = Math.round(pitchDegrees * 4);
        long scaledFill = Math.round(fill * 1000);
        return ((yaw * 721 + pitch) * 262147 + scaledFill) * 4096 + size;
    }

    @Override public void close() throws IOException { resources.close(); }
}
