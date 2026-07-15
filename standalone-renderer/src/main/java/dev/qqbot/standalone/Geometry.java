package dev.qqbot.standalone;

import java.awt.image.BufferedImage;
import java.util.List;

final class Geometry {
    enum Direction {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
        final int x, y, z;
        Direction(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        static Direction parse(String value) {
            if (value == null) return null;
            try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException ignored) { return null; }
        }
    }

    record Vec3(double x, double y, double z) {
        Vec3 add(double dx, double dy, double dz) { return new Vec3(x + dx, y + dy, z + dz); }
        Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        Vec3 cross(Vec3 other) { return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
        double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        Vec3 normalized() {
            double length = Math.sqrt(dot(this));
            return length < 1e-9 ? new Vec3(0, 1, 0) : new Vec3(x / length, y / length, z / length);
        }
    }

    record Vertex(Vec3 position, double u, double v) {}
    record Quad(Vertex[] vertices, BufferedImage texture, Direction cullFace, int tintIndex, boolean shade) {
        Quad(Vertex[] vertices, BufferedImage texture, Direction cullFace, int tintIndex) {
            this(vertices, texture, cullFace, tintIndex, true);
        }
    }
    record BakedModel(List<Quad> quads) {}

    private Geometry() {}
}
