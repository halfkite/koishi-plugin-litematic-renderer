package com.yiyihehe.quickcraft.litematica;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Quad-aligned chunks avoid gigabyte array growth and full-layer copies. */
final class VertexBufferChunks {
    static final int VERTEX_BYTES = 32;
    static final int CHUNK_BYTES = 16 * 1024 * 1024;
    private final int chunkBytes;
    private final long maxBytes;
    private final List<byte[]> complete = new ArrayList<>();
    private byte[] bytes;
    private int position;
    private long size;

    VertexBufferChunks(long maxBytes) {
        this(CHUNK_BYTES, maxBytes);
    }

    VertexBufferChunks(int chunkBytes, long maxBytes) {
        if (chunkBytes <= 0 || chunkBytes % (VERTEX_BYTES * 4) != 0 || maxBytes < 0)
            throw new IllegalArgumentException("Invalid vertex buffer limits");
        this.chunkBytes = chunkBytes;
        this.maxBytes = maxBytes;
        this.bytes = new byte[Math.min(chunkBytes, VERTEX_BYTES * 256)];
    }

    boolean isEmpty() { return size == 0; }

    void beginVertex() {
        if (size + VERTEX_BYTES > maxBytes) throw new IllegalStateException("Vertex buffer limit exceeded");
        if (position == chunkBytes) {
            complete.add(bytes);
            bytes = new byte[Math.min(chunkBytes, VERTEX_BYTES * 256)];
            position = 0;
        }
        int needed = position + VERTEX_BYTES;
        if (needed > bytes.length) bytes = Arrays.copyOf(bytes, grownCapacity(bytes.length, needed, chunkBytes));
        size += VERTEX_BYTES;
    }

    static int grownCapacity(int current, int needed, int maximum) {
        if (current < 0 || needed < 0 || needed > maximum) throw new IllegalArgumentException("Invalid capacity");
        return (int) Math.min(maximum, Math.max((long) needed, (long) current * 2));
    }

    void writeInt(int value) {
        bytes[position++] = (byte) (value >>> 24);
        bytes[position++] = (byte) (value >>> 16);
        bytes[position++] = (byte) (value >>> 8);
        bytes[position++] = (byte) value;
    }

    void writeShort(short value) {
        bytes[position++] = (byte) (value >>> 8);
        bytes[position++] = (byte) value;
    }

    List<byte[]> takeChunks() {
        if (position != 0) complete.add(position == bytes.length ? bytes : Arrays.copyOf(bytes, position));
        List<byte[]> result = List.copyOf(complete);
        complete.clear();
        bytes = new byte[Math.min(chunkBytes, VERTEX_BYTES * 256)];
        position = 0;
        size = 0;
        return result;
    }
}
