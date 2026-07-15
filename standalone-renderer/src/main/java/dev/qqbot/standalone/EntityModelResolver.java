package dev.qqbot.standalone;

import dev.qqbot.standalone.Geometry.BakedModel;
import dev.qqbot.standalone.Geometry.Quad;
import dev.qqbot.standalone.Geometry.Vec3;
import dev.qqbot.standalone.Geometry.Vertex;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

final class EntityModelResolver {
    private final ResourcePacks resources;
    private final ModelResolver blockModels;
    private final Map<String, BakedModel> models = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    EntityModelResolver(ResourcePacks resources, ModelResolver blockModels) {
        this.resources = resources;
        this.blockModels = blockModels;
    }

    BakedModel resolve(Litematic.Entity entity) {
        if (entity.id().equals("minecraft:hopper_minecart")) {
            String key = entity.id() + ":" + Float.floatToIntBits(entity.yaw());
            return models.computeIfAbsent(key, ignored -> hopperMinecart(entity.yaw()));
        }
        if (warned.add(entity.id())) System.err.println("Unsupported entity renderer: " + entity.id());
        return new BakedModel(List.of());
    }

    private BakedModel hopperMinecart(float yaw) {
        List<Quad> quads = new ArrayList<>();
        BufferedImage texture = resources.firstTexture("minecraft:entity/minecart/minecart", "minecraft:entity/minecart");
        double entityRotation = Math.toRadians(180.0 - yaw);

        addMinecartPart(quads, texture, 0, 10, -10, -8, -1, 20, 16, 2,
            0, 4, 0, Math.PI / 2, 0, entityRotation);
        addMinecartPart(quads, texture, 0, 0, -8, -9, -1, 16, 8, 2,
            -9, 4, 0, 0, Math.PI * 3 / 2, entityRotation);
        addMinecartPart(quads, texture, 0, 0, -8, -9, -1, 16, 8, 2,
            9, 4, 0, 0, Math.PI / 2, entityRotation);
        addMinecartPart(quads, texture, 0, 0, -8, -9, -1, 16, 8, 2,
            0, 4, -7, 0, Math.PI, entityRotation);
        addMinecartPart(quads, texture, 0, 0, -8, -9, -1, 16, 8, 2,
            0, 4, 7, 0, 0, entityRotation);

        Litematic.BlockState hopperState = new Litematic.BlockState(
            "minecraft:hopper", Map.of("enabled", "true", "facing", "down"));
        for (Quad quad : blockModels.resolve(hopperState).quads()) {
            Vertex[] vertices = new Vertex[quad.vertices().length];
            for (int index = 0; index < vertices.length; index++) {
                Vertex vertex = quad.vertices()[index];
                Vec3 point = rotateY(vertex.position(), Math.PI / 2)
                    .add(-0.5, -7.0 / 16.0, 0.5);
                point = multiply(point, 0.75);
                point = rotateY(point, entityRotation).add(0, 0.375, 0);
                vertices[index] = new Vertex(point, vertex.u(), vertex.v());
            }
            quads.add(new Quad(vertices, quad.texture(), null, quad.tintIndex(), quad.shade()));
        }
        return new BakedModel(List.copyOf(quads));
    }

    private static void addMinecartPart(List<Quad> target, BufferedImage texture, int textureU, int textureV,
                                        double x, double y, double z, double sizeX, double sizeY, double sizeZ,
                                        double pivotX, double pivotY, double pivotZ,
                                        double pitch, double partYaw, double entityYaw) {
        UnaryOperator<Vec3> transform = point -> {
            Vec3 transformed = rotateX(point, pitch);
            transformed = rotateY(transformed, partYaw).add(pivotX, pivotY, pivotZ);
            transformed = new Vec3(-transformed.x() / 16.0, -transformed.y() / 16.0, transformed.z() / 16.0);
            return rotateY(transformed, entityYaw).add(0, 0.375, 0);
        };
        addModelCuboid(target, texture, textureU, textureV, x, y, z, sizeX, sizeY, sizeZ, transform);
    }

    private static void addModelCuboid(List<Quad> target, BufferedImage texture, int textureU, int textureV,
                                       double x, double y, double z, double sizeX, double sizeY, double sizeZ,
                                       UnaryOperator<Vec3> transform) {
        double x2 = x + sizeX, y2 = y + sizeY, z2 = z + sizeZ;
        Vec3 p0 = new Vec3(x, y, z), p1 = new Vec3(x2, y, z);
        Vec3 p2 = new Vec3(x2, y2, z), p3 = new Vec3(x, y2, z);
        Vec3 p4 = new Vec3(x, y, z2), p5 = new Vec3(x2, y, z2);
        Vec3 p6 = new Vec3(x2, y2, z2), p7 = new Vec3(x, y2, z2);

        double u0 = textureU;
        double u1 = u0 + sizeZ;
        double u2 = u1 + sizeX;
        double u3 = u2 + sizeX;
        double u4 = u2 + sizeZ;
        double u5 = u4 + sizeX;
        double v0 = textureV;
        double v1 = v0 + sizeZ;
        double v2 = v1 + sizeY;

        addFace(target, texture, new Vec3[]{p5, p4, p0, p1}, u1, v0, u2, v1, transform);
        addFace(target, texture, new Vec3[]{p2, p3, p7, p6}, u2, v1, u3, v0, transform);
        addFace(target, texture, new Vec3[]{p0, p4, p7, p3}, u0, v1, u1, v2, transform);
        addFace(target, texture, new Vec3[]{p1, p0, p3, p2}, u1, v1, u2, v2, transform);
        addFace(target, texture, new Vec3[]{p5, p1, p2, p6}, u2, v1, u4, v2, transform);
        addFace(target, texture, new Vec3[]{p4, p5, p6, p7}, u4, v1, u5, v2, transform);
    }

    private static void addFace(List<Quad> target, BufferedImage atlas, Vec3[] points,
                                double u1, double v1, double u2, double v2, UnaryOperator<Vec3> transform) {
        double minU = Math.min(u1, u2), maxU = Math.max(u1, u2);
        double minV = Math.min(v1, v2), maxV = Math.max(v1, v2);
        if (maxU - minU < 1e-9 || maxV - minV < 1e-9) return;
        BufferedImage texture = crop(atlas, minU, minV, maxU, maxV, 64, 32);
        double firstU = (u2 - minU) * 16 / (maxU - minU);
        double secondU = (u1 - minU) * 16 / (maxU - minU);
        double firstV = (v1 - minV) * 16 / (maxV - minV);
        double secondV = (v2 - minV) * 16 / (maxV - minV);
        double[] us = {firstU, secondU, secondU, firstU};
        double[] vs = {firstV, firstV, secondV, secondV};
        Vertex[] vertices = new Vertex[4];
        for (int index = 0; index < vertices.length; index++) {
            vertices[index] = new Vertex(transform.apply(points[index]), us[index], vs[index]);
        }
        target.add(new Quad(vertices, texture, null, -1));
    }

    private static BufferedImage crop(BufferedImage source, double x1, double y1, double x2, double y2,
                                      double logicalWidth, double logicalHeight) {
        int sourceX1 = clamp((int) Math.round(x1 / logicalWidth * source.getWidth()), 0, source.getWidth() - 1);
        int sourceY1 = clamp((int) Math.round(y1 / logicalHeight * source.getHeight()), 0, source.getHeight() - 1);
        int sourceX2 = clamp((int) Math.round(x2 / logicalWidth * source.getWidth()), sourceX1 + 1, source.getWidth());
        int sourceY2 = clamp((int) Math.round(y2 / logicalHeight * source.getHeight()), sourceY1 + 1, source.getHeight());
        BufferedImage result = new BufferedImage(sourceX2 - sourceX1, sourceY2 - sourceY1, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = result.createGraphics();
        graphics.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), sourceX1, sourceY1, sourceX2, sourceY2, null);
        graphics.dispose();
        return result;
    }

    private static Vec3 rotateX(Vec3 point, double angle) {
        double sin = Math.sin(angle), cos = Math.cos(angle);
        return new Vec3(point.x(), point.y() * cos - point.z() * sin, point.y() * sin + point.z() * cos);
    }

    private static Vec3 rotateY(Vec3 point, double angle) {
        double sin = Math.sin(angle), cos = Math.cos(angle);
        return new Vec3(point.x() * cos + point.z() * sin, point.y(), -point.x() * sin + point.z() * cos);
    }

    private static Vec3 multiply(Vec3 point, double value) {
        return new Vec3(point.x() * value, point.y() * value, point.z() * value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
