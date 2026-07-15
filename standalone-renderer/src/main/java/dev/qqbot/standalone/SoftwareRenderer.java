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

    private final Litematic schematic;
    private final ModelResolver models;
    private final EntityModelResolver entityModels;
    private final Map<Position, Litematic.BlockState> blocks = new HashMap<>();
    private final Vec3 light = new Vec3(-0.35, 0.86, -0.38).normalized();

    SoftwareRenderer(Litematic schematic, ModelResolver models, EntityModelResolver entityModels) {
        this.schematic = schematic;
        this.models = models;
        this.entityModels = entityModels;
        for (Litematic.Block block : schematic.blocks()) blocks.put(new Position(block.x(), block.y(), block.z()), block.state());
    }

    void render(Settings settings, double rotation, Path output) throws IOException {
        int size = Math.multiplyExact(settings.resolution(), settings.supersampling());
        Camera camera = camera(settings, rotation, size);
        int background = settings.transparentBackground() ? 0 : parseColor(settings.background());
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, background);
        double[] depth = new double[pixels.length];
        Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        List<PendingQuad> translucent = new ArrayList<>();

        for (Litematic.Block block : schematic.blocks()) {
            BakedModel model = models.resolve(block.state());
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
        ImageIO.write(target, "PNG", output.toFile());
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

    private Camera camera(Settings settings, double rotation, int size) {
        double yaw = Math.toRadians(rotation);
        double pitch = Math.toRadians(settings.slant());
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
        double scale = size * Math.max(0.1, Math.min(0.98, settings.fill())) / span;
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
