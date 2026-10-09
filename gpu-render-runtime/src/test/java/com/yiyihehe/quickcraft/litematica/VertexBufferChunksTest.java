package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class VertexBufferChunksTest {
    @Test
    void growsBeyondOneGigabyteWithoutOverflowOrLooping() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertEquals(1_920_000_000, VertexBufferChunks.grownCapacity(1_073_741_824, 1_073_741_856, 1_920_000_000));
            assertEquals(32, VertexBufferChunks.grownCapacity(0, 32, 1024));
            assertThrows(IllegalArgumentException.class, () -> VertexBufferChunks.grownCapacity(512, 1025, 1024));
        });
    }

    @Test
    void splitsAtQuadBoundariesAndPreservesEveryVertexByte() {
        var vertices = new VertexBufferChunks(256, 640);
        for (int index = 0; index < 20; index++) writeVertex(vertices, index);
        var chunks = vertices.takeChunks();
        assertEquals(3, chunks.size());
        assertEquals(256, chunks.get(0).length);
        assertEquals(256, chunks.get(1).length);
        assertEquals(128, chunks.get(2).length);
        int index = 0;
        for (byte[] chunk : chunks) {
            var data = ByteBuffer.wrap(chunk);
            while (data.hasRemaining()) {
                for (int field = 0; field < 6; field++) assertEquals(index * 10 + field, data.getInt());
                assertEquals((short) index, data.getShort());
                assertEquals(index * 10 + 6, data.getInt());
                assertEquals((short) -index, data.getShort());
                index++;
            }
        }
        assertEquals(20, index);
        assertTrue(vertices.isEmpty());
        writeVertex(vertices, 99);
        assertEquals(99 * 10, ByteBuffer.wrap(vertices.takeChunks().getFirst()).getInt());
        assertEquals(0, vertices.takeChunks().size());
    }

    @Test
    void enforcesBudgetBeforeWritingPastIt() {
        var vertices = new VertexBufferChunks(128, 128);
        for (int index = 0; index < 4; index++) writeVertex(vertices, index);
        assertThrows(IllegalStateException.class, vertices::beginVertex);
        assertEquals(128, vertices.takeChunks().getFirst().length);
    }

    @Test
    void handlesFullFinalChunksAndEmptyReuse() {
        var vertices = new VertexBufferChunks(128, 256);
        for (int index = 0; index < 8; index++) writeVertex(vertices, index);
        assertEquals(2, vertices.takeChunks().size());
        assertFalse(vertices.takeChunks().iterator().hasNext());
    }

    @Test
    void growsWithinChunkThenRollsOverWithoutLosingData() {
        var vertices = new VertexBufferChunks(16_384, 32_896);
        for (int index = 0; index < 1028; index++) writeVertex(vertices, index);
        var chunks = vertices.takeChunks();
        assertEquals(3, chunks.size());
        assertEquals(16_384, chunks.get(0).length);
        assertEquals(16_384, chunks.get(1).length);
        assertEquals(128, chunks.get(2).length);
        assertEquals(5120, ByteBuffer.wrap(chunks.get(1)).getInt());
        assertEquals(10240, ByteBuffer.wrap(chunks.get(2)).getInt());
    }

    private static void writeVertex(VertexBufferChunks vertices, int index) {
        vertices.beginVertex();
        for (int field = 0; field < 6; field++) vertices.writeInt(index * 10 + field);
        vertices.writeShort((short) index);
        vertices.writeInt(index * 10 + 6);
        vertices.writeShort((short) -index);
    }
}
