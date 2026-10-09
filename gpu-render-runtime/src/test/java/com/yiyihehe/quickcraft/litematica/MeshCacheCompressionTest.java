package com.yiyihehe.quickcraft.litematica;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class MeshCacheCompressionTest {
    @Test void fastCompressionIsLosslessAndReadableByExistingGzipReader() throws Exception {
        byte[] bytes = new byte[200000];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (index % 113);
        var compressed = new ByteArrayOutputStream();
        try (var output = new MeshCacheCompression(compressed)) { output.write(bytes); }
        assertTrue(compressed.size() < bytes.length / 10);
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(bytes, input.readAllBytes());
        }
    }
}
