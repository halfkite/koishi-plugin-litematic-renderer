package dev.qqbot.standalone;

import dev.qqbot.standalone.Geometry.BakedModel;
import dev.qqbot.standalone.Geometry.Direction;
import dev.qqbot.standalone.Geometry.Quad;
import dev.qqbot.standalone.Geometry.Vec3;
import dev.qqbot.standalone.Geometry.Vertex;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SoftwareRenderer {
    record Settings(int resolution, int supersampling, double rotation, double slant, double fill,
                    String background, boolean transparentBackground) {}
    private record Position(int x, int y, int z) {}
    private record Projected(double x, double y, double depth, double u, double v) {}
    private record Camera(double yaw, double pitch, double scale, double centerX, double centerY, int size) {}
    private record PendingQuad(Quad quad, double x, double y, double z, int tint, boolean fullBright, double depth) {}
    private record OrthographicView(String[] glyph, double yaw, double pitch) {}

    private static final List<OrthographicView> SIX_FACES = List.of(
        new OrthographicView(new String[]{
            "0000011000000000", "0000011000000000", "0000011000000000", "0000011000000000",
            "0000011111110000", "0000011111110000", "0000011000000000", "0000011000000000",
            "0000011000000000", "0000011000000000", "0000011000000000", "0000011000000000",
            "1111111111111000", "0000000000000000", "0000000000000000", "0000000000000000"
        }, 0, Math.PI / 2),
        new OrthographicView(new String[]{
            "0000000000000000", "1111111111111000", "0000011000000000", "0000011000000000",
            "0000011000000000", "0000011010000000", "0000011011000000", "0000011001100000",
            "0000011000110000", "0000011000000000", "0000011000000000", "0000011000000000",
            "0000011000000000", "0000000000000000", "0000000000000000", "0000000000000000"
        }, Math.PI, -Math.PI / 2),
        new OrthographicView(new String[]{
            "0000011000000000", "0000010000000000", "0111111111110000", "0000100000000000",
            "0001001100000000", "0011001100000000", "0110001100000000", "0111111111110000",
            "0000001100000000", "0001001101100000", "0011001100110000", "0110001100011000",
            "0100011000000000", "0000000000000000", "0000000000000000", "0000000000000000"
        }, -Math.PI / 2, 0),
        new OrthographicView(new String[]{
            "0000001000000000", "1111111111111000", "0000001000000000", "0000001000000000",
            "1111111111110000", "1100100010010000", "1100110110010000", "1101111111010000",
            "1100001000010000", "1111111111110000", "1100001000010000", "1100001000010000",
            "1100001000110000", "0000000000000000", "0000000000000000", "0000000000000000"
        }, 0, 0),
        new OrthographicView(new String[]{
            "0000000000000000", "1111111111111000", "0000010100000000", "0000010100000000",
            "0111111111110000", "0100010100010000", "0100100100010000", "0100100100010000",
            "0101100111010000", "0101000000010000", "0100000000010000", "0111111111110000",
            "0100000000010000", "0000000000000000", "0000000000000000", "0000000000000000"
        }, Math.PI / 2, 0),
        new OrthographicView(new String[]{
            "0000100110000000", "0000100110000000", "0000100110000000", "0000100110000000",
            "1111100110110000", "0000100111100000", "0000100110000000", "0000100110000000",
            "0000100110000000", "0000100110000000", "0111100110001000", "1100100110001000",
            "0000100011111000", "0000000000000000", "0000000000000000", "0000000000000000"
        }, Math.PI, 0)
    );

    private final Litematic schematic;
    private final ModelResolver models;
    private final EntityModelResolver entityModels;
    private final Map<Position, Litematic.BlockState> blocks = new HashMap<>();
    private final Map<Position, Litematic.BlockEntity> blockEntities = new HashMap<>();
    private final Vec3 light = new Vec3(-0.35, 0.86, -0.38).normalized();

    SoftwareRenderer(Litematic schematic, ModelResolver models, EntityModelResolver entityModels) {
        this.schematic = schematic;
        this.models = models;
        this.entityModels = entityModels;
        for (Litematic.Block block : schematic.blocks()) blocks.put(new Position(block.x(), block.y(), block.z()), block.state());
        for (Litematic.BlockEntity entity : schematic.blockEntities()) {
            blockEntities.put(new Position(entity.x(), entity.y(), entity.z()), entity);
        }
    }

    void render(Settings settings, double rotation, Path output) throws IOException {
        BufferedImage image = renderImage(settings, Math.toRadians(rotation), Math.toRadians(settings.slant()));
        ImageIO.write(image, "PNG", output.toFile());
    }

    /**
     * 烘焙一次性的“可见面网格”：把邻居剔除后保留下来的所有 quad 展平为原始数组，
     * 之后 renderBaked 每帧只需做旋转/投影/光栅化，不再遍历全部方块和查询邻居。
     * 交互式预览必须走这条路径——对数百万方块的投影，逐帧全量遍历要数十秒。
     */
    BakedMesh bakeMesh() {
        FloatArray opaquePositions = new FloatArray();
        FloatArray opaqueUvs = new FloatArray();
        List<BufferedImage> opaqueTextures = new ArrayList<>();
        IntArray opaqueTints = new IntArray();
        FloatArray opaqueShades = new FloatArray();
        FloatArray translucentPositions = new FloatArray();
        FloatArray translucentUvs = new FloatArray();
        List<BufferedImage> translucentTextures = new ArrayList<>();
        IntArray translucentTints = new IntArray();
        FloatArray translucentShades = new FloatArray();

        for (Litematic.Block block : schematic.blocks()) {
            BakedModel model = models.resolve(block.state(), blockEntities.get(new Position(block.x(), block.y(), block.z())));
            for (Quad quad : model.quads()) {
                if (hiddenByNeighbor(block, quad.cullFace())) continue;
                int tint = tint(block.state(), quad.tintIndex());
                boolean fullBright = isFullBright(block.state());
                double shade = quadShade(quad, fullBright);
                if (models.isTranslucent(block.state())) {
                    appendQuad(translucentPositions, translucentUvs, translucentTextures, translucentTints, translucentShades,
                        quad, block.x(), block.y(), block.z(), tint, (float) shade);
                } else {
                    appendQuad(opaquePositions, opaqueUvs, opaqueTextures, opaqueTints, opaqueShades,
                        quad, block.x(), block.y(), block.z(), tint, (float) shade);
                }
            }
        }
        for (Litematic.Entity entity : schematic.entities()) {
            BakedModel model = entityModels.resolve(entity);
            for (Quad quad : model.quads()) {
                appendQuad(opaquePositions, opaqueUvs, opaqueTextures, opaqueTints, opaqueShades,
                    quad, entity.x(), entity.y(), entity.z(), 0xffffff, 1.0f);
            }
        }
        return new BakedMesh(
            opaquePositions.toArray(), opaqueUvs.toArray(), opaqueTextures.toArray(new BufferedImage[0]),
            opaqueTints.toArray(), opaqueShades.toArray(),
            translucentPositions.toArray(), translucentUvs.toArray(), translucentTextures.toArray(new BufferedImage[0]),
            translucentTints.toArray(), translucentShades.toArray());
    }

    private double quadShade(Quad quad, boolean fullBright) {
        if (fullBright || !quad.shade()) return 1.0;
        Vec3 first = quad.vertices()[1].position().subtract(quad.vertices()[0].position());
        Vec3 second = quad.vertices()[2].position().subtract(quad.vertices()[0].position());
        return 0.70 + 0.30 * Math.max(0, first.cross(second).normalized().dot(light));
    }

    private static void appendQuad(FloatArray positions, FloatArray uvs, List<BufferedImage> textures,
                                   IntArray tints, FloatArray shades, Quad quad,
                                   double offsetX, double offsetY, double offsetZ, int tint, float shade) {
        for (Vertex vertex : quad.vertices()) {
            Vec3 world = vertex.position().add(offsetX, offsetY, offsetZ);
            positions.add((float) world.x()); positions.add((float) world.y()); positions.add((float) world.z());
            uvs.add((float) vertex.u()); uvs.add((float) vertex.v());
        }
        textures.add(quad.texture());
        tints.add(tint);
        shades.add(shade);
    }

    /** 用烘焙网格渲染一帧（yaw/pitch 为弧度）。半透明面每帧按平均深度排序后绘制。 */
    BufferedImage renderBaked(BakedMesh mesh, int resolution, double yaw, double pitch, double fill) {
        Settings settings = new Settings(resolution, 1, 0, 0, fill, "#000000", true);
        int size = resolution;
        Camera camera = camera(settings, yaw, pitch, size);
        int[] pixels = new int[size * size];
        double[] depth = new double[pixels.length];
        Arrays.fill(depth, Double.NEGATIVE_INFINITY);

        // 不透明面必须写深度：后续半透明面和无深度写的面都依赖它做遮挡测试
        drawBakedLayer(mesh.opaquePositions(), mesh.opaqueUvs(), mesh.opaqueTextures(), mesh.opaqueTints(),
            mesh.opaqueShades(), camera, pixels, depth, size, true);

        int translucentQuads = mesh.translucentTints().length;
        if (translucentQuads > 0) {
            int quads = translucentQuads;
            double[] depths = new double[quads];
            Projected[][] projected = new Projected[quads][];
            for (int quad = 0; quad < quads; quad++) {
                projected[quad] = projectQuad(mesh.translucentPositions(), quad, camera);
                depths[quad] = (projected[quad][0].depth() + projected[quad][1].depth()
                    + projected[quad][2].depth() + projected[quad][3].depth()) / 4.0;
            }
            Integer[] order = new Integer[quads];
            for (int quad = 0; quad < quads; quad++) order[quad] = quad;
            Arrays.sort(order, Comparator.comparingDouble(index -> depths[index]));
            for (Integer index : order) {
                Projected[] vertices = projected[index];
                if (allOffScreen(vertices, size)) continue;
                BufferedImage texture = mesh.translucentTextures()[index];
                int tint = mesh.translucentTints()[index];
                float shade = mesh.translucentShades()[index];
                triangle(vertices[0], vertices[1], vertices[2], texture, shade, tint, pixels, depth, size, false);
                triangle(vertices[0], vertices[2], vertices[3], texture, shade, tint, pixels, depth, size, false);
            }
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        return image;
    }

    private void drawBakedLayer(float[] positions, float[] uvs, BufferedImage[] textures, int[] tints,
                                float[] shades, Camera camera, int[] pixels, double[] depth, int size,
                                boolean writeDepth) {
        int quads = tints.length;
        for (int quad = 0; quad < quads; quad++) {
            Projected[] vertices = projectQuad(positions, quad, camera);
            if (allOffScreen(vertices, size)) continue;
            int baseUv = quad * 8;
            for (int vertex = 0; vertex < 4; vertex++) {
                vertices[vertex] = new Projected(vertices[vertex].x(), vertices[vertex].y(), vertices[vertex].depth(),
                    uvs[baseUv + vertex * 2], uvs[baseUv + vertex * 2 + 1]);
            }
            BufferedImage texture = textures[quad];
            int tint = tints[quad];
            float shade = shades[quad];
            triangle(vertices[0], vertices[1], vertices[2], texture, shade, tint, pixels, depth, size, writeDepth);
            triangle(vertices[0], vertices[2], vertices[3], texture, shade, tint, pixels, depth, size, writeDepth);
        }
    }

    private Projected[] projectQuad(float[] positions, int quad, Camera camera) {
        int base = quad * 12;
        Projected[] vertices = new Projected[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            double[] rotated = rotate(positions[base + vertex * 3], positions[base + vertex * 3 + 1],
                positions[base + vertex * 3 + 2], camera.yaw(), camera.pitch());
            double screenX = camera.size() / 2.0 + (rotated[0] - camera.centerX()) * camera.scale();
            double screenY = camera.size() / 2.0 - (rotated[1] - camera.centerY()) * camera.scale();
            vertices[vertex] = new Projected(screenX, screenY, rotated[2], 0, 0);
        }
        return vertices;
    }

    private static boolean allOffScreen(Projected[] vertices, int size) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Projected vertex : vertices) {
            minX = Math.min(minX, vertex.x()); maxX = Math.max(maxX, vertex.x());
            minY = Math.min(minY, vertex.y()); maxY = Math.max(maxY, vertex.y());
        }
        return maxX < 0 || minX > size || maxY < 0 || minY > size;
    }

    /** 烘焙后的可见面网格，所有数组按 quad 排列：positions 每 quad 12 个 float，uvs 每 quad 8 个 float。 */
    record BakedMesh(float[] opaquePositions, float[] opaqueUvs, BufferedImage[] opaqueTextures,
                     int[] opaqueTints, float[] opaqueShades,
                     float[] translucentPositions, float[] translucentUvs, BufferedImage[] translucentTextures,
                     int[] translucentTints, float[] translucentShades) {}

    private static final class FloatArray {
        private float[] values = new float[1024];
        private int length;
        void add(float value) {
            if (length == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[length++] = value;
        }
        float[] toArray() { return Arrays.copyOf(values, length); }
    }

    private static final class IntArray {
        private int[] values = new int[256];
        private int length;
        void add(int value) {
            if (length == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[length++] = value;
        }
        int[] toArray() { return Arrays.copyOf(values, length); }
    }

    void renderSixFaces(Settings settings, int outputSize, String layout, Path output) throws IOException {
        int size = clamp(outputSize, 128, 4096);
        int gap = Math.max(4, (int) Math.round(size * 0.01));
        boolean vertical = "vertical".equals(layout);
        int columns = vertical ? 2 : 3;
        int rows = vertical ? 3 : 2;
        int tileSize = (size - gap * ((vertical ? rows : columns) + 1)) / (vertical ? rows : columns);
        int width = vertical ? columns * tileSize + gap * (columns + 1) : size;
        int height = vertical ? size : rows * tileSize + gap * (rows + 1);
        int imageType = settings.transparentBackground() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, imageType);
        Graphics2D graphics = target.createGraphics();
        if (!settings.transparentBackground()) {
            graphics.setColor(new Color(parseColor(settings.background()), true));
            graphics.fillRect(0, 0, width, height);
        }
        Settings tileSettings = new Settings(tileSize, settings.supersampling(), settings.rotation(), settings.slant(),
            Math.max(0.90, settings.fill()), settings.background(), settings.transparentBackground());
        for (int index = 0; index < SIX_FACES.size(); index++) {
            OrthographicView view = SIX_FACES.get(index);
            int column = index % columns;
            int row = index / columns;
            int x = gap + column * (tileSize + gap);
            int y = gap + row * (tileSize + gap);
            graphics.drawImage(renderImage(tileSettings, view.yaw(), view.pitch()), x, y, null);
            drawFaceLabel(graphics, view.glyph(), x, y, tileSize, gap);
        }
        graphics.dispose();
        ImageIO.write(target, "PNG", output.toFile());
    }

    BufferedImage renderImage(Settings settings, double yaw, double pitch) {
        int size = Math.multiplyExact(settings.resolution(), settings.supersampling());
        Camera camera = camera(settings, yaw, pitch, size);
        int background = settings.transparentBackground() ? 0 : parseColor(settings.background());
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, background);
        double[] depth = new double[pixels.length];
        Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        List<PendingQuad> translucent = new ArrayList<>();

        for (Litematic.Block block : schematic.blocks()) {
            BakedModel model = models.resolve(block.state(), blockEntities.get(new Position(block.x(), block.y(), block.z())));
            for (Quad quad : model.quads()) {
                if (hiddenByNeighbor(block, quad.cullFace())) continue;
                int tint = tint(block.state(), quad.tintIndex());
                boolean fullBright = isFullBright(block.state());
                if (models.isTranslucent(block.state())) {
                    translucent.add(new PendingQuad(quad, block.x(), block.y(), block.z(), tint, fullBright,
                        quadDepth(quad, block.x(), block.y(), block.z(), camera)));
                } else {
                    drawQuad(quad, block.x(), block.y(), block.z(), tint, fullBright, true, camera, pixels, depth, size);
                }
            }
        }

        for (Litematic.Entity entity : schematic.entities()) {
            BakedModel model = entityModels.resolve(entity);
            for (Quad quad : model.quads()) {
                drawQuad(quad, entity.x(), entity.y(), entity.z(), 0xffffff, false, true, camera, pixels, depth, size);
            }
        }

        translucent.sort(Comparator.comparingDouble(PendingQuad::depth));
        for (PendingQuad pending : translucent) {
            drawQuad(pending.quad(), pending.x(), pending.y(), pending.z(), pending.tint(), pending.fullBright(),
                false, camera, pixels, depth, size);
        }

        BufferedImage highResolution = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        highResolution.setRGB(0, 0, size, size, pixels, 0, size);
        BufferedImage target;
        if (settings.supersampling() == 1) target = highResolution;
        else {
            int imageType = settings.transparentBackground() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            target = new BufferedImage(settings.resolution(), settings.resolution(), imageType);
            Graphics2D graphics = target.createGraphics();
            if (!settings.transparentBackground()) {
                graphics.setColor(new Color(background, true));
                graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(highResolution, 0, 0, target.getWidth(), target.getHeight(), null);
            graphics.dispose();
        }
        return target;
    }

    private static void drawFaceLabel(Graphics2D graphics, String[] glyph, int tileX, int tileY, int tileSize, int gap) {
        int scale = Math.max(1, Math.min(6, tileSize / 80));
        int padding = Math.max(1, scale / 2);
        int x = tileX + Math.max(2, gap / 2);
        int y = tileY + Math.max(2, gap / 2);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(x, y, glyph[0].length() * scale + padding * 2, glyph.length * scale + padding * 2);
        graphics.setColor(Color.WHITE);
        for (int row = 0; row < glyph.length; row++) for (int column = 0; column < glyph[row].length(); column++) {
            if (glyph[row].charAt(column) == '1') {
                graphics.fillRect(x + padding + column * scale, y + padding + row * scale, scale, scale);
            }
        }
    }

    private void drawQuad(Quad quad, double offsetX, double offsetY, double offsetZ, int tint, boolean fullBright,
                          boolean writeDepth,
                          Camera camera, int[] pixels, double[] depth, int size) {
        Projected[] vertices = new Projected[4];
        for (int index = 0; index < 4; index++) {
            Vertex vertex = quad.vertices()[index];
            Vec3 world = vertex.position().add(offsetX, offsetY, offsetZ);
            vertices[index] = project(world, vertex.u(), vertex.v(), camera);
        }
        Vec3 first = quad.vertices()[1].position().subtract(quad.vertices()[0].position());
        Vec3 second = quad.vertices()[2].position().subtract(quad.vertices()[0].position());
        double shade = fullBright || !quad.shade()
            ? 1.0
            : 0.70 + 0.30 * Math.max(0, first.cross(second).normalized().dot(light));
        triangle(vertices[0], vertices[1], vertices[2], quad.texture(), shade, tint, pixels, depth, size, writeDepth);
        triangle(vertices[0], vertices[2], vertices[3], quad.texture(), shade, tint, pixels, depth, size, writeDepth);
    }

    private static double quadDepth(Quad quad, double offsetX, double offsetY, double offsetZ, Camera camera) {
        double total = 0;
        for (Vertex vertex : quad.vertices()) {
            Vec3 world = vertex.position().add(offsetX, offsetY, offsetZ);
            total += rotate(world.x(), world.y(), world.z(), camera.yaw(), camera.pitch())[2];
        }
        return total / quad.vertices().length;
    }

    private Camera camera(Settings settings, double yaw, double pitch, int size) {
        Litematic.Bounds bounds = schematic.bounds();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int xSign : new int[]{0, 1}) for (int ySign : new int[]{0, 1}) for (int zSign : new int[]{0, 1}) {
            double x = xSign == 0 ? bounds.minX() : bounds.maxX() + 1;
            double y = ySign == 0 ? bounds.minY() : bounds.maxY() + 1;
            double z = zSign == 0 ? bounds.minZ() : bounds.maxZ() + 1;
            double[] projected = rotate(x, y, z, yaw, pitch);
            minX = Math.min(minX, projected[0]); maxX = Math.max(maxX, projected[0]);
            minY = Math.min(minY, projected[1]); maxY = Math.max(maxY, projected[1]);
        }
        double span = Math.max(0.001, Math.max(maxX - minX, maxY - minY));
        // 命令行入口已在解析时把 fill 钳制到 [0.1, 0.98]；这里放宽以支持预览的连续缩放
        double scale = size * Math.max(0.05, Math.min(8.0, settings.fill())) / span;
        return new Camera(yaw, pitch, scale, (minX + maxX) / 2, (minY + maxY) / 2, size);
    }

    private static Projected project(Vec3 point, double u, double v, Camera camera) {
        double[] rotated = rotate(point.x(), point.y(), point.z(), camera.yaw(), camera.pitch());
        double screenX = camera.size() / 2.0 + (rotated[0] - camera.centerX()) * camera.scale();
        double screenY = camera.size() / 2.0 - (rotated[1] - camera.centerY()) * camera.scale();
        return new Projected(screenX, screenY, rotated[2], u, v);
    }

    private static double[] rotate(double x, double y, double z, double yaw, double pitch) {
        double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
        double rotatedX = cosYaw * x + sinYaw * z;
        double rotatedZ = -sinYaw * x + cosYaw * z;
        double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
        return new double[]{rotatedX, cosPitch * y - sinPitch * rotatedZ, sinPitch * y + cosPitch * rotatedZ};
    }

    private boolean hiddenByNeighbor(Litematic.Block block, Direction direction) {
        if (direction == null) return false;
        Litematic.BlockState neighbor = blocks.get(new Position(block.x() + direction.x, block.y() + direction.y, block.z() + direction.z));
        return neighbor != null && models.hidesNeighborFace(block.state(), neighbor);
    }

    private static void triangle(Projected a, Projected b, Projected c, BufferedImage texture, double shade, int tint,
                                 int[] pixels, double[] depth, int size, boolean writeDepth) {
        double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
        if (Math.abs(area) < 1e-8) return;
        int minX = clamp((int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))), 0, size - 1);
        int maxX = clamp((int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))), 0, size - 1);
        int minY = clamp((int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))), 0, size - 1);
        int maxY = clamp((int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))), 0, size - 1);
        boolean positive = area > 0;

        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double wa = edge(b.x(), b.y(), c.x(), c.y(), px, py);
                double wb = edge(c.x(), c.y(), a.x(), a.y(), px, py);
                double wc = edge(a.x(), a.y(), b.x(), b.y(), px, py);
                if (positive ? wa < 0 || wb < 0 || wc < 0 : wa > 0 || wb > 0 || wc > 0) continue;
                wa /= area; wb /= area; wc /= area;
                double z = wa * a.depth() + wb * b.depth() + wc * c.depth();
                int offset = y * size + x;
                if (z < depth[offset]) continue;
                double u = wa * a.u() + wb * b.u() + wc * c.u();
                double v = wa * a.v() + wb * b.v() + wc * c.v();
                int sample = sample(texture, u, v);
                int alpha = sample >>> 24;
                if (alpha < 8) continue;
                int color = shade(sample, shade, tint);
                pixels[offset] = alpha >= 250 ? color : blend(color, pixels[offset], alpha);
                if (writeDepth) depth[offset] = z;
            }
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static int sample(BufferedImage texture, double u, double v) {
        int width = texture.getWidth();
        int frameHeight = texture.getHeight() >= width && texture.getHeight() % width == 0 ? width : texture.getHeight();
        double normalizedU = u / 16.0, normalizedV = v / 16.0;
        int x = clamp((int) Math.floor(normalizedU * width), 0, width - 1);
        int y = clamp((int) Math.floor(normalizedV * frameHeight), 0, frameHeight - 1);
        return texture.getRGB(x, y);
    }

    private static int shade(int color, double shade, int tint) {
        int alpha = color >>> 24;
        double tr = ((tint >> 16) & 255) / 255.0, tg = ((tint >> 8) & 255) / 255.0, tb = (tint & 255) / 255.0;
        int red = clamp((int) Math.round(((color >> 16) & 255) * shade * tr), 0, 255);
        int green = clamp((int) Math.round(((color >> 8) & 255) * shade * tg), 0, 255);
        int blue = clamp((int) Math.round((color & 255) * shade * tb), 0, 255);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int tint(Litematic.BlockState state, int tintIndex) {
        if (tintIndex < 0) return 0xffffff;
        String name = state.name();
        if (name.contains("redstone_wire")) {
            int power;
            try { power = Integer.parseInt(state.properties().getOrDefault("power", "0")); } catch (NumberFormatException ignored) { power = 0; }
            double value = power / 15.0;
            int red = clamp((int) (255 * (value * 0.6 + 0.4)), 0, 255);
            int green = clamp((int) (255 * Math.max(0, value * value * 0.7 - 0.5)), 0, 255);
            int blue = clamp((int) (255 * Math.max(0, value * value * 0.6 - 0.7)), 0, 255);
            return (red << 16) | (green << 8) | blue;
        }
        if (name.contains("water") || name.contains("bubble_column")) return 0x3f76e4;
        if (name.contains("leaves") || name.contains("grass") || name.contains("vine")) return 0x75a843;
        return 0xffffff;
    }

    private static boolean isFullBright(Litematic.BlockState state) {
        String name = state.name();
        return (name.contains("repeater") || name.contains("comparator"))
            && state.properties().getOrDefault("powered", "false").equals("true");
    }

    private static int blend(int foreground, int background, int alpha) {
        int backgroundAlpha = background >>> 24;
        int inverse = 255 - alpha;
        int outputAlpha = alpha + backgroundAlpha * inverse / 255;
        if (outputAlpha == 0) return 0;
        int red = (((foreground >> 16) & 255) * alpha * 255
            + ((background >> 16) & 255) * backgroundAlpha * inverse) / (outputAlpha * 255);
        int green = (((foreground >> 8) & 255) * alpha * 255
            + ((background >> 8) & 255) * backgroundAlpha * inverse) / (outputAlpha * 255);
        int blue = ((foreground & 255) * alpha * 255
            + (background & 255) * backgroundAlpha * inverse) / (outputAlpha * 255);
        return outputAlpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int parseColor(String value) {
        try {
            Color color = Color.decode(value);
            return 0xff000000 | color.getRGB() & 0xffffff;
        } catch (NumberFormatException exception) {
            return 0xff000000;
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
